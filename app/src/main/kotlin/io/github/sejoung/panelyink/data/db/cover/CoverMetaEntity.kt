package io.github.sejoung.panelyink.data.db.cover

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 책별 표지 추출 메타 — M3 "표지 자동 추출 + 캐시" 보강.
 *
 * 추출 결과 상태와 시점을 기록해:
 * - **재시도 방지**: 깨진 책은 매번 archive open + 디코드 시도하면 사용자 진입마다
 *   부담. status=FAILED면 다음부터 skip
 * - **추출 페이지 추적**: [sourcePageIndex] — v1.0은 항상 0, v1.5에서 사용자가 다른
 *   페이지로 변경 옵션
 * - **LRU 정리**: [extractedAt]으로 오래된 표지부터 정리
 *
 * 디스크 파일 경로(`filesDir/covers/<bookId>.jpg`)는 결정적이라 컬럼 안 둠 — bookId
 * 만으로 위치 계산. 파일이 사라지면 status=OK여도 재추출.
 */
@Entity(tableName = "cover_meta")
data class CoverMetaEntity(
    @PrimaryKey
    @ColumnInfo(name = "book_id")
    val bookId: String,

    /** [CoverStatus] 이름. Room은 enum 직접 매핑 X라 String. */
    @ColumnInfo(name = "status")
    val status: String,

    /** 추출 출처 페이지 인덱스. v1.0은 항상 0. */
    @ColumnInfo(name = "source_page_index")
    val sourcePageIndex: Int,

    /** 추출 epoch ms — LRU 정리/통계용. */
    @ColumnInfo(name = "extracted_at")
    val extractedAt: Long,
)

/**
 * 표지 추출 결과 상태.
 *
 * - [OK]: 디스크에 정상 PNG 저장됨
 * - [FAILED]: archive open 실패, 페이지 0개, BitmapFactory 디코드 실패 등 — 재시도 X
 *
 * 사용자 명시 "표지 캐시 비우기"는 FAILED 메타까지 지워 다음 진입 때 재추출을 허용한다.
 */
enum class CoverStatus { OK, FAILED }
