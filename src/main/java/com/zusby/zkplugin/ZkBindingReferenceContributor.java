package com.zusby.zkplugin;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.LocalQuickFixProvider;
import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
            if (raw.indexOf('@') < 0) return PsiReference.EMPTY_ARRAY;

            Set<String> roots = ZkViewModelResolver.roots((XmlFile) file);
            List<PsiReference> references = new ArrayList<>();
            for (ZkBindingParser.Token t : ZkBindingParser.parse(raw)) {
                if (t.kind == ZkBindingParser.Kind.PROPERTY && !roots.contains(t.root)) continue;
                TextRange range = new TextRange(valueRange.getStartOffset() + t.start, valueRange.getStartOffset() + t.end);
                references.add(new ZkBindingReference(attrValue, range, t));
            }
            return references.toArray(PsiReference.EMPTY_ARRAY);
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
