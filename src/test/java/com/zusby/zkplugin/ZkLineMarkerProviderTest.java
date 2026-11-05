package com.zusby.zkplugin;

import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.navigation.NavigationGutterIconRenderer;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.List;

public class ZkLineMarkerProviderTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String ZUL =
            "<zk>\n" +
            "<window viewModel=\"@id('vm') @init('com.test.MyVm')\">\n" +
            "  <textbox value=\"@bind(vm.name)\"/>\n" +
            "  <button onClick=\"@command('save', item=self)\"/>\n" +
            "  <label value=\"@load(vm.address.city)\"/>\n" +
            "</window>\n" +
            "</zk>\n";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        com.intellij.openapi.application.WriteAction.run(() ->
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance().associateExtension(
                        com.intellij.ide.highlighter.XmlFileType.INSTANCE, "zul"));
    }

    private void addCommandAnnotation() {
        myFixture.addClass("package org.zkoss.bind.annotation; public @interface Command {}");
    }

    private GutterMark gutterAtCaret(String javaWithCaret) {
        addCommandAnnotation();
        myFixture.addFileToProject("test.zul", ZUL);
        myFixture.configureByText("MyVm.java", javaWithCaret);
        List<GutterMark> gutters = myFixture.findGuttersAtCaret();
        GutterMark zk = gutters.stream()
                .filter(g -> g.getTooltipText() != null && g.getTooltipText().contains("ZUL"))
                .findFirst().orElse(null);
        assertNotNull("ZK gutter icon expected, got: " + gutters, zk);
        return zk;
    }

    private void assertNavigatesToZul(GutterMark mark) {
        LineMarkerInfo<?> info = ((LineMarkerInfo.LineMarkerGutterIconRenderer<?>) mark).getLineMarkerInfo();
        assertNotNull("Gutter icon has no navigation handler (click does nothing)", info.getNavigationHandler());
        assertTrue(info.getNavigationHandler() instanceof NavigationGutterIconRenderer);
        List<PsiElement> targets = ((NavigationGutterIconRenderer) info.getNavigationHandler()).getTargetElements();
        assertFalse("No navigation targets", targets.isEmpty());
        for (PsiElement t : targets) {
            assertEquals("test.zul", t.getContainingFile().getName());
            assertTrue("Target not navigatable: " + t + " " + t.getClass(),
                    t instanceof Navigatable && ((Navigatable) t).canNavigate());
        }
    }

    public void testNestedGetterGutterNavigatesToZul() {
        myFixture.addClass("package com.test; public class MyVm { public Address getAddress() { return null; } }");
        assertNavigatesToZul(gutterAtCaret(
                "package com.test; public class Address { public String get<caret>City() { return null; } }"));
    }

    public void testGetterGutterNavigatesToZul() {
        assertNavigatesToZul(gutterAtCaret(
                "package com.test; public class MyVm { public String get<caret>Name() { return null; } }"));
    }

    public void testCommandGutterNavigatesToZul() {
        assertNavigatesToZul(gutterAtCaret(
                "package com.test; import org.zkoss.bind.annotation.Command;\n" +
                "public class MyVm { @Command public void sa<caret>ve() {} }"));
    }
}
