# Cache Strategy

Panely Ink uses explicit caches because SAF, ZIP inspection, bitmap decoding, and e-ink refreshes
are expensive. This document lists each cache and its invalidation point.

## Library Folder Cache

Location: `LibraryRepository.childrenCache`

Stores:

- `FolderEntry.documentUri -> List<LibraryEntry>`

Used by:

- `LibraryRepository.listChildren`
- recursive folder count
- first-book lookup for folder covers

Invalidated by:

- library header refresh button
- `LibraryViewModel.refresh()`
- `resetAllData()`

External file changes are not watched. Users must refresh the library to rescan the current
location. Adding a new root refreshes the root list and schedules bookmark pruning, but it does not
clear already cached child folders from existing roots.

## ZIP-of-CBZ Series Cache

Location: `LibraryRepository.seriesCache`

Stores:

- `BookEntry.documentUri -> FolderEntry?`
- `null` means the ZIP was inspected and is not a series archive

Used by:

- automatic background ZIP inspection for visible `.zip` books
- `openBook` when the user taps a ZIP

Invalidated by:

- library header refresh button
- `LibraryViewModel.refresh()`
- `resetAllData()`

## Nested ZIP Extraction Cache

Location:

```text
cacheDir/nested/<parentHash>__<entryHash>.cbz
```

Stores:

- one extracted nested archive selected from a ZIP-of-CBZ file

Used by:

- `CbzBookSession.open` for `BookRef` values with `nestedEntryName`

Invalidated by:

- Android cache eviction
- `resetAllData()`

Nested extraction has a 512 MB per-entry safety limit. Normal top-level CBZ files are not copied
before reading.

## Cover Disk Cache

Location:

```text
filesDir/covers/<bookId>.jpg
```

Stores:

- extracted first-page cover thumbnails encoded as JPEG

Used by:

- `LibraryViewModel.requestCover`
- folder cover lookup through first-book mapping

Invalidated by:

- app settings "clear cover cache"
- `resetAllData()`
- startup pruning through `CoverPruner`

## Cover Metadata Cache

Location:

- Room table `cover_meta`
- runtime mirror `LibraryState.coverStatus`

Stores:

- `OK`: disk cover should exist
- `FAILED`: extraction failed; skip future archive opens

Used by:

- `prefetchBookData`
- `requestCover`

Invalidated by:

- app settings "clear cover cache"
- `resetAllData()`

## Cover Memory LRU

Location: `LibraryViewModel.coverMemoryCache`

Stores:

- recent `ImageBitmap` covers

Limit:

- device-memory based byte budget, smaller on low-RAM devices

Used by:

- `LibraryState.covers`
- list, cover, and grid library views

Invalidated by:

- LRU eviction
- app settings "clear cover cache"
- `resetAllData()`

## Book Index

Location: Room `book_index`, accessed through `RoomBookIndexRepository`

Stores:

- recently scanned `bookId -> BookRef` metadata
- sibling group keys for opening a bookmarked book with adjacent volumes

Used by:

- all-bookmarks screen before falling back to SAF scans
- bookmark orphan cleanup after root changes or explicit refresh

Invalidated by:

- replacement after a full root scan
- `resetAllData()`

## Reader Page Bitmap Cache

Location: `CbzBookSession.BitmapPageCache`

Stores:

- decoded page bitmaps for one open book

Limit:

- 100 MB per open session

Used by:

- `ReaderView.onDraw`
- `CbzBookSession.decode`
- ±3 page preload

Invalidated by:

- LRU eviction
- closing or navigating away from the current book session

## Reader Trim Cache

Location: `CbzBookSession.trimCache`

Stores:

- page index -> detected white margin rectangle

Used by:

- `ReaderView.onDraw`
- `FitCalculator`

Invalidated by:

- closing or navigating away from the current book session

## Progress Load Cache

Location: `LibraryViewModel.progressLoadedBookIds`

Stores:

- book ids whose progress has already been queried during this library session

Used by:

- `requestProgress`

Invalidated by:

- `resetAllData()`
- opening a book removes that book id, so returning from reader can refresh the latest progress

## Global Bookmark Book Index

Location: `LibraryViewModel.globalBookmarkBookIndex`

Stores:

- `bookId -> IndexedBookRef`

Used by:

- all-bookmarks screen, to resolve bookmark rows back to books
- opening a bookmarked page directly from the global list

Invalidated by:

- library refresh
- root removal
- reset all data

The all-bookmarks screen loads bookmark rows first. If there are no bookmarks, it returns without
scanning the library. When bookmarks exist, it reuses indexed books and scans only for missing book
ids.

## Bookmark Orphan Pruning

Location: Room table `bookmark`

Orphan bookmarks are bookmarks whose book id no longer exists in the current roots.

Triggered by:

- adding a new root
- library refresh
- opening the global bookmarks screen after missing books are detected

The root-wide prune path is debounced and deduplicated in `LibraryViewModel` so normal screen
navigation does not repeatedly full-scan the library.

## Refresh Button Semantics

The library header refresh button is the user-facing cache invalidation point for folder and series
scan data. It also refreshes the global bookmark index and schedules bookmark orphan pruning. It
does not clear covers, progress, settings, or valid bookmarks.

Use app settings for destructive cache operations such as clearing all cover thumbnails.
