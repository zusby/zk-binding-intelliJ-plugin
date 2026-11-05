package com.zusby.zkplugin;

import junit.framework.TestCase;

import java.util.List;
import java.util.stream.Collectors;

public class ZkBindingParserTest extends TestCase {

    private static List<String> parse(String text) {
        return ZkBindingParser.parse(text).stream().map(Object::toString).collect(Collectors.toList());
    }

    public void testSimple() {
        assertEquals(List.of("vm.name"), parse("@bind(vm.name)"));
    }

    public void testNestedPathYieldsEverySegment() {
        assertEquals(List.of("vm.address", "vm.address.city"), parse("@load(vm.address.city)"));
    }

    public void testExpression() {
        assertEquals(List.of("vm.count", "vm.name", "vm.other"),
                parse("@load(vm.count gt 0 ? vm.name : 'vm.notAPath') @init(not empty vm.other)"));
    }

    public void testMethodCallIsNotAProperty() {
        assertEquals(List.of("vm.items"), parse("@load(vm.items.size())"));
    }

    public void testElSyntax() {
        assertEquals(List.of("vm.name"), parse("@bind(${vm.name})"));
    }

    public void testCommandWithParameters() {
        assertEquals(List.of("command:save", "command:refresh"),
                parse("@command('save', item=each, flag=true) @global-command(\"refresh\")"));
    }

    public void testSaveWithBeforeCommands() {
        assertEquals(List.of("vm.person", "command:save", "vm.person", "command:a", "command:b"),
                parse("@save(vm.person, before='save') @load(vm.person, after={'a', 'b'})"));
    }

    public void testOffsets() {
        String text = "@load(vm.address.city)";
        ZkBindingParser.Token city = ZkBindingParser.parse(text).get(1);
        assertEquals("city", text.substring(city.start, city.end));
    }

    public void testCompletionContexts() {
        ZkBindingParser.CompletionContext c = ZkBindingParser.completionContext("@load(vm.count gt 0 and vm.address.ci");
        assertNotNull(c);
        assertEquals(ZkBindingParser.Kind.PROPERTY, c.kind);
        assertEquals("vm", c.root);
        assertEquals(List.of("address"), c.qualifier);
        assertEquals("ci", c.prefix);

        c = ZkBindingParser.completionContext("@command('sa");
        assertNotNull(c);
        assertEquals(ZkBindingParser.Kind.COMMAND, c.kind);
        assertEquals("sa", c.prefix);

        c = ZkBindingParser.completionContext("@save(vm.person, before='");
        assertNotNull(c);
        assertEquals(ZkBindingParser.Kind.COMMAND, c.kind);

        assertNull(ZkBindingParser.completionContext("@load(vm.x) plain."));
        assertNull(ZkBindingParser.completionContext("@load('vm."));
        assertNull(ZkBindingParser.completionContext("@command('save', item=vm."));
    }
}
