package com.zusby.zkplugin;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

/**
 * Reports ZK member names in Java string literals that don't exist:
 * {@code @NotifyChange("nmae")}, {@code BindUtils.postNotifyChange(null, null, this, "nmae")}
 * or {@code BindUtils.postGlobalCommand(null, null, "refrsh", null)}. These typos fail silently
 * at runtime: the UI simply doesn't refresh.
 */
public class ZkUnresolvedJavaReferenceInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitLiteralExpression(@NotNull PsiLiteralExpression literal) {
                if (!(literal.getValue() instanceof String)) return;
                for (PsiReference ref : literal.getReferences()) {
                    if (ref instanceof ZkJavaReferenceContributor.ZkJavaPropertyReference) {
                        checkProperty((ZkJavaReferenceContributor.ZkJavaPropertyReference) ref);
                    } else if (ref instanceof ZkBindingReferenceContributor.ZkGlobalCommandReference) {
                        checkGlobalCommand((ZkBindingReferenceContributor.ZkGlobalCommandReference) ref);
                    }
                }
            }

            private void checkProperty(ZkJavaReferenceContributor.ZkJavaPropertyReference ref) {
                if (ref.resolve() != null) return;
                PsiClass owner = ref.getOwner();
                LocalQuickFix[] fixes = owner instanceof PsiCompiledElement || !owner.isWritable()
                        ? LocalQuickFix.EMPTY_ARRAY
                        : new LocalQuickFix[]{new ZkCreateMemberFix.Property(owner, ref.getName())};
                holder.registerProblem(ref.getElement(), ref.getRangeInElement(),
                        "Property '" + ref.getName() + "' not found in " + owner.getName(), fixes);
            }

            private void checkGlobalCommand(ZkBindingReferenceContributor.ZkGlobalCommandReference ref) {
                if (ref.isSoft() || ref.multiResolve(false).length > 0) return;
                holder.registerProblem(ref.getElement(), ref.getRangeInElement(),
                        "No @GlobalCommand handler named '" + ref.getName() + "'");
            }
        };
    }
}
