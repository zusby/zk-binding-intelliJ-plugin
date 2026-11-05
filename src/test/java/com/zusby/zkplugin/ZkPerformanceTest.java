package com.zusby.zkplugin;

import com.intellij.codeInsight.daemon.impl.analysis.XmlUnresolvedReferenceInspection;
import com.intellij.codeInspection.deadCode.UnusedDeclarationInspection;
import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

/**
 * Benchmark matching real projects: a few ~20k-line Java ViewModels and hundreds of small
 * (~80 line) .zul files. Only uses platform APIs, so it can also be run against older
 * plugin versions for comparison.
 * <p>
 * Run with: ./gradlew test --tests '*ZkPerformanceTest*' -Dzk.perf=true -i
 */
public class ZkPerformanceTest extends LightJavaCodeInsightFixtureTestCase {

    private static final int VIEW_MODELS = 3;
    private static final int PROPERTIES_PER_VM = 1500;
    private static final int COMMANDS_PER_VM = 300;
    private static final int HELPERS_PER_VM = 500;
    private static final int ZUL_FILES = 400;
    private static final int BINDINGS_PER_ZUL = 25;

    private VirtualFile bigVm;
    private VirtualFile someZul;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        if (!Boolean.getBoolean("zk.perf")) return;
        WriteAction.run(() -> FileTypeManager.getInstance().associateExtension(XmlFileType.INSTANCE, "zul"));
        myFixture.addClass("package org.zkoss.bind.annotation; public @interface Command { String[] value() default {}; }");

        for (int v = 0; v < VIEW_MODELS; v++) {
            StringBuilder s = new StringBuilder("package com.test;\nimport org.zkoss.bind.annotation.Command;\n\npublic class BigVm" + v + " {\n");
            for (int i = 0; i < PROPERTIES_PER_VM; i++) {
                s.append("    private String p").append(i).append(";\n\n")
                 .append("    public String getP").append(i).append("() {\n        return p").append(i).append(";\n    }\n\n")
                 .append("    public void setP").append(i).append("(String value) {\n        this.p").append(i).append(" = value;\n    }\n\n");
            }
            for (int i = 0; i < COMMANDS_PER_VM; i++) {
                s.append("    @Command\n    public void cmd").append(i).append("() {\n        helper").append(i).append("(1);\n    }\n\n");
            }
            for (int i = 0; i < HELPERS_PER_VM; i++) {
                s.append("    private int helper").append(i).append("(int x) {\n")
                 .append("        int y = x * 2;\n        if (y > 10) {\n            y -= 3;\n        }\n")
                 .append("        return y + p").append(i % PROPERTIES_PER_VM).append(".length();\n    }\n\n");
            }
            s.append("}\n");
            VirtualFile f = myFixture.addFileToProject("com/test/BigVm" + v + ".java", s.toString()).getVirtualFile();
            if (v == 0) bigVm = f;
        }

        for (int z = 0; z < ZUL_FILES; z++) {
            int v = z % VIEW_MODELS;
            StringBuilder s = new StringBuilder("<zk>\n<window apply=\"org.zkoss.bind.BindComposer\" viewModel=\"@id('vm') @init('com.test.BigVm" + v + "')\">\n  <vlayout>\n");
            for (int b = 0; b < BINDINGS_PER_ZUL; b++) {
                int p = (z * 7 + b * 13) % PROPERTIES_PER_VM;
                s.append("    <hbox>\n      <label value=\"Field ").append(b).append("\"/>\n")
                 .append("      <textbox value=\"@bind(vm.p").append(p).append(")\" onChange=\"@command('cmd")
                 .append((z + b) % COMMANDS_PER_VM).append("', item=self)\"/>\n    </hbox>\n");
            }
            s.append("  </vlayout>\n</window>\n</zk>\n");
            VirtualFile f = myFixture.addFileToProject("web/page" + z + ".zul", s.toString()).getVirtualFile();
            if (z == 0) someZul = f;
        }
    }

    private static void time(String label, Runnable r) {
        long t = System.nanoTime();
        r.run();
        System.out.println("[zk-perf] " + label + ": " + (System.nanoTime() - t) / 1_000_000 + " ms");
    }

    public void testPerformance() {
        if (!Boolean.getBoolean("zk.perf")) return;
        myFixture.enableInspections(new UnusedDeclarationInspection(true), new XmlUnresolvedReferenceInspection());

        myFixture.configureFromExistingVirtualFile(bigVm);
        System.out.println("[zk-perf] BigVm0.java lines: " + myFixture.getEditor().getDocument().getLineCount());
        time("1. highlight 20k-line ViewModel (cold)", () -> myFixture.doHighlighting());
        time("2. re-highlight, nothing changed", () -> myFixture.doHighlighting());

        int bodyOffset = myFixture.getEditor().getDocument().getText().indexOf("int y = x * 2;");
        myFixture.getEditor().getCaretModel().moveToOffset(bodyOffset);
        myFixture.type("int z = 0; ");
        time("3. re-highlight after typing inside a method body", () -> myFixture.doHighlighting());

        int classEnd = myFixture.getEditor().getDocument().getText().lastIndexOf('}');
        myFixture.getEditor().getCaretModel().moveToOffset(classEnd);
        myFixture.type("public String getExtra() { return null; }\n");
        time("4. re-highlight after adding a method (structural change)", () -> myFixture.doHighlighting());

        myFixture.configureFromExistingVirtualFile(someZul);
        time("5. highlight a .zul file", () -> myFixture.doHighlighting());
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getEditor().getDocument().getText().indexOf("Field 0"));
        myFixture.type("x");
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

        myFixture.configureFromExistingVirtualFile(bigVm);
        time("6. re-highlight ViewModel after a .zul edit", () -> myFixture.doHighlighting());
    }
}
