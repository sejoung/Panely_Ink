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
./gradlew compileDebugKotlin
./gradlew lintDebug
./gradlew compileDebugAndroidTestKotlin
```

Run connected tests for Room migrations or Android framework behavior:

```bash
./gradlew connectedDebugAndroidTest
```

## GitHub Actions

- `.github/workflows/ci.yml` runs on pushes to `main`/`master`, pull requests, and manual dispatch.
  It uploads lint/test reports and a debug APK artifact.
- `.github/workflows/release.yml` runs on `v*` tags or manual dispatch with a tag input. It builds
  an unsigned release APK and publishes it with `SHA256SUMS.txt` to GitHub Releases.

Release APK signing is not configured yet. Add a signing config and GitHub Secrets before treating
release artifacts as install-ready production builds.

## Code Style

- Follow the existing package boundaries.
- Keep UI static and high contrast.
- Avoid animations, shadows, gradients, and ripple effects.
- Prefer explicit caches with documented invalidation points.
- Keep reader hot paths out of Compose when direct `View` drawing is more predictable.
- Put user-facing text in Android string resources. English is the default; Korean lives in
  `values-ko`.
- Add focused tests for behavioral changes.

## Pull Request Checklist

- Explain the user-visible behavior change.
- Mention any cache or persistence migration impact.
- Include test commands you ran.
- Update `README.md` or `docs/` when behavior, architecture, or user workflows change.

## Areas That Need Help

- Meebook refresh API investigation
- Real-device performance measurements
- Dithering and gamma experiments
- Full-library search and metadata indexing
- Release workflow and signed APK packaging

## License

By contributing, you agree that your contribution is licensed under the Apache License 2.0.
