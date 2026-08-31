# BOSS Top of Mind

Every open tab, across every workspace, as a tree in the left sidebar - and a way to move one
workspace to another.

Reconstructs where each tab physically sits: which workspace, and which split pane inside it,
rather than listing tabs flat. It is also the plugin that owns the generic `tabs_*` MCP tools.

## Why there is more than one workspace to show

A BOSS window **runs** several workspaces at once and shows one of them. Switching does not tear
the old one down - its whole split tree stays live, browsers and terminals included. So the tree
here is not a list of saved layouts; it is what this window is actually running right now, and the
workspace on screen is one row in it.

## What it does

- **Tree of all tabs across all running workspaces**, grouped by workspace and then by split
  section (Left, Right, Top, Bottom), nested to whatever depth your splits actually go.
- **Move a tab to another workspace**, by dragging it onto that workspace's header or from the
  right-click menu. The tab keeps running across the move: a browser tab keeps its page and its
  playing media, a terminal keeps its session.
- **The workspace on screen** wears an accent stripe; clicking any workspace header switches to it,
  and clicking any tab focuses it.
- **Search** filters the tree.
- **Per-type icons** so a terminal, a browser tab and an editor tab are distinguishable at a glance,
  with real favicons where the tab has one.
- **Collapsible groups** with tab counts. Workspaces start expanded, split sections start collapsed.

## Moving a tab

| How | What happens |
|---|---|
| Long-press a tab row and drag it onto a workspace header | The header lights up while it is a valid target; releasing moves the tab |
| Right-click a tab, "Move to workspace" | A submenu of every other workspace this window is running |
| `tab_move` MCP tool | Same thing, by tab id and workspace id or name |

Three things are deliberate:

- **Destinations are workspaces this window is RUNNING**, not every workspace you have saved. A
  workspace that exists only on disk has no live pane to receive the tab, and putting one there
  would mean writing it into the saved layout and destroying the running component - a different
  operation with the same name.
- **A move does not take you there.** The current workspace stays on screen and the moved row
  reappears under its new group with a brief highlight. Filing something away is the common case.
- **The tab is not selected in its new pane.** Use the row (or `tab_focus`) if you want it foremost
  when you next go there.

## MCP tools

| Tool | Purpose |
|---|---|
| `tabs_list` | Every active tab across all workspaces, with id, title, type, workspace, panel/window/split and url. The workspace on screen is marked `*` |
| `tab_focus` | Focus a tab by id |
| `tab_close` | Close a tab by id |
| `tab_open_url` | Open a new browser tab at a URL, with an optional title |
| `tab_move` | Move a tab into another running workspace, keeping it alive |

`tabs_list` refreshes before reading, so it does not answer from a stale snapshot. This is the
general-purpose tab surface for agents, which is broader than the panel's name suggests.

## Requirements

- BOSS >= 9.5.6, boss-plugin-api >= 1.0.87. The floor is what the **move** needs: it is a host
  call, and `ActiveTabsProvider` is served parent-first, so an older host's copy does not have it.
- `activeTabsProvider` is required for anything useful. Without it the panel shows a no-provider
  message and every tool returns an error.
- `workspaceDataProvider` and `splitViewOperations` are what let it render the split topology
  rather than a flat list, and what let a header click switch workspaces.
- `contextMenuProvider` is what draws the right-click menu (a real NSMenu on macOS). Without it the
  drag still works.
- Out-of-process: the move is in-process only, so the panel hides the affordance rather than
  offering one that does nothing. It probes `supportsTabTransfer` and believes the answer.
- No external binaries.

## Build

```bash
./gradlew buildPluginJar
cp build/libs/boss-plugin-topofmind-*.jar ~/.boss/plugins/
```

See [AGENTS.md](AGENTS.md) for architecture and conventions.

## License

Proprietary - Risa Labs Inc.
