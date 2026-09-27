<!-- Vendored from kaiharimoto/Master-UI, branch claude/beautiful-bell-7du12e, commit e17e381
     (kit/guides/CURSOR.md). Neue ports it to Compose: app/neue/.../cursor/FamilyCursor.kt and
     app/core/.../input/CropCaption.kt. A change to the spec is a change to both. -->

# Cursor

Every Master app uses the same pointer: **Crop caption**. Four 2px trim marks around a 2px point. Over something clickable the marks open out and frame it, the way a selected tile gets its ring; a micro-caps caption sits in the slug under the frame, as a printer notes the job outside the trim, and says what a click will do. The frame says what you are on; the caption says what happens.

It is one file, `cursor/cursor.js`: no dependencies, any framework, Electron included. It hides the system pointer only while it runs, so if the script never loads the app keeps the normal arrow.

## Install

Plain HTML or any framework, once per page:

```html
<script type="module">
  import { installCursor } from './master-ui/cursor/cursor.js'
  installCursor()
</script>
```

React (`react/Cursor.tsx`), once at the root:

```tsx
import { MasterCursor, useCursorBusy } from './master-ui/react/Cursor'

<MasterCursor />
useCursorBusy(job.running && { pct: job.progress })   // anywhere a long job runs
```

The styles are injected by the script; there is no stylesheet to import. It reads `--ink`, `--paper`, `--ink-25`, `--ink-70`, `--font-sans`, `--font-mono` and `--ease` from `master-ui.css` for the caption, with black/white fallbacks, so the caption follows the theme. The marks are drawn in difference mode and need no theme.

## Anatomy

| Part | Spec |
|---|---|
| Point | 2 × 2 px at the hotspot (the exact pointer position), difference mode |
| Marks | four L-shaped corners, 2 px weight, 6 px arms (8 px when framing), difference mode, so they read on paper, ink and pictures |
| Frame at rest | 16 × 16 around the point |
| Caption | micro caps 10 px, +0.08em, 20 px high, 7 px side padding; ink block with paper text and a 1 px paper outline (the double stroke of §17.3); values in mono, normal case |
| Caption position | 6 px under the frame, flush with its left edge; on wide targets (≥ 480 × 160, e.g. list rows) it follows the pointer instead; flips above the frame near the bottom of the window; never leaves the window |
| Layer | `position: fixed`, `z-index: 2147483647`, `pointer-events: none` |

## States

| State | When | Marks | Caption |
|---|---|---|---|
| Default | nothing interactive under the point | 16 × 16 around the point | none |
| Pointer | links, buttons, tabs, menu items, options, checkboxes, switches, anything whose CSS cursor is `pointer`, `data-cursor="pointer"` | open out to frame the target 5 px outside its edge (6 px inside for wide targets), 180 ms | `data-cursor-caption`, else `aria-label` / `title` when the target shows no words of its own (icon buttons), else none |
| Text | inputs, textareas, contenteditable, `.selectable`, CSS `text` | close to a 10 px caret, as tall as the field's type (20–56 px), centred on a one-line input | `Edit` until the field has focus; none on read-only fields |
| Drag | range inputs, `role="slider"`, focusable separators, CSS `*-resize` / `move` / `grab`, `data-cursor="drag"` | 36 × 14 along the axis of travel (14 × 36 when vertical); locked while the button is held | name (`data-cursor-caption` / `aria-label`) and live value in mono (`data-cursor-value` / `aria-valuetext` / `value`) |
| Busy | `setBusy(…)`, `aria-busy="true"` on the page or the element under the point, CSS `wait` / `progress` | 16 × 16; one mark lit at a time, stepping clockwise every 0.3 s | a breathing 6 px square, `Working` (or the label you pass) and the percent |
| Not allowed | `:disabled`, `aria-disabled="true"`, CSS `not-allowed`, `data-cursor="no"` | marks drop to 30 %, `✕` at the centre, point hidden | outlined (paper, ink-25 border, ink-70 text): `data-cursor-reason`, else `title`, else none |
| Pressed | mouse button held | marks fill solid | caption inverts to paper for the press |
| Native | `data-cursor="native"`, iframes, embeds, modal `<dialog>` and open popovers (top layer), `.drag-region`, a CSS cursor the app chose on purpose (`crosshair`, `cell`, `zoom-in`, `url(…)`), touch and pen | the family cursor steps aside and the system pointer shows | — |

Busy overrides every other state while it is set.

## Hooks

Most apps need none of these: the cursor reads native semantics and the app's existing CSS `cursor` values. Add them where the default is wrong or silent.

| Attribute | Effect |
|---|---|
| `data-cursor="pointer\|text\|drag\|no\|busy\|default\|native"` | forces the state for the element and its children (the nearest one wins) |
| `data-cursor-caption="Play"` | the caption for a pointer, text or drag target |
| `data-cursor-reason="Nothing to export"` | the caption on a disabled target |
| `data-cursor-value="3:00"` | the drag caption's value, when the raw `value` is not what people read |
| `aria-busy="true"` | busy while the pointer is over the element (or anywhere, on `<html>`) |

API (`cursor.d.ts`): `installCursor({ captions?, snap? })` returns `{ uninstall, setBusy, refresh }`; `setCursorBusy(state)` reaches the installed cursor from anywhere.

## Writing captions

Captions are the voice (§9) at its shortest.

- **A verb, one or two words**, sentence case in the source (the caption sets it in micro caps): `Play`, `Open`, `Rename`, `Create →`. Forward motion gets the arrow, as on buttons.
- **Say what the click does, not what the thing is.** The frame already shows what it is.
- **Do not caption what already has words.** A button labelled `Create` needs no `Create` caption; the kit leaves it off automatically. Rows, tiles, cells and icon buttons are where captions earn their place.
- **Reasons, not apologies**, on disabled targets: `Nothing to export`, `Choose a file first`, `Engine offline`. This replaces the tooltip that explains a disabled state (§10).
- **Values in mono**, units spaced: `Length 3:00`, `Volume 72%`, `Width 320 px`.
- No exclamation marks, no emoji, no full stops.

## Motion

| What | Recipe |
|---|---|
| Frame snaps to a new target | transform, width, height; 180 ms `--ease` |
| Caption appears | opacity 0 → 1 and 4 px drop, 120 ms |
| Colour flips (press, disabled) | 120 ms |
| Busy tick | one mark at a time, 1.2 s per turn, `steps(1)` |
| Busy square | the breathe loop, 2.4 s |
| Following the pointer | no easing, no trail: the point is always exactly under the mouse |

Under `prefers-reduced-motion: reduce` the tick and the breathe stop; the snap stays (it is a transition under 320 ms).

## Rules

- **One cursor per app, and it is this one.** Do not ship a second custom pointer, a hand icon, a coloured ring or a trailing dot.
- **The page's hover states stay.** The frame sits around a control; it does not replace its inversion or wash.
- **Tools keep their own pointers.** A brush, eyedropper or crop tool (§17.3) sets `data-cursor="native"` on its canvas, or sets a CSS cursor such as `crosshair` or a `url(…)` image; the family cursor steps aside there.
- **Top-layer surfaces show the system pointer.** Native `<dialog>.showModal()` and the Popover API paint above any z-index, so the cursor steps aside over them. The kit's dialogs and menus render in portals and keep the family cursor.
- **Electron drag regions.** The OS owns the pointer over `-webkit-app-region: drag`; the cursor hides there and comes back over `no-drag` controls.
- **Never colour it**, never round it, never scale it on press.
