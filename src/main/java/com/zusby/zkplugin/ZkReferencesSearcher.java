package com.zusby.zkplugin;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.application.QueryExecutorBase;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Extends "Find Usages" and "Rename" to include binding expressions in .zul files.
 * Without this, {@code @bind(vm.username)} would not appear in the usage results
 * for {@code getUsername()}, because the .zul text never contains the method name.
 * The same goes for ZK names in Java string literals such as {@code @NotifyChange("username")}.
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
        List<String> words = ZkBindingUtils.searchWords(method);
        if (words.isEmpty()) return;

        SearchScope scope = queryParameters.getEffectiveSearchScope();

        if (!searchJavaLiterals(method, words, scope, consumer)) return;
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

    /**
     * Java string literal references for {@link com.intellij.psi.search.searches.MethodReferencesSearch},
     * which Rename and Java "Find Usages" use. That search handles Java files itself instead of
     * delegating them to the plain {@link ReferencesSearch} executors, so literals in Java code
     * would otherwise be missed (and left stale after a rename).
     */
    public static class MethodSearcher extends QueryExecutorBase<PsiReference, MethodReferencesSearch.SearchParameters> {
        public MethodSearcher() {
            super(true);
        }

        @Override
        public void processQuery(@NotNull MethodReferencesSearch.SearchParameters p, @NotNull Processor<? super PsiReference> consumer) {
            PsiMethod method = p.getMethod();
            List<String> words = ZkBindingUtils.searchWords(method);
            if (!words.isEmpty()) searchJavaLiterals(method, words, p.getEffectiveSearchScope(), consumer);
        }
    }

    /** Words that every Java file holding a ZK member-name literal must contain. */
    private static final List<String> JAVA_MARKERS = List.of("NotifyChange", "SmartNotifyChange", "DependsOn", "BindUtils");
    private static final Key<CachedValue<Set<VirtualFile>>> ZK_JAVA_FILES = Key.create("zk.java.files");

    /**
     * Reports references from Java string literals ({@code @NotifyChange("name")},
     * {@code BindUtils.postGlobalCommand(.., "name", ..)}). Only files mentioning one of
     * {@link #JAVA_MARKERS} are scanned, so a common property name like {@code name} does not
     * make every string literal in a large project a candidate.
     *
     * @return false if the consumer asked to stop
     */
    private static boolean searchJavaLiterals(PsiMethod method, List<String> words, SearchScope scope,
                                              Processor<? super PsiReference> consumer) {
        Project project = method.getProject();
        SearchScope javaScope;
        if (scope instanceof GlobalSearchScope) {
            Set<VirtualFile> candidates = zkJavaFiles(project);
            if (candidates.isEmpty()) return true;
            javaScope = ((GlobalSearchScope) scope).intersectWith(GlobalSearchScope.filesScope(project, candidates));
        } else {
            javaScope = scope;
        }

        PsiSearchHelper helper = PsiSearchHelper.getInstance(project);
        Set<PsiElement> seen = new HashSet<>();
        for (String word : new LinkedHashSet<>(words)) {
            boolean proceed = helper.processElementsWithWord((element, offsetInElement) -> {
                // Called for the literal token and each of its ancestors: handle each literal once.
                PsiElement literal = element instanceof PsiLiteralExpression ? element : element.getParent();
                if (!(literal instanceof PsiLiteralExpression) || !seen.add(literal)) return true;
                for (PsiReference ref : literal.getReferences()) {
                    boolean zkReference = ref instanceof ZkJavaReferenceContributor.ZkJavaPropertyReference
                            || ref instanceof ZkBindingReferenceContributor.ZkGlobalCommandReference;
                    if (zkReference && ref.isReferenceTo(method) && !consumer.process(ref)) return false;
                }
                return true;
            }, javaScope, word, UsageSearchContext.IN_STRINGS, true);
            if (!proceed) return false;
        }
        return true;
    }

    /** Java files in the project that contain one of {@link #JAVA_MARKERS}, cached until the next PSI change. */
    private static Set<VirtualFile> zkJavaFiles(Project project) {
        return CachedValuesManager.getManager(project).getCachedValue(project, ZK_JAVA_FILES, () -> {
            Set<VirtualFile> files = new HashSet<>();
            GlobalSearchScope javaFiles = GlobalSearchScope.getScopeRestrictedByFileTypes(
                    GlobalSearchScope.projectScope(project), JavaFileType.INSTANCE);
            PsiSearchHelper helper = PsiSearchHelper.getInstance(project);
            for (String marker : JAVA_MARKERS) {
                helper.processCandidateFilesForText(javaFiles, UsageSearchContext.IN_CODE, true, marker, files::add);
            }
            return CachedValueProvider.Result.create(files, PsiModificationTracker.MODIFICATION_COUNT);
        }, false);
    }

    /** Hands the real {@link ZkBindingReferenceContributor.ZkBindingReference} at {@code offset} to the consumer. */
    private static boolean report(XmlFile file, int offset, Processor<? super PsiReference> consumer) {
        XmlAttributeValue value = PsiTreeUtil.getParentOfType(file.findElementAt(offset), XmlAttributeValue.class, false);
        if (value == null) return true;
        int relative = offset - value.getTextRange().getStartOffset();
        for (PsiReference ref : value.getReferences()) {
            boolean zkReference = ref instanceof ZkBindingReferenceContributor.ZkBindingReference
                    || ref instanceof ZkBindingReferenceContributor.ZkGlobalCommandReference;
            if (zkReference && ref.getRangeInElement().getStartOffset() == relative) {
                if (!consumer.process(ref)) return false;
            }
        }
        return true;
    }
}
