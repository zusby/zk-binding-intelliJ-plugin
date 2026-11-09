package com.zusby.zkplugin;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Code completion inside ZK binding expressions in .zul files:
 * <ul>
 *     <li>{@code @bind(vm.|)}, {@code @load(vm.person.|)}, ... → ViewModel properties</li>
 *     <li>{@code @bind(fx.|)} → properties of the form's backing bean</li>
 *     <li>{@code @command('|')}, {@code @save(vm.x, before='|')} → {@code @Command} methods</li>
 * </ul>
 */
public class ZkCompletionContributor extends CompletionContributor {

    public ZkCompletionContributor() {
        extend(CompletionType.BASIC,
                PlatformPatterns.psiElement().inside(XmlAttributeValue.class),
                new CompletionProvider<>() {
                    @Override
                    protected void addCompletions(@NotNull CompletionParameters parameters,
                                                  @NotNull ProcessingContext context,
                                                  @NotNull CompletionResultSet result) {
                        addZkCompletions(parameters, result);
                    }
                });
    }

    private static void addZkCompletions(CompletionParameters parameters, CompletionResultSet result) {
        PsiFile file = parameters.getOriginalFile();
        if (!(file instanceof XmlFile) || !file.getName().endsWith(".zul")) return;

        PsiElement position = parameters.getPosition();
        XmlAttributeValue attrValue = PsiTreeUtil.getParentOfType(position, XmlAttributeValue.class, false);
        if (attrValue == null) return;

        // Text of the attribute value from its start up to the caret (offsets match the original file).
        int start = attrValue.getTextRange().getStartOffset();
        int caret = parameters.getOffset();
        if (caret < start) return;
        String before = parameters.getEditor().getDocument().getCharsSequence().subSequence(start, caret).toString();

        XmlFile xmlFile = (XmlFile) file;
        ZkBindingParser.CompletionContext ctx = ZkBindingParser.completionContext(before);
        if (ctx == null) return;

        if (ctx.kind == ZkBindingParser.Kind.PROPERTY) {
            if (ctx.root == null || !ZkViewModelResolver.roots(xmlFile).contains(ctx.root)) return;
            PsiClass target = ZkViewModelResolver.ownerClass(xmlFile, ctx.root, ctx.qualifier);
            if (target == null) return;

            CompletionResultSet rs = result.withPrefixMatcher(ctx.prefix);
            for (ZkViewModelResolver.Property p : ZkViewModelResolver.properties(target).values()) {
                PsiClass owner = p.method.getContainingClass();
                rs.addElement(LookupElementBuilder.create(p.method, p.name)
                        .withIcon(AllIcons.Nodes.Property)
                        .withTypeText(p.type != null ? p.type.getPresentableText() : null)
                        .withTailText(owner != null && owner != target ? "  (" + owner.getName() + ")" : null, true));
            }
            rs.stopHere();
            return;
        }

        if (ctx.kind == ZkBindingParser.Kind.GLOBAL_COMMAND) {
            CompletionResultSet rs = result.withPrefixMatcher(ctx.prefix);
            for (Map.Entry<String, List<PsiMethod>> e : ZkViewModelResolver.globalCommands(file.getProject()).entrySet()) {
                List<PsiMethod> handlers = e.getValue();
                PsiClass owner = handlers.get(0).getContainingClass();
                String where = handlers.size() > 1 ? handlers.size() + " handlers" : owner != null ? owner.getName() : null;
                rs.addElement(LookupElementBuilder.create(handlers.get(0), e.getKey())
                        .withIcon(AllIcons.Nodes.Method)
                        .withTailText(where != null ? "  (" + where + ")" : null, true)
                        .withTypeText("@GlobalCommand"));
            }
            rs.stopHere();
            return;
        }

        {
            PsiClass vm = ZkViewModelResolver.resolveViewModelClass(xmlFile);
            if (vm == null) return;

            CompletionResultSet rs = result.withPrefixMatcher(ctx.prefix);
            for (Map.Entry<String, PsiMethod> e : ZkViewModelResolver.commands(vm).entrySet()) {
                PsiClass owner = e.getValue().getContainingClass();
                rs.addElement(LookupElementBuilder.create(e.getValue(), e.getKey())
                        .withIcon(AllIcons.Nodes.Method)
                        .withTailText(owner != null && owner != vm ? "  (" + owner.getName() + ")" : null, true)
                        .withTypeText("@Command"));
            }
            rs.stopHere();
        }
    }
}
