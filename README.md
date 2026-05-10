# Panely Ink

Language: English | [한국어](README.ko.md)

<p align="center">
  <img src="docs/icon/panely-ink-icon.svg" width="128" height="128" alt="Panely Ink icon">
</p>

<p align="center">
  <a href="https://github.com/sejoung/Panely_Ink/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/sejoung/Panely_Ink/actions/workflows/ci.yml/badge.svg"></a>
  <a href="https://github.com/sejoung/Panely_Ink/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/sejoung/Panely_Ink?label=release&color=111111"></a>
  <a href="https://sejoung.github.io/Panely_Ink/"><img alt="Download" src="https://img.shields.io/badge/download-latest%20APK-111111"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache%202.0-111111"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android-111111">
  <img alt="Target" src="https://img.shields.io/badge/target-e--ink-111111">
  <img alt="Status" src="https://img.shields.io/badge/status-active%20development-111111">
</p>

> Android e-ink devices deserve a comic reader that behaves like paper.

Panely Ink is a focused CBZ/ZIP comic reader for Android e-ink devices, built first for
the **Meebook M7**. It ports the spirit of [Panely](https://github.com/sejoung/Panely)
to a touch-first, hardware-key-friendly, high-contrast reading experience.

The app is open source under the Apache 2.0 license.

## Status

Panely Ink is under active development. The core reader and local library are usable:

- SAF folder library with nested folder navigation
- CBZ/ZIP reading through SAF without copying the whole archive
- ZIP-of-CBZ series detection and nested archive opening
- Single-page reader with Canvas rendering
- LTR/RTL reading direction
- Hardware page keys, volume keys, D-pad, and tap zones
- Resume, progress badges, per-book settings, current-book bookmarks, and all-bookmarks view
- Cover extraction with disk + Room metadata + in-memory LRU cache
- E-ink-oriented full refresh policy, trim, contrast, and invert controls
- Previous/next volume navigation and boundary key navigation
- English and Korean UI strings, with English as the default and an in-app language setting

Planned or incomplete:

- Gamma/sharpness and dithering
- Full library search / metadata indexing
- Meebook-specific refresh API spike
- Release packaging and device validation

Remaining work is tracked in [docs/ROADMAP.md](docs/ROADMAP.md).

## Screens And Interaction Model

Panely Ink avoids animated, color-heavy UI. Reader chrome stays hidden until requested:

- Left/right screen zones turn pages
- Center zone opens the reader menu
- Physical page/volume keys work without touching the screen again
- At the first/last page, pressing the boundary key again moves to the previous/next volume when available

The visual system is documented in
[docs/DESIGN.md](docs/DESIGN.md).

## Build

Requirements:

- JDK 17+
- Android SDK 34
- Android device or emulator with API 30+

Common commands:

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
./gradlew testDebugUnitTest
./gradlew compileDebugAndroidTestKotlin
./gradlew connectedDebugAndroidTest
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Latest APK download page:

```text
https://sejoung.github.io/Panely_Ink/
```

## GitHub Actions

- CI runs on pushes to `main`/`master`, pull requests, and manual dispatch. It runs debug unit
  tests, lint, androidTest compilation, and builds a debug APK artifact in one Gradle invocation.
- Release runs on `v*` tags or manual dispatch with a tag input. It runs release checks, builds the
  unsigned release APK, creates SHA-256 checksums, and publishes them to a GitHub Release.
- Pages deploys `docs/` to GitHub Pages and serves the latest APK download page at the repository
  Pages URL.

Local release helper:

```bash
scripts/release.sh patch
scripts/release.sh minor
scripts/release.sh major
```

The script bumps `versionName`/`versionCode` for semantic version modes, builds the unsigned
release APK, creates the local tag, and asks whether to push.

## Project Layout

```text
app/src/main/kotlin/io/github/sejoung/panelyink/
├── core/                 # archive, book identity, fit, position, render, sort, trim primitives
├── data/                 # Room database packages, repositories, preferences, reset helpers
├── library/
│   ├── data/             # SAF scanning, covers, nested ZIP extraction, path codec
│   ├── model/            # LibraryEntry, SortMode, ViewMode
│   ├── ui/               # Library Compose screens and dialogs
│   └── LibraryViewModel.kt
├── reader/
│   ├── input/            # hardware key dispatch
│   ├── model/            # BookSettings, ReadingDirection, SeriesContext
│   ├── session/          # CbzBookSession, PageDecoder, bitmap cache
│   ├── ui/               # Reader Compose + Android View host
│   └── ReaderViewModel.kt
├── ui/                   # theme tokens and shared components
├── MainActivity.kt       # screen host and key dispatcher
└── PanelyInkApp.kt       # app startup and cache pruning
```

More implementation notes are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Performance Model

The app relies on aggressive but explicit caching:

- SAF folder scans are cached per folder until refresh/reset
- ZIP-of-CBZ inspection results are cached, including negative results
- Covers use Room metadata, disk PNG cache, and a bounded in-memory LRU
- Global bookmarks index only the books needed for the all-bookmarks list
- Reader pages use a 100 MB bitmap LRU per open book session
- Page trim results are cached per decoded page

Cache behavior and invalidation points are documented in
[docs/CACHE_STRATEGY.md](docs/CACHE_STRATEGY.md).

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Cache strategy](docs/CACHE_STRATEGY.md)
- [Design](docs/DESIGN.md)
- [Roadmap](docs/ROADMAP.md)
- [Contributing](docs/CONTRIBUTING.md)

## Contributing

Issues and pull requests are welcome. The project is intentionally conservative:

- Keep UI high-contrast and static
- Prefer measured improvements over broad rewrites
- Keep e-ink refresh cost in mind
- Add or update focused tests for behavior changes

See [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) before opening larger changes.

## License

Apache License 2.0. See [LICENSE](LICENSE).
