# Changelog

All notable changes to this plugin. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.2.0]

### Added
- `@global-command('name')` resolves to every `@GlobalCommand` handler in the project, with completion, Find Usages, Rename, gutter icons and a quick fix that creates a missing handler in the ViewModel.
- Java: names in `@NotifyChange`, `@SmartNotifyChange` and `@DependsOn` navigate to the property, complete property names and follow getter renames.
- Java: `BindUtils.postNotifyChange(queue, scope, bean, "name")` resolves `name` on `bean`'s type; `BindUtils.postGlobalCommand(queue, scope, "name", args)` resolves to the `@GlobalCommand` handlers.
- New inspection *Unknown ZK property or global command name* (Settings → Editor → Inspections → ZK Framework) reports typos in those Java literals, with a quick fix that creates the missing property.
- `.zul`: class names in `@init('…')` (in `viewModel`), `@converter('…')`, `@validator('…')` and `apply="…"` navigate to the class, complete packages and classes, and are updated when the class is renamed.

### Fixed
- `@global-command('name')` was resolved against `@Command` methods instead of `@GlobalCommand` handlers.

## [1.1.3]

### Added
- Code completion inside binding expressions: `@bind(vm.` lists ViewModel properties (including nested paths like `vm.person.`), `@command('` lists commands.
- Quick fixes on unresolved bindings: Alt+Enter on a red `vm.foo` creates the property (field + getter + setter); on a red command creates the `@Command` method.
- Nested paths and expressions: `@load(vm.address.city)`, `@load(vm.count gt 0 ? vm.name : '-')`. Every segment navigates, renames and counts as a usage.
- Form binding: `fx.` properties resolve to the form's backing bean, `before='save'` / `after=` resolve to commands.
- Compatibility with IntelliJ IDEA 2026.3.

### Fixed
- Commands with parameters (`@command('save', item=each)`) are recognised.
- Clicking the gutter icon on a Java method navigates to its .zul usages again.

## [1.1.2]

### Added
- Java language injection inside `<zscript>` blocks: completion, highlighting and navigation.

### Fixed
- Methods and variables used in .zul files no longer show a spurious "No usages" hint.

## [1.0.0]

- Initial release: usage detection for getters and setters bound in .zul files, Java ↔ ZUL navigation, gutter icons, support for all ZK binding annotations.
