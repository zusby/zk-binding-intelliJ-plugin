package com.zusby.zkplugin;

import com.intellij.codeInsight.daemon.ImplicitUsageProvider;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Suppresses "unused method" warnings for getters and setters that are referenced
 * by a ZK binding expression in a .zul file. Without this, IntelliJ highlights
 * ViewModel accessor methods as unused because it cannot see the .zul references.
 */
public class ZkImplicitUsageProvider implements ImplicitUsageProvider {

    @Override
    public boolean isImplicitUsage(@NotNull PsiElement element) {
        if (!(element instanceof PsiMethod)) return false;
        PsiMethod method = (PsiMethod) element;
        if (!isGetterOrSetter(method)) return false;
        return isUsedInZulFiles(method);
    }

    @Override
    public boolean isImplicitRead(@NotNull PsiElement element) {
        return false;
    }

    @Override
    public boolean isImplicitWrite(@NotNull PsiElement element) {
        return false;
    }

    private boolean isGetterOrSetter(PsiMethod method) {
        String name = method.getName();
        if ((name.startsWith("get") || name.startsWith("is") || name.startsWith("has"))
                && method.getParameterList().getParametersCount() == 0) {
            return true;
        }
        return name.startsWith("set") && method.getParameterList().getParametersCount() == 1;
    }

    private boolean isUsedInZulFiles(PsiMethod method) {
        String propertyName = getPropertyName(method);
        if (propertyName == null) return false;
        return !ZkBindingUtils.findUsages(method).isEmpty();
    }

    /**
     * Derives the ZK property name from a getter or setter.
     * Returns {@code null} for methods that don't follow the get/set/is/has convention.
     */
    @Nullable
    private String getPropertyName(PsiMethod method) {
        String name = method.getName();
        if (name.startsWith("get") || name.startsWith("set")) {
            return name.length() <= 3 ? null : decapitalize(name.substring(3));
        }
        if (name.startsWith("is")) {
            return name.length() <= 2 ? null : decapitalize(name.substring(2));
        }
        if (name.startsWith("has")) {
            return name.length() <= 3 ? null : decapitalize(name.substring(3));
        }
        return null;
    }

    private String decapitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        if (s.length() == 1) return s.toLowerCase();
        // Preserve all-caps acronyms like "URLConnection"
        if (Character.isUpperCase(s.charAt(1))) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
