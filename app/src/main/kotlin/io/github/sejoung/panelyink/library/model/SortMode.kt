package io.github.sejoung.panelyink.library.model

/**
 * 라이브러리 책 행 정렬 기준 — M3 항목 "정렬: 이름 / Last opened / Recently added".
 *
 * v1.0 1차 범위: [Name], [LastOpened]. Recently added는 SAF `DocumentFile.lastModified()`의
 * IPC 비용 검증 후 후속.
 *
 * 폴더는 항상 이름순(NaturalOrderComparator) — 폴더에 진행률/Last opened 의미가 없다.
 */
enum class SortMode {
    /** 자연 정렬 — `NaturalOrderComparator`로 책 제목 1, 2, 10 순. 기본값. */
    Name,

    /** 마지막으로 본 시점(`position.updated_at`) 내림차순. 미열람 책은 맨 뒤로. */
    LastOpened,
    ;

    companion object {
        val DEFAULT = Name

        fun fromKey(key: String?): SortMode = entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}
