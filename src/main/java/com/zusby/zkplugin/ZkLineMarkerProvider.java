package com.zusby.zkplugin;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.PlatformIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Adds a gutter icon next to Java methods that are referenced by a ZK binding expression.
 * Clicking the icon navigates to the binding site in the .zul file.
 */
public class ZkLineMarkerProvider implements LineMarkerProvider {

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!(element instanceof PsiIdentifier)) return null;

        PsiElement parent = element.getParent();
        if (!(parent instanceof PsiMethod)) return null;

        PsiMethod method = (PsiMethod) parent;
        if (!isZkRelevantMethod(method)) return null;

        List<PsiElement> targets = findZulReferences(method);
        if (targets.isEmpty()) return null;

        return NavigationGutterIconBuilder
                .create(PlatformIcons.XML_TAG_ICON)
                .setTargets(targets)
                .setTooltipText("Used in " + targets.size() + " ZUL file" + (targets.size() > 1 ? "s" : ""))
                .createLineMarkerInfo(element);
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
        for (PsiAnnotation annotation : method.getModifierList().getAnnotations()) {
            String qName = annotation.getQualifiedName();
            if (qName != null && qName.endsWith(".Command")) return true;
        }
        return false;
    }

    /**
     * Finds all PSI elements in .zul files that reference this method via a binding expression.
     * Returns the property name token at each binding site so the gutter icon navigates there.
     */
    private List<PsiElement> findZulReferences(PsiMethod method) {
        List<PsiElement> references = new ArrayList<>();

        String propertyName = ZkBindingUtils.getPropertyName(method);
        if (propertyName == null) propertyName = method.getName();

        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) return references;

        Project project = method.getProject();
        Collection<VirtualFile> zulFiles = ZkBindingUtils.findZulFiles(project, GlobalSearchScope.projectScope(project));

        for (VirtualFile vFile : zulFiles) {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vFile);
            if (!(psiFile instanceof XmlFile)) continue;

            XmlFile xmlFile = (XmlFile) psiFile;
            if (!ZkBindingUtils.usesViewModel(xmlFile, containingClass)) continue;

            for (Integer offset : ZkBindingUtils.findBindingOffsets(xmlFile, propertyName)) {
                PsiElement el = xmlFile.findElementAt(offset);
                if (el != null) references.add(el);
            }
        }

        return references;
    }
}
