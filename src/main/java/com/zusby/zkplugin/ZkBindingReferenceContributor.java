package com.zusby.zkplugin;

import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides Ctrl+Click navigation from ZK binding expressions in .zul files to the
 * corresponding Java getter, setter, or {@code @Command} method.
 */
public class ZkBindingReferenceContributor extends PsiReferenceContributor {

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(XmlAttributeValue.class),
                new ZkBindingReferenceProvider()
        );
    }

    /**
     * Creates {@link ZkBindingReference} instances for every binding or command
     * expression found in a .zul attribute value.
     */
    private static class ZkBindingReferenceProvider extends PsiReferenceProvider {

        private static final Pattern BINDING_PATTERN = Pattern.compile(
                "@(?:bind|load|save|init|ref)\\s*\\(\\s*(?:vm\\.|\\$\\{vm\\.)?([a-zA-Z_][a-zA-Z0-9_]*)(?:\\})?\\s*\\)"
        );

        private static final Pattern COMMAND_PATTERN = Pattern.compile(
                "@command\\s*\\(\\s*['\"]([a-zA-Z_][a-zA-Z0-9_]*)['\"]\\s*\\)"
        );

        @NotNull
        @Override
        public PsiReference[] getReferencesByElement(@NotNull PsiElement element,
                                                     @NotNull ProcessingContext context) {
            if (!(element instanceof XmlAttributeValue)) return PsiReference.EMPTY_ARRAY;

            XmlAttributeValue attrValue = (XmlAttributeValue) element;
            PsiFile containingFile = element.getContainingFile();
            if (!(containingFile instanceof XmlFile) || !containingFile.getName().endsWith(".zul")) {
                return PsiReference.EMPTY_ARRAY;
            }

            String text = attrValue.getValue();
            if (text == null || text.isEmpty()) return PsiReference.EMPTY_ARRAY;

            List<PsiReference> references = new ArrayList<>();

            Matcher m = BINDING_PATTERN.matcher(text);
            while (m.find()) {
                // +1 because TextRange is relative to the XmlAttributeValue element, which
                // includes the surrounding quote character
                TextRange range = new TextRange(m.start(1) + 1, m.end(1) + 1);
                references.add(new ZkBindingReference(attrValue, range, m.group(1), false));
            }

            m = COMMAND_PATTERN.matcher(text);
            while (m.find()) {
                TextRange range = new TextRange(m.start(1) + 1, m.end(1) + 1);
                references.add(new ZkBindingReference(attrValue, range, m.group(1), true));
            }

            return references.toArray(PsiReference.EMPTY_ARRAY);
        }
    }

    /**
     * A PSI reference from a ZK binding expression to a Java method.
     * Resolves property bindings to the corresponding getter (get/is/has prefix)
     * and command bindings to the method with a matching name.
     */
    private static class ZkBindingReference extends PsiReferenceBase<XmlAttributeValue> {
        private final String propertyName;
        private final boolean isCommand;

        public ZkBindingReference(@NotNull XmlAttributeValue element,
                                  @NotNull TextRange range,
                                  @NotNull String propertyName,
                                  boolean isCommand) {
            super(element, range);
            this.propertyName = propertyName;
            this.isCommand = isCommand;
        }

        @Nullable
        @Override
        public PsiElement resolve() {
            PsiClass viewModelClass = findViewModel();
            if (viewModelClass == null) return null;

            if (isCommand) return findMethodInHierarchy(viewModelClass, propertyName);

            String cap = capitalize(propertyName);
            PsiMethod m = findMethodInHierarchy(viewModelClass, "get" + cap);
            if (m != null) return m;
            m = findMethodInHierarchy(viewModelClass, "is" + cap);
            if (m != null) return m;
            return findMethodInHierarchy(viewModelClass, "has" + cap);
        }

        /**
         * Walks the superclass chain explicitly rather than using
         * {@link PsiClass#findMethodsByName(String, boolean)} with {@code checkInheritance=true},
         * because the built-in deep search can silently stop at a class that falls outside
         * the element's default resolve scope.
         */
        @Nullable
        private PsiMethod findMethodInHierarchy(@NotNull PsiClass start, @NotNull String methodName) {
            Set<String> visited = new HashSet<>();
            PsiClass current = start;
            while (current != null) {
                String qn = current.getQualifiedName();
                if (qn != null && !visited.add(qn)) break;
                PsiMethod[] found = current.findMethodsByName(methodName, false);
                if (found.length > 0) return found[0];
                current = current.getSuperClass();
            }
            return null;
        }

        /**
         * Resolves the ViewModel class declared in this .zul file.
         * If the class name is not a resolvable Java type (e.g. an inline {@code <zscript>}
         * class), the declared superclass is resolved instead.
         */
        @Nullable
        private PsiClass findViewModel() {
            XmlFile xmlFile = (XmlFile) getElement().getContainingFile();
            XmlTag rootTag = xmlFile.getRootTag();
            if (rootTag == null) return null;

            String className = findViewModelClassName(rootTag);
            if (className == null) return null;

            GlobalSearchScope allScope = GlobalSearchScope.allScope(getElement().getProject());
            PsiClass found = JavaPsiFacade.getInstance(getElement().getProject()).findClass(className, allScope);
            if (found != null) return found;

            // Class is inline in <zscript> — resolve via its declared superclass
            String superName = findSuperClassFromZScript(xmlFile, className);
            if (superName != null) {
                return JavaPsiFacade.getInstance(getElement().getProject()).findClass(superName, allScope);
            }
            return null;
        }

        /**
         * Searches {@code <zscript>} blocks for an inline class declaration and returns
         * the name of its declared superclass.
         */
        @Nullable
        private String findSuperClassFromZScript(XmlFile xmlFile, String className) {
            XmlTag rootTag = xmlFile.getRootTag();
            if (rootTag == null) return null;
            for (XmlTag child : rootTag.getSubTags()) {
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
         * Recursively searches the tag tree for a viewModel/apply/use/layoutModel attribute
         * and returns the class name it refers to, or {@code null} if none is found.
         */
        @Nullable
        private String findViewModelClassName(XmlTag tag) {
            for (String attr : new String[]{"viewModel", "apply", "use", "layoutModel"}) {
                XmlAttribute attribute = tag.getAttribute(attr);
                if (attribute == null) continue;
                String value = attribute.getValue();
                if (value == null || value.isEmpty()) continue;
                String className = extractClassNameFromAttribute(value);
                if (className != null) return className;
            }
            for (XmlTag child : tag.getSubTags()) {
                String result = findViewModelClassName(child);
                if (result != null) return result;
            }
            return null;
        }

        /**
         * Extracts the class name from a ZK attribute value such as
         * {@code @id('vm') @init('com.example.Foo')} or a bare qualified name.
         */
        @Nullable
        private String extractClassNameFromAttribute(String attributeValue) {
            Matcher m = Pattern.compile("@init\\s*\\(\\s*['\"]([a-zA-Z0-9_.]+)['\"]\\s*\\)").matcher(attributeValue);
            if (m.find()) return m.group(1);
            String cleaned = attributeValue.trim().replaceAll("['\"]", "");
            if (cleaned.matches("[a-zA-Z][a-zA-Z0-9_.]*")) return cleaned;
            return null;
        }

        private String capitalize(String str) {
            if (str == null || str.isEmpty()) return str;
            return Character.toUpperCase(str.charAt(0)) + str.substring(1);
        }
    }
}
