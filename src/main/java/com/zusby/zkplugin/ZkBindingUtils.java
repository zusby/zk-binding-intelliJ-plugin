package com.zusby.zkplugin;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared utilities for locating ZK MVVM bindings in .zul files and resolving
 * their associated ViewModel classes.
 */
public class ZkBindingUtils {

    /** Matches @bind/@load/@save/@init/@ref with optional vm. or ${vm.} prefix. */
    private static final Pattern BINDING_PATTERN = Pattern.compile(
            "@(?:bind|load|save|init|ref)\\s*\\(\\s*(?:vm\\.|\\$\\{vm\\.)?([a-zA-Z_][a-zA-Z0-9_]*)(?:\\})?\\s*\\)"
    );

    /** Matches @command('name') and @command("name"). */
    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "@command\\s*\\(\\s*['\"]([a-zA-Z_][a-zA-Z0-9_]*)['\"]\\s*\\)"
    );

    /**
     * Returns true if {@code propertyName} appears as a binding expression in any .zul file
     * within {@code scope} that uses {@code viewModelClass} (or a class in its hierarchy).
     */
    public static boolean findZulBindings(@NotNull Project project,
                                          @NotNull GlobalSearchScope scope,
                                          @NotNull String propertyName,
                                          @NotNull PsiClass viewModelClass) {
        for (VirtualFile vFile : findZulFiles(project, scope)) {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vFile);
            if (psiFile instanceof XmlFile) {
                XmlFile xmlFile = (XmlFile) psiFile;
                if (usesViewModel(xmlFile, viewModelClass) && containsBinding(xmlFile, propertyName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns all .zul files found within the given search scope.
     */
    public static Collection<VirtualFile> findZulFiles(@NotNull Project project,
                                                        @NotNull GlobalSearchScope scope) {
        List<VirtualFile> zulFiles = new ArrayList<>();
        for (VirtualFile file : FileTypeIndex.getFiles(com.intellij.ide.highlighter.XmlFileType.INSTANCE, scope)) {
            if (file.getName().endsWith(".zul")) {
                zulFiles.add(file);
            }
        }
        return zulFiles;
    }

    /**
     * Returns true if {@code xmlFile} declares a ViewModel that is {@code viewModelClass}
     * or any class in its inheritance hierarchy (in either direction).
     */
    public static boolean usesViewModel(@NotNull XmlFile xmlFile, @NotNull PsiClass viewModelClass) {
        XmlTag rootTag = xmlFile.getRootTag();
        return rootTag != null && checkViewModelInTag(rootTag, viewModelClass);
    }

    /**
     * Recursively walks the tag tree looking for a viewModel/apply/use attribute whose
     * resolved class is related to {@code targetClass}.
     */
    private static boolean checkViewModelInTag(@NotNull XmlTag tag, @NotNull PsiClass targetClass) {
        for (String attr : new String[]{"viewModel", "apply", "use"}) {
            XmlAttribute attribute = tag.getAttribute(attr);
            if (attribute == null) continue;
            String value = attribute.getValue();
            if (value == null) continue;
            String className = extractClassName(value);
            if (className == null) continue;

            GlobalSearchScope allScope = GlobalSearchScope.allScope(tag.getProject());
            PsiClass zulClass = JavaPsiFacade.getInstance(tag.getProject()).findClass(className, allScope);

            // Class not found as a regular Java type — check if it's defined inline in <zscript>
            if (zulClass == null) {
                String superName = findSuperClassFromZScript(tag, className);
                if (superName != null) {
                    zulClass = JavaPsiFacade.getInstance(tag.getProject()).findClass(superName, allScope);
                }
            }

            if (zulClass != null && isInClassHierarchy(targetClass, zulClass)) {
                return true;
            }
        }

        for (XmlTag child : tag.getSubTags()) {
            if (checkViewModelInTag(child, targetClass)) return true;
        }
        return false;
    }

    /**
     * Extracts a fully-qualified class name from a ZK attribute value.
     * <p>
     * Handles {@code @id('vm') @init('com.example.Foo')} as well as plain class names
     * used in {@code apply} and {@code use} attributes.
     */
    @Nullable
    private static String extractClassName(String attributeValue) {
        Matcher m = Pattern.compile("@init\\s*\\(\\s*['\"]([a-zA-Z0-9_.]+)['\"]\\s*\\)").matcher(attributeValue);
        if (m.find()) return m.group(1);

        String cleaned = attributeValue.trim().replaceAll("['\"]", "");
        if (cleaned.matches("[a-zA-Z][a-zA-Z0-9_.]*") && cleaned.contains(".")) return cleaned;
        return null;
    }

    /**
     * Searches {@code <zscript>} blocks (direct children of the root tag) for an inline class
     * declaration of the form {@code class ClassName extends SuperClass} and returns the
     * superclass name, or {@code null} if not found.
     * <p>
     * Only direct children of root are searched because nesting {@code <zscript>} deeper
     * than the root container is non-standard ZK usage.
     */
    @Nullable
    private static String findSuperClassFromZScript(XmlTag currentTag, String className) {
        XmlTag root = currentTag;
        while (root.getParentTag() != null) root = root.getParentTag();

        for (XmlTag child : root.getSubTags()) {
            if (!"zscript".equals(child.getName())) continue;
            String script = child.getValue().getText();
            if (script == null) continue;
            Matcher m = Pattern.compile(
                    "class\\s+" + Pattern.quote(className) + "\\s+extends\\s+([a-zA-Z0-9_.]+)"
            ).matcher(script);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /**
     * Returns true if {@code targetClass} and {@code zulClass} are related by inheritance
     * (same class, or one extends the other at any depth).
     * <p>
     * Uses {@link #walkUpContains} instead of {@link PsiClass#isInheritor} because
     * {@code isInheritor} can silently return false when a class in the middle of the chain
     * falls outside the element's default resolve scope.
     */
    static boolean isInClassHierarchy(@NotNull PsiClass targetClass, @NotNull PsiClass zulClass) {
        String targetQName = targetClass.getQualifiedName();
        String zulQName = zulClass.getQualifiedName();
        if (targetQName == null || zulQName == null) return false;
        return walkUpContains(zulClass, targetQName) || walkUpContains(targetClass, zulQName);
    }

    /**
     * Walks the superclass chain of {@code start} and returns true if {@code fqn} is
     * encountered. A visited-set guards against cycles.
     */
    private static boolean walkUpContains(@NotNull PsiClass start, @NotNull String fqn) {
        Set<String> visited = new HashSet<>();
        PsiClass current = start;
        while (current != null) {
            String qn = current.getQualifiedName();
            if (qn == null || !visited.add(qn)) break;
            if (fqn.equals(qn)) return true;
            current = current.getSuperClass();
        }
        return false;
    }

    /**
     * Returns true if {@code xmlFile} contains a binding or command expression for
     * {@code propertyName}.
     */
    public static boolean containsBinding(@NotNull XmlFile xmlFile, @NotNull String propertyName) {
        String content = xmlFile.getText();
        Matcher m = BINDING_PATTERN.matcher(content);
        while (m.find()) {
            if (propertyName.equals(m.group(1))) return true;
        }
        m = COMMAND_PATTERN.matcher(content);
        while (m.find()) {
            if (propertyName.equals(m.group(1))) return true;
        }
        return false;
    }

    /**
     * Returns the character offsets (within the file text) of every binding or command
     * expression that refers to {@code propertyName}. The offset points at the property
     * name token itself, not the surrounding annotation.
     */
    public static List<Integer> findBindingOffsets(@NotNull XmlFile xmlFile, @NotNull String propertyName) {
        List<Integer> offsets = new ArrayList<>();
        String content = xmlFile.getText();

        Matcher m = BINDING_PATTERN.matcher(content);
        while (m.find()) {
            if (propertyName.equals(m.group(1))) offsets.add(m.start(1));
        }
        m = COMMAND_PATTERN.matcher(content);
        while (m.find()) {
            if (propertyName.equals(m.group(1))) offsets.add(m.start(1));
        }
        return offsets;
    }

    /**
     * Derives the ZK property name from a getter or setter method name.
     * Returns {@code null} for methods that don't follow the get/set/is/has convention.
     */
    @Nullable
    public static String getPropertyName(@NotNull PsiMethod method) {
        String name = method.getName();
        if (name.startsWith("get") || name.startsWith("set")) {
            return name.length() <= 3 ? null : decapitalize(name.substring(3));
        }
        if (name.startsWith("is")) {
            return name.length() <= 2 ? null : decapitalize(name.substring(2));
        }
        if (name.startsWith("has")) {
            return name.length() <= 3 ? null : decapitalize(name.substring(3));
        }
        return null;
    }

    private static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        if (s.length() == 1) return s.toLowerCase();
        // Preserve all-caps acronyms like "URLConnection"
        if (Character.isUpperCase(s.charAt(1))) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
