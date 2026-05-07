package com.zusby.zkplugin;

import com.intellij.openapi.application.QueryExecutorBase;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

/**
 * Extends "Find Usages" to include binding expressions in .zul files.
 * Without this, {@code @bind(vm.username)} would not appear in the usage results
 * for {@code getUsername()}.
 */
public class ZkReferencesSearcher extends QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters> {

    public ZkReferencesSearcher() {
        super(true);
    }

    @Override
    public void processQuery(@NotNull ReferencesSearch.SearchParameters queryParameters,
                             @NotNull Processor<? super PsiReference> consumer) {
        PsiElement element = queryParameters.getElementToSearch();
        if (!(element instanceof PsiMethod)) return;

        PsiMethod method = (PsiMethod) element;
        String propertyName = ZkBindingUtils.getPropertyName(method);
        if (propertyName == null) {
            if (!hasCommandAnnotation(method)) return;
            propertyName = method.getName();
        }

        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) return;

        Project project = method.getProject();
        com.intellij.psi.search.SearchScope effectiveScope = queryParameters.getEffectiveSearchScope();

        // LocalSearchScope cannot be used for iterating virtual files — fall back to project scope
        GlobalSearchScope scope = effectiveScope instanceof GlobalSearchScope
                ? (GlobalSearchScope) effectiveScope
                : GlobalSearchScope.projectScope(project);

        Collection<VirtualFile> zulFiles = ZkBindingUtils.findZulFiles(project, scope);

        for (VirtualFile vFile : zulFiles) {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vFile);
            if (!(psiFile instanceof XmlFile)) continue;

            XmlFile xmlFile = (XmlFile) psiFile;
            if (!ZkBindingUtils.usesViewModel(xmlFile, containingClass)) continue;

            List<Integer> offsets = ZkBindingUtils.findBindingOffsets(xmlFile, propertyName);
            for (Integer offset : offsets) {
                PsiElement refElement = xmlFile.findElementAt(offset);
                if (refElement != null) {
                    consumer.process(new ZkUsageReference(refElement, method));
                }
            }
        }
    }

    private boolean hasCommandAnnotation(PsiMethod method) {
        for (PsiAnnotation annotation : method.getModifierList().getAnnotations()) {
            String qName = annotation.getQualifiedName();
            if (qName != null && qName.endsWith(".Command")) return true;
        }
        return false;
    }

    /**
     * A synthetic PSI reference that points from a .zul binding expression back to the
     * Java method it references. Used to populate "Find Usages" results.
     */
    private static class ZkUsageReference extends PsiReferenceBase<PsiElement> {
        private final PsiMethod targetMethod;

        public ZkUsageReference(@NotNull PsiElement element, @NotNull PsiMethod targetMethod) {
            super(element, element.getTextRangeInParent());
            this.targetMethod = targetMethod;
        }

        @Override
        public PsiElement resolve() {
            return targetMethod;
        }

        @NotNull
        @Override
        public Object[] getVariants() {
            return new Object[0];
        }
    }
}
