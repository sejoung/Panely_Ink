package io.github.sejoung.panelyink.reader.model

import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.core.preferences.AppPreferences
import io.github.sejoung.panelyink.core.preferences.ReaderOrientation
import io.github.sejoung.panelyink.core.preferences.ReadingDirection

/**
 * 책별로 **명시적으로 사용자가 변경한 필드만** 담는 sparse 표현. 영속 저장 단위.
 *
 * 이 모델이 v1.1에서 도입된 이유 — 기존 [BookSettings]는 6개 필드 전부를 묶어 한 row로 저장했다.
 * 그 결과 사용자가 한 필드(예: spreadMode)만 토글해도 나머지 5필드도 그 시점의 전역값으로
 * 그대로 row에 박혀, 이후 [AppPreferences] 전역값을 바꿔도 이 책엔 영향이 가지 않는 부작용이 있었다.
 *
 * sparse 모델의 규칙:
 * - 필드 값이 `null` → 사용자가 그 필드는 건드린 적 없음 → 진입 시 [resolve]가 [AppPreferences] /
 *   [BookSettings.DEFAULTS]에서 합성
 * - 필드 값이 non-null → 사용자가 명시적으로 그 값을 선택함 → 전역값 변경과 무관하게 유지
 *
 * 모든 필드가 null이면 [isEmpty]=true. 이 경우 영속 저장 없이 row를 삭제해 다음 진입에 깨끗하게 전역 기본값을 받는다.
 *
 * **시리즈 형제 권 propagation**: 사용자가 책 A에서 책 B로 넘어갈 때 A의 overrides를 그대로 B에 전달.
 * 명시적으로 바꾼 것만 따라가고, 안 바꾼 필드는 B 자신의 전역값을 따른다.
 */
data class BookSettingsOverrides(
    val fitMode: FitMode? = null,
    val direction: ReadingDirection? = null,
    val trimEnabled: Boolean? = null,
    val contrast: Float? = null,
    val spreadMode: Boolean? = null,
    val orientation: ReaderOrientation? = null,
) {
    val isEmpty: Boolean
        get() = fitMode == null && direction == null && trimEnabled == null &&
            contrast == null && spreadMode == null && orientation == null

    companion object {
        /** 아무 override도 없는 상태. 새 책 첫 진입 시 또는 책별 row 미존재. */
        val NONE: BookSettingsOverrides = BookSettingsOverrides()
    }
}

/**
 * sparse override를 전역 prefs와 합성해 reader가 사용할 fully-resolved 값으로 변환.
 *
 * 우선순위: override(책별 명시) → appPrefs(전역 기본값).
 * v1.1부터 6필드 전부 [AppPreferences]에 동일 짝이 있어 하드코드 fallback은 사용 안 함.
 * [BookSettings.DEFAULTS]는 [AppPreferences.DEFAULTS]가 fallback일 때만 간접 영향 (SharedPreferences 미설정 시).
 */
fun BookSettingsOverrides.resolve(appPrefs: AppPreferences): BookSettings = BookSettings(
    fitMode = fitMode ?: appPrefs.defaultFitMode,
    direction = direction ?: appPrefs.defaultReadingDirection,
    trimEnabled = trimEnabled ?: appPrefs.defaultTrimEnabled,
    contrast = contrast ?: appPrefs.defaultContrast,
    spreadMode = spreadMode ?: appPrefs.defaultSpreadMode,
    orientation = orientation ?: appPrefs.defaultOrientation,
)
