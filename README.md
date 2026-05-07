# ZK Binding Support

IntelliJ plugin for ZK Framework MVVM projects. Adds navigation between `.zul` binding expressions and their Java ViewModel getters/setters, and keeps "Find Usages" honest about ZUL-only references.

## Features

**Ctrl+Click in `.zul` files** — `@bind(vm.username)` navigates to `getUsername()` / `setUsername()`. Works across deep inheritance chains, including inline `<zscript>` class definitions.

**Gutter icons in Java** — methods referenced by a `.zul` binding show a gutter icon. Click to jump to the binding site.

**Find Usages** — `Alt+F7` on a getter/setter includes `.zul` usages in the results.

**No false "unused" warnings** — getters and setters bound in `.zul` are marked as implicitly used.

## Supported binding syntax

```
@bind(vm.property)
@load(vm.property)
@save(vm.property)
@init(vm.property)
@command('methodName')
```

## Inheritance

The plugin resolves the full superclass chain. If your `.zul` initialises a thin client-specific class that extends a base class with the actual getter/setter implementations, navigation and gutter icons work at every level.

Inline `<zscript>` classes are also supported:

```xml
<zscript>
    class ZModel extends com.example.BaseViewModel { }
</zscript>
<window viewModel="@id('vm') @init('ZModel')">
    <textbox value="@bind(vm.name)"/>  <!-- navigates to BaseViewModel.getName() -->
</window>
```

## Installation

Build from source (requires JDK 21 as the Gradle JVM, output targets Java 17):

```
gradle buildPlugin
```

Install the generated `.zip` from `build/distributions/` via **Settings → Plugins → Install Plugin from Disk**.

## Compatibility

IntelliJ IDEA 2026.1 and later.
