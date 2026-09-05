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
  switched to it. `TabTreeBuilder.workspaceOrder` sorts by `LayoutWorkspace.timestamp`, oldest
  first, so the newest workspace is the bottom row and a row's position never depends on which
  workspace is current. That timestamp is when the workspace was last WRITTEN - the only clock the
  api offers, since `ActiveTabData` carries no time and the ids are names (`workspace-claude-code`)
  rather than the `workspace-<epoch millis>` `generateId` produces - so saving a workspace moves it
  down. A workspace absent from `workspaces` has never been saved, which makes it the newest thing
  there is, and it sorts to the bottom. Name then id break ties, so two workspaces written in the
  same millisecond never swap places between rebuilds.
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

`WorkspaceFloors.kt` draws every workspace this window is running as a storey of a building, in a
fixed-height block between the tree and the footer. The tree says where a tab is by naming its
workspace and its pane; this says it as a shape. Clicking a storey switches to that workspace,
through the same `switchToWorkspace` the workspace headers use.

- **It is an isometric stack that does NOT drift sideways as it rises, and that is a width
  decision.** The hand-drawn version of this offsets each floor a little further right than the one
  below, which is a cavalier oblique: the skew is then paid once per storey, so eight workspaces at
  22dp a floor would want 176dp of lateral room before the first plate is drawn, in a sidebar that
  has about 180dp in total. In a true isometric the vertical world axis maps to the vertical screen
  axis, so a building's corner columns are drawn as vertical lines and congruent floor plates sit
  squarely above one another. That is what is drawn here, and it is why the skew is a flat 22dp for
  the whole building however many storeys it has.
- **The HEIGHT is fixed and the storeys divide it.** `FLOORS_HEIGHT` is 140dp, sized against the
  host's own navigation map (a 1.5 aspect-ratio box inside a 10dp inset, so about that tall in a
  200dp sidebar). The two are the same kind of thing, a small picture of where you are - and every
  number in here used to be a constant, so six workspaces drew a building six times as tall as one
  and pushed the tree out of the panel a row at a time. `floorMetricsFor(count)` solves
  `height = count * slab - (count - 1) * FLOOR_OVERLAP` for the slab. **Only the SLAB is dynamic**:
  the bite between two storeys is a constant 12dp wherever it is. A fraction of the slab was the
  other option and it makes the bite deepest exactly when there are two workspaces and the plates
  are largest and most worth seeing; a constant is also the thing a reader can hold onto, since the
  storeys resize and the join between them does not.
- **What the bite is FOR.** The step left in the left silhouette at a seam is
  `(plate - overlap) / plate * SKEW`, because the plate's left edge travels the whole skew over the
  whole plate depth - so with the storeys merely touching, the outline stepped in by the full 22dp
  at every seam and the stack read as a pile of trays. Closing it entirely means biting the WHOLE
  plate - identical boxes stacked with no gap hide each other's top faces exactly - which is the
  version with no panes visible below the top floor, and the panes are what this view is for.
- **The clamps give the fixed height up, each in a chosen direction.** The height is exact for three
  to five workspaces; outside that a clamp bites. `MAX_SLAB` (56dp) stops one or two workspaces
  drawing 70-80dp slabs; the stack is then SHORTER than 140dp and the tree gets the difference.
  `MIN_SLAB` (30dp), `MIN_PITCH` (18dp) and `MAX_OVERLAP_OF_PLATE` (0.55) stop a dozen workspaces
  becoming slivers with two names on top of each other; the stack is then TALLER and scrolls inside
  its cap. `MIN_RISER` (16dp) keeps a face able to hold an 11sp name whatever the slab is, so the
  PLATE gives way first - at the minimum slab that is a 14dp plate, which still shows a two- or
  four-way split. `MAX_OVERLAP_OF_PLATE` is the one clamp on the bite itself: a constant bite is
  only constant while there is a plate to take it out of. `FloorMetricsTest` pins the fixed height
  over 3..5 storeys, that the bite does not move with the stack size, and each clamp's direction;
  three of those fail against an overlap derived from the plate.
- **The rest of the geometry, so the next change can be judged against it.** 10dp of inset each
  side and `SKEW` 22dp, both fixed. The riser started at 4dp and the plate at 18dp, which made a
  storey read as a card with a line under it; the riser is what carries the workspace NAME now,
  which is why it has a share of the slab and a floor of its own. An 8dp gap sits under the rule
  ABOVE the stack, or the top plate's back edge lands on that rule and the two read as one line.
  There is no rule BELOW: a line under the building read as ground it was standing on rather than as
  the top of the footer, so `WorkspaceActionsFooter` draws none. At the sidebar's usual 200dp the
  plate is 158dp wide: a two-pane split draws two 79dp blocks, a four-way split four 39dp blocks.
  Dragged down to 120dp - which the footer's own `FlowRow` note says is reachable - the plate is
  78dp and a four-way split still draws four 19dp blocks. The shallow-perspective fallback (flat
  rectangles, each slightly narrower as it recedes) was not needed: nothing here becomes a sliver,
  because the projection's cost is a constant rather than a per-floor one.
- **The label goes on the slab, not beside it, and on the FACE rather than the plate.** A name in
  its own column to the right wants about 60dp permanently, which at 120dp would leave the plate
  under 40dp - four panes of 10dp, the exact illegible outcome the view exists to avoid. It was on
  the plate first, where it sat across the very pane rectangles it was captioning; the front face is
  the caption surface, and the plate is the picture. The face is a plain rectangle - a vertical
  extrusion stays vertical in this projection - so the text needs no clearance for the slant, just
  8dp at each end plus `SKEW` on the trailing side, since the face's right edge is the plate's
  front-right corner.
- **Structurally true, schematically proportioned - the same caveat as `SplitPositionGlyph`.**
  A pane's place on the plate comes from `paneAreaFor`, so a divider dragged to 20/80 draws as
  halves: the name says which edges the pane touches and nothing about where the divider is. No
  ratio is invented from tab counts or anything else. If the api ever exposes measured pane rects,
  this and `SplitPositionGlyph` are the two functions that should start using them.
- **The exact placement is taken only when the names actually tile the plate.** "Left" plus
  "Top right" plus "Bottom right" describes an arrangement completely and is drawn as it is.
  "Left" plus "Pane 2" plus "Right" is the three-column split - the host numbers the middle pane
  because no honest name fits - and taking the two names at face value would draw the third pane on
  top of the other two, so that falls back to equal slices. `tiles` tests coverage as well as
  overlap: overlap alone would accept "Left" and "Top" and leave half the plate blank. The fallback
  still reads the AXIS from the names, because a pane called "Top" or "Bottom" runs the full width
  and slicing into columns would draw a three-row split lying on its side.
- **It reads `TabTreeBuilder`'s output, not `SplitConfig` a second time.** The floors are built from
  the same `WorkspaceTabStructure` the tree is drawing, in the same order, so a storey and its group
  in the tree can never disagree about the shape of a workspace and always sit in the same
  position.
- **The storeys overlap by sliding UP and clipping to a SHAPE, not by overflowing DOWN.** Flush was
  not enough: with the slabs merely touching, the left silhouette stepped in by `SKEW` at every seam
  - a floor's face ends at its plate's front-left corner and the next floor's plate begins at its
  back-left one - so the outline restarted at each storey. Every floor draws a whole slab; each one
  below the top is then slid up by `FLOOR_OVERLAP` inside a band that much shorter, and clipped to
  what the storey above leaves showing. The picture is identical to letting that storey paint over
  it, and nothing overlaps anything, so **there is no z-order to get right**.
  - **The clip is `clipPath`, not `clipToBounds`, and that difference was a visible bug.** A slab's
    underside runs level across its front face and then SLOPES UP along its side face, so the region
    it covers is not a rectangle. Clipping the band rectangle took the back-right corner off every
    plate but the top one - a flat crop across the right of each slab, with nothing above it to
    justify the cut. The path follows that underside: `overlap` down at the front, a whole
    plate-depth higher at the far right.
  - **Every storey but the top draws a COLUMN over its own plate corner.** Showing any of a lower
    plate is non-physical - identical boxes stacked with no gap hide each other's top faces exactly -
    and the symptom was that plate's back-right corner poking out to the right of the face above,
    under the side face's slanted bottom edge, where nothing covers it. So a storey with another one
    above it runs its side face UP to meet the one above on a shared edge, and draws it AFTER its
    plate: the plate keeps its whole front strip and its right end is the column's vertical edge.
    Only the TOP floor keeps the slanted plate corner, because there it is the real top of the
    column. The column's top edge is not stroked - it already carries the storey above's outline,
    which may be the accent.
  - The first attempt did overflow downward, which meant the UPPER floor had to paint last, which a
    `LazyColumn` will not do (it paints in index order) - so it used `reverseLayout = true` with the
    items fed bottom-first. That worked and cost the list its top anchor: with more floors than fit,
    a reversed list starts scrolled to the BOTTOM, and the top floor lost its back edge off the top
    of the viewport. Sliding up has no such trade.
  - **Two storeys STAND FREE: the top one and the selected one.** Both get a full slab of band and
    no clip, so their whole slab shows - back edge, plate corner and all. The top one because
    nothing is above it; the selected one because the workspace you are in is the one whose panes
    are worth reading, and it was arriving half-tucked under its neighbour with its accent outline
    cut off at the corner. It is lifted by making ROOM, never by drawing over the storey above: a
    lower slab painted on top would cover the face carrying that workspace's name, and a building
    does not work that way either. The cost is that the stack is `FLOOR_OVERLAP` taller while the
    selection is not the top floor, which the `heightIn` cap absorbs.
- **Hit-testing is per floor BAND, not per parallelogram.** Selection is per workspace, so the
  fixed-height `Box` takes the click and the `Canvas` inside it only draws. Inverting the projection
  on every press would buy a hit test nobody could tell apart from this one.
- **`heightIn(max = ...)`, not `height(...)`.** For three or more workspaces the storeys are sized
  to fill exactly `FLOORS_HEIGHT`, so the two agree; for one or two `MAX_SLAB` bites and the block
  is shorter, and holding the full height empty there would take room the tree can use. Past the
  clamps it scrolls. Capping the floor COUNT instead would have put a "+N more" in a panel where the
  thing behind it cannot be clicked.
- **Always on, and with no heading**, like the host's navigation map. There was a FLOORS heading
  with a chevron and a `FloorsViewState` behind it; both are gone. A heading over a picture of the
  window's workspaces labels something that is already showing what it is, and it cost a 24dp row
  to do it. `heightIn` means the block shrinks to fit a two-workspace window anyway, so there was
  never much height for a toggle to hand back.
- **The slab is SOLID: every face is an opaque BLEND, never a surface at an alpha.** A translucent
  face lets the panel's ground through, which reads as a wash over the page rather than a block
  sitting on it. Two bugs came out of getting that wrong, both visible: a non-current floor's SIDE
  face was `BackgroundColor` - the panel's own ground - so the slab had a hole cut in its right side
  instead of a shaded face; and the current floor's front face was the accent at a HIGHER alpha than
  its plate, which is the page showing through less rather than a face catching more light. The
  plate and the front are `lerp(darkSurface, accent, …)`, and the side is derived from the front by
  `lerp(front, Color.Black, SIDE_FACE_SHADE)` - one material, one light, so the two faces cannot
  drift apart again. Only the PANES are translucent, and they have an opaque plate under them to be
  translucent against.
- **The lit floor is accent FILL; its label is `TextPrimary`.** The name sits on the front face,
  and for the current floor that face IS the accent - so the accent cannot also be the text. The
  fill is already saying which floor this is. (`AccentColor` landing under 4.5:1 as text is written
  down under Colours and has caught this plugin before.) The pane being worked in inside the lit
  floor is filled harder still, off `activeTabsProvider.activePanelId` - which is null for every
  workspace that is not on screen, so only the lit floor ever marks one.
- **An empty pane is drawn as an outline with no fill.** A pane with no tabs in it is part of the
  split and should be visible as one; filling it would claim there is something in it.

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
