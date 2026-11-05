package com.zusby.zkplugin;

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.pom.Navigatable;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import org.jetbrains.annotations.NotNull;

/**
 * Quick fixes offered on unresolved ZK bindings: create the missing property
 * (field + getter + setter) or {@code @Command} method in the ViewModel / bean class.
 */
public abstract class ZkCreateMemberFix implements LocalQuickFix {

    protected final SmartPsiElementPointer<PsiClass> targetClass;
    protected final String name;
    private final String className;

    protected ZkCreateMemberFix(@NotNull PsiClass target, @NotNull String name) {
        this.targetClass = SmartPointerManager.createPointer(target);
        this.name = name;
        this.className = target.getName();
    }

    protected String className() {
        return className;
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Create ZK ViewModel member";
    }

    /** The fix edits another file (the Java class), so no in-place preview. */
    @Override
    public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull ProblemDescriptor previewDescriptor) {
        return IntentionPreviewInfo.EMPTY;
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiClass cls = targetClass.getElement();
        if (cls == null || !cls.isValid()) return;
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        PsiElement navigateTo = create(cls, factory);
        if (navigateTo != null) {
            SmartPsiElementPointer<PsiElement> pointer = SmartPointerManager.createPointer(navigateTo);
            ApplicationManager.getApplication().invokeLater(() -> {
                PsiElement e = pointer.getElement();
                if (e instanceof Navigatable && ((Navigatable) e).canNavigate()) ((Navigatable) e).navigate(true);
            }, project.getDisposed());
        }
    }

    /** Adds the member(s) to {@code cls} and returns the element to navigate to. */
    protected abstract PsiElement create(@NotNull PsiClass cls, @NotNull PsiElementFactory factory);

    static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Creates {@code private String name;} plus getter and setter (type can be changed afterwards). */
    public static final class Property extends ZkCreateMemberFix {
        public Property(@NotNull PsiClass target, @NotNull String name) {
            super(target, name);
        }

        @Override
        public @NotNull String getName() {
            return "Create property '" + name + "' in " + className();
        }

        @Override
        protected PsiElement create(@NotNull PsiClass cls, @NotNull PsiElementFactory factory) {
            String cap = capitalize(name);
            PsiElement field = cls.findFieldByName(name, false);
            if (field == null) {
                field = cls.add(factory.createFieldFromText("private String " + name + ";", cls));
            }
            if (cls.findMethodsByName("get" + cap, true).length == 0 && cls.findMethodsByName("is" + cap, true).length == 0) {
                cls.add(factory.createMethodFromText(
                        "public String get" + cap + "() {\n return " + name + ";\n}", cls));
            }
            if (cls.findMethodsByName("set" + cap, true).length == 0) {
                cls.add(factory.createMethodFromText(
                        "public void set" + cap + "(String " + name + ") {\n this." + name + " = " + name + ";\n}", cls));
            }
            return field;
        }
    }

    /** Creates {@code @Command public void name() {}}. */
    public static final class Command extends ZkCreateMemberFix {
        public Command(@NotNull PsiClass target, @NotNull String name) {
            super(target, name);
        }

        @Override
        public @NotNull String getName() {
            return "Create command '" + name + "' in " + className();
        }

        @Override
        protected PsiElement create(@NotNull PsiClass cls, @NotNull PsiElementFactory factory) {
            PsiMethod method = factory.createMethodFromText(
                    "@org.zkoss.bind.annotation.Command\npublic void " + name + "() {\n}", cls);
            PsiElement added = cls.add(method);
            JavaCodeStyleManager.getInstance(cls.getProject()).shortenClassReferences(added);
            return added;
        }
    }
}
