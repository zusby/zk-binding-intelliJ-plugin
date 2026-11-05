package com.zusby.zkplugin;

import com.intellij.openapi.util.Key;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the ViewModel declared in a .zul file and enumerates the bindable
 * properties and commands it exposes.
 * <p>
 * Performance: everything read from the .zul file is extracted from its raw text with
 * regular expressions (never by building the XML PSI tree, which is expensive for
 * 20k-line files) and cached until the file changes. Everything derived from Java classes
 * is cached until the next PSI change.
 */
public final class ZkViewModelResolver {

    private static final Pattern INIT_PATTERN = Pattern.compile("@init\\s*\\(\\s*['\"]([a-zA-Z0-9_.]+)['\"]\\s*\\)");
    private static final Pattern ID_PATTERN = Pattern.compile("@id\\s*\\(\\s*['\"]([a-zA-Z_][a-zA-Z0-9_]*)['\"]\\s*\\)");
    /** {@code viewModel="..."} (group 1 = value); also matches single-quoted values. */
    private static final Pattern VIEW_MODEL_ATTR = Pattern.compile("\\bviewModel\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    /** Fallback ViewModel/composer declarations. */
    private static final Pattern OTHER_VM_ATTR = Pattern.compile("\\b(?:apply|use|layoutModel)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern FORM_ATTR = Pattern.compile("\\bform\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern FORM_SOURCE = Pattern.compile(
            "@(?:load|save)\\s*\\(\\s*([a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)*)");
    private static final Pattern ZSCRIPT = Pattern.compile("<zscript\\b[^>]*>(.*?)</zscript>", Pattern.DOTALL);

    private static final Key<CachedValue<FileInfo>> FILE_INFO = Key.create("zk.file.info");
    private static final Key<CachedValue<Map<String, Optional<PsiClass>>>> OWNER_CACHE = Key.create("zk.owner.cache");
    private static final Key<CachedValue<Map<String, Property>>> PROPERTIES = Key.create("zk.class.properties");
    private static final Key<CachedValue<Map<String, PsiMethod>>> COMMANDS = Key.create("zk.class.commands");

    private ZkViewModelResolver() {
    }

    /** A bindable property exposed by a ViewModel (derived from a getter or setter). */
    public static final class Property {
        public final String name;
        public final PsiMethod method;
        public final @Nullable PsiType type;

        Property(String name, PsiMethod method, @Nullable PsiType type) {
            this.name = name;
            this.method = method;
            this.type = type;
        }
    }

    /** ViewModel declarations extracted from the raw .zul text. */
    private static final class FileInfo {
        final String vmId;
        final @Nullable String vmClassName;
        /** Superclass of an inline {@code <zscript>} ViewModel class, if the ViewModel is declared there. */
        final @Nullable String zscriptSuperName;
        final Map<String, List<String>> forms;
        final Set<String> roots;

        FileInfo(String vmId, @Nullable String vmClassName, @Nullable String zscriptSuperName, Map<String, List<String>> forms) {
            this.vmId = vmId;
            this.vmClassName = vmClassName;
            this.zscriptSuperName = zscriptSuperName;
            this.forms = forms;
            Set<String> r = new HashSet<>(forms.keySet());
            r.add(vmId);
            this.roots = Collections.unmodifiableSet(r);
        }
    }

    private static FileInfo fileInfo(XmlFile xmlFile) {
        return CachedValuesManager.getCachedValue(xmlFile, FILE_INFO, () ->
                CachedValueProvider.Result.create(computeFileInfo(xmlFile.getViewProvider().getContents()), xmlFile));
    }

    private static FileInfo computeFileInfo(CharSequence text) {
        String vmId = null;
        String className = null;
        Matcher vm = VIEW_MODEL_ATTR.matcher(text);
        while (vm.find() && (vmId == null || className == null)) {
            String value = attrValue(vm);
            Matcher id = ID_PATTERN.matcher(value);
            if (vmId == null && id.find()) vmId = id.group(1);
            if (className == null) className = classNameFrom(value);
        }
        if (className == null) {
            Matcher other = OTHER_VM_ATTR.matcher(text);
            while (other.find() && className == null) className = classNameFrom(attrValue(other));
        }

        Map<String, List<String>> forms = new HashMap<>();
        Matcher form = FORM_ATTR.matcher(text);
        while (form.find()) {
            String value = attrValue(form);
            Matcher id = ID_PATTERN.matcher(value);
            Matcher src = FORM_SOURCE.matcher(value);
            if (id.find() && src.find()) forms.put(id.group(1), List.of(src.group(1).split("\\.")));
        }

        String zscriptSuper = className != null && !className.contains(".") ? findZScriptSuperClass(text, className) : null;
        return new FileInfo(vmId != null ? vmId : "vm", className, zscriptSuper, forms);
    }

    private static String attrValue(Matcher m) {
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    @Nullable
    private static String classNameFrom(String attributeValue) {
        Matcher m = INIT_PATTERN.matcher(attributeValue);
        if (m.find()) return m.group(1);
        String cleaned = attributeValue.trim().replaceAll("['\"]", "");
        return cleaned.matches("[a-zA-Z][a-zA-Z0-9_.]*") ? cleaned : null;
    }

    @Nullable
    private static String findZScriptSuperClass(CharSequence text, String className) {
        Pattern classDecl = Pattern.compile("class\\s+" + Pattern.quote(className) + "\\s+extends\\s+([a-zA-Z0-9_.]+)");
        Matcher block = ZSCRIPT.matcher(text);
        while (block.find()) {
            String script = block.group(1);
            Matcher m = classDecl.matcher(script);
            if (!m.find()) continue;
            String superName = m.group(1);
            if (!superName.contains(".")) {
                Matcher imp = Pattern.compile("import\\s+([a-zA-Z0-9_.]+\\." + Pattern.quote(superName) + ")\\s*;").matcher(script);
                if (imp.find()) return imp.group(1);
            }
            return superName;
        }
        return null;
    }

    /**
     * Resolves the ViewModel class declared in {@code xmlFile}. If the declared class is an
     * inline {@code <zscript>} class, its declared superclass is returned instead.
     */
    @Nullable
    public static PsiClass resolveViewModelClass(@NotNull XmlFile xmlFile) {
        return ownerClass(xmlFile, viewModelId(xmlFile), List.of());
    }

    @Nullable
    private static PsiClass computeViewModelClass(XmlFile xmlFile) {
        FileInfo info = fileInfo(xmlFile);
        if (info.vmClassName == null) return null;
        JavaPsiFacade facade = JavaPsiFacade.getInstance(xmlFile.getProject());
        GlobalSearchScope allScope = GlobalSearchScope.allScope(xmlFile.getProject());
        PsiClass found = facade.findClass(info.vmClassName, allScope);
        if (found != null) return found;
        return info.zscriptSuperName != null ? facade.findClass(info.zscriptSuperName, allScope) : null;
    }

    /** Returns the ViewModel id declared with {@code @id('...')}, defaulting to {@code vm}. */
    @NotNull
    public static String viewModelId(@NotNull XmlFile xmlFile) {
        return fileInfo(xmlFile).vmId;
    }

    /** Form ids declared via {@code form="@id('fx') @load(vm.person) ..."}, mapped to their backing path. */
    @NotNull
    public static Map<String, List<String>> formDeclarations(@NotNull XmlFile xmlFile) {
        return fileInfo(xmlFile).forms;
    }

    /** All path roots that refer to Java objects in this file: the ViewModel id plus form ids. */
    @NotNull
    public static Set<String> roots(@NotNull XmlFile xmlFile) {
        return fileInfo(xmlFile).roots;
    }

    /**
     * Resolves the class that owns the property {@code root.qualifier...<name>}: for
     * {@code vm.address.city} with root {@code vm} and qualifier {@code [address]}, the type of
     * {@code getAddress()}. Form roots resolve to their backing bean. Results are cached until
     * the next PSI change.
     */
    @Nullable
    public static PsiClass ownerClass(@NotNull XmlFile xmlFile, @NotNull String root, @NotNull List<String> qualifier) {
        String key = qualifier.isEmpty() ? root : root + "." + String.join(".", qualifier);
        Map<String, Optional<PsiClass>> cache = CachedValuesManager.getCachedValue(xmlFile, OWNER_CACHE, () ->
                CachedValueProvider.Result.create(new ConcurrentHashMap<>(), PsiModificationTracker.MODIFICATION_COUNT));
        Optional<PsiClass> cached = cache.get(key);
        if (cached == null) {
            // Not computeIfAbsent: computing may recurse into this cache for the root key.
            cached = Optional.ofNullable(computeOwner(xmlFile, root, qualifier));
            cache.putIfAbsent(key, cached);
        }
        return cached.orElse(null);
    }

    @Nullable
    private static PsiClass computeOwner(XmlFile xmlFile, String root, List<String> qualifier) {
        FileInfo info = fileInfo(xmlFile);
        PsiClass rootClass;
        if (root.equals(info.vmId)) {
            if (qualifier.isEmpty()) return computeViewModelClass(xmlFile);
            rootClass = resolveViewModelClass(xmlFile);
        } else {
            List<String> formPath = info.forms.get(root);
            // A form is backed by a path on the ViewModel, e.g. @load(vm.person)
            if (formPath == null || formPath.isEmpty() || !formPath.get(0).equals(info.vmId)) return null;
            rootClass = ownerClass(xmlFile, info.vmId, formPath.subList(1, formPath.size()));
        }
        return rootClass != null ? resolvePath(rootClass, qualifier) : null;
    }

    /**
     * Follows a property path such as {@code person.address} starting at {@code start},
     * returning the class of the last property's type, or {@code null} if any step fails.
     */
    @Nullable
    public static PsiClass resolvePath(@NotNull PsiClass start, @NotNull List<String> path) {
        PsiClass current = start;
        for (String segment : path) {
            Property p = properties(current).get(segment);
            if (p == null || p.type == null) return null;
            current = PsiUtil.resolveClassInClassTypeOnly(p.type);
            if (current == null) return null;
        }
        return current;
    }

    /**
     * All readable or writable properties of {@code cls} and its superclasses, keyed by name.
     * Cached until the next PSI change.
     */
    @NotNull
    public static Map<String, Property> properties(@NotNull PsiClass cls) {
        return CachedValuesManager.getCachedValue(cls, PROPERTIES, () ->
                CachedValueProvider.Result.create(computeProperties(cls), PsiModificationTracker.MODIFICATION_COUNT));
    }

    private static Map<String, Property> computeProperties(PsiClass cls) {
        Map<String, Property> result = new LinkedHashMap<>();
        for (PsiClass c : hierarchy(cls)) {
            for (PsiMethod m : c.getMethods()) {
                if (m.isConstructor() || !m.hasModifierProperty(PsiModifier.PUBLIC)
                        || m.hasModifierProperty(PsiModifier.STATIC)) continue;
                int params = m.getParameterList().getParametersCount();
                String name = ZkBindingUtils.getPropertyName(m);
                if (name == null || name.isEmpty()) continue;

                boolean isGetter = params == 0 && !m.getName().startsWith("set")
                        && m.getReturnType() != null && !PsiTypes.voidType().equals(m.getReturnType());
                boolean isSetter = params == 1 && m.getName().startsWith("set");
                if (isGetter) {
                    // A getter wins over a previously seen setter; subclasses win over superclasses.
                    Property existing = result.get(name);
                    if (existing == null || existing.method.getName().startsWith("set")) {
                        result.put(name, new Property(name, m, m.getReturnType()));
                    }
                } else if (isSetter && !result.containsKey(name)) {
                    result.put(name, new Property(name, m, m.getParameterList().getParameters()[0].getType()));
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * All {@code @Command}-annotated methods of {@code cls} and its superclasses, keyed by
     * command name. Cached until the next PSI change.
     */
    @NotNull
    public static Map<String, PsiMethod> commands(@NotNull PsiClass cls) {
        return CachedValuesManager.getCachedValue(cls, COMMANDS, () -> {
            Map<String, PsiMethod> result = new LinkedHashMap<>();
            for (PsiClass c : hierarchy(cls)) {
                for (PsiMethod m : c.getMethods()) {
                    for (String n : commandNames(m)) result.putIfAbsent(n, m);
                }
            }
            return CachedValueProvider.Result.create(Collections.unmodifiableMap(result), PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    /**
     * Command names a method is bound to: the explicit {@code @Command("...")} values, or the
     * method name for a bare {@code @Command}. Empty if the method is not a command.
     */
    @NotNull
    public static List<String> commandNames(@NotNull PsiMethod method) {
        PsiAnnotation a = commandAnnotation(method);
        if (a == null) return List.of();
        List<String> names = commandNames(a);
        return names.isEmpty() ? List.of(method.getName()) : names;
    }

    /** True if the method's {@code @Command} names its commands explicitly (renaming the method keeps them). */
    public static boolean hasExplicitCommandName(@NotNull PsiMethod method) {
        PsiAnnotation a = commandAnnotation(method);
        return a != null && !commandNames(a).isEmpty();
    }

    @Nullable
    private static PsiAnnotation commandAnnotation(PsiMethod method) {
        // Check the short name first: resolving every annotation's qualified name is costly.
        for (PsiAnnotation a : method.getModifierList().getAnnotations()) {
            PsiJavaCodeReferenceElement ref = a.getNameReferenceElement();
            if (ref == null || !"Command".equals(ref.getReferenceName())) continue;
            String qName = a.getQualifiedName();
            if (qName != null && (qName.equals("Command") || qName.endsWith(".Command"))) return a;
        }
        return null;
    }

    /** Reads explicit names from {@code @Command("a")} or {@code @Command({"a", "b"})}. */
    private static List<String> commandNames(PsiAnnotation annotation) {
        List<String> names = new ArrayList<>();
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue("value");
        if (value instanceof PsiArrayInitializerMemberValue) {
            for (PsiAnnotationMemberValue v : ((PsiArrayInitializerMemberValue) value).getInitializers()) {
                addLiteral(v, names);
            }
        } else if (value != null) {
            addLiteral(value, names);
        }
        return names;
    }

    private static void addLiteral(PsiAnnotationMemberValue v, List<String> names) {
        if (v instanceof PsiLiteralExpression && ((PsiLiteralExpression) v).getValue() instanceof String) {
            String s = (String) ((PsiLiteralExpression) v).getValue();
            if (!s.isEmpty()) names.add(s);
        }
    }

    /** {@code cls} followed by its superclasses, excluding {@code java.lang.Object}. */
    private static List<PsiClass> hierarchy(PsiClass cls) {
        List<PsiClass> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (PsiClass c = cls; c != null; c = c.getSuperClass()) {
            String qn = c.getQualifiedName();
            if (CommonClassNames.JAVA_LANG_OBJECT.equals(qn)) break;
            if (qn != null && !visited.add(qn)) break;
            chain.add(c);
        }
        return chain;
    }
}
