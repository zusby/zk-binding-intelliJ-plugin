package com.zusby.zkplugin;

import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the string literals ZK uses to name ViewModel members in Java code into references:
 * <ul>
 *     <li>{@code @NotifyChange("name")}, {@code @NotifyChange({"a", "b"})}, {@code @DependsOn("x")},
 *         {@code @SmartNotifyChange("x")} → the property's getter in the enclosing class</li>
 *     <li>{@code BindUtils.postNotifyChange(queue, scope, bean, "name", ...)} → the property of
 *         {@code bean}'s type</li>
 *     <li>{@code BindUtils.postGlobalCommand(queue, scope, "name", args)} → the
 *         {@code @GlobalCommand} handlers</li>
 * </ul>
 * This gives Ctrl+Click, completion and rename for those names, and lets
 * {@link ZkUnresolvedJavaReferenceInspection} report typos.
 */
public class ZkJavaReferenceContributor extends PsiReferenceContributor {

    private static final Set<String> PROPERTY_ANNOTATIONS = Set.of("NotifyChange", "DependsOn", "SmartNotifyChange");
    /** Runs for every string literal in the project, so keep the first check cheap. */
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(PsiLiteralExpression.class), new Provider());
    }

    private static final class Provider extends PsiReferenceProvider {
        @Override
        public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element, @NotNull ProcessingContext context) {
            PsiLiteralExpression literal = (PsiLiteralExpression) element;
            if (!(literal.getValue() instanceof String)) return PsiReference.EMPTY_ARRAY;
            // stripTrailing: during completion the literal holds the dummy identifier "IntellijIdeaRulezzz ".
            String name = ((String) literal.getValue()).stripTrailing();
            // "*" (all properties) and "." (the bean itself) are ZK wildcards, not property names.
            if (!IDENTIFIER.matcher(name).matches()) return PsiReference.EMPTY_ARRAY;
            TextRange range = ElementManipulators.getValueTextRange(literal);

            PsiElement parent = literal.getParent();
            if (parent instanceof PsiArrayInitializerMemberValue) parent = parent.getParent();
            if (parent instanceof PsiNameValuePair) {
                PsiClass owner = annotatedPropertyOwner((PsiNameValuePair) parent);
                return owner == null ? PsiReference.EMPTY_ARRAY
                        : new PsiReference[]{new ZkJavaPropertyReference(literal, range, name, owner)};
            }

            if (parent instanceof PsiExpressionList && parent.getParent() instanceof PsiMethodCallExpression) {
                PsiMethodCallExpression call = (PsiMethodCallExpression) parent.getParent();
                String method = call.getMethodExpression().getReferenceName();
                PsiExpression[] args = ((PsiExpressionList) parent).getExpressions();
                int index = indexOf(args, literal);
                if ("postNotifyChange".equals(method) && index >= 3 && isBindUtils(call)) {
                    PsiClass owner = classOf(args[2]);
                    return owner == null ? PsiReference.EMPTY_ARRAY
                            : new PsiReference[]{new ZkJavaPropertyReference(literal, range, name, owner)};
                }
                if ("postGlobalCommand".equals(method) && index == 2 && isBindUtils(call)) {
                    return new PsiReference[]{
                            new ZkBindingReferenceContributor.ZkGlobalCommandReference(literal, range, name)};
                }
            }
            return PsiReference.EMPTY_ARRAY;
        }
    }

    /** The class whose properties a {@code @NotifyChange}-style annotation names, or {@code null}. */
    @Nullable
    private static PsiClass annotatedPropertyOwner(PsiNameValuePair pair) {
        String attribute = pair.getName();
        if (attribute != null && !"value".equals(attribute)) return null;
        PsiAnnotation annotation = PsiTreeUtil.getParentOfType(pair, PsiAnnotation.class);
        if (annotation == null || !(annotation.getOwner() instanceof PsiModifierList)) return null;
        PsiElement annotated = ((PsiModifierList) annotation.getOwner()).getParent();
        if (!(annotated instanceof PsiModifierListOwner)) return null;
        boolean isZk = false;
        for (String shortName : PROPERTY_ANNOTATIONS) {
            if (ZkViewModelResolver.zkAnnotation((PsiModifierListOwner) annotated, shortName) == annotation) {
                isZk = true;
                break;
            }
        }
        return isZk ? PsiTreeUtil.getParentOfType(annotation, PsiClass.class) : null;
    }

    private static boolean isBindUtils(PsiMethodCallExpression call) {
        PsiMethod target = call.resolveMethod();
        PsiClass cls = target != null ? target.getContainingClass() : null;
        return cls != null && "BindUtils".equals(cls.getName());
    }

    @Nullable
    private static PsiClass classOf(PsiExpression bean) {
        if (bean instanceof PsiThisExpression && ((PsiThisExpression) bean).getQualifier() == null) {
            return PsiTreeUtil.getParentOfType(bean, PsiClass.class);
        }
        return PsiUtil.resolveClassInClassTypeOnly(bean.getType());
    }

    private static int indexOf(PsiExpression[] args, PsiExpression arg) {
        for (int i = 0; i < args.length; i++) {
            if (args[i] == arg) return i;
        }
        return -1;
    }

    /** A property name in a Java string literal, resolving to the property's getter (or setter). */
    static final class ZkJavaPropertyReference extends PsiReferenceBase<PsiLiteralExpression> {
        private final String name;
        private final PsiClass owner;

        ZkJavaPropertyReference(@NotNull PsiLiteralExpression element, @NotNull TextRange range,
                                @NotNull String name, @NotNull PsiClass owner) {
            super(element, range, false);
            this.name = name;
            this.owner = owner;
        }

        @NotNull
        PsiClass getOwner() {
            return owner;
        }

        @NotNull
        String getName() {
            return name;
        }

        @Nullable
        @Override
        public PsiElement resolve() {
            ZkViewModelResolver.Property p = ZkViewModelResolver.properties(owner).get(name);
            return p != null ? p.method : null;
        }

        @Override
        public boolean isReferenceTo(@NotNull PsiElement element) {
            if (!(element instanceof PsiMethod)) return false;
            PsiMethod method = (PsiMethod) element;
            PsiClass methodClass = method.getContainingClass();
            return methodClass != null && name.equals(ZkBindingUtils.getPropertyName(method))
                    && ZkBindingUtils.isInClassHierarchy(methodClass, owner);
        }

        @Override
        public PsiElement handleElementRename(@NotNull String newElementName) throws IncorrectOperationException {
            String property = ZkBindingUtils.propertyNameFromMethodName(newElementName);
            return super.handleElementRename(property != null ? property : newElementName);
        }

        @Override
        public Object @NotNull [] getVariants() {
            List<Object> variants = new ArrayList<>();
            for (ZkViewModelResolver.Property p : ZkViewModelResolver.properties(owner).values()) {
                variants.add(LookupElementBuilder.create(p.method, p.name)
                        .withIcon(AllIcons.Nodes.Property)
                        .withTypeText(p.type != null ? p.type.getPresentableText() : null));
            }
            return variants.toArray();
        }
    }
}
