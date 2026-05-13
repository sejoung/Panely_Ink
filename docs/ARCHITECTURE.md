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
  archive/       CBZ/ZIP random access and nested ZIP extraction
  book/          stable book identity and feature-neutral book references
  fit/           fit mode and draw rectangle calculation
  position/      progress model and position repository contract
  preferences/   app preferences and shared reading direction enum
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
    settings/    per-book settings entity, DAO, repository
  preferences/   SharedPreferences-backed app settings

library/
  data/          SAF scanning, cover extraction/cache, path codec
  model/         library entries and UI modes
  ui/            Compose library UI
  LibraryViewModel.kt

reader/
  input/         hardware key mapping
  model/         book settings and series context
  session/       open-book resources and page decode pipeline
  ui/            reader UI, overlays, Android View host
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
2. `CbzBookSession.open` opens the archive and loads resume position, book settings, and app
   preferences.
3. The resumed page is decoded once before entering the ready state.
4. `ReaderViewModel` starts preload when viewport size is known.

Rendering:

- `ReaderView.onDraw` reads cache hits only.
- Cache misses draw a blank paper background.
- Decode and trim work happens off the UI thread.
- A generation counter prevents stale preload results from invalidating the view after rapid page
  moves.
- Boundary navigation uses `SeriesContext` built from `BookRef` siblings.

## Persistence

Room stores:

- resume position and page counts
- cover metadata
- per-book settings
- bookmarks
- scanned book index for fast all-bookmark resolution

SharedPreferences stores:

- selected library roots and current path
- library sort/view mode
- global app preferences such as language, default reading direction, invert, and full-refresh
  interval

Reader position/settings writes are debounced and finalized on reader disposal.

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
