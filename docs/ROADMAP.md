# Roadmap

Panely Ink is currently focused on a local, offline-first e-ink comic reader for the Meebook M7.

## Current State

Implemented:

- SAF folder library
- folder navigation and current-folder search
- library refresh button
- CBZ/ZIP reader without full archive copy
- ZIP-of-CBZ series detection and nested opening
- single-page Canvas reader
- LTR/RTL reading direction
- tap zones and hardware key input
- first/last page boundary navigation to adjacent volumes
- resume, progress, per-book settings, current-book bookmarks, and all-bookmarks screen
- cover extraction with disk, Room metadata, and memory LRU caches
- e-ink full refresh interval, manual full refresh, trim, contrast, and invert
- English/Korean localization with in-app language selection
- Room migrations and focused unit/instrumentation tests

## Before v1.0

- Meebook M7 real-device validation
- Meebook refresh API spike
- Resume definition and polish
- dithering decision
- gamma/sharpness decision
- release packaging and GitHub Releases APK
- splash screen with static icon

## Later

- full-library search and metadata indexing
- ComicInfo.xml parsing
- OPDS 1.2 / 2.0
- Komga / Kavita integration
- WebDAV / SMB
- CBR/RAR support with license isolation
- vertical webtoon mode
- two-page spread for larger devices
- Boox/Onyx SDK integration
- import/export for progress and bookmarks

## Non-Goals For v1.0

- DRM support
- manga download/scraping
- cloud sync infrastructure
- LCD-first animation-heavy UI
- device-specific SDK dependency required for core reading
