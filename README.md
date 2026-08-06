# BOSS Top of Mind

Every open tab, across every workspace, as a tree in the left sidebar.

Reconstructs where each tab physically sits - which workspace, and which split pane inside it -
rather than listing tabs flat. It is also the plugin that owns the generic `tabs_*` MCP tools.

## What it does

- **Tree of all tabs across all workspaces**, grouped by workspace and then by split section
  (Left, Right, Top, Bottom), nested to whatever depth your splits actually go.
- **Current workspace is highlighted**, and clicking any tab focuses it.
- **Search** filters the tree.
- **Per-type icons and colours** so a terminal, a browser tab and an editor tab are
  distinguishable at a glance.
- **Collapsible groups** with tab counts. Workspaces start expanded, split sections start
  collapsed.

## MCP tools

| Tool | Purpose |
|---|---|
| `tabs_list` | Every active tab across all workspaces, with id, title, type, workspace, panel/window/split and url |
| `tab_focus` | Focus a tab by id |
| `tab_close` | Close a tab by id |
| `tab_open_url` | Open a new browser tab at a URL, with an optional title |

`tabs_list` refreshes before reading, so it does not answer from a stale snapshot. This is the
general-purpose tab surface for agents, which is broader than the panel's name suggests.

## Requirements

- BOSS >= 9.2.20, boss-plugin-api >= 1.0.20
- `activeTabsProvider` is required for anything useful. Without it the panel shows a
  no-provider message and all four tools return an error.
- `workspaceDataProvider` and `splitViewOperations` are what let it render the split topology
  rather than a flat list.
- No external binaries.

## Notes

Expansion state lives in a top-level `TabTreeState` object, so it is process-global: expanding
a workspace in one window expands it in every panel instance, not just the one you clicked.

## Build

```bash
./gradlew buildPluginJar
cp build/libs/boss-plugin-topofmind-*.jar ~/.boss/plugins/
```

See [AGENTS.md](AGENTS.md) for architecture and conventions.

## License

Proprietary - Risa Labs Inc.
