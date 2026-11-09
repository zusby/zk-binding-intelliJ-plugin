package com.zusby.zkplugin;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.LocalQuickFixProvider;
import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.resolve.reference.impl.providers.JavaClassReferenceProvider;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides Ctrl+Click navigation, rename support and "create member" quick fixes for ZK
 * binding expressions in .zul files. Every segment of a property path
 * ({@code vm.address.city}) and every command name gets its own reference.
 */
public class ZkBindingReferenceContributor extends PsiReferenceContributor {

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(XmlAttributeValue.class),
                new ZkBindingReferenceProvider()
        );
    }

    private static class ZkBindingReferenceProvider extends PsiReferenceProvider {

        @NotNull
        @Override
        public PsiReference[] getReferencesByElement(@NotNull PsiElement element,
                                                     @NotNull ProcessingContext context) {
            if (!(element instanceof XmlAttributeValue)) return PsiReference.EMPTY_ARRAY;
            PsiFile file = element.getContainingFile();
            if (!(file instanceof XmlFile) || !file.getName().endsWith(".zul")) return PsiReference.EMPTY_ARRAY;

            XmlAttributeValue attrValue = (XmlAttributeValue) element;
            TextRange valueRange = attrValue.getValueTextRange().shiftLeft(attrValue.getTextRange().getStartOffset());
            String raw = valueRange.substring(attrValue.getText());
            int base = valueRange.getStartOffset();
            String attrName = attrValue.getParent() instanceof XmlAttribute ? ((XmlAttribute) attrValue.getParent()).getName() : "";

            List<PsiReference> references = new ArrayList<>();
            if (raw.indexOf('@') < 0) {
                // apply="com.example.MyComposer"
                if ("apply".equals(attrName) && QUALIFIED_NAME.matcher(raw.trim()).matches()) {
                    addClassReferences(attrValue, raw.trim(), base + raw.indexOf(raw.trim()), references);
                }
                return references.toArray(PsiReference.EMPTY_ARRAY);
            }

            Set<String> roots = ZkViewModelResolver.roots((XmlFile) file);
            for (ZkBindingParser.Token t : ZkBindingParser.parse(raw)) {
                if (t.kind == ZkBindingParser.Kind.PROPERTY && !roots.contains(t.root)) continue;
                TextRange range = new TextRange(base + t.start, base + t.end);
                references.add(t.kind == ZkBindingParser.Kind.GLOBAL_COMMAND
                        ? new ZkGlobalCommandReference(attrValue, range, t.name)
                        : new ZkBindingReference(attrValue, range, t));
            }

            // @init('com.example.MyVm') in viewModel="...", @converter('...') / @validator('...') anywhere
            Matcher m = CLASS_ANNOTATION.matcher(raw);
            while (m.find()) {
                if ("init".equals(m.group(1)) && !"viewModel".equals(attrName)) continue;
                addClassReferences(attrValue, m.group(3), base + m.start(3), references);
            }
            return references.toArray(PsiReference.EMPTY_ARRAY);
        }

        /** References for every package / class segment of {@code qualifiedName}, with completion and rename on move. */
        private static void addClassReferences(XmlAttributeValue host, String qualifiedName, int offset, List<PsiReference> out) {
            Collections.addAll(out, CLASS_REFERENCES.getReferencesByString(qualifiedName, host, offset));
        }
    }

    /** A dotted name: only those are treated as classes, plain names may be inline zscript classes or built-in converters. */
    private static final Pattern QUALIFIED_NAME = Pattern.compile("[a-zA-Z_][\\w$]*(?:\\.[a-zA-Z_][\\w$]*)+");
    private static final Pattern CLASS_ANNOTATION = Pattern.compile(
            "@(init|converter|validator)\\s*\\(\\s*(['\"])(" + QUALIFIED_NAME.pattern() + ")\\2");
    private static final JavaClassReferenceProvider CLASS_REFERENCES = new JavaClassReferenceProvider();

    /**
     * Reference from {@code @global-command('name')} to the {@code @GlobalCommand} methods that
     * handle it. A global command is broadcast to every ViewModel, so it may resolve to several
     * methods in different classes.
     */
    static class ZkGlobalCommandReference extends PsiPolyVariantReferenceBase<PsiElement> implements LocalQuickFixProvider {
        private final String name;

        ZkGlobalCommandReference(@NotNull PsiElement element, @NotNull TextRange range, @NotNull String name) {
            super(element, range);
            this.name = name;
        }

        String getName() {
            return name;
        }

        @Override
        public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
            List<PsiMethod> handlers = ZkViewModelResolver.globalCommands(getElement().getProject()).getOrDefault(name, List.of());
            return PsiElementResolveResult.createResults(handlers);
        }

        /** Without ZK on the classpath nothing can resolve, so don't paint every global command red. */
        @Override
        public boolean isSoft() {
            return !ZkViewModelResolver.isGlobalCommandAvailable(getElement().getProject());
        }

        @Override
        public boolean isReferenceTo(@NotNull PsiElement element) {
            return element instanceof PsiMethod && ZkViewModelResolver.globalCommandNames((PsiMethod) element).contains(name);
        }

        @Override
        public PsiElement handleElementRename(@NotNull String newElementName) throws IncorrectOperationException {
            for (ResolveResult r : multiResolve(false)) {
                if (r.getElement() instanceof PsiMethod && ZkViewModelResolver.hasExplicitCommandName((PsiMethod) r.getElement())) {
                    return getElement();   // @GlobalCommand("name") keeps its name when the method is renamed
                }
            }
            return super.handleElementRename(newElementName);
        }

        /** In .zul files completion comes from ZkCompletionContributor; in Java literals from here. */
        @Override
        public Object @NotNull [] getVariants() {
            if (getElement() instanceof XmlAttributeValue) return EMPTY_ARRAY;
            return ZkViewModelResolver.globalCommands(getElement().getProject()).keySet().toArray();
        }

        /** Offers to create the handler in the .zul file's ViewModel. */
        @Override
        public LocalQuickFix @Nullable [] getQuickFixes() {
            if (!(getElement().getContainingFile() instanceof XmlFile) || multiResolve(false).length > 0) {
                return LocalQuickFix.EMPTY_ARRAY;
            }
            PsiClass vm = ZkViewModelResolver.resolveViewModelClass((XmlFile) getElement().getContainingFile());
            if (vm == null || vm instanceof PsiCompiledElement || !vm.isWritable()) return LocalQuickFix.EMPTY_ARRAY;
            return new LocalQuickFix[]{new ZkCreateMemberFix.GlobalCommand(vm, name)};
        }
    }

    /**
     * Reference from a property-path segment or command name to the Java getter or
     * {@code @Command} method it binds to.
     */
    static class ZkBindingReference extends PsiReferenceBase<XmlAttributeValue> implements LocalQuickFixProvider {
        private final ZkBindingParser.Token token;

        ZkBindingReference(@NotNull XmlAttributeValue element, @NotNull TextRange range, @NotNull ZkBindingParser.Token token) {
            super(element, range);
            this.token = token;
        }

        boolean isCommand() {
            return token.kind == ZkBindingParser.Kind.COMMAND;
        }

        String getName() {
            return token.name;
        }

        /** The class expected to declare this member, or {@code null} if it cannot be determined. */
        @Nullable
        PsiClass ownerClass() {
            XmlFile file = (XmlFile) getElement().getContainingFile();
            String root = isCommand() ? ZkViewModelResolver.viewModelId(file) : token.root;
            return root == null ? null : ZkViewModelResolver.ownerClass(file, root, isCommand() ? List.of() : token.qualifier);
        }

        @Nullable
        @Override
        public PsiElement resolve() {
            PsiClass owner = ownerClass();
            if (owner == null) return null;
            if (isCommand()) return ZkViewModelResolver.commands(owner).get(token.name);
            ZkViewModelResolver.Property p = ZkViewModelResolver.properties(owner).get(token.name);
            return p != null ? p.method : null;
        }

        /** Don't flag a segment red when its owner is unknown (unresolved parent segment, missing ViewModel). */
        @Override
        public boolean isSoft() {
            return ownerClass() == null;
        }

        /** A binding refers to both the getter and the setter of its property. */
        @Override
        public boolean isReferenceTo(@NotNull PsiElement element) {
            if (!(element instanceof PsiMethod)) return false;
            PsiMethod method = (PsiMethod) element;
            PsiClass methodClass = method.getContainingClass();
            PsiClass owner = ownerClass();
            if (methodClass == null || owner == null) return false;
            boolean nameMatches = isCommand()
                    ? ZkViewModelResolver.commandNames(method).contains(token.name)
                    : token.name.equals(ZkBindingUtils.getPropertyName(method));
            return nameMatches && ZkBindingUtils.isInClassHierarchy(methodClass, owner);
        }

        /** Renaming {@code getFoo} to {@code getBar} rewrites the binding to {@code bar}. */
        @Override
        public PsiElement handleElementRename(@NotNull String newElementName) throws IncorrectOperationException {
            String newText;
            if (isCommand()) {
                PsiElement target = resolve();
                if (target instanceof PsiMethod && ZkViewModelResolver.hasExplicitCommandName((PsiMethod) target)) {
                    return getElement();   // @Command("name") keeps its name when the method is renamed
                }
                newText = newElementName;
            } else {
                String property = ZkBindingUtils.propertyNameFromMethodName(newElementName);
                newText = property != null ? property : newElementName;
            }
            return super.handleElementRename(newText);
        }

        @NotNull
        @Override
        public Object[] getVariants() {
            return EMPTY_ARRAY;   // provided by ZkCompletionContributor
        }

        @Override
        public LocalQuickFix @Nullable [] getQuickFixes() {
            if (resolve() != null) return LocalQuickFix.EMPTY_ARRAY;
            PsiClass owner = ownerClass();
            if (owner == null || owner instanceof PsiCompiledElement || !owner.isWritable()) return LocalQuickFix.EMPTY_ARRAY;
            return new LocalQuickFix[]{
                    isCommand() ? new ZkCreateMemberFix.Command(owner, token.name)
                                : new ZkCreateMemberFix.Property(owner, token.name)
            };
        }
    }
}
