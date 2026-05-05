# Panely Ink — 구현 체크리스트

> 진행 상황을 마일스톤 단위로 추적. PRD `panely_ink_prd.md` §6.1 / §12와 일치.
> 체크하며 구현하기 위한 작업 보드.

| 항목 | 값 |
|---|---|
| 작성일 | 2026-05-05 |
| 기준 | PRD v0.2 (Meebook M7 단일 타깃) |
| 현재 단계 | **M1 — 뷰어 셸** 진행 중 |

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
- [x] **M1.2.5** — random-access 아카이브
  - `CbzArchive`: SAF Uri → 캐시 디렉토리 복사 → `ZipFile` Central Directory 직참조
  - 첫 진입 1~2초 (캐시 miss), 재진입 ms 단위 (캐시 hit)
  - `/proc/self/fd/N` 트릭은 SELinux 정책으로 막혀 폐기
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
- [ ] **M1.3** — Fit modes 적용 + 최소 메뉴
  - 중앙 탭 → 메뉴 표시 (fit 토글 / 방향 토글 / 페이지 점프)
  - `FitCalculator` 결과를 `ReaderView`가 사용 (현재 FitScreen 고정)
  - 슬라이더 / 토글 — Guidelines §6 컴포넌트 규칙 준수
- [ ] **M1.4** — Resume / 위치 기억
  - `PositionKey` 영속화 (SharedPreferences 또는 Room)
  - 책 재진입 시 마지막 페이지 자동 복원
  - 라이브러리에서 마지막 책 자동 열기는 M3
- [ ] **M1.5** — 세로 스크롤(웹툰) 모드
  - `ReadingDirection.VerticalScroll` 활성
  - 페이지 사이 8dp Paper 간격 (Design Guidelines §12)
  - 멈춤 감지 후 풀리프레시 1회 (Guidelines §10)
- [ ] **M1.후반** — 입력 보강
  - 더블탭 / 길게 누르기 (페이지 점프 / 챕터 점프) — PRD §6.1
  - 핀치 줌 (단계적, 1× ↔ 2× 더블탭) — PRD §6.1
  - 사용자 키 학습/리바인드 화면 (선택, M3로 미룰 수도)

---

## M2 — e-ink 최적화

- [ ] **풀리프레시 정책** — N페이지마다 강제 `View.invalidate` (기본 N=5)
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
- [ ] Apache Commons Compress 도입 검토 (현재 캐시 복사 1~2초가 거슬리면)

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

| 항목 | 한계 | 해결 시점 |
|---|---|---|
| 캐시 복사 | 첫 진입 1~2초 (큰 CBZ 기준) | v1.5 — Commons Compress + SeekableByteChannel로 교체 검토 |
| 파일 변경 미감지 | 같은 SAF 파일이 갱신돼도 캐시는 옛 버전 | v1.0 후반 — mtime/size 비교로 invalidate |
| ZipFile API | Java 표준만 사용 | Need-based: NDK libjpeg-turbo / libwebp는 디코드 측에서 (PRD §8) |
| 키 코드 매핑 | 표준 KeyCode만 매핑됨 | M5 — 사용자 리바인드 화면 |
| 색 변환 | ARGB8888 고정 | RGB565는 PRD §11 Q5에 명시, 측정 후 결정 |
| 런처 아이콘 | 단일 vector — OS 어댑티브 마스크/parallax 미적용, Material You 테마 아이콘 비활성 | 디자인 의도(e-ink 단순성). 변경 시 monochrome 분리본만 추가 가능 |
| ReaderViewModel | `androidx.lifecycle.ViewModel` 미상속 — 프로세스 사망 시 상태 미복원 | M1.4 Resume에서 `PositionKey` 영속화로 보강 |

---

## 진행 로그

- **2026-05-05** — M0 골격 + 디자인 토큰 + 코어 도메인 완료
- **2026-05-05** — M1.0~M1.2 (뷰어 셸 + 입력) 완료
- **2026-05-05** — M1.2.5/M1.2.6 (성능 보강) — random-access + downscale
- **2026-05-05** — ReaderViewModel 라이프사이클 수정 (재진입 크래시 해결)
- **2026-05-05** — 런처 아이콘 v0.4: outline-only + 1.3× scale + 시각 균형 조정
- **2026-05-05** — 어댑티브 분리본 폐기, 단일 vector drawable로 통일
- **2026-05-05** — 캐시 복사 전략 결정 (`/proc/self/fd/N`은 SELinux로 차단)
