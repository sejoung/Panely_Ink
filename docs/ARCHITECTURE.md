# Panely Ink Architecture

This document describes the current implementation shape. Product direction and remaining work
live in `ROADMAP.md`; this file is for contributors reading the code.

## Runtime Shape

Panely Ink is a single-activity Android app:

- `MainActivity` owns the top-level screen state: library or reader.
- `LibraryScreen` uses a lifecycle `LibraryViewModel`.
- `ReaderScreen` creates a `CbzBookSession` and a short-lived `ReaderViewModel` per opened book.
- Reader rendering uses a custom `ReaderView` with `Canvas`, hosted from Compose through `AndroidView`.

The reader intentionally does not store its session in `androidx.lifecycle.ViewModel`.
An open book holds a `ZipFile`, file descriptor, page cache, and trim cache. Keeping that in a
ViewModelStore can leave stale archive handles after navigation. Instead, `ReaderScreen` ties the
session lifetime to composition and closes it through `DisposableEffect`.

## Package Map

```text
core/
  archive/      CBZ/ZIP random access
  book/         stable book identity used across aggregates
  fit/          fit mode and draw rectangle calculation
  position/     book id and progress model
  render/       contrast / invert color matrices
  sort/         natural order sorting
  trim/         margin detection

data/
  db/           Room database root
    bookmark/   bookmark entity, DAO, repository
    cover/      cover metadata entity, DAO, repository
    migration/  one-off persistence migrations
    position/   resume/progress entity, DAO, repository
    settings/   per-book settings entity, DAO, repository
  preferences/  SharedPreferences backed app settings

library/
  data/         SAF scanning, cover extraction/cache, nested ZIP extraction
  model/        library entries and UI modes
  ui/           Compose library UI
  LibraryViewModel.kt

reader/
  input/        hardware key mapping
  model/        book settings, reading direction, series context
  session/      open-book resources and page decode pipeline
  ui/           reader UI, overlays, Android View host
  ReaderViewModel.kt
```

## Archive Pipeline

`CbzArchive` opens user-selected files through SAF:

1. `ContentResolver.openFileDescriptor(uri, "r")`
2. `FileInputStream(pfd.fileDescriptor).channel`
3. Apache Commons Compress `ZipFile` over the seekable channel

The app does not copy normal CBZ files into cache before reading. Nested ZIP-of-CBZ entries are the
exception: a selected nested book is extracted once into `cacheDir/nested` and then opened as a
regular archive.

## Reader Pipeline

Opening a book:

1. `CbzBookSession.open`
2. Room loads resume position and book settings
3. app preferences load global e-ink options
4. resumed page is decoded once before entering ready state
5. `ReaderViewModel` starts preload when viewport size is known

Rendering:

- `ReaderView.onDraw` reads cache hits only.
- Cache misses draw a blank paper background.
- Decode and trim work happens off the UI thread.
- A generation counter prevents stale preload results from invalidating the view after rapid page moves.

## Library Pipeline

The library is intentionally shallow by default:

- Roots are SAF tree URIs selected by the user.
- `LibraryRepository.listChildren` scans only the current folder depth.
- Folder rows lazily request book counts and first-cover data.
- Book rows lazily request progress and covers.
- Search is currently in-memory within the current folder.
- Global bookmarks load bookmark rows first, then index only the missing book ids needed to show
  the list.

This keeps large SD-card libraries responsive and avoids full-library scans on startup.

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
- global app preferences such as language, default reading direction, invert, and full-refresh interval

Reader position/settings writes are debounced and finalized on reader disposal.

## Localization

User-facing strings live in Android XML resources:

- `app/src/main/res/values/strings.xml`: English default
- `app/src/main/res/values-ko/strings.xml`: Korean

The selected language is stored in app preferences. The default is `system`; unsupported system languages fall back to the default English resources.

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
