package com.zusby.zkplugin;

import com.intellij.openapi.project.DumbService;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared utilities for locating ZK MVVM bindings in .zul files and matching them to
 * Java ViewModel members.
 */
public class ZkBindingUtils {

    /** A parsed binding token together with its absolute offset in the .zul file. */
    public static final class FileToken {
        public final ZkBindingParser.Token token;
        public final int offset;

        FileToken(ZkBindingParser.Token token, int offset) {
            this.token = token;
            this.offset = offset;
        }
    }

    /** A binding in a .zul file that refers to a Java member. */
    public static final class Usage {
        public final XmlFile file;
        public final int offset;
        final ZkBindingParser.Token token;

        Usage(XmlFile file, FileToken ft) {
            this.file = file;
            this.offset = ft.offset;
            this.token = ft.token;
        }
    }

    private static final Key<CachedValue<List<FileToken>>> TOKENS = Key.create("zk.binding.tokens");
    private static final Key<CachedValue<List<Usage>>> USAGES = Key.create("zk.method.usages");

    /** Double- or single-quoted attribute values (group 1 / group 2). */
    private static final Pattern ATTRIBUTE_VALUE = Pattern.compile("=\\s*(?:\"([^\"<]*)\"|'([^'<]*)')");

    /**
     * All binding tokens in {@code xmlFile}, cached until the file changes. Scans the raw text
     * rather than the PSI tree, so a 20k-line file never has to be parsed into XML PSI just
     * to answer "is this getter used?".
     */
    @NotNull
    public static List<FileToken> tokens(@NotNull XmlFile xmlFile) {
        return CachedValuesManager.getCachedValue(xmlFile, TOKENS, () -> {
            CharSequence text = xmlFile.getViewProvider().getContents();
            List<FileToken> result = new ArrayList<>();
            Matcher m = ATTRIBUTE_VALUE.matcher(text);
            while (m.find()) {
                int group = m.start(1) >= 0 ? 1 : 2;
                int start = m.start(group);
                int end = m.end(group);
                if (!containsAt(text, start, end)) continue;
                for (ZkBindingParser.Token t : ZkBindingParser.parse(text.subSequence(start, end))) {
                    result.add(new FileToken(t, start + t.start));
                }
            }
            return CachedValueProvider.Result.create(Collections.unmodifiableList(result), xmlFile);
        });
    }

    private static boolean containsAt(CharSequence text, int start, int end) {
        for (int i = start; i < end; i++) {
            if (text.charAt(i) == '@') return true;
        }
        return false;
    }

    /** Returns all .zul files found within the given search scope. */
    public static Collection<VirtualFile> findZulFiles(@NotNull Project project,
                                                        @NotNull GlobalSearchScope scope) {
        return FilenameIndex.getAllFilesByExt(project, "zul", scope);
    }

    /** Words a binding to {@code method} must contain: its property name and/or command names. */
    @NotNull
    public static List<String> searchWords(@NotNull PsiMethod method) {
        List<String> words = new ArrayList<>(ZkViewModelResolver.commandNames(method));
        words.addAll(ZkViewModelResolver.globalCommandNames(method));
        String property = getPropertyName(method);
        if (property != null) words.add(property);
        return words;
    }

    /**
     * All .zul bindings in the project that refer to {@code method}. Cached until the next PSI
     * change, so the line marker, the implicit-usage check and repeated highlighting passes
     * share one computation.
     */
    @NotNull
    public static List<Usage> findUsages(@NotNull PsiMethod method) {
        if (DumbService.isDumb(method.getProject())) return List.of();
        return CachedValuesManager.getCachedValue(method, USAGES, () ->
                CachedValueProvider.Result.create(computeUsages(method), PsiModificationTracker.MODIFICATION_COUNT));
    }

    private static List<Usage> computeUsages(PsiMethod method) {
        List<String> words = searchWords(method);
        if (words.isEmpty() || method.getContainingClass() == null) return List.of();
        Map<String, List<Usage>> bindings = projectBindings(method.getProject());
        List<Usage> usages = new ArrayList<>();
        for (String word : new LinkedHashSet<>(words)) {
            for (Usage candidate : bindings.getOrDefault(word, List.of())) {
                if (candidate.file.isValid() && matches(candidate.file, candidate.token, method)) usages.add(candidate);
            }
        }
        return Collections.unmodifiableList(usages);
    }

    private static final Key<CachedValue<Map<String, List<Usage>>>> PROJECT_BINDINGS = Key.create("zk.project.bindings");

    /**
     * Every binding token of every .zul file in the project, grouped by property/command name.
     * <p>
     * Built once by scanning the (small) .zul files' text and rebuilt only when a .zul file
     * changes: editing Java code never invalidates it. Checking whether one of the thousands
     * of methods in a large Java class is bound then costs a single map lookup, and methods
     * whose name appears in no binding (the vast majority) cost nothing.
     */
    @NotNull
    static Map<String, List<Usage>> projectBindings(@NotNull Project project) {
        return CachedValuesManager.getManager(project).getCachedValue(project, PROJECT_BINDINGS, () -> {
            Map<String, List<Usage>> map = new HashMap<>();
            Collection<VirtualFile> zulFiles = findZulFiles(project, GlobalSearchScope.projectScope(project));
            PsiManager psiManager = PsiManager.getInstance(project);
            for (VirtualFile vFile : zulFiles) {
                PsiFile psiFile = psiManager.findFile(vFile);
                if (!(psiFile instanceof XmlFile)) continue;
                for (FileToken ft : tokens((XmlFile) psiFile)) {
                    map.computeIfAbsent(ft.token.name, k -> new ArrayList<>()).add(new Usage((XmlFile) psiFile, ft));
                }
            }
            List<Object> dependencies = new ArrayList<>(zulFiles);      // content changes on disk
            dependencies.add(PsiModificationTracker.getInstance(project).forLanguage(XMLLanguage.INSTANCE)); // in-editor edits
            dependencies.add(VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS);                                // added/removed files
            dependencies.add(ProjectRootManager.getInstance(project));                                       // module/root changes
            return CachedValueProvider.Result.create(map, dependencies.toArray());
        }, false);
    }

    /**
     * True if {@code token} in {@code xmlFile} binds to {@code method}: same property name
     * (any depth, e.g. {@code vm.address.city}, or via a form such as {@code fx.city}) or
     * command name, and the binding's owner class is related to the method's class.
     */
    private static boolean matches(XmlFile xmlFile, ZkBindingParser.Token t, PsiMethod method) {
        PsiClass methodClass = method.getContainingClass();
        if (methodClass == null) return false;
        PsiClass owner;
        if (t.kind == ZkBindingParser.Kind.PROPERTY) {
            if (!t.name.equals(getPropertyName(method))) return false;
            if (t.root == null || !ZkViewModelResolver.roots(xmlFile).contains(t.root)) return false;
            owner = ZkViewModelResolver.ownerClass(xmlFile, t.root, t.qualifier);
        } else if (t.kind == ZkBindingParser.Kind.GLOBAL_COMMAND) {
            // Broadcast to every ViewModel: any @GlobalCommand handler with this name is a target.
            return ZkViewModelResolver.globalCommandNames(method).contains(t.name);
        } else {
            if (!ZkViewModelResolver.commandNames(method).contains(t.name)) return false;
            owner = ZkViewModelResolver.ownerClass(xmlFile, ZkViewModelResolver.viewModelId(xmlFile), List.of());
        }
        return owner != null && isInClassHierarchy(methodClass, owner);
    }

    /** Offsets in {@code xmlFile} of every binding that refers to {@code method}. */
    @NotNull
    public static List<Integer> findUsageOffsets(@NotNull XmlFile xmlFile, @NotNull PsiMethod method) {
        if (searchWords(method).isEmpty()) return List.of();
        List<Integer> offsets = new ArrayList<>();
        for (FileToken ft : tokens(xmlFile)) {
            if (matches(xmlFile, ft.token, method)) offsets.add(ft.offset);
        }
        return offsets;
    }

    /**
     * Derives the ZK property name from a getter or setter method name.
     * Returns {@code null} for methods that don't follow the get/set/is/has convention.
     */
    @Nullable
    public static String getPropertyName(@NotNull PsiMethod method) {
        return propertyNameFromMethodName(method.getName());
    }

    @Nullable
    public static String propertyNameFromMethodName(@NotNull String name) {
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

    private static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        if (s.length() == 1) return s.toLowerCase();
        // Preserve all-caps acronyms like "URLConnection"
        if (Character.isUpperCase(s.charAt(1))) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
