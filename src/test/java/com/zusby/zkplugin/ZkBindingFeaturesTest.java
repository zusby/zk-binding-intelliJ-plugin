package com.zusby.zkplugin;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.daemon.impl.analysis.XmlUnresolvedReferenceInspection;
import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.Collection;
import java.util.List;

public class ZkBindingFeaturesTest extends LightJavaCodeInsightFixtureTestCase {

    private PsiClass vm;
    private PsiClass address;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        WriteAction.run(() -> FileTypeManager.getInstance().associateExtension(XmlFileType.INSTANCE, "zul"));
        myFixture.addClass("package org.zkoss.bind.annotation; public @interface Command { String[] value() default {}; }");
        address = myFixture.addClass(
                "package com.test; public class Address {\n" +
                "  public String getCity() { return null; }\n" +
                "  public void setCity(String c) {}\n" +
                "}");
        vm = myFixture.addClass(
                "package com.test; public class MyVm {\n" +
                "  public String getName() { return null; }\n" +
                "  public int getCount() { return 0; }\n" +
                "  public Address getAddress() { return null; }\n" +
                "  @org.zkoss.bind.annotation.Command public void save() {}\n" +
                "}");
    }

    private void zul(String body) {
        myFixture.configureByText("test.zul",
                "<zk>\n<window viewModel=\"@id('vm') @init('com.test.MyVm')\">\n" + body + "\n</window>\n</zk>\n");
    }

    private PsiElement resolveAtCaret() {
        PsiReference ref = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
        assertNotNull("No reference at caret", ref);
        return ref.resolve();
    }

    private void assertResolvesTo(String methodName, PsiClass cls) {
        PsiElement target = resolveAtCaret();
        assertTrue("Expected method, got " + target, target instanceof PsiMethod);
        assertEquals(methodName, ((PsiMethod) target).getName());
        assertEquals(cls, ((PsiMethod) target).getContainingClass());
    }

    private Collection<PsiReference> usages(PsiMethod method) {
        return ReferencesSearch.search(method, GlobalSearchScope.projectScope(getProject())).findAll();
    }

    // --- commands with parameters ---

    public void testCommandWithParametersResolves() {
        zul("<button onClick=\"@command('sa<caret>ve', item=self, flag=true)\"/>");
        assertResolvesTo("save", vm);
    }

    public void testCommandWithParametersCountsAsUsage() {
        zul("<button onClick=\"@command('save', item=self)\"/>");
        assertFalse("Find Usages should see the .zul command", usages(vm.findMethodsByName("save", false)[0]).isEmpty());
    }

    // --- nested & expression bindings ---

    public void testNestedSegmentResolvesToNestedClass() {
        zul("<label value=\"@load(vm.address.ci<caret>ty)\"/>");
        assertResolvesTo("getCity", address);
    }

    public void testFirstSegmentOfNestedPathResolves() {
        zul("<label value=\"@load(vm.addr<caret>ess.city)\"/>");
        assertResolvesTo("getAddress", vm);
    }

    public void testExpressionResolvesEveryPath() {
        zul("<label value=\"@load(vm.count gt 0 ? vm.na<caret>me : 'none')\"/>");
        assertResolvesTo("getName", vm);
    }

    public void testNestedGetterIsUsedAndFound() {
        zul("<label value=\"@load(vm.address.city)\"/>");
        PsiMethod getCity = address.findMethodsByName("getCity", false)[0];
        assertTrue(new ZkImplicitUsageProvider().isImplicitUsage(getCity));
        assertEquals(1, usages(getCity).size());
    }

    public void testUsageCacheFollowsZulEdits() {
        zul("<label value=\"<caret>\"/>");
        PsiMethod getName = vm.findMethodsByName("getName", false)[0];
        assertFalse(new ZkImplicitUsageProvider().isImplicitUsage(getName));
        myFixture.type("@load(vm.name)");
        com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertTrue("Binding typed into .zul must be picked up", new ZkImplicitUsageProvider().isImplicitUsage(getName));
        myFixture.addFileToProject("other.zul",
                "<zk><window viewModel=\"@id('vm') @init('com.test.MyVm')\"><label value=\"@load(vm.count)\"/></window></zk>");
        assertTrue("New .zul file must be picked up",
                new ZkImplicitUsageProvider().isImplicitUsage(vm.findMethodsByName("getCount", false)[0]));
    }

    public void testUnrelatedClassWithSamePropertyIsNotMatched() {
        PsiClass other = myFixture.addClass("package com.test; public class Other { public String getCity() { return null; } }");
        zul("<label value=\"@load(vm.address.city)\"/>");
        assertFalse(new ZkImplicitUsageProvider().isImplicitUsage(other.findMethodsByName("getCity", false)[0]));
    }

    public void testRenameGetterUpdatesNestedBinding() {
        zul("<label value=\"@load(vm.address.city)\"/>");
        myFixture.renameElement(address.findMethodsByName("getCity", false)[0], "getTown");
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("@load(vm.address.town)"));
    }

    // --- form binding ---

    private static final String FORM =
            "<grid form=\"@id('fx') @load(vm.address) @save(vm.address, before='save')\">\n" +
            "  <textbox value=\"@bind(fx.%s)\"/>\n</grid>";

    public void testFormPropertyResolvesToBackingBean() {
        zul(String.format(FORM, "ci<caret>ty"));
        assertResolvesTo("getCity", address);
    }

    public void testFormBeforeCommandResolves() {
        zul("<grid form=\"@id('fx') @load(vm.address) @save(vm.address, before='sa<caret>ve')\"/>");
        assertResolvesTo("save", vm);
    }

    public void testFormCompletion() {
        zul(String.format(FORM, "<caret>"));
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertContainsElements(items, "city");
        assertDoesntContain(items, "name");
    }

    public void testFormSetterUsage() {
        zul(String.format(FORM, "city"));
        assertTrue(new ZkImplicitUsageProvider().isImplicitUsage(address.findMethodsByName("setCity", false)[0]));
    }

    // --- red highlighting & quick fixes ---

    private void launchFix(String text) {
        myFixture.enableInspections(new XmlUnresolvedReferenceInspection());
        myFixture.doHighlighting();
        IntentionAction fix = myFixture.findSingleIntention(text);
        myFixture.launchAction(fix);
    }

    public void testUnresolvedPropertyIsHighlighted() {
        myFixture.enableInspections(new XmlUnresolvedReferenceInspection());
        zul("<label value=\"@load(vm.<error descr=\"Cannot resolve symbol 'missing'\">missing</error>)\"/>");
        myFixture.checkHighlighting(false, false, false);
    }

    public void testUnresolvedParentDoesNotFlagChild() {
        myFixture.enableInspections(new XmlUnresolvedReferenceInspection());
        zul("<label value=\"@load(vm.<error descr=\"Cannot resolve symbol 'nope'\">nope</error>.child)\"/>");
        myFixture.checkHighlighting(false, false, false);
    }

    public void testCreatePropertyFix() {
        zul("<label value=\"@load(vm.miss<caret>ing)\"/>");
        launchFix("Create property 'missing' in MyVm");
        assertEquals(1, vm.findMethodsByName("getMissing", false).length);
        assertEquals(1, vm.findMethodsByName("setMissing", false).length);
        assertNotNull(vm.findFieldByName("missing", false));
    }

    public void testCreateNestedPropertyFixTargetsNestedClass() {
        zul("<label value=\"@load(vm.address.zi<caret>p)\"/>");
        launchFix("Create property 'zip' in Address");
        assertEquals(1, address.findMethodsByName("getZip", false).length);
    }

    public void testCreateCommandFix() {
        zul("<button onClick=\"@command('del<caret>ete', item=self)\"/>");
        launchFix("Create command 'delete' in MyVm");
        PsiMethod[] created = vm.findMethodsByName("delete", false);
        assertEquals(1, created.length);
        assertEquals(List.of("delete"), ZkViewModelResolver.commandNames(created[0]));
    }
}
