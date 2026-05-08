package com.zusby.zkplugin;

import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public class ZkScriptInjector implements MultiHostInjector {

    @Override
    public void getLanguagesToInject(@NotNull MultiHostRegistrar registrar,
                                     @NotNull PsiElement context) {
        if (!(context instanceof XmlText)) return;

        XmlTag tag = ((XmlText) context).getParentTag();
        if (tag == null || !"zscript".equals(tag.getName())) return;

        if (!context.getContainingFile().getName().endsWith(".zul")) return;

        String text = context.getText();
        if (text.trim().isEmpty()) return;

        registrar.startInjecting(JavaLanguage.INSTANCE)
                 .addPlace(null, null, (PsiLanguageInjectionHost) context,
                           new TextRange(0, context.getTextLength()))
                 .doneInjecting();
    }

    @Override
    public @NotNull List<? extends Class<? extends PsiElement>> elementsToInjectIn() {
        return Collections.singletonList(XmlText.class);
    }
}
