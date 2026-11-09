package com.zusby.zkplugin;

import com.intellij.codeInsight.daemon.impl.analysis.XmlUnresolvedReferenceInspection;
import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.List;

/** Java-side references: @NotifyChange / @DependsOn / BindUtils.postNotifyChange literals, and class names in .zul. */
public class ZkJavaReferenceTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        WriteAction.run(() -> FileTypeManager.getInstance().associateExtension(XmlFileType.INSTANCE, "zul"));
        // The light fixture has no JDK: stub the java.lang types the test code relies on.
        myFixture.addClass("package java.lang; public class Object {}");
        myFixture.addClass("package java.lang; public final class String {}");
        myFixture.addClass("package com.test; public @interface Other { String value(); }");
        myFixture.addClass("package org.zkoss.bind.annotation; public @interface Command { String[] value() default {}; }");
        myFixture.addClass("package org.zkoss.bind.annotation; public @interface NotifyChange { String[] value() default {}; }");
        myFixture.addClass("package org.zkoss.bind.annotation; public @interface DependsOn { String[] value(); }");
        myFixture.addClass(
                "package org.zkoss.bind; public class BindUtils {\n" +
                "  public static void postNotifyChange(String q, String s, Object bean, String... props) {}\n" +
                "}");
        myFixture.addClass(
                "package com.test; public class Base {\n" +
                "  public String getTitle() { return null; }\n" +
                "}");
        myFixture.addClass(
                "package com.test; public class Person {\n" +
                "  public String getEmail() { return null; }\n" +
                "}");
    }

    private void vm(String body) {
        myFixture.configureByText("MyVm.java",
                "package com.test;\n" +
                "import org.zkoss.bind.annotation.*;\n" +
                "public class MyVm extends Base {\n" +
                "  public String getName() { return null; }\n" +
                "  public void setName(String n) {}\n" +
                "  public int getCount() { return 0; }\n" +
                body + "\n}");
    }

    private PsiElement resolveAtCaret() {
        PsiReference ref = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
        assertNotNull("No reference at caret", ref);
        return ref.resolve();
    }

    private void assertResolvesToMethod(String name) {
        PsiElement target = resolveAtCaret();
        assertTrue("Expected method, got " + target, target instanceof PsiMethod);
        assertEquals(name, ((PsiMethod) target).getName());
    }

    // --- @NotifyChange / @DependsOn ---

    public void testNotifyChangeResolvesToGetter() {
        vm("@Command @NotifyChange(\"na<caret>me\") public void save() {}");
        assertResolvesToMethod("getName");
    }

    public void testNotifyChangeArrayResolvesInheritedGetter() {
        vm("@Command @NotifyChange({\"count\", \"ti<caret>tle\"}) public void save() {}");
        assertResolvesToMethod("getTitle");
    }

    public void testDependsOnResolves() {
        vm("@DependsOn(\"co<caret>unt\") public String getLabel() { return null; }");
        assertResolvesToMethod("getCount");
    }

    public void testWildcardsAreNotReferences() {
        vm("@Command @NotifyChange(\"*\") public void a() {}\n@Command @NotifyChange(\".\") public void b() {}");
        myFixture.enableInspections(new ZkUnresolvedJavaReferenceInspection());
        myFixture.checkHighlighting();
    }

    public void testNotifyChangeCompletion() {
        vm("@Command @NotifyChange(\"<caret>\") public void save() {}");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertContainsElements(items, "name", "count", "title");
    }

    public void testNotifyChangeTypoIsReported() {
        myFixture.enableInspections(new ZkUnresolvedJavaReferenceInspection());
        vm("@Command @NotifyChange({\"name\", \"<warning descr=\"Property 'nmae' not found in MyVm\">nmae</warning>\"}) public void save() {}");
        myFixture.checkHighlighting();
    }

    public void testCreatePropertyFromNotifyChange() {
        myFixture.enableInspections(new ZkUnresolvedJavaReferenceInspection());
        vm("@Command @NotifyChange(\"sel<caret>ected\") public void save() {}");
        myFixture.launchAction(myFixture.findSingleIntention("Create property 'selected' in MyVm"));
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("public String getSelected()"));
    }

    public void testFindUsagesIncludesNotifyChange() {
        vm("@Command @NotifyChange(\"name\") public void save() {}");
        PsiClass cls = ((PsiJavaFile) myFixture.getFile()).getClasses()[0];
        PsiMethod getName = cls.findMethodsByName("getName", false)[0];
        GlobalSearchScope project = GlobalSearchScope.projectScope(getProject());
        // Each search path must report the literal exactly once (no duplicates between executors).
        assertEquals(1, ReferencesSearch.search(getName).findAll().size());
        assertEquals(1, MethodReferencesSearch.search(getName, project, false).findAll().size());
        assertEquals(1, MethodReferencesSearch.search(getName, project, true).findAll().size());
        assertEquals(1, RenamePsiElementProcessor.forElement(getName).findReferences(getName, project, false).size());
    }

    public void testRenameGetterUpdatesNotifyChange() {
        vm("@Command @NotifyChange(\"name\") public void save() {}");
        PsiClass cls = ((PsiJavaFile) myFixture.getFile()).getClasses()[0];
        myFixture.renameElement(cls.findMethodsByName("getName", false)[0], "getFullName");
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("@NotifyChange(\"fullName\")"));
    }

    public void testUnrelatedAnnotationIsIgnored() {
        vm("@Other(\"nonexistent\") public void x() {}");
        myFixture.enableInspections(new ZkUnresolvedJavaReferenceInspection());
        myFixture.checkHighlighting();
    }

    // --- BindUtils.postNotifyChange ---

    public void testPostNotifyChangeOnThis() {
        vm("void x() { org.zkoss.bind.BindUtils.postNotifyChange(null, null, this, \"co<caret>unt\"); }");
        assertResolvesToMethod("getCount");
    }

    public void testPostNotifyChangeOnOtherBean() {
        vm("void x(Person p) { org.zkoss.bind.BindUtils.postNotifyChange(null, null, p, \"em<caret>ail\"); }");
        assertResolvesToMethod("getEmail");
    }

    public void testPostNotifyChangeTypoIsReported() {
        myFixture.enableInspections(new ZkUnresolvedJavaReferenceInspection());
        vm("void x(Person p) { org.zkoss.bind.BindUtils.postNotifyChange(null, null, p, \"<warning descr=\"Property 'mail' not found in Person\">mail</warning>\"); }");
        myFixture.checkHighlighting();
    }

    // --- class names in .zul ---

    private void zul(String body) {
        myFixture.configureByText("test.zul", "<zk>\n" + body + "\n</zk>\n");
    }

    public void testViewModelInitClassResolves() {
        zul("<window viewModel=\"@id('vm') @init('com.test.Per<caret>son')\"/>");
        PsiElement target = resolveAtCaret();
        assertTrue("Expected class, got " + target, target instanceof PsiClass);
        assertEquals("com.test.Person", ((PsiClass) target).getQualifiedName());
    }

    public void testPackageSegmentResolves() {
        zul("<window viewModel=\"@id('vm') @init('com.te<caret>st.Person')\"/>");
        assertTrue(resolveAtCaret() instanceof PsiPackage);
    }

    public void testApplyComposerResolves() {
        zul("<window apply=\"com.test.Ba<caret>se\"/>");
        assertTrue(resolveAtCaret() instanceof PsiClass);
    }

    public void testConverterClassResolves() {
        zul("<label value=\"@load(1) @converter('com.test.Ba<caret>se')\"/>");
        assertTrue(resolveAtCaret() instanceof PsiClass);
    }

    public void testUnknownClassIsHighlighted() {
        myFixture.enableInspections(new XmlUnresolvedReferenceInspection());
        zul("<window viewModel=\"@id('vm') @init('com.test.<error descr=\"Cannot resolve class 'Missing'\">Missing</error>')\"/>");
        myFixture.checkHighlighting(false, false, false);
    }

    public void testInitInPropertyAttributeIsNotAClass() {
        myFixture.enableInspections(new XmlUnresolvedReferenceInspection());
        zul("<label value=\"@init('just.a.string')\"/>\n<window viewModel=\"@id('vm') @init('InlineZScriptClass')\"/>");
        myFixture.checkHighlighting(false, false, false);
    }

    public void testRenameClassUpdatesZul() {
        PsiClass person = myFixture.findClass("com.test.Person");
        zul("<window viewModel=\"@id('vm') @init('com.test.Person')\"/>");
        myFixture.renameElement(person, "Customer");
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("@init('com.test.Customer')"));
    }
}
