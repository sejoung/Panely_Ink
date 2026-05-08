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
  - 중첩 ZIP-of-CBZ(PRD §6.1)는 다음 단계
- [ ] **M1.후반** — 입력 보강 — **출시 후 피드백 보고 결정**(2026-05-08). 현재 단일 탭 + 하드웨어 키 + 메뉴 슬라이더로 핵심 사용 가능
  - (보류) 더블탭 / 길게 누르기 — 단일 탭 응답이 ~300ms 지연되는 트레이드오프
  - (보류) 핀치 줌 — pan 정책·잔상 처리 비용. M2 자동 트리밍과 묶이는 게 자연스러움
  - (보류) 키 리바인드 화면 — M5 베타 실기 테스트 후

--- 

## M2 — e-ink 최적화

- [x] **풀리프레시 정책** — N페이지마다 검정 1프레임 → 정상 1프레임. e-ink 컨트롤러가 큰 색차를 풀리프레시 신호로 인식 (SDK 의존 없는 1차 방어선)
  - `ReaderViewModel.fullRefreshInterval`(기본 5) + `pagesSinceFullRefresh` 카운터
  - `ReaderState.fullRefreshGeneration` monotonic 증가 — `LaunchedEffect` 키로 사용
  - `ReaderView.requestFullRefresh()` → `pendingFullRefresh` 플래그 → 다음 onDraw에서 검정 fill 후 `postInvalidateOnAnimation`으로 정상 콘텐츠 1프레임
  - 단위 테스트 4개 추가 (총 24개)
  - 실제 효과는 디바이스 의존 — M5 실기 테스트에서 N 조정
- [ ] **시스템 refresh 모드 연동** (M0.5 spike 결과 반영, best-effort)
- [ ] **자동 여백 트리밍** — 좌/우/상/하 흰 여백 감지 후 `TrimRect`로 fit
- [ ] **흑백 변환 + Floyd–Steinberg dithering** (1종만)
- [ ] **Contrast / Gamma** 슬라이더 + 책별 저장
- [ ] **Invert (블랙/화이트 반전)** — macOS 다크모드 대체

---

## M3 — 라이브러리 보강

- [ ] 표지 자동 추출 + 캐시 (자연 정렬 첫 페이지)
- [ ] 진행률 배지 (% 또는 호선)
- [ ] 정렬: 이름 / Last opened / Recently added
- [ ] 검색 (파일명 / 시리즈명)
- [ ] 시리즈 그룹핑 (폴더 = 시리즈 자동 인식)
- [ ] Room 도입 (라이브러리/메타/진행률/북마크)
- [ ] 캐시 디렉토리 LRU 정리 정책 (현재는 Android 자동 정리에만 의존)

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
