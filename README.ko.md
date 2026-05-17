# Panely Ink

언어: [English](README.md) | 한국어

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

> Android e-ink 기기에도 종이처럼 반응하는 만화 리더가 필요합니다.

Panely Ink는 Android e-ink 기기를 위한 CBZ/ZIP 만화 리더입니다. 첫 대상 기기는
**Meebook M7**이며, [Panely](https://github.com/sejoung/Panely)의 방향성을 터치와
물리키 중심의 고대비 리딩 경험으로 옮기는 것을 목표로 합니다.

이 앱은 Apache 2.0 라이선스의 오픈소스 프로젝트입니다.

## 상태

Panely Ink는 현재 개발 중입니다. 핵심 리더와 로컬 라이브러리는 사용할 수 있습니다.

- SAF 폴더 기반 라이브러리와 중첩 폴더 탐색
- 전체 아카이브 복사 없이 SAF를 통한 CBZ/ZIP 읽기
- ZIP-of-CBZ 시리즈 감지와 중첩 아카이브 열기
- Canvas 기반 단일 페이지 / 두쪽 보기 리더
- LTR/RTL 읽기 방향, 화면 맞춤, 트리밍, 대비, 두쪽 보기, 회전 잠금 — 각 항목마다
  전역 기본값과 책별 sparse override 지원
- `Activity.requestedOrientation`을 무시하는 e-ink OEM을 위한 소프트웨어 회전 폴백
- 물리 페이지 키, 볼륨 키, D-pad, 탭 영역 (hit-testing이 회전 인지)
- 이어보기, 진행률 배지, 책별 설정, 현재 책 북마크, 전체 북마크 화면
- 전체 북마크 행과 인접 권 이동을 해결하기 위한 책 인덱스
- 디스크 JPEG 캐시, Room 메타데이터, 메모리 LRU를 사용하는 표지 추출
- 선택형 e-ink 풀리프레시 주기, 수동 풀리프레시, 흑백 반전 컨트롤
- 이전/다음 권 이동과 첫/마지막 페이지 경계 키 이동
- 영어/한국어 UI 문자열, 시스템 언어 기본값, 영어 fallback, 앱 내 언어 설정

계획 중이거나 아직 미완성인 항목:

- 감마/선명도와 디더링 결정
- 전체 라이브러리 검색 / 메타데이터 인덱싱
- Meebook 전용 refresh API 조사
- 서명된 릴리스 패키징과 실기기 검증
- NDK JPEG/WebP 디코더, 비트맵 풀, 디코드 결과 디스크 캐시 (성능 최적화 항목은
  [docs/ROADMAP.md](docs/ROADMAP.md) 참고)

남은 작업은 [docs/ROADMAP.md](docs/ROADMAP.md)에 정리되어 있습니다.

## 화면과 조작 모델

Panely Ink는 애니메이션이나 색상이 많은 UI를 피합니다. 리더 UI는 필요할 때만
표시됩니다.

- 왼쪽/오른쪽 화면 영역으로 페이지 이동
- 가운데 영역으로 리더 메뉴 열기
- 물리 페이지/볼륨 키는 화면을 다시 터치하지 않아도 계속 동작
- 첫 페이지 또는 마지막 페이지에서 경계 방향 키를 한 번 더 누르면 가능한 경우
  이전/다음 권으로 이동
- 두쪽 보기 모드에서는 화면을 50/50으로 양분하고 두 페이지 안쪽 가장자리가 중앙선에서
  맞붙어 spread가 끊김 없이 이어지도록 그림
- 회전은 우선 `Activity.requestedOrientation`으로 잠금. OEM이 무시하는 경우 Compose
  레벨 transform이 리더 트리(탭 영역/메뉴/오버레이 포함) 전체를 회전시켜 입력 좌표가
  사용자 시야와 일치하도록 유지

시각 시스템은 [docs/DESIGN.md](docs/DESIGN.md)에 정리되어 있습니다.

## 빌드

필요한 환경:

- JDK 17+
- Android SDK 34
- API 30+ Android 기기 또는 에뮬레이터

자주 쓰는 명령:

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
./gradlew test
./gradlew lintDebug
./gradlew compileDebugAndroidTestKotlin
./gradlew connectedDebugAndroidTest
```

디버그 APK 위치:

```text
app/build/outputs/apk/debug/app-debug.apk
```

최신 APK 다운로드 페이지:

```text
https://sejoung.github.io/Panely_Ink/
```

## GitHub Actions

- CI는 `main`/`master` 푸시, Pull Request, 수동 실행에서 동작합니다. debug 단위 테스트,
  lint, androidTest 컴파일, debug APK artifact 생성을 한 번의 Gradle 호출로 수행합니다.
- Release는 `v*` 태그 또는 태그 입력을 받는 수동 실행에서 동작합니다. 릴리즈 검증을
  실행하고 unsigned release APK와 SHA-256 체크섬을 GitHub Release에 게시합니다.
- Pages는 `docs/`를 GitHub Pages로 배포하고 저장소 Pages URL에서 최신 APK 다운로드
  페이지를 제공합니다.

로컬 릴리즈 헬퍼:

```bash
scripts/release.sh patch
scripts/release.sh minor
scripts/release.sh major
```

스크립트는 semantic version 모드에서 `versionName`/`versionCode`를 올리고, unsigned
release APK를 빌드한 뒤 로컬 태그를 만들고 push 여부를 물어봅니다.

## 프로젝트 구조

```text
app/src/main/kotlin/io/github/sejoung/panelyink/
├── core/                 # archive (dup-PFD reader pool), book refs, fit, position,
│                         #   prefs (reading direction, orientation), render, sort, trim
├── data/                 # Room packages, repositories, preferences, reset helpers
├── library/
│   ├── data/             # SAF scanning, covers, path codec
│   ├── model/            # LibraryEntry, BookEntry adapters, SortMode, ViewMode
│   ├── ui/               # Library + AppSettings Compose screens and dialogs
│   └── LibraryViewModel.kt
├── reader/
│   ├── input/            # hardware key dispatch
│   ├── model/            # BookSettings (resolved), BookSettingsOverrides (sparse),
│   │                     #   SeriesContext
│   ├── session/          # CbzBookSession, PageDecoder (keepWarm), bitmap cache
│   ├── ui/               # Reader Compose + Android View host,
│   │                     #   ReaderRotationLayout (SW rotation fallback)
│   └── ReaderViewModel.kt
├── ui/                   # theme tokens and shared components
├── MainActivity.kt       # screen host and key dispatcher
└── PanelyInkApp.kt       # app startup, migration, cover pruning
```

구현 구조는 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)에 더 자세히 정리되어 있습니다.

## 성능 모델

Panely Ink는 비용이 큰 작업을 줄이기 위해 명시적인 캐시를 적극적으로 사용합니다.

- SAF 폴더 스캔은 refresh/reset 전까지 폴더 단위로 캐시
- ZIP-of-CBZ 검사 결과는 시리즈가 아닌 경우까지 캐시
- 중첩 ZIP entry는 `cacheDir/nested`에 한 번 추출
- 표지는 Room 메타데이터, 디스크 JPEG 캐시, 메모리 LRU 사용
- 전체 북마크는 전체 목록에 필요한 책만 인덱싱
- 리더 페이지는 디바이스 등급에 따른 비트맵 LRU 사용 (보통 32–64 MB). 비트맵은
  `RGB_565`로 디코드해 ±3 프리로드 윈도우가 thrashing 없이 fit. 현재 보이는 페이지
  (또는 두쪽 보기 페어)는 다음 디코드 직전마다 LRU touch로 갱신해 e-ink 풀리프레시
  중에도 evict 보호
- 두쪽 보기는 dup-PFD로 만든 독립 ZipFile reader 2개로 두 페이지를 병렬 디코드한 뒤
  invalidate 1회만 발행해 양쪽이 동시에 등장하도록 처리
- 페이지 트림 결과는 디코드된 페이지 단위로 캐시

캐시 동작과 갱신 지점은 [docs/CACHE_STRATEGY.md](docs/CACHE_STRATEGY.md)에 정리되어 있습니다.

## 문서

- [Architecture](docs/ARCHITECTURE.md)
- [Cache strategy](docs/CACHE_STRATEGY.md)
- [Design](docs/DESIGN.md)
- [Roadmap](docs/ROADMAP.md)
- [Contributing](docs/CONTRIBUTING.md)

## 기여

이슈와 Pull Request를 환영합니다. 이 프로젝트는 보수적인 방향을 유지합니다.

- UI는 고대비와 정적인 상태 변화를 우선합니다.
- 넓은 리라이트보다 측정 가능한 개선을 선호합니다.
- e-ink refresh 비용을 항상 고려합니다.
- `core`, `library`, `reader`, `data` 패키지 경계를 지킵니다.
- 동작 변경에는 집중된 테스트를 추가하거나 갱신합니다.

큰 변경을 제안하기 전에는 [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md)를 확인해 주세요.

## 라이선스

Apache License 2.0. 자세한 내용은 [LICENSE](LICENSE)를 참고하세요.
