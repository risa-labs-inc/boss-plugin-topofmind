# AGENTS.md

## Project Overview

**Top of Mind (Dynamic)** (`ai.rever.boss.plugin.dynamic.topofmind`) is a dynamic plugin for the BOSS desktop application.

Every open tab across every running workspace, as a tree, with a tab movable between workspaces.

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.topofmind`
- **Main Class**: `ai.rever.boss.plugin.dynamic.topofmind.TopofmindDynamicPlugin`
- **API Version**: 1.0.87 - **Min BOSS**: 9.5.6

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew build              # Full build
./gradlew processResources   # Process resources (syncs version)
```

## Workflow Rules

- Do NOT run the BOSS application to test. The user will test manually.
- After building, copy JAR to `~/.boss/plugins/` for local testing (or `~/.boss_debug/plugins/`
  for a dev-mode host).

## The fact the whole plugin is built on

**A BOSS window RUNS several workspaces at once and shows one of them.** Switching does not tear
the old one down: `SplitViewState.preserveCurrentState` keeps its whole split tree, and those stay
live tab components with live browsers and terminals in them.

That is why `ActiveTabsProvider.activeTabs` reports tabs whose `workspaceId` is not the current one,
and why this panel is a tree of workspaces rather than a list of the tabs on screen. It is also why
a move is a *move of a running thing* rather than a close and reopen.

## Architecture

```
TopofmindDynamicPlugin  registers the panel + the tabs_* MCP tools
TopofmindComponent      owns TabTreeState and TabDragState, one set per mounted panel
TopOfMindPanel          the panel: search, the tree, workspace switching, the move
TabRow                  one tab: 32dp flush row, drag source, context menu
SectionHeaders          workspace group header (also the drop target) + split section header
TabTransfer             which workspaces a tab can move to, and the move itself
TabDragState            drag in flight, drop-target bounds, the post-move highlight
TabTreeState            which groups are open
TabTreeBuilder          activeTabs + workspace layouts -> the tree
TabTreeType             tree node types
```

### Key patterns

- Entry point: `DynamicPlugin` with `register(context)`.
- UI: `PanelComponentWithUI` with `@Composable Content()`, Compose Multiplatform only.
- Providers from `PluginContext` (`activeTabsProvider`, `workspaceDataProvider`,
  `splitViewOperations`, `contextMenuProvider`) may be **null**. Degrade, never crash.
- **State is component-scoped, never a top-level `object`.** Expansion and drag both used to be
  process-global, which meant two windows showing this panel shared one drag and one set of open
  groups.

### Things that bit us, written down

- **`pointerInput` captures at composition.** Every value the drag gesture reads goes through
  `rememberUpdatedState`. A directly captured lambda keeps whatever it saw when the gesture block
  started, which is the shape of the split-drag gain bug (BossTerm#319).
- **A drop target's bounds are window-relative.** A row's pointer events arrive local to that row
  and the headers share no parent with it, so the row adds its own `boundsInWindow().topLeft`
  before reporting. Nothing here works in local coordinates.
- **Unregistering a drop target is guarded on the bounds still matching.** A collapsing group
  disposes its old header after the replacement has registered, so an unguarded removal drops a
  live target and that workspace silently stops accepting drops.
- **Naming a new host member gates the WHOLE jar.** The host's `BinaryCompatibilityValidator`
  member-checks every `ai.rever.boss.plugin.*` class in the jar, and this plugin's own package is
  one of those - so referencing `moveTabToWorkspace` means an older host rejects the plugin
  outright rather than loading it with the move disabled. `minBossVersion` is the real gate;
  `supportsTabTransfer` answers the *other* question, which is a host that has the member and says
  no (an out-of-process plugin, whose IPC proxy cannot forward a live component transfer).
- **A reflective probe against the host's provider does not work.** `ApiActiveTabsProviderAdapter`
  is a private class, so `getMethod(...).invoke(...)` finds the method and then throws
  `IllegalAccessException`. Call the interface member directly.

### Colours

Use `BossThemeColors` / `BossColors` only, never literals. `ai.rever.boss.plugin.ui.` is served
**parent-first**, so at runtime these resolve to the host's copy, whose values are getters over the
active theme - they follow a theme switch for free. The per-type icon colours here used to be fixed
hex values chosen against one dark theme; they are tokens now.

`signalText` is **not** reachable from `BossColors`. `BossColors.darkAccent` is `signal`, which is
a fill colour: under the default theme it lands below 4.5:1 as text. Keep the accent to fills and
the selection stripe.

### The look

The panel deliberately copies the host's vertical tab bar (`BossTabButton.kt`, `TabBarSections.kt`):
32dp flush rows, 3dp radius, 8dp inset, 6dp item gap, a 13sp single-line title, a 14dp bare icon,
and 10sp SemiBold headers with 0.8sp tracking. No card elevation, no row gutters, no second line -
separation comes from the fill.

## Version Management

**`build.gradle.kts` is the single source of truth for version.** `processResources` syncs it into
`plugin.json` at build time. Never hand-edit the version there. `apiVersion` and `minBossVersion`
*are* hand-edited in `plugin.json`.

`TopofmindDynamicPlugin.version` is a third copy the host reads for its plugin list. It had drifted
five releases behind; keep it in step.

## Code Quality

- Compose Multiplatform APIs (not Android-specific)
- All Kotlin files must end with a newline
- Handle null providers gracefully - show fallback UI, never crash
- Prose uses a spaced hyphen, never an em-dash

## CI/CD

Pushes to `main` trigger the release workflow: build the JAR, create a GitHub release, publish to
the BOSS Plugin Store. Defined in `.github/workflows/build.yml`, delegating to
`risa-labs-inc/BossConsole-Releases`. It passes `boss_plugin_api_version: 'latest'`, so a new api
symbol needs no pin change here.
