# Panely Ink

> Android e-ink 기기를 위한 만화 리더 (CBZ). [Panely](https://github.com/sejoung/Panely) 의 영혼을 종이 화면으로.

타깃 디바이스: **Meebook M7** (Carta 1200 / 1648×1236 / 7" / Android 11 / RK3566 / 3GB).
설계 단일 출처는 [`docs/panely_ink_prd.md`](docs/panely_ink_prd.md) 와 [`docs/Panely_Ink_Design_Guidelines.md`](docs/Panely_Ink_Design_Guidelines.md).

## 현재 상태

**M0 — 코어 포팅** 진행 중. 안드로이드 프로젝트 골격 + 디자인 토큰 + 코어 도메인 + 라이브러리 진입 화면이 들어 있다.

| 영역 | 상태 |
|---|---|
| Gradle / Kotlin 2.0 / Compose Compiler / minSdk 30 | 골격 완 |
| 디자인 토큰 (Color/Spacing/Typography) → Compose Theme | 완 |
| Ripple/Indication 비활성, Material3 colorScheme를 Ink/Paper로 잠금 | 완 |
| 코어: `CbzLoader` `NaturalOrderComparator` `FitCalculator` `PositionKey` | 완 |
| `ReaderViewModel` 형태 (±3 프리로드 윈도, PageDecoder 인터페이스) | 인터페이스만 |
| 라이브러리: SAF 폴더 추가/제거 + depth-1 CBZ/ZIP 목록 | 완 |
| 본문 뷰어 (View+Canvas) | M1 |
| NDK 디코더 (libjpeg-turbo / libwebp) | M1 |
| 자동 트리밍 / dithering / contrast | M2 |
| 진척률·표지·검색 | M3 |
| 어댑티브 런처 아이콘 (`docs/icon/`) | M5 — 현재는 placeholder vector |

## 빌드

저장소에 Gradle wrapper jar는 포함되지 않았다. 다음 중 하나로 wrapper를 생성한다.

**Android Studio (권장)** — 프로젝트 루트를 열면 wrapper가 자동 생성된다.

**CLI**
```bash
brew install gradle
gradle wrapper --gradle-version 8.9
./gradlew assembleDebug
```

JDK 17 이상 필요. SDK는 Android API 34, minSdk 30.

테스트:
```bash
./gradlew :app:testDebugUnitTest
```

## 구조

```
app/src/main/kotlin/io/github/sejoung/panelyink/
├── PanelyInkApp.kt           # Application
├── MainActivity.kt           # Compose host
├── core/
│   ├── archive/CbzLoader.kt
│   ├── sort/NaturalOrderComparator.kt
│   ├── fit/{FitMode, FitCalculator}.kt
│   └── position/PositionKey.kt
├── library/
│   ├── LibraryRepository.kt   # SAF depth-1 스캔
│   ├── LibraryViewModel.kt    # roots 영속, 자동 재스캔
│   └── LibraryScreen.kt       # 헤더 + 목록 + 폴더 관리 다이얼로그
├── reader/
│   ├── ReadingDirection.kt
│   ├── PageDecoder.kt         # 인터페이스 (M1에서 NDK 구현)
│   └── ReaderViewModel.kt     # ±3 프리로드, PositionKey, fit/방향
└── ui/
    ├── theme/                 # Color/Spacing/Type/Theme + 토큰 facade
    └── components/            # PanelyButton, PanelyIcons (Canvas outline)
```

## 라이선스

Apache 2.0 — 모체 [Panely](https://github.com/sejoung/Panely)와 동일 (PRD §11 Q4).
