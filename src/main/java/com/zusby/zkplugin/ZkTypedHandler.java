package com.zusby.zkplugin;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/**
 * Opens the completion popup automatically when typing {@code .} after a path root in a
 * binding (e.g. {@code @bind(vm.}) or a quote in {@code @command(} / {@code before=}, since
 * XML attribute values do not trigger auto-popup on their own.
 */
public class ZkTypedHandler extends TypedHandlerDelegate {

    @Override
    public @NotNull Result checkAutoPopup(char charTyped, @NotNull Project project,
                                          @NotNull Editor editor, @NotNull PsiFile file) {
        if (!file.getName().endsWith(".zul")) return Result.CONTINUE;
        if (charTyped != '.' && charTyped != '\'' && charTyped != '"') return Result.CONTINUE;

        // Called before the character is inserted: check the line as it will look afterwards.
        Document document = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        int lineStart = document.getLineStartOffset(document.getLineNumber(offset));
        String after = document.getCharsSequence().subSequence(lineStart, offset).toString() + charTyped;

        if (ZkBindingParser.completionContext(after) != null) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
        }
        return Result.CONTINUE;
    }
}
