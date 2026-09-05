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
  and clicking any tab focuses it. Workspaces are listed in name order, so switching never moves a
  row out from under your cursor.
- **Search** filters the tree.
- **Per-type icons** so a terminal, a browser tab and an editor tab are distinguishable at a glance,
  with real favicons where the tab has one.
- **Collapsible groups** with tab counts. The workspace on screen starts expanded and every other
  one starts collapsed; split sections start collapsed. Open or close a group yourself and it stays
  the way you left it for the rest of the session, switching workspaces included.
- **Close everything under a header**, workspace or split section, after a confirm that names what
  it is about to close and how many.
- **A workspace-actions footer** pinned under the tree, mirroring the menu the host hangs off the
  foot of its vertical tab bar.

## Closing a group of tabs

Every workspace header and every split-section header carries a close button, styled like the tab
bar's own pane-header actions. It closes the tabs drawn under that header.

- **It always asks first**, through the host's confirmation dialog, and the message names the group
  and the exact number of tabs. Closing a dozen running things is not a click you can take back.
- **The number is what is on screen.** With a search active the tree is filtered, so the confirm
  counts and closes the rows you can actually see - not every tab the workspace owns.
- **It reaches workspaces that are not on screen.** The host's close resolves a tab anywhere, so
  clearing a workspace you are not currently in does not first switch to it.
- **No dialog provider, no button.** If `genericDialogProvider` is absent there is nothing to
  confirm with, and the close action is not drawn rather than being offered without a question.

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

## The workspace footer

Under the tree, behind a full-width rule, sit four icon buttons. They do not scroll with the tree.

| Button | What it does |
|---|---|
| Open workspace | A menu of every saved workspace. A filled dot marks the one on screen, an outlined dot one this window is merely running behind it. Clicking one switches to it, preserving the layout you are leaving |
| Save workspace | Prompts for a name and saves the current workspace under it |
| Open workspace from file | Picks a `.json` workspace file, loads it and applies its layout |
| Delete workspace | Pick one from a list, confirm, and it is deleted by name |

A button whose provider is missing is not drawn at all, rather than shown and dead. Save and Delete
need `genericDialogProvider` (their prompts), Open from file additionally needs
`filePickerProvider`, and the workspace menu needs `splitViewOperations` as well as
`workspaceDataProvider`.

The host's menu has two more entries, **Open Workspace Folder** and **Reset to Default**, which are
deliberately absent here: they need `WorkspaceManager.getWorkspaceDirectory()` and
`WorkspaceManager.resetToDefault()`, and neither is on the plugin api's `WorkspaceDataProvider`.

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

- BOSS >= 9.5.9, boss-plugin-api >= 1.0.88. The floor is what the **move** needs: it is a host
  call, and `ActiveTabsProvider` is served parent-first, so an older host's copy does not have it.
- `activeTabsProvider` is required for anything useful. Without it the panel shows a no-provider
  message and every tool returns an error.
- `workspaceDataProvider` and `splitViewOperations` are what let it render the split topology
  rather than a flat list, and what let a header click switch workspaces.
- `contextMenuProvider` is what draws the right-click menu (a real NSMenu on macOS). Without it the
  drag still works.
- `filePickerProvider` and `genericDialogProvider` drive the footer's actions. Each button is hidden
  when the provider it needs is absent, so the footer shrinks rather than lying.
  `genericDialogProvider` also gates the headers' close buttons, for the same reason: it is the
  thing that asks.
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
