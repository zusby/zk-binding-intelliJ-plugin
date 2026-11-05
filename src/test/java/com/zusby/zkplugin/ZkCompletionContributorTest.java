package com.zusby.zkplugin;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.List;

public class ZkCompletionContributorTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        WriteAction.run(() -> FileTypeManager.getInstance().associateExtension(XmlFileType.INSTANCE, "zul"));
        myFixture.addClass("package org.zkoss.bind.annotation; public @interface Command { String[] value() default {}; }");
        myFixture.addClass("package com.test; public class Address { public String getCity() { return null; } }");
        myFixture.addClass(
                "package com.test; public class Base {\n" +
                "  public String getName() { return null; }\n" +
                "  public void setName(String n) {}\n" +
                "  public boolean isActive() { return false; }\n" +
                "  public void setOnlyWritable(int x) {}\n" +
                "  public Address getAddress() { return null; }\n" +
                "  @org.zkoss.bind.annotation.Command public void save() {}\n" +
                "  @org.zkoss.bind.annotation.Command(\"doReset\") public void reset() {}\n" +
                "  public void helper() {}\n" +
                "  private String getSecret() { return null; }\n" +
                "}");
        myFixture.addClass("package com.test; public class MyVm extends Base { public String getNotes() { return null; } }");
    }

    private List<String> complete(String attributeValue) {
        myFixture.configureByText("test.zul",
                "<zk>\n<window viewModel=\"@id('vm') @init('com.test.MyVm')\">\n" +
                "  <textbox value=\"" + attributeValue + "\"/>\n</window>\n</zk>\n");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull("Expected a completion popup", items);
        return items;
    }

    public void testPropertiesIncludeWholeHierarchy() {
        List<String> items = complete("@bind(vm.<caret>)");
        assertContainsElements(items, "notes", "name", "active", "address", "onlyWritable");
        assertDoesntContain(items, "save", "helper", "secret", "class");
    }

    public void testNestedPath() {
        List<String> items = complete("@load(vm.address.<caret>)");
        assertContainsElements(items, "city");
        assertDoesntContain(items, "name");
    }

    public void testPrefixFiltersAndSingleItemCompletes() {
        myFixture.configureByText("test.zul",
                "<zk>\n<window viewModel=\"@id('vm') @init('com.test.MyVm')\">\n" +
                "  <textbox value=\"@bind(vm.not<caret>)\"/>\n</window>\n</zk>\n");
        myFixture.completeBasic();
        assertTrue(myFixture.getEditor().getDocument().getText().contains("@bind(vm.notes)"));
    }

    public void testCommands() {
        List<String> items = complete("@command('<caret>')");
        assertContainsElements(items, "save", "doReset");
        assertDoesntContain(items, "reset", "helper", "name");
    }

    public void testCustomViewModelId() {
        myFixture.configureByText("test.zul",
                "<zk>\n<window viewModel=\"@id('model') @init('com.test.MyVm')\">\n" +
                "  <textbox value=\"@bind(model.<caret>)\"/>\n</window>\n</zk>\n");
        myFixture.completeBasic();
        assertContainsElements(myFixture.getLookupElementStrings(), "notes", "name");
    }

    public void testCtrlClickStillResolvesToGetterInSuperclass() {
        myFixture.configureByText("test.zul",
                "<zk>\n<window viewModel=\"@id('vm') @init('com.test.MyVm')\">\n" +
                "  <textbox value=\"@bind(vm.na<caret>me)\"/>\n</window>\n</zk>\n");
        com.intellij.psi.PsiElement target = myFixture.getElementAtCaret();
        assertTrue(target instanceof com.intellij.psi.PsiMethod);
        assertEquals("getName", ((com.intellij.psi.PsiMethod) target).getName());
    }
}
