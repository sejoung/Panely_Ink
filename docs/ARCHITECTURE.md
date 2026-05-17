# Panely Ink Architecture

This document describes the current implementation shape. Product direction and remaining work
live in `ROADMAP.md`; this file is for contributors reading the code.

## Runtime Shape

Panely Ink is a single-activity Android app:

- `MainActivity` owns the top-level screen state: library or reader.
- `LibraryScreen` uses a lifecycle `LibraryViewModel` for library state and background work.
- `ReaderScreen` creates a `CbzBookSession` and a short-lived `ReaderViewModel` per opened book.
- Reader rendering uses a custom `ReaderView` with `Canvas`, hosted from Compose through
  `AndroidView`.

The reader intentionally does not store its archive session in `androidx.lifecycle.ViewModel`.
An open book owns a `ZipFile`, file descriptor, bitmap cache, and trim cache. Keeping those
resources in a `ViewModelStore` can leave stale archive handles after navigation. Instead,
`ReaderScreen` ties the session lifetime to composition and closes it through `DisposableEffect`.

## Dependency Direction

The code is organized around feature screens, with shared domain primitives pulled down into
`core`:

```text
core  -> no app feature dependency
data  -> core + Android persistence APIs
library -> core + data
reader  -> core + data
ui      -> reusable visual components and theme
```

Important boundary rules:

- Reader code should not depend on `library.model.BookEntry`.
- Library-only scan rows stay in `library.model`.
- Cross-feature book navigation and bookmark resolution use `core.book.BookRef` and
  `core.book.IndexedBookRef`.
- App preferences that affect multiple features, such as `ReadingDirection`, live in
  `core.preferences`.
- Shared archive helpers, including nested ZIP extraction, live in `core.archive`.

`library.model.BookEntryIdentity` is the boundary adapter from library rows to `BookRef`.

## Package Map

```text
core/
  archive/       CBZ/ZIP random access (dup-PFD reader pool) and nested ZIP extraction
  book/          stable book identity and feature-neutral book references
  fit/           fit mode and draw rectangle calculation
  position/      progress model and position repository contract
  preferences/   app preferences, reading direction, and reader orientation enums
  render/        contrast / invert color matrices
  sort/          natural order sorting
  trim/          margin detection

data/
  db/            Room database root and SQL chunking helpers
    bookindex/   scanned book index for global bookmark resolution
    bookmark/    bookmark entity, DAO, repository
    cover/       cover metadata entity, DAO, repository
    migration/   one-off persistence migrations
    position/    resume/progress entity, DAO, repository
    settings/    per-book settings entity (nullable sparse columns), DAO, repository
  preferences/   SharedPreferences-backed app settings

library/
  data/          SAF scanning, cover extraction/cache, path codec
  model/         library entries and UI modes
  ui/            Compose library UI + AppSettingsScreen (global reader defaults)
  LibraryViewModel.kt

reader/
  input/         hardware key mapping
  model/         BookSettings (resolved), BookSettingsOverrides (sparse, persisted),
                 SeriesContext
  session/       open-book resources, parallel page decode pipeline, bitmap cache
                 with explicit LRU keep-warm for visible pages
  ui/            reader UI, overlays, Android View host, ReaderRotationLayout
                 (Compose-level software rotation fallback)
  ReaderViewModel.kt

ui/
  components/    shared buttons, icons, segmented controls
  theme/         e-ink color, spacing, typography tokens
```

## Archive Pipeline

Normal CBZ files are opened through SAF without copying the whole archive:

1. `ContentResolver.openFileDescriptor(uri, "r")`
2. `FileInputStream(pfd.fileDescriptor).channel`
3. Apache Commons Compress `ZipFile` over the seekable channel

Apache Commons Compress `ZipFile` is not thread-safe — multiple `InputStream` instances share a
single `SeekableByteChannel`, so concurrent reads corrupt the channel position. To enable parallel
page decode (used by the two-page spread reader), `CbzArchive` keeps a small pool of independent
`ZipFile` instances. Each pool slot owns its own `ParcelFileDescriptor` (created from
`ParcelFileDescriptor.dup`) and `SeekableByteChannel`, so reads through different slots cannot
collide. `openPage` borrows a slot, wraps the returned `InputStream` in a delegate that releases
the slot back to the pool on `close`, and uses the wrapped stream with the existing
`use { ... }` pattern.

Reader sessions open the archive with `parallelReaders = 2`, paying for one extra central
directory parse (~50–100 ms) and one extra file descriptor. Library scan, cover extraction, and
nested-extractor paths keep the default `parallelReaders = 1` so they do not double-pay for the
extra ZipFile build.

ZIP-of-CBZ entries are the exception. Apache Commons Compress exposes nested entries as streams,
not random-access archives, so `NestedZipExtractor` extracts one selected nested entry into
`cacheDir/nested` and then opens that file as a normal `CbzArchive`.

## Library Pipeline

The library is intentionally shallow by default:

- Roots are SAF tree URIs selected by the user.
- `LibraryRepository.listChildren` scans only the current folder depth.
- `ContentResolver.query` is used directly to avoid `DocumentFile.listFiles()` N+1 IPC behavior.
- Folder rows lazily request book counts and first-cover data.
- Book rows lazily request progress and covers.
- Current-folder search is in-memory.
- ZIP-of-CBZ inspection is cached, including negative results.
- Global bookmarks load bookmark rows first, then resolve only missing book ids through the book
  index or targeted SAF scans.

This keeps large SD-card libraries responsive and avoids full-library scans on startup.

## Reader Pipeline

Opening a book:

1. `LibraryScreen` converts a selected `BookEntry` to `BookRef`.
2. `CbzBookSession.open` opens the archive (with two pool slots for parallel decode) and loads
   resume position, book settings overrides, and app preferences.
3. `BookSettingsOverrides.resolve(appPrefs)` merges the sparse per-book overrides with the
   current global defaults to produce a fully resolved `BookSettings`.
4. The resumed page is decoded once before entering the ready state.
5. `ReaderViewModel` starts preload when viewport size is known.

Preload (`ReaderViewModel.triggerPreload`):

- Phase 1 — both pages of the current visible spread (or just the current page in single mode)
  decode in parallel through separate pool slots, then a single `_decoded` emit triggers one
  view invalidate so both halves appear together.
- Phase 2 — the remaining ±3 window decodes sequentially in proximity order.
- Before every decode, `decoder.keepWarm(visibleIndices)` LRU-touches the visible pages so they
  stay protected from eviction even while `ReaderView.onDraw` is painting solid colors for an
  e-ink full-refresh sequence.
- Spread mode also halves the viewport hint, so decoded bitmaps are sized to a single spread
  pane and decode at `RGB_565` to keep the entire preload window cache-resident.

Rendering:

- `ReaderView.onDraw` reads cache hits only.
- Cache misses draw a blank paper background.
- Decode and trim work happens off the UI thread.
- In spread mode, the left and right pages are drawn with inner-edge alignment so they touch at
  the screen center even when each page is narrower than its half pane.
- A generation counter prevents stale preload results from invalidating the view after rapid page
  moves.
- Boundary navigation uses `SeriesContext` built from `BookRef` siblings.

Rotation (`ReaderRotationLayout`):

- The reader first sets `Activity.requestedOrientation` to honor the user's per-book or global
  choice through the OS path.
- On OEMs that ignore the request (observed on Mee OS), a Compose `Layout` measures the reader
  tree with swapped constraints and applies a 90° `graphicsLayer` rotation. Because the
  rotation lives on the Compose composition layer, hit-testing automatically inverts the
  transform — taps on rotated menus, segmented controls, and tap zones all map back to the
  correct logical coordinates.
- When the OS does honor the rotation request, `LocalConfiguration.orientation` already matches
  the desired direction and the layout disables the software transform, so there is no double
  rotation.

## Persistence

Room stores:

- resume position and page counts
- cover metadata
- per-book settings **as sparse `BookSettingsOverrides`** — every reader-settable field
  (`fitMode`, `direction`, `trimEnabled`, `contrast`, `spreadMode`, `orientation`) is nullable.
  `NULL` means "the user has not explicitly set this field, so the current global default
  applies." A book that was opened but never customized has either no row at all or a row whose
  fields are still all `NULL`. When the user changes a setting, only that one field flips from
  `NULL` to the chosen value; the other fields keep following the global. This is the only way
  global changes can take effect on books the user did not customize, with no side effects.
- bookmarks
- scanned book index for fast all-bookmark resolution

SharedPreferences stores:

- selected library roots and current path
- library sort/view mode
- global app preferences: language, invert, full-refresh interval, and the global defaults that
  back the sparse per-book overrides (default reading direction, default fit mode, default
  trim, default contrast, default spread mode, default orientation)

Reader position/override writes are debounced and finalized on reader disposal — but the
finalize step only writes when the override set actually changed during the session. Opening a
book without changing any setting leaves the per-book row absent, so future global changes still
reach that book on the next open.

## Localization

User-facing strings live in Android XML resources:

- `app/src/main/res/values/strings.xml`: English default
- `app/src/main/res/values-ko/strings.xml`: Korean

The selected language is stored in app preferences. The default is `system`; unsupported system
languages fall back to the default English resources.

## Testing

Use these commands before submitting changes:

```bash
./gradlew test
./gradlew lintDebug
./gradlew compileDebugAndroidTestKotlin
```

Run connected tests when a change touches Room migrations, Android framework behavior, or SAF/URI
logic:

```bash
./gradlew connectedDebugAndroidTest
```
