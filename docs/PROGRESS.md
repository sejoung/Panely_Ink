# Panely Ink — 구현 체크리스트

> 진행 상황을 마일스톤 단위로 추적. PRD `panely_ink_prd.md` §6.1 / §12와 일치.
> 체크하며 구현하기 위한 작업 보드.

| 항목    | 값                            |
|-------|------------------------------|
| 작성일   | 2026-05-05                   |
| 기준    | PRD v0.2 (Meebook M7 단일 타깃)  |
| 현재 단계 | **M1 — 뷰어 셸** 진행 중 (M1.3 완료) |

범례: `[x]` 완료 · `[ ]` 미착수 · `[~]` 부분 구현 (한계 명시)

---

## M0 — 코어 포팅 ✅

- [x] Gradle 골격 (Kotlin 2.0 + Compose Compiler plugin, minSdk 30)
- [x] Manifest + Application + MainActivity
- [x] 디자인 토큰 → Compose Theme (Color/Spacing/Typography, Ripple/Indication 비활성)
- [x] `CbzLoader` (M1.2.5에서 `CbzArchive`로 교체)
- [x] `NaturalOrderComparator` (단위 테스트 4개)
- [x] `FitMode` + `FitCalculator` (단위 테스트 4개)
- [x] `PositionKey` (단위 테스트 3개)
- [x] `ReaderViewModel` 형태 (PageDecoder 인터페이스, ±3 프리로드 윈도)
- [x] 라이브러리 화면: SAF 폴더 추가/제거, depth-1 CBZ/ZIP 목록
- [x] `LibraryRepository` + `LibraryViewModel` (SharedPreferences 영속)
- [x] README/빌드 안내

---

## M0.5 — Meebook refresh API spike

- [ ] Meebook 시스템 인텐트 / 리플렉션 / EPD 시스템 프로퍼티 탐색
- [ ] 발견되면 M2 시스템 모드 연동 범위 확정, 안 되면 정책만으로 출시
- [ ] 결과 문서화 (Design Guidelines §10 갱신)

---

## M1 — 뷰어 셸

### 완료

- [x] **M1.0** — AppleDouble (`._book.cbz`) / 닷파일 라이브러리 필터링
- [x] **M1.1** — 라이브러리 → 리더 진입 + 첫 페이지 표시
  - `ReaderScreen`, `ReaderView` (View+Canvas), `BitmapPageCache`, `CbzBookSession`
  - sealed Screen 분기로 화면 라우팅
  - `produceState` + `DisposableEffect`로 로드/해제
- [x] **M1.2** — 입력
  - 3분할 탭 영역 (좌 30 / 중앙 40 / 우 30) + LTR/RTL 자동 반전 (Guidelines §11)
  - 하드웨어 키 — 볼륨 ↑↓ / Page Up·Down / DPad — `ReaderInput` 디스패처
  - Activity의 `dispatchKeyEvent` 가로채기로 시스템 볼륨 변동 차단
- [x] **M1.2.5** — random-access 아카이브 (1차: 캐시 복사)
  - `CbzArchive`: SAF Uri → 캐시 디렉토리 복사 → `java.util.zip.ZipFile` Central Directory 직참조
  - 첫 진입 1~10초+ (큰 파일은 외장 sdcard read 한계), 재진입 ms 단위
  - `/proc/self/fd/N` 트릭은 SELinux 정책으로 막혀 폐기
  - **M1.2.7에서 Commons Compress로 대체됨** (캐시 복사 없음)
- [x] **M1.2.7** — Commons Compress + SeekableByteChannel + `ignoreLocalFileHeader`
  - SAF Uri → `ParcelFileDescriptor` → `FileInputStream.channel` → `org.apache.commons.compress.archivers.zip.ZipFile`
  - **`setIgnoreLocalFileHeader(true)` 필수** — 이게 빠지면 entry × random seek 누적으로 외장 sdcard에서 13초+. 옵션 적용 시 ~500ms (~26배 단축)
  - 220MB 책 첫 진입: build 477ms + enum 21ms = 502ms (Meebook M7 + 외장 sdcard 측정)
  - 캐시 디렉토리 사용 안 함, 디스크 누적 0
  - 의존성: `commons-compress` ~3MB
- [x] **M1.2.6** — `inSampleSize` 다운스케일
  - `inJustDecodeBounds`로 헤더 → viewport 대비 sample 계산 → 본 디코드
  - 디코드 시간/메모리 1/N²로 감소
- [x] **ReaderViewModel 라이프사이클 수정**
  - `androidx.lifecycle.ViewModel` 상속 제거 → 자체 `CoroutineScope`
  - ViewModelStore 캐시로 인한 stale ZipFile 참조 / 두 번째 진입 크래시 해결
- [x] **런처 아이콘 — 단일 vector drawable**
  - 마스터 SVG (`docs/icon/panely-ink-icon.svg`) v0.4: outline-only, 콘텐츠 1.3× scale, 중심 (512, 490)으로 위쪽 편향 (아래 여백 확보)
  - 어댑티브 분리본(background/foreground/monochrome + `mipmap-anydpi-v26`)은 **삭제**, 단일 `drawable/ic_launcher.xml`로 통일
  - 외곽 카드가 이미 둥근 사각형이라 OS 마스크 없이도 자연스러움
  - Material You 테마 아이콘은 e-ink 디바이스에서 무의미하므로 비포기

### 남음

- [x] **M1.3** — Fit modes 적용 + 최소 메뉴
  - `ReaderMenu` 컴포넌트 (`reader/ReaderMenu.kt`) — 하단 패널, Paper 배경 + 2dp Ink 보더
  - 중앙 탭 → 메뉴 토글, 패널 외부 탭 = 닫기 (Guidelines §6 다이얼로그 규칙)
  - Fit 세그먼트(화면/가로/세로) + 방향 세그먼트(좌→우/우→좌) — 선택 시 fill 반전 (§7)
  - 페이지 점프 슬라이더 — 트랙 4dp Hairline / fill 4dp Ink / 24dp 정사각 핸들, 드래그 중 본문 변경 안 함, 손 떼야 적용 (§6)
  - 메뉴 열린 동안 하드웨어 키는 메뉴 닫기로 흡수 (의도치 않은 페이지 넘김 방지)
  - `ReaderView`는 이미 `FitCalculator` 결과를 사용 중 (M1.1부터). 메뉴에서 `FitMode` 선택 시 `ReaderViewModel.setFitMode` → `ReaderState` → `ReaderView.setFitMode` 단방향 전파
  - `ReadingDirection.VerticalScroll`은 M1.5에서 추가 (현재는 LTR/RTL만 노출)
- [x] **M1.4** — Resume / 위치 기억
  - `core/position/PositionRepository` 인터페이스 + `SharedPreferencesPositionRepository` 구현 (`panely_ink_positions` prefs)
  - `ReaderScreen`이 세션 오픈 후 `load(bookId)` → `ReaderViewModel.initialPage`로 복원 (페이지 수 줄어든 책에 대비해 `coerceIn`)
  - `LaunchedEffect(state.currentPage)` 에서 `save(PositionKey(...))` — `apply()`로 메인스레드 블록 없이 합쳐짐
  - 책 수 1000+ 시 Room으로 교체 (M3)
  - 라이브러리에서 마지막 책 자동 열기는 M3
- [x] **M1.4.5** — 라이브러리 폴더 트리 탐색 (1단계)
  - `LibraryEntry`를 sealed로 분리: `BookEntry` / `FolderEntry` (root 폴더는 `isRoot=true`)
  - `LibraryRepository.listRoots` / `listChildren(parent)` — 한 단계만 SAF 호출
  - `LibraryViewModel.path: List<FolderEntry>` 스택, `enterFolder` / `goUp`
  - `LibraryScreen` — breadcrumb (`라이브러리 ▸ root ▸ folder`) + 폴더/책 행 분기 + back 화살표
  - `BackHandler` — 폴더 안에서 시스템 뒤로 키 = `goUp`
  - 화면 폭(7"/1648×1236) 고려: 좌측 트리 사이드바 대신 한 화면 = 한 디렉토리
  - 자식 카운트, 표지, 진행률은 M3로 미룸 (lazy 캐시 비용 큼)
  - 중첩 ZIP-of-CBZ는 별도 작업으로 정식 구현(2026-05-09 — 아래 진행 로그 참조)
- [ ] **M1.후반** — 입력 보강 — **출시 후 피드백 보고 결정**(2026-05-08). 현재 단일 탭 + 하드웨어 키 + 메뉴 슬라이더로 핵심 사용 가능
  - (보류) 더블탭 / 길게 누르기 — 단일 탭 응답이 ~300ms 지연되는 트레이드오프
  - (보류) 핀치 줌 — pan 정책·잔상 처리 비용. M2 자동 트리밍과 묶이는 게 자연스러움
  - (보류) 키 리바인드 화면 — M5 베타 실기 테스트 후

--- 

## M2 — e-ink 최적화

- [x] **풀리프레시 정책** — N페이지마다 검정 1프레임 → 정상 1프레임. e-ink 컨트롤러가 큰 색차를 풀리프레시 신호로 인식 (SDK 의존 없는 1차 방어선)
  - `ReaderState.fullRefreshGeneration` monotonic 증가 — `LaunchedEffect` 키로 사용
  - `ReaderView.requestFullRefresh()` → `pendingFullRefresh` 플래그 → 다음 onDraw에서 검정 fill 후 `postInvalidateOnAnimation`으로 정상 콘텐츠 1프레임. logcat `PanelyInk.ReaderView`에 `full refresh tick — page=N` 출력
  - **사용자 조정 가능**: 메뉴에 "풀리프레시 주기 (페이지) [1/3/5/10/끔]" 세그먼트 + "지금 풀리프레시" 강제 버튼
  - `setFullRefreshInterval(0)`은 자동 비활성, `triggerFullRefresh()`만 동작
  - `ReaderState.fullRefreshInterval` 기본 5
  - 단위 테스트 8개 (총 28개) — 자동 트리거, 같은 페이지 no-op, custom interval, 0=끔, 즉시 트리거, 카운터 리셋, state 갱신
  - 실제 효과는 디바이스 의존 — M5 실기 테스트에서 default 조정
- [ ] **시스템 refresh 모드 연동** (M0.5 spike 결과 반영, best-effort)
- [x] **자동 여백 트리밍** — 좌/우/상/하 흰 여백 감지 후 `TrimRect`로 fit
  - `core/trim/MarginTrimmer` (JVM 순수 함수, 단위 테스트 7개) — IntArray + width/height 입력. 임계값(밝기 240, 흰 비율 95%, 안전가드 30%) 조정 가능
  - `CbzBookSession` 디코드 후 IO 디스패처에서 1회 계산, `trimCache: ConcurrentHashMap<Int, TrimRect>`에 보관
  - `FitCalculator`는 이미 `trim` 인자 처리 — 추가 변경 없이 통합
  - `ReaderState.trimEnabled` + `setTrimEnabled` (기본 ON), `ReaderView.setTrimEnabled`
  - `ReaderMenu`에 "자동 여백 트리밍 [자동/끔]" 세그먼트 추가
- [ ] **흑백 변환 + Floyd–Steinberg dithering** (1종만)
- [~] **Contrast / Gamma** 슬라이더 + 책별 저장
  - [x] **Contrast** — `core/render/ContrastMatrix` (JVM 순수 함수, 단위 테스트 6개), `ReaderState.contrast`, `ReaderView.setContrast`로 `ColorMatrixColorFilter` 토글, 메뉴 슬라이더(0.5..2.0, 5% 스냅) + "원본" 버튼
  - [x] **책별 저장** — M3 BookSettings 도입 시 통합(`book_settings.contrast` REAL 컬럼, `ReaderScreen`이 변경 시 자동 upsert). 책 재진입 시 마지막 대비 그대로 복원
  - [ ] **Gamma** — ColorMatrix는 선형이라 비선형 gamma는 비트맵 LUT 변환 필요. minSdk 30에선 RuntimeShader 미사용, Bitmap pixel manipulation. 비용 큼 → 다음 단계
- [x] **Invert (블랙/화이트 반전)** — macOS 다크모드 대체
  - `core/render/InvertMatrix` (4×5 ColorMatrix, JVM 단위 테스트 1개)
  - `ReaderState.invertEnabled` + `setInvertEnabled`. 설정 화면에 [끔/켬] 세그먼트
  - `ReaderView.applyColorAdjust` — contrast와 invert 결합(`ColorMatrix.postConcat`로 contrast → invert 순). 둘 다 비활성이면 colorFilter=null로 비용 0
  - 책별 저장은 contrast/gamma와 함께 M3 Room 도입 시 통합

---

## M3 — 라이브러리 보강

- [x] 표지 자동 추출 + 캐시 (자연 정렬 첫 페이지)
  - `library/CoverExtractor` — `CbzArchive.openPage(0)` + `inSampleSize` 다운스케일(한 변 ≤ 400px). archive open 자체가 무거워(수백 ms) 화면에 보이는 책만 lazy 추출
  - `library/CoverCache` — `filesDir/covers/<bookId>.png`(SHA-1 hex) PNG 무손실 저장. `cacheDir`은 OS가 비울 수 있어 사용자 데이터로 두고 `filesDir` 사용
  - `LibraryViewModel.requestCover(book)` — folderCount 패턴(작업 dedup). `state.covers: Map<bookId, ImageBitmap>`. 디스크 hit → 즉시, miss → extract → save → in-memory
  - `BookRow`에 80×112dp 표지 자리. 미추출 시 `PanelyBookIcon` placeholder, 도착 시 `Image(ContentScale.Fit)`. 행에 `LaunchedEffect`로 등장 시 1회 요청
  - 표지 메타(`CoverMeta` Room 테이블)는 다음 단계 — 추출 실패 책 재시도 방지/표지 페이지 변경 옵션에 사용
  - LRU 정리 정책은 별도 항목으로 추후
- [x] 진행률 배지 (% 또는 호선)
  - `core/position/BookProgress(pageIndex, pageCount)` 도메인 (단위 테스트 4개) — `(pageIndex+1)/pageCount` 수식, `isKnown` 가드
  - Room v2→v3 마이그레이션: `position` 테이블에 `page_count INTEGER DEFAULT 0` 컬럼 추가
  - `PositionRepository` 시그니처 갱신: `load → BookProgress?`, `save(bookId, pageIndex, pageCount)`. ReaderScreen이 `viewModel.pageCount` 같이 저장
  - `LibraryViewModel.requestProgress` lazy + dedup. `state.bookProgress: Map<bookId, BookProgress>` (미열람 책은 키 없음)
  - `BookRow` 표지 우하단에 작은 라벨 "N%" — Paper 배경 + 1dp Ink 보더, caption typography
  - 미열람/page_count=0 책은 라벨 미표시 (한 번이라도 책 열면 다음 진입에서 등장)
- [~] 정렬: 이름 / Last opened / Recently added
  - [x] **이름** (NaturalOrderComparator) / **Last opened** (`position.updated_at`) — 1차
  - [ ] **Recently added** — SAF `DocumentFile.lastModified()` IPC 비용 검증 후 후속
  - 폴더는 항상 이름순 고정. 책만 모드에 따라 정렬, 미열람 책은 후순위 + 자연순 tiebreaker
  - `library/SortMode` enum + `library/SortDialog` 다이얼로그(Guidelines §6 규칙)
  - `LibraryViewModel.setSortMode` — entries 재정렬만(디스크 재스캔 X), SharedPreferences 영속
  - `PositionDao.loadUpdatedAtFor(bookIds)` batch 쿼리 — N권에 N쿼리 대신 1쿼리
  - 헤더에 `PanelySortIcon` 버튼 추가 (정렬/메뉴/추가 3개 아이콘)
- [~] 검색 (파일명 / 시리즈명)
  - [x] **현재 폴더 in-memory 검색** — `LibraryViewModel.searchQuery` + `LibraryHeader` 검색 모드(돋보기 → BasicTextField + 자동 포커스). 폴더 + 책 displayName `contains(ignoreCase)` 필터. 검색 결과 빈 상태 메시지
  - 폴더 이동 시 검색어 자동 클리어. ← back 우선순위(검색 → 폴더 위로 → 시스템)
  - [ ] **전체 라이브러리 검색** — 책 인덱스 테이블이 필요해 후속(BookMeta 또는 Room FTS)
  - [ ] **시리즈명** 검색 — ComicInfo.xml 파싱(v1.5)이 전제
- [x] 시리즈 그룹핑 (폴더 = 시리즈 자동 인식)
  - 1차는 M1.4.5 폴더 트리 탐색이 곧 시리즈 단위(폴더 진입 = 시리즈 진입)
  - 보강: 폴더 행에 첫 책 표지를 시리즈 thumbnail로 표시 — `LibraryRepository.firstBookIn`(depth 1) → `LibraryViewModel.requestFolderCover` (folder.uri → bookId 매핑) → 책 cover 추출은 기존 흐름 재사용
  - 같은 비트맵 1개만 메모리(state.covers + state.folderFirstBook 두 단계 lookup)
  - Cover 모드 / Grid 모드에 표시. List 모드는 컴팩트 유지
- [~] Room 도입 (라이브러리/메타/진행률/북마크)
  - [x] **인프라 + Position 마이그레이션** — KSP + Room 2.6.1, `data/db/PanelyDatabase` v1, `PositionEntity`/`PositionDao`, `RoomPositionRepository`(suspend). `PanelyInkApp`에서 1회 SharedPrefs → Room 이전(`PositionMigration`, 멱등). `PositionRepository` 인터페이스를 suspend로 변경, `ReaderScreen`은 `produceState`에서 비동기 로드 후 `SessionState.Ready(session, resumedPage)`로 전달
  - [x] **BookSettings** (책별 fit/direction/trim/contrast/invert/풀리프레시 주기) — `book_settings` 테이블 v2 마이그레이션. `BookSettings` 도메인 + 직렬화 헬퍼(JVM 단위 테스트 8개), `BookSettingsRepository` Room 구현. `ReaderViewModel.initialBookSettings`로 초기 상태 통합, settings 변경 시 `LaunchedEffect`로 자동 upsert
  - [x] **CoverMeta** (표지 캐시 메타) — Room v3→v4 마이그레이션. `cover_meta` 테이블(book_id PK, status, source_page_index, extracted_at). `CoverStatus.OK/FAILED` enum. `RoomCoverMetaRepository`. `LibraryViewModel.requestCover` 흐름 갱신 — FAILED는 영구 skip(깨진 책 재시도 방지), OK + 디스크 hit이면 즉시, 디스크 손상 시 재추출. 사용자 명시 새로고침은 `coverMetaRepo.delete`로 재추출 가능
  - [ ] Bookmark (M4 페이지 북마크용)
- [x] 캐시 디렉토리 LRU 정리 정책
  - `library/CoverPruner.prune` — orphan(메타 없는 디스크 파일) 정리 + LRU(80MB 초과 시 `cover_meta.extracted_at` ASC 순으로 삭제)
  - `library/CoverPruner.clearAll` — 사용자 명시 비우기. 디스크 + 메타(FAILED 포함) 모두 삭제 → 깨진 책 재시도 가능
  - `CoverMetaDao.loadOkOrderedByLru` / `deleteAll` 쿼리
  - `PanelyInkApp.onCreate` 백그라운드에서 자동 prune 1회
  - **앱 설정 → 캐시 그룹**에 "표지 캐시 비우기" 버튼 — 사용자 명시. `LibraryViewModel.clearCoverCache` (in-memory state.covers도 비움)

---

## M4 — 시리즈 연속 · 북마크 · Resume 보강

- [ ] 시리즈 연속 읽기 카드 (Up next / Previous)
- [ ] 권의 마지막 페이지에서 "다음 권" 카드
- [ ] 페이지 북마크 + 즐겨찾기
- [ ] Quick jump (페이지 번호 입력)
- [ ] 같은 시리즈 다음 권에 책별 설정 propagate

---

## M5 — 베타

- [ ] Meebook M7 실기 테스트 (refresh, 잔상, 키 응답)
- [ ] 런처 아이콘 검증 (단일 vector drawable 기준)
  - [ ] M7 런처에서 stroke 끊김·잔상 없는지 (특히 컷 stroke 14)
  - [ ] Recents 카드에서 식별 가능한지
  - [ ] OEM 자체 마스킹이 들어와도 외곽 카드가 자연스러운지
- [ ] 스플래시 화면 (Paper 바탕 + 마스터 아이콘, 애니메이션 없음)
- [ ] 첫 릴리스 (GitHub Releases APK)

---

## v1.5 (이후)

- [ ] **세로 스크롤(웹툰) 모드** — v1.0에서 미뤘음. vertical paginated(viewport 단위 분할 + 흰 줄 컷 보호) 또는 Onyx fast-mode 연동 검토. 표준 안드로이드 fling 스크롤은 e-ink 잔상으로 부적합 — `ReadingDirection.VerticalScroll` enum은 코드에 그대로 두고 메뉴/UI에선 비노출
- [ ] 두 페이지 펼침 모드 (10"+ 디바이스 확장 시)
- [ ] CBR (RAR) 지원 — junrar GPL 격리 또는 native 7z
- [ ] Dithering 추가 알고리즘 — Atkinson / Threshold
- [ ] Sharpness 슬라이더
- [ ] Boox/Onyx SDK 연동 (P2 디바이스 확장)
- [ ] OPDS 1.2 / 2.0 카탈로그 클라이언트
- [ ] Komga / Kavita 네이티브 연동 (진행률 양방향 동기화)
- [ ] WebDAV / SMB 직접 마운트
- [ ] ComicInfo.xml 파싱 (시리즈/볼륨/저자/요약)
- [ ] 컬렉션 / 태그

---

## 비기능 요구사항 (PRD §9) 측정

> Meebook M7 실기 테스트 시 채워넣음.

- [ ] 콜드 스타트: 라이브러리 첫 화면 ≤ 1.5초
- [ ] 페이지 전환 지연 ≤ 100ms (디코드 완료 페이지 기준)
- [ ] 메모리 상한: 페이지 캐시 ≤ 100MB
- [ ] 배터리: 1시간 연속 읽기 ≤ 8% (M7 2300mAh 기준)
- [ ] 오프라인 동작 검증
- [ ] 샌드박스/SAF 외 파일시스템 접근 없음 검증

---

## 알려진 한계 / 임시 처방

| 항목              | 한계                                                                                  | 해결 시점                                                                |
|-----------------|-------------------------------------------------------------------------------------|----------------------------------------------------------------------|
| ~~캐시 복사~~       | ~~첫 진입 1~2초~~                                                                       | **M1.2.7에서 해결** — Commons Compress로 random-access                    |
| 파일 변경 미감지       | (해당 없음 — 캐시 안 쓰니까)                                                                  | —                                                                    |
| ZipFile API     | Java 표준만 사용                                                                         | Need-based: NDK libjpeg-turbo / libwebp는 디코드 측에서 (PRD §8)            |
| 키 코드 매핑         | 표준 KeyCode만 매핑됨                                                                     | M5 — 사용자 리바인드 화면                                                     |
| 색 변환            | ARGB8888 고정                                                                         | RGB565는 PRD §11 Q5에 명시, 측정 후 결정                                      |
| 런처 아이콘          | 단일 vector — OS 어댑티브 마스크/parallax 미적용, Material You 테마 아이콘 비활성                       | 디자인 의도(e-ink 단순성). 변경 시 monochrome 분리본만 추가 가능                        |
| ReaderViewModel | `androidx.lifecycle.ViewModel` 미상속 — 프로세스 사망 시 in-memory 상태(fitMode/direction)는 미복원 | 페이지 위치는 M1.4에서 `PositionRepository`로 보강. fit/direction은 책별 설정과 함께 M3 |

---

## 진행 로그

- **2026-05-05** — M0 골격 + 디자인 토큰 + 코어 도메인 완료
- **2026-05-05** — M1.0~M1.2 (뷰어 셸 + 입력) 완료
- **2026-05-05** — M1.2.5/M1.2.6 (성능 보강) — random-access + downscale
- **2026-05-05** — ReaderViewModel 라이프사이클 수정 (재진입 크래시 해결)
- **2026-05-05** — 런처 아이콘 v0.4: outline-only + 1.3× scale + 시각 균형 조정
- **2026-05-05** — 어댑티브 분리본 폐기, 단일 vector drawable로 통일
- **2026-05-05** — 캐시 복사 전략 결정 (`/proc/self/fd/N`은 SELinux로 차단)
- **2026-05-05** — M1.2.7: Commons Compress + SeekableByteChannel로 교체. 220MB 첫 진입 11초 → ~500ms (`setIgnoreLocalFileHeader(true)` 필수)
- **2026-05-08** — M1.3: 최소 메뉴 (`ReaderMenu`) — fit/방향 세그먼트 + 페이지 점프 슬라이더. Guidelines §6 슬라이더 규칙(드래그 중 본문 변경 X) 준수. 메뉴 열린 동안 하드웨어 키는 메뉴 닫기로 흡수
- **2026-05-08** — `ReaderViewModel` JVM 단위 테스트 20개 추가 (페이지 이동/clamp/preload window/cancel/close)
- **2026-05-08** — 본문 모드에서 시스템 status/navigation bar 숨김(`WindowInsetsControllerCompat`) — Guidelines §12 "크롬 0dp"
- **2026-05-08** — `PanelyIconButton` 추가 + 라이브러리 헤더 슬림화 (고정 80dp 제거, 정사각 아이콘 버튼이 짜부 없이 정렬)
- **2026-05-08** — M1.4.5: 라이브러리 폴더 트리 탐색 1단계. `LibraryEntry` sealed(Book/Folder), `LibraryRepository.listChildren`, `path` 스택, breadcrumb UI, `BackHandler`로 `goUp`. 카운트/표지/ZIP-of-CBZ는 다음 단계
- **2026-05-08** — M1.4: Resume / 위치 기억. `PositionRepository`(SharedPrefs 구현), 세션 오픈 시 `initialPage` 복원, 페이지 변경마다 `apply()`로 저장
- **2026-05-08** — 라이브러리 single-root 자동 진입. root 1개면 첫 화면 한 프레임도 노출하지 않고 그 안으로 한 단계 진입. `pendingAutoDescend` 플래그로 init/add/remove에서만 트리거하고 `goUp`(사용자 의도)에선 끔. 폴더 안에서도 +/관리 버튼 노출
- **2026-05-08** — 라이브러리 last-visited path 영속화 + 복원 (JSON in SharedPrefs). `setPath` 단일 진입점에서 state 갱신과 영속화를 묶음. root가 사라진/path가 root 밖 인 경우 무시
- **2026-05-08** — 폴더 자식 카운트 lazy + in-memory 캐시 (1차). `LibraryRepository.countBooks`(재귀, depth ≤ 3), `LibraryViewModel.requestFolderCount` 작업 dedup, `FolderRow`에 "N권" / "비어있음" 표시. 디스크 캐시·invalidation은 M3 Room 도입 시 통합
- **2026-05-08** — 세로 스크롤(웹툰) 모드를 v1.5로 미룸. e-ink fling 스크롤 잔상/갱신 속도 한계. PRD §6.1·§6.2와 PROGRESS v1.5 갱신. `ReadingDirection.VerticalScroll` enum은 코드에 그대로 두되 `ReaderMenu`에서 비노출(이미 그 상태)
- **2026-05-08** — M1.후반 입력 보강(더블탭/핀치 줌/키 리바인드)을 출시 후 피드백 의존으로 보류. 단일 탭+하드웨어 키+메뉴 슬라이더로 v1.0 핵심 사용성 확보. M2 e-ink 최적화로 진행
- **2026-05-08** — M2 풀리프레시 정책. `ReaderViewModel`에 페이지 카운터 + `fullRefreshGeneration` state, `ReaderView`에 검정 1프레임 + `postInvalidateOnAnimation` trick. 기본 N=5, `setFullRefreshInterval`로 조정 가능. 단위 테스트 4개 추가
- **2026-05-08** — M2 자동 여백 트리밍. `MarginTrimmer`(JVM 순수 함수, 단위 테스트 7개), `CbzBookSession.trimCache`, `ReaderState.trimEnabled` + 메뉴 토글. 디코드 후 1회 계산되어 onDraw에서 즉시 사용. 안전가드(결과 < 30%)로 잘못 감지 방지
- **2026-05-08** — 풀리프레시 정책 사용자 가시화. 메뉴에 주기 세그먼트(1/3/5/10/끔) + "지금 풀리프레시" 버튼. `triggerFullRefresh()`/`setFullRefreshInterval(0)` API. `ReaderView`에 logcat 한 줄. 단위 테스트 4개 추가
- **2026-05-08** — 풀리프레시 검정 hold 120ms. 한 vsync(16ms)는 LCD에서 거의 안 보여 사용자가 동작 여부를 의심. `postInvalidateOnAnimation` → `postInvalidateDelayed(120)`로 검정 유지 시간 명시. e-ink 픽셀 변환(~150ms) 시간에도 부합
- **2026-05-08** — 풀리프레시 시퀀스 = 검정→흰→검정 (80ms × 3 = 240ms). Meebook M7 실기에서 단일 검정 프레임은 풀리프레시 waveform을 트리거하지 못했음 — 컨트롤러가 부분 갱신으로 흡수. 픽셀 다수가 두 번 반전되는 시퀀스로 해결. **Meebook M7 실기 깜빡임 확인됨**
- **2026-05-08** — M2 Contrast 1단계(세션 한정). `ContrastMatrix` 4×5 ColorMatrix 빌더, `ColorMatrixColorFilter` 적용, 슬라이더 0.5..2.0(5% 스냅) + "원본" 버튼. 옅은 스캔본 만화 가독성용. Gamma·책별 저장은 보류
- **2026-05-08** — 리더 메뉴를 빠른 메뉴 + 설정 화면으로 분리. 메뉴 패널 = 페이지 점프 + 라이브러리로 + "설정 ⋯"만(본문 가독성 ↑). 책당 1회 설정(맞춤/방향/트림/대비/풀리프레시)은 풀스크린 `ReaderSettingsScreen`. 공통 컨트롤은 `ReaderControls.kt`로 추출. BackHandler 우선순위(settings → menu → 라이브러리). 2차 "자주 쓰는 메뉴 핀" 옵션은 출시 후 사용 패턴 보고 SharedPrefs로 결정
- **2026-05-08** — M2 Invert. `InvertMatrix` 4×5 ColorMatrix, contrast와 결합(postConcat). 설정 화면에 [끔/켬] 세그먼트. 단위 테스트 3개 추가
- **2026-05-08** — 설정 화면 3그룹 분리. `GroupHeader`(list+Ink) 추가, 섹션 라벨(caption+Mute)과 시각 hierarchy. 그룹: [페이지 레이아웃] / [화질] / [디스플레이]. 그룹 사이 Hairline divider + space4 spacing
- **2026-05-08** — M3 Room 인프라 도입(KSP 2.0.20-1.0.25 + Room 2.6.1). `PanelyDatabase` v1 + `PositionEntity`/`PositionDao` + `RoomPositionRepository`. `PositionRepository` 인터페이스 suspend로 변경, `ReaderScreen.produceState`에서 비동기 로드 → `SessionState.Ready(session, resumedPage)`. SharedPreferences → Room 1회 마이그레이션(`PositionMigration`, 멱등 플래그)은 `PanelyInkApp.onCreate`에서 백그라운드 실행. `SharedPreferencesPositionRepository` 클래스 제거
- **2026-05-08** — M3 BookSettings 책별 저장. Room v1→v2 마이그레이션(`book_settings` 테이블 신규). `BookSettings` 도메인 + FitMode/Direction 직렬화 헬퍼(테스트 8개), `RoomBookSettingsRepository`. `ReaderViewModel`은 `initialBookSettings: BookSettings`로 초기 상태 통합(기존 initialDirection/FitMode/TrimEnabled 인자 제거). `ReaderScreen`에서 settings 6필드 변경 시 자동 upsert
- **2026-05-08** — M3 표지 자동 추출 + 디스크 캐시 1단계. `CoverExtractor`(첫 페이지 inSampleSize 다운스케일 ≤ 400px), `CoverCache`(filesDir/covers PNG 저장), `LibraryViewModel.requestCover` lazy + dedup, `BookRow` 80×112dp 표지 자리. 추출 실패 메타/LRU 정리는 후속
- **2026-05-08** — M3 진행률 배지. Room v2→v3(`position.page_count` 컬럼), `BookProgress` 도메인 + 테스트 4개. `PositionRepository`가 `BookProgress?`/`save(bookId, pageIndex, pageCount)`로 변경. ReaderScreen이 페이지 변경마다 viewModel.pageCount 같이 저장. 라이브러리 표지 우하단 "N%" 라벨(미열람 책 미표시)
- **2026-05-08** — M3 정렬 1차. `SortMode`(Name/LastOpened) + `SortDialog`. `PositionDao.loadUpdatedAtFor` batch 쿼리, `LibraryViewModel.applySort`(폴더 이름순 고정 + 책 모드별). 헤더에 `PanelySortIcon` 버튼. SharedPreferences 영속
- **2026-05-08** — M3 검색 1차(현재 폴더 in-memory). `searchQuery` state + 헤더 검색 모드 토글(돋보기 → 입력 필드 + X). `LaunchedEffect`로 자동 포커스. 폴더 이동/← back 시 자동 클리어. `PanelySearchIcon`/`PanelyCloseIcon` 추가. 전체 라이브러리/시리즈명 검색은 인덱스 테이블 필요해 후속
- **2026-05-08** — M3 표시 모드 3종(List/Cover/Grid). `ViewMode` enum + 영속. `LibraryOptionsDialog`로 정렬+표시 통합(SortDialog 대체). List는 표지 추출 안 함(archive open 비용 0). Grid는 `LazyVerticalGrid` 3열 + 4:5.6 표지 슬롯 + 진행률 코너 라벨. 폴더 셀은 큰 폴더 아이콘 + 권수 라벨
- **2026-05-08** — 버그픽스: 로딩 중 같은 폴더 행 더블 탭 시 path stack에 중복 push되어 ← back 여러 번 눌러야 탈출하던 문제. `enterFolder`에서 마지막 path documentUri 비교로 중복 push 차단
- **2026-05-08** — 설정 전역/책별 분리. `AppPreferences`(전역: 풀리프레시 주기, 흑백 반전) + `SharedPrefsAppPreferencesRepository`. `BookSettings`에서 두 필드 제거(entity 컬럼은 orphan 유지). `AppSettingsScreen` 신규 — 라이브러리 메뉴(햄버거 → "앱 설정") 진입. 책 메뉴는 책별 옵션(맞춤/방향/트림/대비/흑백 반전 토글/지금 풀리프레시 버튼)에 집중. 흑백 반전은 어디서 토글해도 전역 저장
- **2026-05-08** — 앱 설정 진입 단순화. 헤더 햄버거 메뉴 → 톱니바퀴(`PanelySettingsIcon`)로 교체, 한 번 클릭에 앱 설정 화면 직접 진입. `ManageRootsDialog` 삭제 — 폴더 관리는 `AppSettingsScreen` 안의 "폴더" 섹션으로 흡수. 단계 깊이 4→1 단축
- **2026-05-08** — 헤더 폴더 추가(+) 아이콘 제거 → 4→3 아이콘(검색/정렬/설정). 폴더 추가 액션은 `AppSettingsScreen` 폴더 섹션 + `LibraryEmptyState` 첫 사용자 가이드 버튼으로 흡수. SAF picker launcher는 `LibraryScreen`에서 한 번 정의해 양쪽에 공유
- **2026-05-08** — SAF picker 취소 안내 추가. 시스템 polder picker는 우리 앱 외부라 X/취소 UI를 추가할 수 없음 — 빈 상태와 앱 설정 폴더 섹션에 caption으로 "시스템 뒤로 키로 취소" 안내. 옛 "+ 버튼" 안내 문구는 갱신
- **2026-05-08** — SAF picker 안내 보강. 키 없는 Meebook M7에서 picker 우상단 ←를 시스템 뒤로 키로 오인 → 폴더 위 네비게이션이라 못 빠져나옴. 안내 텍스트 구체화("화면 하단 ◁ / picker 우상단 ⋮ / 우상단 ←는 폴더 한 단계 위"). picker 호출 직전 `WindowInsetsControllerCompat.show(systemBars())` 강제로 가상 navigation bar 가시성 보장
- **2026-05-08** — 라이브러리 화면 진입 시 `systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` + `show(systemBars())` 명시. ReaderScreen에서 hide 후 라이브러리 복귀해도 시스템 바 표시 보장. 안내 텍스트에 "임의 폴더 선택 후 앱 설정에서 제거" 우회 추가 — Meebook M7처럼 OEM이 navigation bar를 hide한 디바이스 대응
- **2026-05-08** — SAF picker 깊이 안내 + 시작 위치 hint. 사용자가 깊이 들어가면 ←를 그만큼 눌러야 빠져나오는 picker UI 한계 — 안내 텍스트에 "책 폴더 보이는 시점 즉시 선택" 가이드 추가. `pickFolder.launch(state.roots.lastOrNull())`로 마지막 추가 root를 picker 시작 위치 hint 전달, 깊이 ↓
- **2026-05-08** — M3 CoverMeta 도입. Room v3→v4(`cover_meta` 테이블), `CoverStatus.OK/FAILED` enum, `RoomCoverMetaRepository`. `LibraryViewModel.requestCover`가 메타 확인 후 FAILED는 영구 skip — 깨진 책에 archive open 매번 시도하던 부담 제거. 디스크 파일 손상은 재추출. 사용자 새로고침 UI는 후속(현재는 `coverMetaRepo.delete`로 재추출 가능 API만)
- **2026-05-08** — M3 표지 캐시 LRU 정리. `CoverPruner.prune` — covers 디렉토리 사이즈 80MB 초과 시 가장 오래된 메타+파일부터 삭제. `loadOkOrderedByLru` 쿼리. `PanelyInkApp.onCreate`에서 백그라운드 1회 호출. orphan 파일은 1차 미처리
- **2026-05-08** — 캐시 정리 보강. `CoverPruner.prune`에 orphan(메타 없는 디스크 파일) 정리 통합 + `clearAll`(전체 비우기 — FAILED 메타 포함) 신규. `LibraryViewModel.clearCoverCache` + 앱 설정 화면 캐시 그룹에 "표지 캐시 비우기" 버튼. 호환성 고려는 미배포라 X
- **2026-05-09** — Contrast 책별 저장은 M3 BookSettings 도입 시 이미 통합되어 동작 중. PROGRESS.md M2 메모만 갱신(코드 변경 없음)
- **2026-05-09** — 전체 초기화 기능. `data/AppDataResetter` — Room clearAllTables + SharedPreferences 3종 + SAF 권한 release(prefs 비우기 전 root 읽기) + covers 디렉토리 삭제. `LibraryViewModel.resetAllData` (진행 중 작업 cancel + state 초기화). `ConfirmResetDialog`(취소 primary, 초기화 secondary). 앱 설정 화면 끝에 "초기화" 그룹 + 버튼. 디버깅/기기 양도 시 신규 사용자 상태 복귀
- **2026-05-09** — 시리즈 그룹핑 보강. 폴더 행에 첫 책 표지를 thumbnail로(폴더=시리즈). `LibraryRepository.firstBookIn`(depth 1), `LibraryViewModel.requestFolderCover` lazy + dedup, `state.folderFirstBook`(folder.uri→bookId) 매핑. 책 cover 흐름 재사용으로 비트맵 1개만 메모리. Cover/Grid 모드에 적용, List는 컴팩트 유지
- **2026-05-09** — 첫 진입 성능 보강. (1) **표지 추출 동시성 제한** `Semaphore(2)` — archive open 30개 동시 시도 → 2개씩 처리, 화면 상단부터 빠르게 채워짐. 메타/디스크 hit 검사는 semaphore 밖이라 캐시된 행은 즉시 표시. (2) **Position progress batch load** — `PositionDao.loadAllByIds`/`Repository.loadProgressMap`. 폴더 진입 시 `prefetchBookData`로 책 진행률 1쿼리에 미리 채움 (N→1). `requestProgress` LaunchedEffect는 캐시 hit이라 추가 query 없음
- **2026-05-09** — `LibraryRepository`를 `ContentResolver` 직접 쿼리로 재작성 — N+1 IPC 제거. `androidx.documentfile.DocumentFile.listFiles()` + 각 child의 `length()/type()` 호출은 자식마다 별도 ContentResolver query. 폴더에 책 50권이면 51번 IPC, `countBooks`는 재귀로 폭주. `DocumentsContract.buildChildDocumentsUriUsingTree` + 한 번의 `query`로 모든 자식의 모든 컬럼(name/mime/size)을 받아 폴더당 IPC 1번. `resolveFolder`/`descendTo` BFS 제거 — `getDocumentId`로 직접 URI 빌드
- **2026-05-09** — 추가 성능 보강. (1) **listChildren in-memory 캐시** — 폴더 트리 위/아래 이동에서 같은 폴더 재진입 시 SAF query 0번. 외장 SD에서 큰 효과. `countBooks`를 `listChildren` 기반으로 재작성해 자식 폴더도 캐시 hit. `refresh`/`resetAllData`에서 invalidate. (2) **CoverMeta batch 캐시** — `state.coverStatus`에 `prefetchBookData`가 한 번에 채움. 책 N권 화면이면 메타 query N→0(이미 batch에 있음). (3) **Job cleanup finally** — `coverJobs`/`countingJobs`/`progressJobs`/`folderCoverJobs` 모두 `finally`에서 dedup map 정리. cancel/예외 시에도 다음 요청에서 재시도 가능
- **2026-05-09** — 표지 state debounce flush 100ms. 추출 1장씩 도착마다 `_state.value.copy(covers = ...)` → 30번 recompose stutter 문제. `pendingCovers`/`pendingCoverStatus` 버퍼 + `delay(100)` flush 코루틴으로 100ms 동안 도착한 표지를 모아 한 번에 갱신. recompose 30번 → 1~2번. `clearCoverCache`/`resetAllData`에서도 버퍼 + flushJob cleanup
- **2026-05-09** — 뷰어 좌상단 ← 라이브러리 복귀 아이콘 상시 노출. Guidelines §12 "크롬 0dp" 부분 보완 — 시스템 navigation bar가 노출되지 않는 e-ink 디바이스(Meebook M7)에선 메뉴(중앙 탭 → 하단 패널)를 거치지 않고 한 번에 라이브러리로. 메뉴/설정 열림 시엔 그 화면이 자체 back을 가지므로 숨김. 라이브러리/설정 화면과 일관된 ← 위치
- **2026-05-09** — 뷰어 좌상단 상시 ← 제거 + 메뉴에 상단 헤더 추가. 본문 가독성을 위해 Guidelines §12 "크롬 0dp" 본문 모드 복원. 메뉴 호출(중앙 탭) 시 상단 헤더(← 라이브러리 + 책 제목 + "현재/전체" 페이지 인디케이터)와 하단 패널이 함께 등장 → 라이브러리/설정 화면과 일관된 ← 위치 + 사용자가 어떤 책 어디인지 한눈에. `entry.displayName`을 ReaderContent까지 prop drill로 전달
- **2026-05-09** — PRD §6.1 **중첩 아카이브(ZIP-of-CBZ) 정식 구현**. (1) `CbzArchive`에 nested entries 분류(이미지/.cbz/.zip), `isSeriesArchive` 플래그, `openNestedEntry` InputStream, File 기반 `open(File)`. (2) `BookEntry.nestedEntryName` + `FolderEntry.nestedBooks` 필드로 sealed 도메인 확장. (3) `NestedZipExtractor` — `cacheDir/nested/<parentHash>__<entryHash>.cbz`로 1회 추출 + 캐시. (4) `LibraryRepository.inspectZipForSeries` — ZIP 안 nested .cbz 2+이면 가상 `FolderEntry` 반환. `listChildren`은 가상 폴더면 SAF query 대신 `nestedBooks` 그대로. (5) `LibraryViewModel.openBook` — 책 클릭 시 검사 → 시리즈면 가상 폴더 path push, 일반이면 onBook. (6) `CbzBookSession.open`이 nested entry면 추출 후 file 기반 open. bookId 체계: nested는 `parentUri#entryName`으로 unique → 진행률/BookSettings/CoverMeta 모두 nested 책별 저장. (7) `clearCoverCache`에서 nested cacheDir도 정리
- **2026-05-09** — ZIP-of-CBZ 핫픽스 4종. (1) **LazyColumn key 중복 크래시** — nested 책들이 부모 ZIP의 `documentUri`를 공유해 `IllegalArgumentException: Key was already used`. `LibraryEntry.stableKey`(BookEntry는 `bookIdSource`, FolderEntry는 documentUri) 도입 후 LazyColumn/LazyVerticalGrid에 적용. (2) **bookId source 통일** — `BookEntry.bookIdSource` (nested: `uri#entry`, 일반: `uri.toString()`) — `CbzBookSession.open`과 일치. ViewModel 5곳(requestCover/Progress/FolderCover/prefetchBookData/applySort)을 통일 → 표지/진행률 매핑이 reader와 같은 키. (3) **nested 표지 추출 경로** — `CoverExtractor.extract(File)` overload, `requestCover`에서 nested 책은 `NestedZipExtractor.cacheFile`이 존재할 때만 추출(reader에서 한 번 열린 적 있어야 함). 첫 라이브러리 진입에서 모든 nested cbz를 추출하는 비용을 회피. (4) **nested 책 FAILED 영구 저장 차단** — 캐시 없어 image=null인 케이스는 `coverMetaRepo.save(FAILED)` 안 함 → 사용자가 reader에서 책을 열어 캐시가 생기면 다음 라이브러리 진입에서 표지 등장
