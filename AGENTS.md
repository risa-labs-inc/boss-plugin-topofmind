# AGENTS.md

## Project Overview

**Top of Mind (Dynamic)** (`ai.rever.boss.plugin.dynamic.topofmind`) is a dynamic plugin for the BOSS desktop application.

Every open tab across every running workspace, as a tree, with a tab movable between workspaces.

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.topofmind`
- **Main Class**: `ai.rever.boss.plugin.dynamic.topofmind.TopofmindDynamicPlugin`
- **API Version**: 1.0.88 - **Min BOSS**: 9.5.9

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew test              # Unit tests - pane naming, workspace order, floor plan
./gradlew build             # Full build (runs the tests)
./gradlew processResources  # Process resources (syncs version)
```

`FloorMetricsTest` pins that the floors stack is a fixed height whatever the workspace count, that
the bite between two storeys is the same at every stack size, and that each clamp gives the height
up in the right direction. `PaneStructureTest` pins what can be checked
without a screen: that a section is named by whatever
`ActiveTabData.splitPosition` says, that sections are flat and in the host's order, that workspaces
are ordered oldest-saved first, and that the floor plan takes a pane's name literally only when the
names actually tile the plate. The api is `compileOnly` for the plugin, so the tests take it onto
their own runtime classpath through the same conditional - the local sibling jar, or CI's
downloaded one.

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
TopofmindComponent      owns TabTreeState, TabDragState, SplitPaneExpansion and PanelDialogState
TopOfMindPanel          the panel: the tree, workspace switching, the move, and the dialogs
WorkspaceFloors         the workspaces as isometric storeys, between the tree and the footer
WorkspaceFooter         the workspace actions + the search button, pinned under the tree
QuickSwitcher           the switcher over EVERY window's tabs: search, arrows, Enter
PanelDialogs            which dialog a panel is showing, and which panel a request lands on
TabRow                  one tab: 32dp flush row, drag source, context menu
SectionHeaders          workspace group header (also the drop target) + split section header
TabTransfer             which workspaces a tab can move to, and the move itself
TabDragState            drag in flight, drop-target bounds, the post-move highlight
TabTreeState            which WORKSPACE groups are open, and the user's overrides of the default
SplitPaneExpansion      which split pane is showing all its tabs: sticky hover, plus pins
TabTreeBuilder          activeTabs -> the tree: one section per pane, oldest workspace first
PanePlacement           a pane's NAME -> where it sits, for the header glyph and the floors
TabTreeType             tree node types
```

### Key patterns

- Entry point: `DynamicPlugin` with `register(context)`.
- UI: `PanelComponentWithUI` with `@Composable Content()`, Compose Multiplatform only.
- Providers from `PluginContext` (`activeTabsProvider`, `workspaceDataProvider`,
  `splitViewOperations`, `contextMenuProvider`, `filePickerProvider`, `genericDialogProvider`) may
  be **null**. Degrade, never crash - in the footer that means a button whose provider is absent is
  not drawn, never drawn and dead.
- **State is component-scoped, never a top-level `object`.** Expansion and drag both used to be
  process-global, which meant two windows showing this panel shared one drag and one set of open
  groups. `SplitPaneExpansion` is a field on the component for the same reason, where the host's
  equivalent can afford to be an `object`.

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
- **`activeTabs` arrives current-workspace-first.** The host's `collectAllActiveTabs` emits the
  current workspace's tabs and then the preserved ones, so a plain `groupBy { it.workspaceId }`
  ordered the groups by arrival and every workspace jumped to the top of the panel the moment you
  switched to it. `WorkspaceArrival` answers "when did this start running HERE" instead: a workspace
  takes the next free slot the first time it is seen and keeps it, so the one you just opened is the
  bottom row and nothing already in the list moves when it lands.
- **The arrival order is SEEDED, not invented.** The first sighting is a whole set arriving at once -
  everything the window was already running when the panel mounted - and their order is not knowable
  from inside it. `TabTreeBuilder.seedOrder` supplies it: `LayoutWorkspace.timestamp` oldest first,
  name then id as tie-breaks. That timestamp is when a workspace was last WRITTEN, which is the only
  clock the api offers, since `ActiveTabData` carries no time and the ids are names
  (`workspace-claude-code`) rather than the `workspace-<epoch millis>` `generateId` produces.
  - Seeding is all it is good for. It was the WHOLE ordering once, and it answers the wrong
    question: opening a workspace saved months ago dropped it into the middle of the list at its
    save-time position, and workspaces sharing a timestamp fell through to the name tie-break and
    came out alphabetical.
  - What must NOT be the seed is the order the host emits, which is current-workspace-first: that
    would put whichever workspace is on screen at the top of the panel on every launch.
  - A slot is session-scoped and never persisted - the next launch seeds again from the timestamps,
    the only durable answer there is. `WorkspaceArrival` is a field on `TopofmindComponent`, never a
    top-level object, for the reason all the state here is.
  - **An id absent from `slotsFor` is FORGOTTEN**, so closing a workspace and opening it again puts
    it at the bottom, which is what opening it means. Keeping the old slot would make a reopened
    workspace reappear in the middle. `WorkspaceArrivalTest` pins that, plus the seed, plus that a
    rebuild with the same set moves nothing; three of them fail if the arrival slots are computed
    and then ignored.
- **Default expansion depends on the current workspace, so it needs overrides.** Only the workspace
  on screen is open by default. Because that default is not a constant, `TabTreeState` records the
  user's toggles in a `Map<String, Boolean>` rather than a set of exceptions: a set cannot tell "the
  user closed this" from "the rule closed this", and the tree is rebuilt roughly every 2s.
  `syncDefaultExpansion(nodes, currentWorkspaceId)` is pure in its inputs so a rebuild is a no-op.
- **"Expanded while hovered" is the wrong model, and it is the obvious one.** It collapses the
  group the instant the pointer moves down onto the rows it just revealed, because those rows are
  underneath where the pointer was going. Hovering a header CHOOSES the open pane and the choice is
  sticky; only leaving the panel or hovering another header drops it. Copied from the host's
  `TabGroupExpansion` KDoc, which is where this was worked out.
- **A reflective probe against the host's provider does not work.** `ApiActiveTabsProviderAdapter`
  is a private class, so `getMethod(...).invoke(...)` finds the method and then throws
  `IllegalAccessException`. Call the interface member directly.

### The host raises this panel's dialogs

Two host controls dispatch straight into this plugin's `DeepLinkActionHandler` and then open the
panel, rather than doing the job themselves:

| Host control | Action | Dialog |
|---|---|---|
| `WorkspaceButton`, LEFT click | `open-workspace-picker` | the footer's workspace picker |
| Ctrl+Space (`QUICK_SWITCHER_OPEN`) | `open-quick-switcher` | the quick switcher |

The workspace button's own menu is not gone, it has moved to its right click, because the menu's
Options submenu is the only route in the app to Open Workspace Folder and Reset to Default.
Ctrl+Space has no fallback: the host **deleted** its own `TopOfMindDialog`, and when nothing
answers it says why and offers to install or enable this plugin.

- **`PANEL_DIALOG_ACTIONS` is a map, not a branch.** The handler's contract is "true for an action
  of mine, false for anything else", and a lookup cannot drift from it. Adding a third dialog is a
  line in that map plus a member on `PanelDialog`.
- **Returning false for an action this build does not know is load-bearing now.** It is how a
  newer host tells an OLD plugin apart from a missing one - it offers an update rather than an
  install. Before the switcher moved, false only meant "not here", which is what makes the host's
  workspace button fall back to its own menu.
- **The handler raises the panel's own dialog, not a second copy.** That visibility used to be a
  `remember` inside `WorkspaceActionsFooter`, which is the right place while the button beside it
  is the only thing that can open it. A caller outside the composition needs somewhere it can write,
  so it is a `PanelDialogState` on `TopofmindComponent` now - panel-scoped like `TabTreeState`
  and `TabDragState`, and **never a top-level object**: two windows must not share one dialog.
- **ONE slot, not a flag per dialog.** Both dialogs are modal, so "both open" is not a state this
  panel can be in; two booleans could express it and would eventually be asked to.
- **`PanelDialogRequests` decides WHICH panel opens it, and carries WHICH dialog.** A deep-link
  action carries no window and the panel factory is handed no window id, so the target has to be
  inferred: the panel most recently composed. Panels attach in a `DisposableEffect` inside
  `Content()`, so the list holds the ones actually on screen rather than every component the host is
  keeping alive. A parallel copy per dialog would have needed its own pending window, its own
  attach/detach bookkeeping and its own idea of the front panel, and the two would have disagreed
  the first time a panel closed between an attach and a request.
- **A request that arrives before the panel is composed is held for a few seconds.** The host opens
  the panel and asks in the same click, and opening a panel is an event that lands a frame or more
  later - dropping the request because nothing was mounted yet would make the first click on a
  closed panel do half the job.
- **No manual unregister.** `TrackingPluginContext` records a deep-link handler as a UI extension
  and removes it when the plugin unloads, exactly as it does the MCP tools.

### The quick switcher

`QuickSwitcher.kt` is the host's old `TopOfMindDialog` (384 lines, `components/dialogs/`), moved
here so there is ONE switcher rather than two. The host's copy is deleted.

- **It reads `allWindowTabs`, not `activeTabs`.** That api member exists for this: `activeTabs` is
  this window's tabs alone, which is right for the tree and wrong for a switcher, because the tab
  you are reaching for may be in the window behind this one. `refreshAllWindowTabs()` is called when
  the dialog opens and once a second while it is up - cross-window state is collected on demand
  rather than pushed, so showing whatever the last window to publish left behind would be showing a
  stale list at the one moment it matters. Both members are gated on `minBossVersion`, not
  `minApiVersion`: `ActiveTabsProvider` is `@HostImplemented` and served parent-first.
- **The api default degrades, it does not empty.** `allWindowTabs` defaults to `activeTabs`, so a
  host that cannot see other windows shows this window's tabs rather than none.
- **Rows are grouped by (window, workspace), not by workspace.** The same workspace can be running
  in two windows, and merging them would put a tab you cannot reach under a heading that says it is
  here. This window's groups come first; within a block, workspace name order, for the reason
  `TabTreeBuilder.workspaceOrder` records.
- **Two indices per row, and deriving one from the other is the bug.** `matchIndex` is the position
  among TABS, which is what the arrows move through - headers are not stops. `rowIndex` is the
  position in the flat list, which is what `animateScrollToItem` takes. They differ by the number of
  headers above.
- **`onPreviewKeyEvent`, not `onKeyEvent`.** The search field has focus, so Up/Down would move a
  caret and Escape would do nothing if the dialog did not take them first.
- **Known: selecting a tab in another WINDOW does not focus it.** `selectTab` resolves against the
  split-view state of the window whose provider this is. Cross-*workspace* works (the host's
  `selectTabAnywhere` plus its workspace switch); cross-window needs a window-aware verb on the api,
  and listing those tabs is still worth it because it answers "where is that tab".

### Search left the top of the panel

There is no `BossSearchBar` above the tree any more, and the tree has **no filter at all** -
`TabTreeBuilder.filterTreeNodes`, `filterTabStructure`, the `allowCollapse` parameter threaded
through `workspaceGroup` and `TabStructure`, and the never-constructed `TabTreeNode.TabNode` all
went with it rather than being left as dead branches.

The reasoning: the field cost 28dp of a narrow sidebar permanently, to filter a tree that is already
grouped by workspace and collapsible per pane, and the search actually worth having is the switcher's
- it spans every window, where the tree is this window's by design. Two search boxes over overlapping
sets of tabs, one of which cannot see half of them, is worse than one.

`allowCollapse` existed only because a search must not collapse the rows it just matched. With no
search there is no such case, so the collapse rule is unconditional and the parameter is gone rather
than being passed `true` from one call site.

### The workspace footer

`WorkspaceFooter.kt` mirrors the menu the host hangs off `WorkspaceButton` at the foot of its
vertical tab bar. Four 32dp icon buttons under a full-width rule - open a workspace, save one, open one from a file,
delete one - and then, rightmost, a search button that raises the quick switcher.

- **It is a sibling of the LazyColumn, not an item in it.** The list takes `weight(1f)` and the
  footer sits under it, so the actions stay put while the tree scrolls. The empty state takes a
  weight for the same reason - `fillMaxSize()` there pushed the footer off the bottom.
- **No rule above the actions.** It separated the footer from the tree when the two were adjacent.
  The floors stack sits between them now, and a line directly under the building read as ground the
  building was standing on.
- **The search button is always drawn, the workspace buttons are not.** The four workspace actions
  moved into a private `WorkspaceActions`, emitted straight into the footer's `FlowRow`, so its
  null-provider early returns refuse those buttons without refusing the row, the rule and the search
  button beside them. Search needs no provider the footer can be missing.
- **The switcher is raised by `TopOfMindContent`, not by the button that opens it** - unlike the
  workspace picker, which the button hosts as its `overlay`. The switcher needs `ActiveTabsProvider`
  (non-null only inside the tree) and the footer draws nothing at all without a
  `workspaceDataProvider`, so hosting it here would take the deep-link action's only landing place
  away on a host that serves tabs and no workspaces.
- **A FlowRow, not a Row.** The host's `HostActionsFlowRow` KDoc has the measurement: at 120dp a Row
  gives its LAST child zero width rather than clipping it, so the last button silently disappears at
  a width the user can reach by dragging.
- **Dialogs go through `genericDialogProvider`, not hand-drawn Compose ones.** Its prompts are
  suspend calls that return the answer, so there is no dialog-visibility state to hold, and the host
  draws them - which is what puts them above a GPU-composited browser surface. The workspace menu is
  the exception, because it has to mark three states per row and `ContextMenuItemData` has no
  trailing icon: that one is a `BossPopup`, which is the same guarantee for a non-modal. **Never a
  raw Compose `Dialog` or `Popup`** - under JxBrowser HARDWARE_ACCELERATED they render behind the
  page.
- **`WorkspaceDataProvider.deleteWorkspace` takes a NAME**, where the rest of the interface is keyed
  by id. Passing an id deletes nothing and reports nothing.
- **Open Workspace Folder and Reset to Default are deliberately omitted.** They need
  `WorkspaceManager.getWorkspaceDirectory()` and `WorkspaceManager.resetToDefault()`; neither is on
  `WorkspaceDataProvider`, so there is nothing to wire them to. If they are ever wanted, that is an
  api change first. A disabled button would have been the same absence taking up room.
- **Save cannot snapshot the live layout first.** The host's own Save calls
  `updateCurrentWorkspace(getCurrentWorkspace())` before `saveCurrentWorkspace(name)`; nothing on
  `SplitViewOperations` hands a plugin the layout that is on screen, so this saves whatever the host
  is currently holding as the current workspace.
- **Host-side, delete is currently a no-op** (BossConsole `components/plugin/providers/`
  `WorkspaceDataProviderImpl.deleteWorkspace`): it looks the workspace up by name and then calls
  `WorkspaceManager.deleteWorkspace(workspace.id)`, but that function matches on NAME, so nothing
  matches. The plugin side is right; the fix belongs in the host.
- **`liveWorkspaceIds` is a getter, not a flow**, so reading it during composition would never
  recompose. The menu takes a snapshot as it opens, unioned with the workspace ids in the tab list.
- **The footer's glyph is 20dp inside a 32dp target**, not 14dp. These sit in the same column as
  the host's own action row (`SIDEBAR_ICON_SIZE` = 32dp buttons wrapping `BossActionButton`'s
  20dp `iconSize` at 2dp content padding); a 14dp glyph is a tab row's bare icon and read as a
  smaller class of control next to the host's.

### The floors view

`WorkspaceFloors.kt` draws every workspace this window is running as a floor of a building, in a
fixed-height block between the tree and the footer. The tree says where a tab is by naming its
workspace and its pane; this says it as a shape. Clicking a floor switches to that workspace,
through the same `switchToWorkspace` the workspace headers use.

- **A floor is a shallow BOX, seen head on and a little from above.** A front face carries the
  workspace name and its panes; a thin top face and a thin right face recede back-and-up off it. The
  depth is ONE vector - `FLOOR_DEPTH_X` 8dp across, `FLOOR_DEPTH_Y` 4dp up, about tan(30 degrees),
  which is the isometric ratio - applied to every corner, so vertical world edges stay vertical on
  screen and every storey is the same box drawn in the same place. It is paid once for the whole
  building, not once per storey, so eight workspaces cost the same 8dp of width as one does. It is
  meant to read as a bar with a little solidity, not as a slab: "slightly" is the whole brief.
- **Why this does not hit the trap that killed five rewrites.** The view was an isometric PLATE
  first: the panes lived on a top face seen from above, with extruded faces hanging off it. Every
  version of that foundered on one corner, because two identical boxes stacked with no air between
  them hide each other's top faces exactly - so any version that showed a lower workspace's plate
  was drawing a thing that cannot exist, and that plate's back-right corner had nowhere to go. It
  came back as a wedge poking out past the face above, then as a flat crop when the clip was a
  rectangle, then as a column, then as a skirt, then as a step where each lower plate grew deeper.
  **Each of the five was rejected on sight, and that is the reason not to push the projection any
  further than it goes now.** What makes the current depth safe is that it is the opposite model:
  the panes stay on the FRONT face, and each box's whole silhouette - front, top and side - is laid
  out inside its own floor band. The depth comes OUT of the band rather than being added on top of
  it, so `FLOOR_GAP` stays air that nothing is drawn into, no floor can occlude another, and there
  is no impossible corner to resolve. Deepen it far enough and the top faces close that air up
  again, which is where the old failure is waiting.
- **The panes need no transform, and skewing them is exactly the old mistake.**
  `WorkspaceFloorPlan.panesOf` answers in a 0..1 box, so on the front face the fractions ARE the
  rectangle: a left/right split draws as two columns, a top/bottom one as two rows, which is what
  those words mean. The name is on the same face and unskewed, its padding giving back exactly the
  room the two receding faces took. Text sliding onto a receding face is text on a wall that is not
  facing the reader.
- **The receding faces are SHADING of the front one, not colours of their own.** Each is a blend of
  what the front face reads as - the floor's ground plus whatever wash the panes lay over it - with
  the side going 35% toward black and the top 10% toward white, so three flat quadrilaterals read as
  one solid and a lit floor is lit on all three faces. Shading off the bare ground instead gave the
  current floor a top face darker than its own front, which read as a shadow between two bars.
  An early version painted the side in `BossThemeColors.BackgroundColor`, a token with nothing to do
  with the floor, and it read as a hole punched in the bar.
- **Structurally true, schematically proportioned.** Which panes there are and which side each one
  is on is exact. The PROPORTIONS are not, and cannot be: `paneAreaFor` maps a pane's NAME back to
  a rectangle, and a name says which edges a pane touches and nothing about where the divider sits,
  so a split dragged to 20/80 draws as halves. The host's own `SplitMap` can be honest about
  proportion because it is handed the panes' measured rectangles; nothing on the plugin api hands a
  plugin those. No ratio is invented from tab counts or anything else.
- **The HEIGHT is fixed and the floors divide it.** `FLOORS_HEIGHT` is 140dp, sized against the
  host's own navigation map (a 1.5 aspect-ratio box inside a 10dp inset, so about that tall in a
  200dp sidebar). The two are the same kind of thing, a small picture of where you are - and every
  number in here used to be a constant, so six workspaces drew a block six times as tall as one and
  pushed the tree out of the panel a row at a time. `floorMetricsFor(count)` solves
  `height = count * floor + (count - 1) * FLOOR_GAP` for the floor. Only the FLOOR is dynamic; the
  3dp of air between two of them is the same at every stack size. The depth changes none of this -
  a floor's BAND is still `FloorMetrics.height`, and the box is drawn to fit inside it.
- **Two clamps give the fixed height up, each in a chosen direction.** The height is exact for three
  or four workspaces. `MAX_FLOOR` (48dp) stops one or two drawing enormous bars; the stack is then
  SHORTER than 140dp and the tree gets the difference. `MIN_FLOOR` (26dp) stops a dozen becoming
  slivers with unreadable names; the stack is then TALLER and scrolls inside its cap.
  `FloorMetricsTest` pins the exact height over 3..4, that past five it only ever GROWS, that the
  gap never moves, and each clamp's direction.
- **It reads `TabTreeBuilder`'s output, not `SplitConfig` a second time.** The floors are built from
  the same `WorkspaceTabStructure` the tree is drawing, in the same order, so a floor and its group
  in the tree can never disagree about the shape of a workspace and always sit in the same position.
- **Always on, and with no heading**, like the host's navigation map. There was a FLOORS heading
  with a chevron and a `FloorsViewState` behind it; both are gone. A heading over a picture of the
  window's workspaces labels something that is already showing what it is, and it cost a 24dp row
  to do it. `heightIn` means the block shrinks to fit a two-workspace window anyway, so there was
  never much height for a toggle to hand back. There is no rule BELOW it either: a line under the
  block read as ground it was standing on rather than as the top of the footer, so
  `WorkspaceActionsFooter` draws none.
- **The scrollbar is pinned visible, where the panel default fades in on a scroll.** The stack is a
  fixed height with no other edge to it, so a hidden scrollbar left no way to tell a stack of four
  floors from the top four of nine - the block looked complete either way. Safe to pin because
  `lazyListScrollbar` already refuses to draw when the content fits its viewport, so a window with
  three workspaces shows nothing; setting `ScrollbarConfig.alpha` to a non-null value is exactly
  what turns the fade off (`alpha ?: if (isScrolling) 0.8f else 0f`). **It is an indicator, not a
  control**: that modifier is `drawWithContent` with no pointer input, so the thumb cannot be
  dragged. Scrolling the stack means the wheel, or clicking a floor.
- **A workspace that has just opened is scrolled into view.** It is appended to the bottom (see
  `WorkspaceArrival`), so past the four or so floors that fit it opens BELOW the fold and the panel
  would answer a new workspace by showing nothing at all. Three things about the effect: the FIRST
  sighting is not an opening - everything the window was already running arrives at once when the
  panel mounts, and scrolling then would jump the block to the bottom on every launch, so that batch
  is recorded and nothing is scrolled to. The LAST new id is the target, because two workspaces
  opening in one tick are both below the fold and the lower one is what needs the scroll. And it is
  keyed on the workspace list, so the roughly-2s rebuild that changes nothing does not re-run it.
  A stack that fits its cap scrolls nowhere, which is a no-op rather than a special case.
- **Hit-testing is per floor, and the whole pitch takes the click.** Selection is per workspace, so
  the `Box` takes the press and the `Canvas` inside it only draws.
- **Every fill is an opaque BLEND, never a surface at an alpha.** A translucent floor lets the
  panel's ground through and reads as a wash over the page rather than a bar drawn on it. The floor
  is `lerp(darkSurface, accent, …)`; only the PANES are translucent, and they have an opaque floor
  under them to be translucent against. The lit floor's name is `TextPrimary`, not the accent:
  `AccentColor` is a fill token and lands under 4.5:1 as text, which is written down under Colours
  and has caught this plugin before. The pane being worked in is filled harder still, off
  `activeTabsProvider.activePanelId` - null for every workspace that is not on screen, so only the
  lit floor ever marks one.
- **An empty pane is drawn as an outline with no fill.** A pane with no tabs is part of the split
  and should be visible as one; filling it would claim there is something in it.
- **`FloorsViewState` is gone**, so nothing here is component-scoped state any more. Hover lives in
  a `MutableInteractionSource` per floor, which is per composition and correct by construction.
- **Verify it by LOOKING at it.** Four of the five rewrites were reasoned on paper and each was
  wrong on screen; the first screenshot also found a placement bug the arithmetic had hidden
  (`requiredHeight` centres an oversized child). Hot reload into the running dev host over its own
  MCP port and `screencapture -R` the window - see the project memory for the recipe.


### One section per pane, named by the host

`TabTreeBuilder.buildTabStructure` groups tabs by `panelId` and names each group from
`ActiveTabData.splitPosition`, which the host derives with the SAME function its vertical tab bar
uses for its own group headers (`paneLabel` over `paneGlyphFor`, on the panes' measured rectangles).
So the panel and the bar an inch to its left agree by construction.

It used to rebuild the split TREE from the workspace's saved `SplitConfig` instead, and disagreed
with the bar three ways at once:

- **Shape.** A nested pane arrived as "RIGHT > TOP" at two levels of indent where the bar called the
  same pane "Top right" in a flat list.
- **Freshness.** `SplitConfig` is the SAVED layout, so splitting a pane without saving made its
  panel count disagree with the running one and the whole workspace fell back to a single undivided
  list - the panes vanished from the panel while the bar still drew them.
- **Identity.** Layout panel ids were mapped to runtime ones by position in a depth-first walk, so
  any mismatch filed one pane's tabs under another pane's heading.

Three things to know:

- **A host too old to populate `splitPosition` needs no gate.** The field has always been on
  `ActiveTabData`; an old host leaves it null and a pane falls back to "Pane N", which is the same
  word the bar uses for a pane no honest name fits. Fewer names, never a wrong one.
- **One pane means no section.** A heading over every tab in the workspace would claim a divider
  that is not there. The bar's rule too.
- **Order is the host's.** Tabs arrive pane by pane in layout order and `groupBy` keeps
  first-encounter order, so a section's place in the panel is its pane's place in the window.

`paneAreaFor` in `PanePlacement.kt` maps a pane's name back to the rectangle it was named for, and
is the ONE definition of that: the section header's position glyph and the floors stack both read
it, so a header and a storey cannot put one pane on two different sides.

### Dropping on a pane, and springing a workspace open

A drag names a WORKSPACE when it lands on a workspace header and a PANE when it lands on a split
header. `TabDragState` keeps the two in separate maps and `endDrag` prefers the pane: it is the more
precise answer to the same gesture. On screen they cannot both be under the pointer - a workspace
target is its header row, a pane target is a split header further down - so the precedence is a
stated rule, and `TabDragTargetTest` pins it with deliberately overlapping bounds because nothing
else would notice if it flipped.

- **A pane of the tab's OWN workspace is a target, where the workspace is not.** `hoveredWorkspaceId`
  refuses the tab's own workspace, because a workspace-level move to where it already is does
  nothing. A different PANE of that workspace is a real destination, and the only way to express it:
  `moveTabToWorkspace` has to refuse a same-workspace move because without a pane it cannot tell one
  from the other. `moveTabToPane` (api 1.0.88, host 9.5.9) is what carries it.
- **Its own pane is never a target**, and the guard is on the PANE id, not the workspace id. With the
  workspace id it would refuse every pane of the workspace the tab is in, which is exactly the case
  the pane targets were added for.
- **A pane with no tabs cannot be a drop target at all.** The tree is built from `ActiveTabData`, so
  an empty pane contributes no id and draws no header. Dropping on the workspace header and letting
  the host pick is the route to one.
- **A section that stands for a nested split gets no pane target**, the same three cases that leave
  its toggle null: it has no pane id to name.
- **Hovering a collapsed workspace with a tab in hand opens it after 550ms.** Its panes only exist as
  drop targets once the group is open, so without this a pane in a collapsed workspace could not be
  reached without letting go first. The delay is what makes dragging PAST a header free - an ordinary
  drag crosses every header between the tab and its destination, and opening each one would reflow
  the tree under the pointer. It only ever OPENS: a group that closed again when the pointer left
  would take with it the panes that were the reason to open it. The timer re-checks the pointer and
  the drag after the wait, since either may have moved on.

### The headers

`SectionHeaders.kt` draws both header rows, and the split-section one is the host's pane group
header (`GroupHeaderRow` in `TabBarGroupHeader.kt`) to the dp: 24dp tall, `padding(horizontal =
10.dp)`, items 8dp apart, `raised` under the pointer, a 10sp SemiBold label on 0.8sp tracking that
takes `weight(1f)` and ellipsises, and actions as 24dp targets with a 4dp radius around a 12dp
glyph tinted `textSecondary`, lifting to `textPrimary` on hover.

- **The split glyph is a position marker, not a measured diagram.** The host's 16x12dp
  `SplitPositionGlyph` is honest because it is drawn from the panes' real rectangles, so it follows
  a divider as it is dragged. This panel has only the NAME the host derived from those rectangles,
  and a name says which edges a pane touches and nothing about where the divider sits. So the fill
  here is a schematic half: it says WHICH side a pane is on - which is what "Left" already claims -
  and nothing about how the split is actually divided. It draws the four corners as well as the
  four edges, and nothing at all for "Pane 3", because the host numbers a pane precisely when no
  honest name fits. If the api ever hands a plugin the measured pane rects, `paneAreaFor` is what
  should stop being consulted.
- **There is no chevron on a section header.** The glyph leads the row where the host puts its own,
  and it brightens when the pane is open, which is the state a chevron would have carried. The
  collapsed pane's summary row keeps a real chevron, because that row has nothing else to say
  "open this".
- **The label stays uppercased**, where the host's is not. This one sits directly under a workspace
  header in the same tree; a tracked heading and an untracked one stacked 24dp apart read as a
  mistake rather than a hierarchy.
- **The trailing inset is 10dp, matching the leading one.** It was 4dp, which was fine while the
  slot held only a tab count. Two stacked header rows whose action buttons do not line up read as
  two different controls.
- **Closing a group asks first, or is not offered.** Both headers carry an `Icons.Outlined.Close`
  action that closes every tab under them, routed through `genericDialogProvider`'s confirm with
  the group named and the count spelled out, `isDestructive = true`. A null dialog provider hides
  the button: a control that closes a dozen running tabs with nothing to undo it is not an
  acceptable degradation. `TopOfMindPanel` owns the one `closeTabs` lambda every header hangs off,
  and each header composes its own message because only it knows what it is about to close.
- **It closes what is DRAWN, not what the workspace owns.** `TabTreeBuilder.tabsIn(structure)`
  flattens the structure the header is rendering, which a search has already filtered - so the
  count in the confirm and the tabs that close are one list. The snapshot is taken when the header
  composes and used verbatim after the dialog returns, because the tree rebuilds roughly every 2s
  and would otherwise change under an open dialog.
- **`closeTab` reaches tabs anywhere** (the host's `closeTabAnywhere`), so clearing a workspace
  that is not on screen does not first have to switch to it. Refresh afterwards, exactly as the
  move does, rather than waiting on the host's 2s poll.

### Collapsing a split pane

`SplitPaneExpansion.kt` is the host's `TabGroupExpansion` (BossConsole,
`main_window_panels/TabGroupExpansion.kt`) ported. A split section that is not the one being worked
in draws only the tab its pane is showing, plus a summary row of favicons standing in for the rest,
so a four-way split costs a few rows rather than twenty.

- **Hovered and pinned are separate collections.** Hovering a section header (or its summary row)
  chooses which pane is the open one and that choice persists until another header is hovered or
  the pointer leaves the PANEL; clicking the header, or the summary row's chevron, pins the pane
  open and that survives the pointer leaving. Two collections, because leaving the panel must not
  silently undo a click.
- **The panel is the only hover boundary that counts.** `TopOfMindPanel` puts one `hoverable` on
  the panel's root Box and calls `panelExited()` when it goes false. A per-section exit is exactly
  what must NOT clear the choice - moving from a header down onto its rows leaves that header.
- **`retainOnly(panelIds)` on every rebuild.** Both collections are keyed by panel id and nothing
  tells them when a pane closes, so a long session accumulates ids and a recycled id would come up
  pinned open for no reason. Called from `TabTree` off the live panel ids in `activeTabs`.
- **The active pane is never asked about.** `activeTabsProvider.activePanelId`, in the workspace on
  screen, is always expanded - that is a fact about the split rather than something hover decided.
- **A workspace that is NOT on screen has no focused pane at all**, so every one of its panes reads
  as "not the one being worked in" and collapses to its selected tab until the pointer chooses one.
  That is the consistent reading, and those workspaces are the long tail this collapse exists for.
- **Only leaf sections collapse.** `TabTreeBuilder.paneIdOf` returns the pane a section stands for,
  and null for a section whose children are further sections: a container for a nested split has no
  tab of its own to collapse TO. Since the builder went flat there are no containers left, so this
  answers for every section it is given - it is kept because the renderer is written against the
  sealed type rather than against that promise.
- **A search expands everything.** The tree is already filtered to what matched, so collapsing
  would hide the very rows that were searched for. `allowCollapse = searchQuery.isBlank()`.
- **The row a collapsed pane keeps is an ordinary `TabRow`**, emitted by recursing into
  `TabStructure` with a one-item structure. Hand-building it would be a second copy of the markers,
  the menu, the drag and the close, drifting away from the first.
- **The summary row is favicons, not a count.** The host's KDoc has the reason: "7 more tabs" says
  how many there were and nothing about what they were. Capped at 8 chips plus a `+N`, so it is
  always exactly one row tall. Its chips reuse `TabRow`'s `TabGlyph` (now `internal`) so a tab
  looks the same collapsed as it does with a row of its own.
- **A chip's hover fill is `contextMenuBorder`, not `darkSurface`.** The row underneath is already
  `darkSurface` once the pointer is anywhere in it, so a chip in the same token would be invisible
  exactly when it is being pointed at.
- **The chips carry no tooltip**, where the host's do: the plugin ui exposes no hover-tooltip
  primitive, and a raw Compose `Popup` renders behind a hardware-composited browser surface.

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
