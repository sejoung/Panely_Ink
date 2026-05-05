# Panely Ink

> Android e-ink 기기를 위한 만화 리더 (CBZ). [Panely](https://github.com/sejoung/Panely) 의 영혼을 종이 화면으로.

타깃 디바이스: **Meebook M7** (Carta 1200 / 1648×1236 / 7" / Android 11 / RK3566 / 3GB).
설계 단일 출처는 [`docs/panely_ink_prd.md`](docs/panely_ink_prd.md) 와 [`docs/Panely_Ink_Design_Guidelines.md`](docs/Panely_Ink_Design_Guidelines.md).
구현 진행 보드는 [`docs/PROGRESS.md`](docs/PROGRESS.md).

## 현재 상태

**M1 — 뷰어 셸** 진행 중. 책을 열어 페이지 표시, 탭 영역 + 하드웨어 키로 페이지 이동까지 동작한다.

| 영역 | 상태 |
|---|---|
| Gradle / Kotlin 2.0 / Compose Compiler / minSdk 30 | ✅ |
| 디자인 토큰 (Color/Spacing/Typography) → Compose Theme | ✅ |
| Ripple/Indication 비활성, Material3 colorScheme를 Ink/Paper로 잠금 | ✅ |
| 코어: `CbzArchive` `NaturalOrderComparator` `FitCalculator` `PositionKey` | ✅ |
| `ReaderViewModel` (자체 CoroutineScope, ±3 프리로드, decoded 신호) | ✅ |
| 라이브러리: SAF 폴더 추가/제거 + depth-1 CBZ/ZIP 목록 + 닷파일 필터 | ✅ |
| 본문 뷰어 (View+Canvas) — 단일 페이지 렌더 | ✅ |
| 라이브러리 → 리더 진입 + 첫 페이지 표시 | ✅ |
| 3분할 탭 영역 + LTR/RTL 자동 반전 | ✅ |
| 하드웨어 키 (볼륨/Page Up·Down/DPad) | ✅ |
| ZIP 캐시 복사 후 `ZipFile` random-access | ✅ |
| `inSampleSize` 다운스케일 디코드 | ✅ |
| 단일 마스터 vector launcher icon | ✅ |
| Fit modes 적용 + 메뉴 (중앙 탭) | ⏳ M1.3 |
| Resume / 위치 기억 | ⏳ M1.4 |
| 세로 스크롤(웹툰) 모드 | ⏳ M1.5 |
| 풀리프레시 정책 / 자동 트리밍 / dithering / contrast | ⏳ M2 |
| 진척률 / 표지 / 검색 / 시리즈 | ⏳ M3 |
| NDK 디코더 (libjpeg-turbo / libwebp) | 측정 후 결정 |

## 빌드

저장소에 Gradle wrapper(`gradlew`, `gradlew.bat`)는 포함되어 있다. 별도 설치 없이 바로 실행 가능.

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:installDebug           # 연결된 디바이스에 설치
./gradlew :app:testDebugUnitTest      # 단위 테스트
./gradlew :app:lintDebug              # lint
```

JDK 17 이상 필요. SDK는 Android API 34, minSdk 30.

## 구조

```
app/src/main/kotlin/io/github/sejoung/panelyink/
├── PanelyInkApp.kt              # Application
├── MainActivity.kt              # Compose host + sealed Screen 라우팅 + 키 디스패처
├── core/
│   ├── archive/CbzArchive.kt    # SAF Uri → 캐시 복사 → ZipFile random-access
│   ├── archive/CbzPage.kt
│   ├── sort/NaturalOrderComparator.kt
│   ├── fit/{FitMode, FitCalculator}.kt
│   └── position/PositionKey.kt
├── library/
│   ├── LibraryRepository.kt     # SAF depth-1 스캔, 닷파일 필터
│   ├── LibraryViewModel.kt      # roots 영속, 자동 재스캔
│   ├── LibraryScreen.kt         # 헤더 + 목록 + 행 탭 onOpenBook
│   └── ManageRootsDialog.kt
├── reader/
│   ├── ReadingDirection.kt
│   ├── PageDecoder.kt           # 추상 인터페이스
│   ├── BitmapPageCache.kt       # LruCache, byte 단위, 100MB 상한
│   ├── CbzBookSession.kt        # 책 세션 + inJustDecodeBounds + inSampleSize
│   ├── ReaderViewModel.kt       # 자체 CoroutineScope, ±3 프리로드, decoded SharedFlow
│   ├── ReaderInput.kt           # 하드웨어 키 → onPrev/onNext 디스패치
│   ├── ReaderView.kt            # View+Canvas, FitCalculator로 그림
│   └── ReaderScreen.kt          # produceState 로드, 탭 영역, AndroidView 호스트
└── ui/
    ├── theme/                   # Color/Spacing/Type/Theme + 토큰 facade
    └── components/              # PanelyButton, PanelyIcons (Canvas outline)
```

리소스:
```
app/src/main/res/
├── drawable/ic_launcher.xml    # 단일 vector launcher icon (마스터 SVG 1:1)
├── values/{colors,strings,themes}.xml
```

## 디자인 결정 메모 (구현 단계에서 추가된 것)

PRD/Design Guidelines가 단일 출처지만, 구현하면서 결정한 사항 몇 가지:

- **ZIP 처리**: SAF Uri의 `/proc/self/fd/N` 트릭은 Android 11 SELinux 정책으로 차단됨 (M7 포함). 대신 **앱 캐시 디렉토리에 한 번 복사 후 일반 File로 ZipFile 오픈**. 첫 진입 1~2초, 재진입은 ms 단위. 캐시는 Android가 디스크 부족 시 자동 정리.
- **디코드**: `BitmapFactory` 표준 경로 + `inJustDecodeBounds`로 헤더만 먼저 → viewport 대비 `inSampleSize` 계산 → 본 디코드. NDK libjpeg-turbo는 성능 측정 후 결정 (PRD §8).
- **ReaderViewModel**: `androidx.lifecycle.ViewModel` 상속하지 **않음**. ViewModelStore 캐시 부작용(stale ZipFile 참조)을 피하려고 자체 `CoroutineScope`를 가지고 Compose 라이프사이클(`remember(session)`)에 1:1 묶임.
- **런처 아이콘**: 어댑티브 아이콘 분리본 대신 **단일 vector drawable**로 통일. 외곽 카드가 이미 둥근 사각형이라 OS 마스크 없이도 자연스럽고, e-ink 디바이스에선 Material You 테마 아이콘이 무의미.

## 라이선스

Apache 2.0 — 모체 [Panely](https://github.com/sejoung/Panely)와 동일 (PRD §11 Q4).
