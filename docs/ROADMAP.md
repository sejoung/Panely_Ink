# Roadmap

Panely Ink is currently focused on a local, offline-first e-ink comic reader for the Meebook M7.

## Current State

Implemented:

- SAF folder library
- folder navigation and current-folder search
- library refresh button
- CBZ/ZIP reader without full archive copy
- ZIP-of-CBZ series detection and nested opening
- feature-neutral `BookRef` navigation between library, reader, bookmarks, and book index
- single-page Canvas reader
- LTR/RTL reading direction with an app-level default and per-book override
- tap zones and hardware key input
- first/last page boundary navigation to adjacent volumes
- resume, progress, per-book settings, current-book bookmarks, and all-bookmarks screen
- cover extraction with disk, Room metadata, and memory LRU caches
- optional e-ink full refresh interval, manual full refresh, trim, contrast, and invert
- English/Korean localization with system-language default, English fallback, and in-app language
  selection
- Room migrations and focused unit/instrumentation tests
- CI, release, and GitHub Pages workflows for debug artifacts and unsigned release APKs

## Before v1.0

- Meebook M7 real-device validation
- Meebook refresh API spike
- Resume/progress edge-case polish
- dithering decision
- gamma/sharpness decision
- signed release packaging
- splash screen with static icon

## v1.1 (in progress)

- two-page spread view with per-book toggle and global default
- rotation lock (portrait / landscape) with Compose-level software rotation fallback for
  OEMs that ignore `Activity.requestedOrientation` (Mee OS)
- sparse per-book settings overrides — global defaults always apply to books the user has
  not individually customized; no side effects between global and per-book changes
- global defaults extended to cover every per-book field (fit, direction, trim, contrast,
  spread, orientation) so reader defaults can be tuned once for all new books
- `RGB_565` page decoding for ~50% smaller bitmaps, keeping the ±3 preload window fully
  cached on 64 MB caches (eliminates blank-page flashes from LRU eviction)
- dup-PFD reader pool inside `CbzArchive` so both pages of a spread decode in parallel
  through independent ZipFile instances (~200 ms vs 400 ms sequential)

## Later

- full-library search and metadata indexing
- ComicInfo.xml parsing
- OPDS 1.2 / 2.0
- Komga / Kavita integration
- WebDAV / SMB
- CBR/RAR support with license isolation
- vertical webtoon mode
- Boox/Onyx SDK integration
- import/export for progress and bookmarks

### Performance optimizations

- NDK `libjpeg-turbo` / `libwebp` decoders (PRD §8) — replace framework `BitmapFactory`
  for faster JPEG/WebP page decoding on RK3566-class CPUs
- Bitmap pool with `BitmapFactory.Options.inBitmap` reuse to reduce GC pressure during
  rapid page navigation
- On-disk cache of decoded/down-sampled bitmaps for instant book re-entry without
  re-decoding pages already viewed in the previous session
- Adaptive preload radius — automatically shrink the ±3 window on low-memory devices
  (`isLowRamDevice == true`, cache 32 MB MIN) so RGB_565 still fits without eviction
- Decode-time logging hook surfacing outliers (>500 ms per page) for triaging huge
  images or storage stalls in the field

## Non-Goals For v1.0

- DRM support
- manga download/scraping
- cloud sync infrastructure
- LCD-first animation-heavy UI
- device-specific SDK dependency required for core reading
