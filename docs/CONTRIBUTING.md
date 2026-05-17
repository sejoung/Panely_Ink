# Contributing

Panely Ink is open source and accepts issues, experiments, and pull requests. The project is still
small, so focused changes are easier to review than broad rewrites.

## Development Setup

Requirements:

- JDK 17+
- Android SDK 34
- Android API 30+ device or emulator

Useful checks:

```bash
./gradlew test
./gradlew lintDebug
./gradlew compileDebugAndroidTestKotlin
./gradlew assembleDebug
```

Run connected tests for Room migrations, Android framework behavior, or SAF/URI behavior:

```bash
./gradlew connectedDebugAndroidTest
```

## Architecture Guidelines

- Keep shared primitives in `core`.
- Keep persistence implementations in `data`.
- Keep SAF scan rows and library-only UI state in `library`.
- Keep open-book resources and page rendering in `reader`.
- Use `core.book.BookRef` or `IndexedBookRef` when data crosses from library into reader,
  bookmarks, or indexing.
- Do not pass `library.model.BookEntry` deeper into reader/session code.
- Per-book settings are persisted as `BookSettingsOverrides` (every field nullable). A
  per-book setting only becomes non-null after the user explicitly changes it. New
  reader-settable fields must follow this same pattern, with a matching global default in
  `AppPreferences` and a merge step in `BookSettingsOverrides.resolve`.
- Per-book and global settings must stay decoupled: changing one global default should still
  affect every book the user has not individually customized. If you add a new setting,
  verify both directions in `BookSettingsOverridesTest`.
- Put user-facing strings in Android string resources. English is the default; Korean lives in
  `values-ko`.

## Code Style

- Follow the existing package boundaries.
- Keep UI static and high contrast.
- Avoid animations, shadows, gradients, and ripple effects.
- Prefer explicit caches with documented invalidation points.
- Keep reader hot paths out of Compose when direct `View` drawing is more predictable.
- Add focused tests for behavioral changes.

## GitHub Actions

- `.github/workflows/ci.yml` runs on pushes to `main`/`master`, pull requests, and manual dispatch.
  It runs debug unit tests, lint, androidTest compilation, and debug APK packaging in one Gradle
  invocation, then uploads lint/test reports and a debug APK artifact.
- `.github/workflows/release.yml` runs on `v*` tags or manual dispatch with a tag input. It runs
  release checks, builds an unsigned release APK, writes `SHA256SUMS.txt`, and publishes the files
  to GitHub Releases.
- `.github/workflows/pages.yml` deploys `docs/` to GitHub Pages. The root page is
  `docs/index.html`, which resolves the latest APK from GitHub Releases.

Release APK signing is not configured yet. Add a signing config and GitHub Secrets before treating
release artifacts as install-ready production builds.

For local release preparation:

```bash
scripts/release.sh patch
scripts/release.sh minor
scripts/release.sh major
```

The script requires a clean working tree, bumps `versionName`/`versionCode` for semantic version
modes, builds the unsigned APK, writes checksums under `build/release/<tag>/`, commits the version
bump, and creates the local git tag. By default it asks before pushing. Use `--push` for
non-interactive push or `--no-push` to skip the prompt.

## Pull Request Checklist

- Explain the user-visible behavior change.
- Mention any cache or persistence migration impact.
- Include test commands you ran.
- Update `README.md` or `docs/` when behavior, architecture, or user workflows change.

## Areas That Need Help

- Meebook refresh API investigation
- Real-device performance measurements (decode time, cache hit rate, rotation latency)
- Dithering and gamma experiments
- Full-library search and metadata indexing
- Release signing and install-ready APK packaging
- Performance roadmap items in `docs/ROADMAP.md`: NDK `libjpeg-turbo` / `libwebp` decoders,
  bitmap pool via `BitmapFactory.Options.inBitmap`, on-disk decoded-bitmap cache, and
  adaptive preload radius for low-memory devices

## License

By contributing, you agree that your contribution is licensed under the Apache License 2.0.
