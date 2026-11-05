package com.zusby.zkplugin;

import com.intellij.openapi.application.QueryExecutorBase;
import com.intellij.psi.*;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;

/**
 * Extends "Find Usages" and "Rename" to include binding expressions in .zul files.
 * Without this, {@code @bind(vm.username)} would not appear in the usage results
 * for {@code getUsername()}, because the .zul text never contains the method name.
 * <p>
 * IntelliJ also runs reference searches in the background (e.g. for unused-declaration
 * checks on public methods), so this must be cheap for methods that are not bound:
 * it relies on the cached project-wide binding map and returns immediately for them.
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
        if (ZkBindingUtils.searchWords(method).isEmpty()) return;

        SearchScope scope = queryParameters.getEffectiveSearchScope();
        if (scope instanceof LocalSearchScope) {
            for (PsiElement e : ((LocalSearchScope) scope).getScope()) {
                PsiFile f = e.getContainingFile();
                if (!(f instanceof XmlFile) || !f.getName().endsWith(".zul")) continue;
                for (int offset : ZkBindingUtils.findUsageOffsets((XmlFile) f, method)) {
                    if (!report((XmlFile) f, offset, consumer)) return;
                }
            }
            return;
        }

        for (ZkBindingUtils.Usage usage : ZkBindingUtils.findUsages(method)) {
            if (!scope.contains(usage.file.getVirtualFile())) continue;
            if (!report(usage.file, usage.offset, consumer)) return;
        }
    }

    /** Hands the real {@link ZkBindingReferenceContributor.ZkBindingReference} at {@code offset} to the consumer. */
    private static boolean report(XmlFile file, int offset, Processor<? super PsiReference> consumer) {
        XmlAttributeValue value = PsiTreeUtil.getParentOfType(file.findElementAt(offset), XmlAttributeValue.class, false);
        if (value == null) return true;
        int relative = offset - value.getTextRange().getStartOffset();
        for (PsiReference ref : value.getReferences()) {
            if (ref instanceof ZkBindingReferenceContributor.ZkBindingReference
                    && ref.getRangeInElement().getStartOffset() == relative) {
                if (!consumer.process(ref)) return false;
            }
        }
        return true;
    }
}
