# ZK Framework Variables Binding

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/31652?label=Marketplace)](https://plugins.jetbrains.com/plugin/31652-zk-framework-variables-binding)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/31652)](https://plugins.jetbrains.com/plugin/31652-zk-framework-variables-binding)
[![Build](https://github.com/zusby/zk-binding-intelliJ-plugin/actions/workflows/build.yml/badge.svg)](https://github.com/zusby/zk-binding-intelliJ-plugin/actions/workflows/build.yml)

IntelliJ IDEA plugin for [ZK Framework](https://www.zkoss.org/) MVVM projects. It connects `.zul` binding expressions to the Java ViewModels behind them, so navigation, completion, Find Usages and Rename work across both.

## Features

### In `.zul` files

| Feature | What it does |
|---|---|
| **Navigation** | Ctrl+Click on `@bind(vm.address.city)` jumps to `getCity()`. Every segment of a nested path resolves, including inside expressions like `@load(vm.count gt 0 ? vm.name : '-')`. |
| **Completion** | `@bind(vm.` lists the ViewModel's properties (inherited ones too), `@command('` lists `@Command` methods, `@global-command('` lists `@GlobalCommand` handlers. |
| **Errors** | Unknown properties and commands are highlighted. Alt+Enter creates the property (field + getter + setter) or the command method. |
| **Global commands** | `@global-command('refresh')` resolves to *every* `@GlobalCommand` handler in the project, since a global command is broadcast to all ViewModels. |
| **Forms** | `fx.` properties resolve to the form's backing bean; `before='save'` / `after=` resolve to commands. |
| **Class names** | `@init('com.example.MyVm')`, `@converter('…')`, `@validator('…')` and `apply="…"` navigate to the class, complete package and class names, and follow class renames. |
| **`<zscript>`** | Java support (highlighting, completion, navigation) inside `<zscript>` blocks. |

### In Java

| Feature | What it does |
|---|---|
| **Gutter icons** | Getters, setters, `@Command` and `@GlobalCommand` methods used from `.zul` files get a gutter icon that jumps to the bindings. |
| **No false "unused"** | Accessors referenced only from `.zul` files are not reported as unused. |
| **Find Usages / Rename** | `Alt+F7` includes `.zul` usages; renaming `getName()` updates `@bind(vm.name)` and `@NotifyChange("name")`. |
| **`@NotifyChange` & co.** | Names in `@NotifyChange`, `@SmartNotifyChange`, `@DependsOn` and `BindUtils.postNotifyChange(..., "name")` navigate, complete and rename like code. |
| **Typo inspection** | A misspelled name in `@NotifyChange`, `postNotifyChange` or `BindUtils.postGlobalCommand(..., "name", ...)` is reported. At runtime such a typo fails silently: the UI just doesn't refresh. |

Inheritance is resolved through the whole superclass chain, including inline `<zscript>` ViewModel classes:

```xml
<zscript>
    class ZModel extends com.example.BaseViewModel { }
</zscript>
<window viewModel="@id('vm') @init('ZModel')">
    <textbox value="@bind(vm.name)"/>  <!-- navigates to BaseViewModel.getName() -->
</window>
```

## Installation

Install **ZK Framework Variables Binding** from **Settings → Plugins → Marketplace**, or from the [Marketplace page](https://plugins.jetbrains.com/plugin/31652-zk-framework-variables-binding).

Requires IntelliJ IDEA 2026.1 or later.

## Development

Building needs JDK 25, because the IntelliJ 2026.3 platform is compiled for Java 25. The plugin itself targets Java 21 bytecode so it still loads on 2026.1 and 2026.2.

```
./gradlew test          # run the test suite
./gradlew runIde        # start a sandbox IDE with the plugin installed
./gradlew buildPlugin   # build/distributions/*.zip
./gradlew verifyPlugin  # check compatibility with the IDEs listed in build.gradle.kts
```
