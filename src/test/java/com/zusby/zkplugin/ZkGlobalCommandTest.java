package com.zusby.zkplugin;

import com.intellij.codeInsight.daemon.impl.analysis.XmlUnresolvedReferenceInspection;
import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ZkGlobalCommandTest extends LightJavaCodeInsightFixtureTestCase {

    private PsiClass vm;
    private PsiClass otherVm;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        WriteAction.run(() -> FileTypeManager.getInstance().associateExtension(XmlFileType.INSTANCE, "zul"));
        myFixture.addClass("package org.zkoss.bind.annotation; public @interface Command { String[] value() default {}; }");
        myFixture.addClass("package org.zkoss.bind.annotation; public @interface GlobalCommand { String[] value() default {}; }");
        myFixture.addClass(
                "package org.zkoss.bind; public class BindUtils {\n" +
                "  public static void postGlobalCommand(String q, String s, String cmd, java.util.Map<String, Object> args) {}\n" +
                "}");
        vm = myFixture.addClass(
                "package com.test; import org.zkoss.bind.annotation.*; public class MyVm {\n" +
                "  @Command public void refresh() {}\n" +
                "  @GlobalCommand public void reload() {}\n" +
                "}");
        otherVm = myFixture.addClass(
                "package com.test; import org.zkoss.bind.annotation.*; public class OtherVm {\n" +
                "  @GlobalCommand public void reload() {}\n" +
                "  @GlobalCommand(\"renamedCmd\") public void handler() {}\n" +
                "}");
    }

    private void zul(String body) {
        myFixture.configureByText("test.zul",
                "<zk>\n<window viewModel=\"@id('vm') @init('com.test.MyVm')\">\n" + body + "\n</window>\n</zk>\n");
    }

    private PsiPolyVariantReference referenceAtCaret() {
        PsiReference ref = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
        assertTrue("Expected a poly-variant reference, got " + ref, ref instanceof PsiPolyVariantReference);
        return (PsiPolyVariantReference) ref;
    }

    private Set<PsiElement> targets(PsiPolyVariantReference ref) {
        Set<PsiElement> result = new HashSet<>();
        for (ResolveResult r : ref.multiResolve(false)) result.add(r.getElement());
        return result;
    }

    private PsiMethod method(PsiClass cls, String name) {
        return cls.findMethodsByName(name, false)[0];
    }

    public void testGlobalCommandResolvesToEveryHandler() {
        zul("<button onClick=\"@global-command('rel<caret>oad')\"/>");
        assertEquals(Set.of(method(vm, "reload"), method(otherVm, "reload")), targets(referenceAtCaret()));
    }

    public void testGlobalCommandDoesNotResolveToPlainCommand() {
        zul("<button onClick=\"@global-command('ref<caret>resh')\"/>");
        assertTrue(targets(referenceAtCaret()).isEmpty());
    }

    public void testExplicitGlobalCommandName() {
        zul("<button onClick=\"@global-command('renamed<caret>Cmd')\"/>");
        assertEquals(Set.of(method(otherVm, "handler")), targets(referenceAtCaret()));
    }

    public void testUnresolvedGlobalCommandIsHighlighted() {
        myFixture.enableInspections(new XmlUnresolvedReferenceInspection());
        zul("<button onClick=\"@global-command('<error descr=\"Cannot resolve symbol 'missing'\">missing</error>')\"/>");
        myFixture.checkHighlighting(false, false, false);
    }

    public void testCreateGlobalCommandFix() {
        myFixture.enableInspections(new XmlUnresolvedReferenceInspection());
        zul("<button onClick=\"@global-command('mis<caret>sing')\"/>");
        myFixture.doHighlighting();
        myFixture.launchAction(myFixture.findSingleIntention("Create global command 'missing' in MyVm"));
        assertEquals(List.of("missing"), ZkViewModelResolver.globalCommandNames(method(vm, "missing")));
    }

    public void testGlobalCommandCompletion() {
        zul("<button onClick=\"@global-command('<caret>')\"/>");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertContainsElements(items, "reload", "renamedCmd");
        assertDoesntContain(items, "refresh");
    }

    public void testGlobalCommandHandlerIsUsedFromZulAndJava() {
        myFixture.addFileToProject("a.zul",
                "<zk><window viewModel=\"@id('vm') @init('com.test.MyVm')\"><button onClick=\"@global-command('reload')\"/></window></zk>");
        myFixture.addClass(
                "package com.test; public class Poster {\n" +
                "  void post() { org.zkoss.bind.BindUtils.postGlobalCommand(null, null, \"reload\", null); }\n" +
                "}");
        // Both handlers of a global command count: the .zul usage and the Java post.
        assertEquals(2, ReferencesSearch.search(method(otherVm, "reload"), GlobalSearchScope.projectScope(getProject())).findAll().size());
    }

    public void testPostGlobalCommandLiteralResolves() {
        myFixture.configureByText("Poster.java",
                "package com.test; public class Poster {\n" +
                "  void post() { org.zkoss.bind.BindUtils.postGlobalCommand(null, null, \"rel<caret>oad\", null); }\n" +
                "}");
        assertEquals(Set.of(method(vm, "reload"), method(otherVm, "reload")), targets(referenceAtCaret()));
    }

    public void testPostGlobalCommandTypoIsReported() {
        myFixture.enableInspections(new ZkUnresolvedJavaReferenceInspection());
        myFixture.configureByText("Poster.java",
                "package com.test; public class Poster {\n" +
                "  void post() { org.zkoss.bind.BindUtils.postGlobalCommand(null, null, \"<warning descr=\"No @GlobalCommand handler named 'relaod'\">relaod</warning>\", null); }\n" +
                "}");
        myFixture.checkHighlighting();
    }

    public void testRenameGlobalCommandHandlerUpdatesZul() {
        PsiClass single = myFixture.addClass(
                "package com.test; public class SingleVm { @org.zkoss.bind.annotation.GlobalCommand public void ping() {} }");
        zul("<button onClick=\"@global-command('ping')\"/>");
        myFixture.renameElement(method(single, "ping"), "pong");
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("@global-command('pong')"));
    }
}
