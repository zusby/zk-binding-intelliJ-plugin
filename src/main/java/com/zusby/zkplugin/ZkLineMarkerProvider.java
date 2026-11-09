package com.zusby.zkplugin;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.NotNullLazyValue;
import com.intellij.psi.*;
import com.intellij.util.PlatformIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Adds a gutter icon next to Java methods that are referenced by a ZK binding expression.
 * Clicking the icon navigates to the binding site in the .zul file.
 * <p>
 * The search runs in the "slow" line-marker pass (background, after the file is highlighted)
 * instead of {@link #getLineMarkerInfo}, which is called for every identifier on the
 * critical highlighting path. Navigation targets are only turned into PSI when clicked.
 */
public class ZkLineMarkerProvider implements LineMarkerProvider {

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        return null;
    }

    @Override
    public void collectSlowLineMarkers(@NotNull List<? extends PsiElement> elements,
                                       @NotNull Collection<? super LineMarkerInfo<?>> result) {
        for (PsiElement element : elements) {
            ProgressManager.checkCanceled();
            if (!(element instanceof PsiIdentifier) || !(element.getParent() instanceof PsiMethod)) continue;
            PsiMethod method = (PsiMethod) element.getParent();
            if (method.getNameIdentifier() != element || !isZkRelevantMethod(method)) continue;

            List<ZkBindingUtils.Usage> usages = ZkBindingUtils.findUsages(method);
            if (usages.isEmpty()) continue;

            Set<PsiFile> files = new HashSet<>();
            for (ZkBindingUtils.Usage u : usages) files.add(u.file);

            result.add(NavigationGutterIconBuilder
                    .create(PlatformIcons.XML_TAG_ICON)
                    .setTargets(NotNullLazyValue.lazy(() -> navigationTargets(usages)))
                    .setTooltipText("Used " + usages.size() + (usages.size() > 1 ? " times" : " time")
                            + " in " + files.size() + " ZUL file" + (files.size() > 1 ? "s" : ""))
                    .createLineMarkerInfo(element));
        }
    }

    private static Collection<PsiElement> navigationTargets(List<ZkBindingUtils.Usage> usages) {
        List<PsiElement> targets = new ArrayList<>();
        for (ZkBindingUtils.Usage u : usages) {
            if (!u.file.isValid()) continue;
            PsiElement target = toNavigatableTarget(u.file.findElementAt(u.offset));
            if (target != null && !targets.contains(target)) targets.add(target);
        }
        return targets;
    }

    /**
     * Returns true for getters, setters, and methods annotated with {@code @Command}.
     * These are the only method types that ZK MVVM binds to.
     */
    private boolean isZkRelevantMethod(PsiMethod method) {
        String name = method.getName();
        if ((name.startsWith("get") || name.startsWith("is") || name.startsWith("has"))
                && method.getParameterList().getParametersCount() == 0) {
            return true;
        }
        if (name.startsWith("set") && method.getParameterList().getParametersCount() == 1) {
            return true;
        }
        return !ZkViewModelResolver.commandNames(method).isEmpty()
                || !ZkViewModelResolver.globalCommandNames(method).isEmpty();
    }

    /**
     * Leaf tokens (e.g. XML_ATTRIBUTE_VALUE_TOKEN) are not navigatable in IntelliJ 2026.3,
     * so clicking the gutter icon did nothing. Walk up to the nearest element that can
     * actually be navigated to (normally the XmlAttributeValue holding the binding).
     */
    @Nullable
    private static PsiElement toNavigatableTarget(@Nullable PsiElement leaf) {
        for (PsiElement e = leaf; e != null && !(e instanceof PsiFile); e = e.getParent()) {
            if (e instanceof NavigatablePsiElement && ((NavigatablePsiElement) e).canNavigate()) return e;
        }
        return leaf;
    }
}
