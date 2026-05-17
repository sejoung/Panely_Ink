# Design

Panely Ink is designed for e-ink first. The UI should feel quiet, static, and readable rather than
animated or colorful.

## Principles

- Contrast over decoration.
- Static state changes over animation.
- Reader content over chrome.
- Hardware keys and large tap zones are primary controls.
- Persist reading state and book-specific settings.

## Color Tokens

Only four tones are used in UI:

| Token | Hex | Use |
|---|---|---|
| `Ink` | `#111111` | primary text, borders, icons |
| `Paper` | `#FFFFFF` | app and reader background |
| `Mute` | `#6B6B6B` | secondary text |
| `Hairline` | `#C8C8C8` | dividers only |

Avoid gradients, shadows, blur, and color-dependent states.

## Typography And Layout

- Use system fonts only.
- Avoid thin/light font weights.
- Keep interactive targets at least 48 x 48 dp.
- Library rows should be easy to hit and scan.
- Reader mode uses no padding unless fit/trim calculation requires it.
- Shared controls live under `ui/components`; screen-specific controls stay with the screen.

## Reader Interaction

The reader screen is split into three tap zones:

```text
left 30%   -> previous/next depending on reading direction
center 40% -> menu
right 30%  -> next/previous depending on reading direction
```

Physical page keys, volume keys, D-pad keys, and tap zones should all stay consistent with reading
direction. At the first or last page, pressing the boundary key again may navigate to the previous
or next volume when a series context exists.

In two-page spread mode the viewport is split exactly in half. Each half draws one page with
inner-edge alignment, so the gutter sides of both pages meet at the screen center. The user
expects the spread to read as a single canvas. Decoration between the halves is not added.

Rotation respects the user's per-book setting (or the global default for books they have not
customized). The app asks the OS first via `Activity.requestedOrientation`. When the OS does not
honor the request, `ReaderRotationLayout` rotates the entire reader Compose tree — page content,
overlays, menu, segmented controls, tap zones — so input and visuals remain consistent. Visual
state never has the page rotated while the menu stays in the original orientation.

## Components

- Buttons are flat, high contrast, and border-driven.
- Selection is expressed with fill inversion, not color accents.
- Segmented controls are shared through `PanelySegments`.
- Dialogs use `Paper` background and `Ink` border.
- Ripple, elevation, shadow, and animated spinners are avoided.
- Sliders apply expensive changes on release, not on every drag frame.

## Text And Localization

- All user-facing text belongs in Android string resources.
- English is the default locale.
- Korean translations live in `values-ko`.
- Avoid embedding workflow explanations or keyboard shortcut documentation directly in the UI.

## Refresh Policy

E-ink redraw cost matters:

- Page turns use partial redraws by default.
- Users can enable configurable full refresh every N pages when their device shows noticeable
  ghosting.
- Users can trigger a manual full refresh from the reader controls.
- Menus, dialogs, and large UI state changes should avoid animated transitions.
- Loading states should use static text.
- Reader full refresh is simulated with high-contrast frame changes because there is no public
  Meebook refresh SDK integration yet.

## Icon Policy

Brand assets use only `Ink` and `Paper`.

Current files:

- `docs/icon/panely-ink-icon.svg`: master icon for README/web/release graphics
- `app/src/main/res/drawable/ic_launcher.xml`: Android launcher vector

Adaptive icon layers and Material You themed icons are intentionally not used for now. On e-ink
launchers, a single predictable black/white vector is clearer than platform-tinted variants.
