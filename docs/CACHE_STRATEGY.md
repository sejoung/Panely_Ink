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
location.

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

## Cover Disk Cache

Location:

```text
filesDir/covers/<bookId>.png
```

Stores:

- extracted first-page cover thumbnails

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

- 96 entries

Used by:

- `LibraryState.covers`
- list, cover, and grid library views

Invalidated by:

- LRU eviction
- app settings "clear cover cache"
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

## Refresh Button Semantics

The library header refresh button is the user-facing cache invalidation point for folder and series
scan data. It does not clear covers, progress, settings, or bookmarks.

Use app settings for destructive cache operations such as clearing all cover thumbnails.
