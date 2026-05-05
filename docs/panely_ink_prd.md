# Panely Ink — PRD

> Android e-ink 기기를 위한 Panely 포팅 프로젝트

| 항목 | 내용                                                         |
|---|------------------------------------------------------------|
| 프로젝트명 | **Panely Ink**                                             |
| 모체 | [Panely](https://github.com/sejoung/Panely) (macOS)        |
| 타깃 플랫폼 | Android 기반 e-ink 리더 (Boox, Onyx, Meebook, Kindle Scribe 등) |
| 작성일 | 2026-05-05                                                 |
| 상태 | Draft v0.1                                                 |

---

## 1. 비전

> **방해 없는 만화 리더, 종이 같은 화면에서.**

Panely의 핵심 철학(미니멀, 키보드/입력 우선, 자동 숨김 크롬, 빠른 코어)을
e-ink의 물리적 제약 위에서 재해석한다. macOS Panely의 사용 경험을 그대로
가져오는 것이 아니라, e-ink 사용자가 macOS Panely를 봤을 때 "익숙한 영혼이
종이 화면에 잘 녹아들어간 것" 같다는 인상을 주는 것이 목표다.

## 2. 문제 정의

안드로이드 e-ink 기기에서 만화를 읽는 현재 옵션들은 다음 중 하나다:

1. **범용 리더** (Perfect Viewer, Tachiyomi, Komikku) — 만화 기능은 풍부하나
   e-ink 특성(refresh mode, ghosting, dithering)에 둔감
2. **e-ink 전용 OEM 앱** (Boox NeoReader 등) — e-ink 최적화는 좋으나
   라이브러리/시리즈 관리, 웹툰 모드, 자동 트리밍 등 만화에 특화된
   UX가 부족
3. **PDF 변환 후 PDF 리더** — CBZ 직접 읽기 불가, 메타데이터 손실,
   재인코딩 비용

**Panely Ink가 채울 자리:** e-ink 네이티브 최적화 + 만화 전용 UX
+ 자체 호스팅 라이브러리(Komga/Kavita) 친화 + 미니멀 크롬.

## 3. 타깃 사용자

- **P1 — Boox/Onyx 사용자(만화 중심)**: 해상도 1404×1872 이상, 7~13인치,
  하드웨어 페이지 버튼 또는 큰 화면 탭 영역 사용. 자체 NAS에 CBZ 컬렉션
  보유. Komga/Kavita 사용 가능성 높음.
- **P2 — Kindle Scribe / 안드로이드 사이드로드 사용자**: 10인치급, 키 없음,
  스타일러스 보유. 한 손 탭 위주.
- **P3 — 안드로이드 일반 태블릿에서 e-ink 모드를 흉내내는 사용자**:
  부수 타깃. UI는 e-ink 가정에 맞춤.

**비타깃:** 모바일 폰의 일반 LCD 사용자 (Tachiyomi 등이 이미 잘 함).

## 4. 목표 / 비목표

### 목표 (v1.0)
- macOS Panely의 핵심 읽기 경험(레이아웃·맞춤·방향·시리즈 연속·위치 기억)을
  안드로이드 e-ink에서 동등하거나 더 자연스럽게 재현
- Onyx SDK refresh mode와 풀리프레시 정책으로 ghosting 체감 최소화
- CBZ / ZIP / 폴더 + 중첩 아카이브 추출 (macOS와 동일 동작)
- 하드웨어 키 + 탭 영역 우선 입력 모델
- 자동 여백 트리밍과 dithering으로 작은 화면(6~7")에서도 본문 가독성 확보
- 샌드박스/SAF 환경에서 사용자 선택 폴더의 안정적 영속 접근

### 비목표 (v1.0에서 제외)
- 클라우드 동기화 자체 인프라 구축 (Komga/Kavita에 위임)
- 만화 다운로드/스크래퍼 (Tachiyomi 영역, 라이선스 회피)
- DRM 콘텐츠 지원
- 컬러 e-ink(Kaleido) 컬러 보정 — v2에서 검토
- iOS/iPadOS 포팅 — 별개 프로젝트

## 5. 핵심 사용자 여정

### J1. "어제 읽던 데서 이어 읽기"
1. 앱 실행 → 마지막 읽은 책의 마지막 페이지 자동 복원 (Resume)
2. 하드웨어 페이지 키 또는 우측 탭으로 다음 페이지
3. 권의 마지막 페이지에서 "다음 권" 카드 → 한 번 더 다음 키로 권 이동

### J2. "새 컬렉션 추가하고 첫 책 펴기"
1. 라이브러리 화면 → SAF로 폴더 또는 OPDS 카탈로그 추가
2. 자동 스캔(시리즈/볼륨/표지/메타데이터) 백그라운드 진행, depth-1은 즉시
   목록 표시
3. 시리즈 카드 탭 → 첫 미읽음 권 자동 선택 → 본문 진입

### J3. "스캔본 가독성 보정"
1. 본문에서 길게 누르기 또는 메뉴 → 디스플레이 설정
2. Refresh mode (A2/Normal/Regal) · 자동 트리밍 on/off · Dithering 알고리즘
   · Contrast/Gamma/Sharpness 슬라이더
3. 책별로 저장. 같은 시리즈 다음 권에 자동 적용 옵션

## 6. 기능 스코프

### 6.1 v1.0 (MVP) — Panely 1:1 + e-ink 필수

#### 읽기 코어 (macOS Panely에서 그대로 포팅)
- [ ] 단일 페이지 / 두 페이지 펼침 / 세로 스크롤(웹툰)
- [ ] LTR / RTL 토글 (세로 모드는 RTL 무시)
- [ ] Fit modes: 화면 / 가로 / 세로 + 사용자 정의 줌 단계(100/125/150/200%)
- [ ] 페이지 모드 ±5 프리로드 (macOS의 ±2보다 공격적, e-ink CPU 보완)
- [ ] 세로 모드 지연 윈도잉 + 헤더 전용 크기 페치
- [ ] 시리즈 연속 읽기 카드 (Up next / Previous)
- [ ] 위치 기억 (Resume)
- [ ] 페이지 북마크 + 즐겨찾기
- [ ] Quick jump (페이지 번호 입력)
- [ ] 자연 정렬 (1, 2, 10)
- [ ] 중첩 아카이브 추출 (최대 3단계)

#### e-ink 필수 최적화
- [ ] **Refresh mode 토글** — A2 / Normal / Regal, 사용자 또는 자동
  - 페이지 모드 진입 시 풀리프레시
  - 세로 스크롤 중 A2, 멈추면 풀리프레시 한 번
- [ ] **풀리프레시 정책** — N페이지마다 강제 풀리프레시 (기본 5)
- [ ] **자동 여백 트리밍** — 스캔본 좌/우/상/하 흰 여백 감지 후 잘라서 fit
- [ ] **흑백 변환 + Dithering** — Floyd–Steinberg / Atkinson / Threshold
- [ ] **Contrast / Gamma / Sharpness** — 책별 저장
- [ ] **Invert (블랙/화이트 반전)** — macOS의 다크모드 대체

#### 입력
- [ ] **하드웨어 페이지 키 매핑** — Boox/Onyx 표준 키, 볼륨 키 옵션, 방향 반전
- [ ] **탭 영역** — 좌(이전) / 우(다음) / 중앙(메뉴 토글), 방향 반전 따라 자동
- [ ] **물리 키 다중 동작** — 더블탭, 길게 누르기 (페이지 점프 / 챕터 점프)
- [ ] **스와이프 옵션** — 기본 off (잔상). on 가능
- [ ] **핀치 줌** — 단계적(연속 X). 더블탭 1× ↔ 2×

#### 라이브러리
- [ ] SAF 폴더 추가 / 제거 (영속 접근 권한)
- [ ] CBZ / ZIP / 폴더 / **CBR (RAR)**
- [ ] 표지 자동 추출 + 캐시
- [ ] 진행률 배지 (% 또는 호선)
- [ ] 정렬: 이름 / Last opened / Recently added
- [ ] 검색 (파일명 / 시리즈명)
- [ ] 시리즈 그룹핑 (폴더 = 시리즈 자동 인식)

### 6.2 v1.5 — 자체 호스팅 친화

- [ ] **OPDS 1.2 / 2.0 카탈로그 클라이언트**
- [ ] **Komga / Kavita 네이티브 연동** (진행률 양방향 동기화)
- [ ] **WebDAV / SMB** 직접 마운트
- [ ] **ComicInfo.xml 파싱** (시리즈/볼륨/저자/요약)
- [ ] 컬렉션 / 태그 (사용자 정의)

### 6.3 v2 — 확장

- [ ] **PDF 만화 모드** (fixed-layout, 페이지 단위)
- [ ] **EPUB fixed-layout**
- [ ] **스타일러스 주석** (Boox 펜, Scribe 펜) — 페이지 위 메모/하이라이트
- [ ] **컬러 e-ink (Kaleido)** 컬러 채도/감마 보정
- [ ] **북마크 / 진행률 export·import** (JSON)
- [ ] **다국어 OCR 검색** (선택, 라이센스 검토)

### 6.4 명시적 제외 / 단순화

| 제외 | 이유 |
|---|---|
| 호버 기반 핫엣지 사이드바 | 터치엔 호버 없음 → 탭 영역으로 대체 |
| 트랙패드 핀치, ⌘+휠 연속 줌 | 잔상·배터리. 단계 줌으로 충분 |
| 60fps 매끈한 세로 스크롤 | e-ink 물리 한계. "한 화면 분량 점프" 모드가 자연스러움 |
| 다크 크롬 강조 | e-ink은 흰 배경이 정상. 흑백 high-contrast |
| ⌘ 단축키 전반 | 키보드 없음. 키맵 메뉴 별도 |

## 7. UX 원칙

1. **기본은 보이지 않게.** 본문 외 모든 크롬은 명시 제스처(중앙 탭, 메뉴 키)로만.
2. **e-ink 물리에 양보한다.** 애니메이션은 0ms가 기본. 페이드/슬라이드 금지.
3. **흑백 high-contrast.** 회색 톤 대신 검정/흰색 + 1px 라인. 음영 그림자 X.
4. **큰 터치 타깃.** 손가락/스타일러스 가정. 최소 48dp.
5. **상태가 영속이다.** 모든 책별 설정(refresh mode, 트리밍, contrast, fit)은
   책별로 저장되고 시리즈 다음 권에 propagate.
6. **물리 키가 1급 시민.** 모든 핵심 동작은 키만으로 가능해야 한다.

## 8. 기술 결정

### 스택 (예정)
- **언어/UI**: Kotlin + Jetpack Compose. 단, e-ink 잔상 회피를 위해
  애니메이션은 명시적으로 끔. 핫패스(이미지 뷰어 캔버스)는 `View` +
  `Canvas`로 직접 그리는 것을 검토.
- **이미지 디코드**: Coil 기반, 큰 만화 페이지는 자체 파이프라인
  (libjpeg-turbo / libwebp NDK 직접 호출 옵션) — Panely의 eager-decode와
  대응
- **아카이브**: ZIP은 표준 `java.util.zip` + 헤더 전용 prefetch (macOS의
  `loadDataPrefix` 대응). RAR은 `junrar` 또는 native 7z
- **저장소**: Room (라이브러리/메타/진행률/북마크)
- **파일 접근**: `androidx.documentfile` SAF (macOS의 security-scoped
  bookmark 대응)
- **e-ink SDK**:
  - Onyx SDK (Boox 타깃): refresh mode, EAC, 부분 갱신
  - 일반 안드로이드: best-effort로 시스템 기본 사용
- **테스트**: JUnit + Robolectric (코어 디코드/정렬), Compose UI test 일부

### Panely 코어 매핑 (참고)

| Panely (Swift) | Panely Ink (Kotlin) 후보 |
|---|---|
| `ReaderViewModel` (`@Observable @MainActor`) | `ReaderViewModel : ViewModel` + `StateFlow` |
| `actor ArchiveReader` | `class ArchiveReader` + `Mutex` 또는 single-thread `Dispatcher` |
| `FolderLoader` / `CBZLoader` | 동명 클래스, suspend 함수 |
| `FitCalculator` (순수) | object FitCalculator (그대로 1:1) |
| `PositionKey` | 그대로 1:1 (temp-dir 추출 안정 키) |
| `NSCache` | `LruCache` + 메모리 압박 콜백 |
| `ViewerController` (NSScrollView 줌) | Canvas 기반 자체 줌 컨트롤러 |
| `CenteringClipView` | Compose `Box(contentAlignment = Center)` 또는 자체 |
| security-scoped bookmark | `ContentResolver.takePersistableUriPermission` |

## 9. 비기능 요구사항

- **콜드 스타트**: 라이브러리 첫 화면 1초 이내 (Boox Note Air 4 기준)
- **페이지 전환 지연**: 디코드 완료 페이지 기준 100ms 이내 표시 호출
  (refresh 자체 지연은 e-ink 물리에 종속)
- **메모리 상한**: 페이지 캐시 ~150MB (macOS와 동일), 메모리 압박 시 즉시 evict
- **배터리**: 1시간 연속 읽기 ≤ 5% (typical Boox 7" 배터리)
- **오프라인 우선**: 네트워크 없이 모든 로컬 기능 동작
- **샌드박스**: 사용자 선택 폴더 외 파일시스템 접근 금지

## 10. 분석 / 성공 지표

> 오픈소스 + 사이드로드 가정. 분석 인프라는 두지 않음. 대신:

- GitHub stars / issue 활동
- Release 다운로드 수
- Komga/Kavita 커뮤니티(Reddit r/Komga, r/selfhosted) 언급
- Boox 사용자 커뮤니티 (MyBoox 포럼) 추천 스레드

## 11. 리스크 / 오픈 퀘스천

| 항목 | 리스크 | 대응 |
|---|---|---|
| Onyx SDK 미공개 API | 버전별 깨짐 | 리플렉션 + fallback. 일반 모드도 사용 가능하게 |
| Compose 애니메이션 잔상 | UX 손상 | 글로벌 `MotionScheme.None` 정책, 핫패스는 View 폴백 |
| CBR 의존성 (junrar) GPL | 라이선스 충돌 | 별도 모듈 분리, Apache 2.0 코어 보호 |
| SAF 성능 (대형 라이브러리) | 스캔 지연 | 인덱스 캐시, depth-1 즉시 표시 |
| 다양한 OEM 키 매핑 | 디바이스 호환성 | 사용자 키 학습/리바인드 화면 제공 |

**오픈 퀘스천:**
- Q1. v1.0 타깃 디바이스를 Boox만으로 좁힐까? (Onyx SDK 의존 정도 결정)
- Q2. 메타데이터 데이터베이스를 자체 vs Komga 의존할지
- Q3. 패키징 — F-Droid 등록할지, GitHub Releases APK만 할지
- Q4. 라이선스 — Panely(Apache 2.0)와 동일 유지

## 12. 마일스톤 제안

| 마일스톤 | 범위 | 추정 |
|---|---|---|
| **M0 — 코어 포팅** | CBZ/ZIP 로더, FitCalculator, PositionKey, ReaderViewModel 형태 | 2~3주 |
| **M1 — 뷰어 셸** | Canvas 뷰어, 페이지/세로 모드, 탭 영역, 하드웨어 키 | 2~3주 |
| **M2 — e-ink 최적화** | Refresh mode, 자동 트리밍, dithering, contrast | 2주 |
| **M3 — 라이브러리** | SAF, 표지, 진행률, 검색, 시리즈 그룹 | 2주 |
| **M4 — 시리즈 연속·북마크·Resume** | macOS Panely 동등 | 1~2주 |
| **M5 — 베타** | 디바이스 테스트(Boox/Scribe), 문서, 첫 릴리스 | 2주 |
| **v1.0 합계** | | ~3개월 |

## 13. 참고

- Onyx SDK: https://github.com/onyx-intl/OnyxAndroidDemo
- OPDS spec: https://specs.opds.io/
- ComicInfo.xml: https://anansi-project.github.io/docs/comicinfo/
- Komga API: https://komga.org/docs/openapi/
- Kavita API: https://www.kavitareader.com/docs/

---

_본 PRD는 초안이며, M0 진입 전 개발자/사용자 피드백으로 갱신될 예정._
