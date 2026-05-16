package io.github.sejoung.panelyink.library.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sejoung.panelyink.R
import io.github.sejoung.panelyink.core.preferences.AppLanguage
import io.github.sejoung.panelyink.core.preferences.AppPreferences
import io.github.sejoung.panelyink.core.preferences.AppPreferencesRepository
import io.github.sejoung.panelyink.data.preferences.SharedPrefsAppPreferencesRepository
import io.github.sejoung.panelyink.library.model.ViewMode
import io.github.sejoung.panelyink.core.render.ContrastMatrix
import io.github.sejoung.panelyink.reader.ui.ContrastSlider
import io.github.sejoung.panelyink.reader.ui.FitSegments
import io.github.sejoung.panelyink.reader.ui.OrientationSegments
import io.github.sejoung.panelyink.reader.ui.SpreadSegments
import io.github.sejoung.panelyink.reader.ui.TrimSegments
import io.github.sejoung.panelyink.ui.components.DirectionSegments
import io.github.sejoung.panelyink.ui.components.FullRefreshIntervalSegments
import io.github.sejoung.panelyink.ui.components.GroupHeader
import io.github.sejoung.panelyink.ui.components.InvertSegments
import io.github.sejoung.panelyink.ui.components.Segments
import io.github.sejoung.panelyink.ui.components.PanelyArrowBackIcon
import io.github.sejoung.panelyink.ui.components.PanelyIconButton
import io.github.sejoung.panelyink.ui.components.PanelyTextButton
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkSpacing
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors
import kotlinx.coroutines.launch

/**
 * 앱 전역 설정 화면 — 라이브러리에서 진입. 모든 책에 공통 적용되는 옵션만.
 *
 * 책별 설정([io.github.sejoung.panelyink.reader.ui.ReaderSettingsScreen])과 분리:
 * - 앱 기본값/디바이스 특성/환경 의존(기본 읽기 방향, 풀리프레시 주기, 흑백 반전, 언어) → 여기
 * - 현재 책에 대한 사용자 취향(맞춤/방향/트리밍/대비) → 책 메뉴
 *
 * 흑백 반전은 리더 설정에서도 토글할 수 있지만 저장소는 같은 전역 설정이다.
 *
 * 변경 사항은 즉시 [AppPreferencesRepository]에 저장. 다음 책 진입에 반영.
 */
@Composable
fun AppSettingsScreen(
  roots: List<Uri>,
  viewMode: ViewMode,
  onAddRoot: () -> Unit,
  onRemoveRoot: (Uri) -> Unit,
  onViewModeChange: (ViewMode) -> Unit,
  onClearCoverCache: () -> Unit,
  onResetAllData: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler { onBack() }

  val typography = LocalPanelyInkTypography.current
  val spacing = LocalPanelyInkSpacing.current
  val localContext = LocalContext.current
  val ctx = localContext.applicationContext
  val repo: AppPreferencesRepository = remember(ctx) {
    SharedPrefsAppPreferencesRepository(ctx)
  }
  val scope = rememberCoroutineScope()
  var resetDialogOpen by remember { mutableStateOf(false) }

  // 화면 진입 시 1회 SharedPreferences 로드. 책 진입 흐름과 달리 빈번하지 않아 단순.
  var prefs by remember { mutableStateOf<AppPreferences?>(null) }
  LaunchedEffect(repo) { prefs = repo.load() }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(PanelyInkColors.Paper),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = spacing.space3, vertical = spacing.space2),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
      PanelyIconButton(onClick = onBack, primary = false) { tint ->
        PanelyArrowBackIcon(tint = tint)
      }
      Text(
        text = stringResource(R.string.settings_app_title),
        style = typography.title,
        color = PanelyInkColors.Ink,
      )
    }
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(1.dp)
        .background(PanelyInkColors.Hairline),
    )

    val current = prefs
    if (current == null) {
      // 1회 로드 동안 placeholder. 거의 즉시 끝나서 시각적으로 거의 안 보임.
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(spacing.space3),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = stringResource(R.string.common_loading),
          style = typography.body,
          color = PanelyInkColors.Mute
        )
      }
      return@Column
    }

    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = spacing.space3, vertical = spacing.space2),
    ) {
      GroupHeader(stringResource(R.string.settings_language))
      Spacer(Modifier.height(spacing.space2))
      LanguageSegments(
        languageTag = current.languageTag,
        onSelect = { language ->
          prefs = current.copy(languageTag = language.tag)
          scope.launch {
            repo.setLanguageTag(language.tag)
            resolveActivity(localContext)?.recreate()
          }
        },
      )

      GroupSeparator(spacing.space4)

      GroupHeader(stringResource(R.string.settings_library))
      Spacer(Modifier.height(spacing.space2))

      SectionLabel(stringResource(R.string.settings_library_view_mode))
      Spacer(Modifier.height(spacing.space1))
      ViewModeSegments(
        viewMode = viewMode,
        onSelect = onViewModeChange,
      )

      GroupSeparator(spacing.space4)

      GroupHeader(stringResource(R.string.settings_reader_defaults))
      Spacer(Modifier.height(spacing.space2))

      SectionLabel(stringResource(R.string.settings_default_reading_direction))
      Spacer(Modifier.height(spacing.space1))
      DirectionSegments(
        selected = current.defaultReadingDirection,
        onSelect = { value ->
          prefs = current.copy(defaultReadingDirection = value)
          scope.launch { repo.setDefaultReadingDirection(value) }
        },
      )

      Spacer(Modifier.height(spacing.space3))

      // 새 책 첫 진입 시 기본값. 책별 spreadMode가 저장된 책에는 영향 없음 — 별개로 유지.
      SectionLabel(stringResource(R.string.settings_default_spread_mode))
      Spacer(Modifier.height(spacing.space1))
      SpreadSegments(
        enabled = current.defaultSpreadMode,
        onSelect = { value ->
          prefs = current.copy(defaultSpreadMode = value)
          scope.launch { repo.setDefaultSpreadMode(value) }
        },
      )

      Spacer(Modifier.height(spacing.space3))

      SectionLabel(stringResource(R.string.settings_default_orientation))
      Spacer(Modifier.height(spacing.space1))
      OrientationSegments(
        selected = current.defaultOrientation,
        onSelect = { value ->
          prefs = current.copy(defaultOrientation = value)
          scope.launch { repo.setDefaultOrientation(value) }
        },
      )

      Spacer(Modifier.height(spacing.space3))

      // 자동 여백 트리밍 — 두쪽 보기에서 페이지별 trim이 좌/우 정렬을 깰 수 있어 기본 OFF.
      SectionLabel(stringResource(R.string.settings_default_trim_enabled))
      Spacer(Modifier.height(spacing.space1))
      TrimSegments(
        enabled = current.defaultTrimEnabled,
        onSelect = { value ->
          prefs = current.copy(defaultTrimEnabled = value)
          scope.launch { repo.setDefaultTrimEnabled(value) }
        },
      )

      Spacer(Modifier.height(spacing.space3))

      // 화면 맞춤 — FitMode.Zoom은 책별 명시만 가능, 여기엔 3개 segment만.
      SectionLabel(stringResource(R.string.settings_default_fit_mode))
      Spacer(Modifier.height(spacing.space1))
      FitSegments(
        selected = current.defaultFitMode,
        onSelect = { value ->
          prefs = current.copy(defaultFitMode = value)
          scope.launch { repo.setDefaultFitMode(value) }
        },
      )

      Spacer(Modifier.height(spacing.space3))

      SectionLabel(stringResource(R.string.settings_default_contrast))
      Spacer(Modifier.height(spacing.space1))
      ContrastSlider(
        contrast = current.defaultContrast,
        onCommit = { value ->
          prefs = current.copy(defaultContrast = value)
          scope.launch { repo.setDefaultContrast(value) }
        },
        onReset = {
          prefs = current.copy(defaultContrast = ContrastMatrix.IDENTITY)
          scope.launch { repo.setDefaultContrast(ContrastMatrix.IDENTITY) }
        },
      )

      GroupSeparator(spacing.space4)

      GroupHeader(stringResource(R.string.settings_display))
      Spacer(Modifier.height(spacing.space2))

      SectionLabel(stringResource(R.string.settings_full_refresh_interval))
      Spacer(Modifier.height(spacing.space1))
      FullRefreshIntervalSegments(
        interval = current.fullRefreshInterval,
        onSelect = { value ->
          prefs = current.copy(fullRefreshInterval = value)
          scope.launch { repo.setFullRefreshInterval(value) }
        },
      )

      Spacer(Modifier.height(spacing.space3))

      SectionLabel(stringResource(R.string.settings_invert))
      Spacer(Modifier.height(spacing.space1))
      InvertSegments(
        enabled = current.invertEnabled,
        onSelect = { value ->
          prefs = current.copy(invertEnabled = value)
          scope.launch { repo.setInvertEnabled(value) }
        },
      )

      Spacer(Modifier.height(spacing.space4))
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(1.dp)
          .background(PanelyInkColors.Hairline),
      )
      Spacer(Modifier.height(spacing.space4))

      // 캐시 — 표지 추출 디스크 캐시. 사용자가 명시 정리 가능.
      GroupHeader(stringResource(R.string.settings_cache))
      Spacer(Modifier.height(spacing.space2))
      Text(
        text = stringResource(R.string.settings_cover_cache_body),
        style = typography.body,
        color = PanelyInkColors.Mute,
      )
      Spacer(Modifier.height(spacing.space2))
      PanelyTextButton(
        label = stringResource(R.string.settings_clear_cover_cache),
        onClick = onClearCoverCache,
        primary = false,
        modifier = Modifier.fillMaxWidth(),
      )

      Spacer(Modifier.height(spacing.space4))
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(1.dp)
          .background(PanelyInkColors.Hairline),
      )
      Spacer(Modifier.height(spacing.space4))

      // 폴더 관리 — 추가/제거 모두 여기. 이전엔 별도 ManageRootsDialog였고
      // 추가는 헤더 + 아이콘에 분리됐지만 둘 다 흡수해 한 곳에 모음(2026-05-08).
      GroupHeader(stringResource(R.string.settings_folders))
      Spacer(Modifier.height(spacing.space2))
      if (roots.isEmpty()) {
        Text(
          text = stringResource(R.string.settings_no_folders),
          style = typography.body,
          color = PanelyInkColors.Mute,
        )
      } else {
        roots.forEach { uri ->
          RootRow(uri = uri, onRemove = { onRemoveRoot(uri) })
        }
      }

      Spacer(Modifier.height(spacing.space2))
      PanelyTextButton(
        label = stringResource(R.string.library_add_folder),
        onClick = onAddRoot,
        primary = roots.isEmpty(),
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(Modifier.height(spacing.space1))
      // 시스템 SAF picker는 우리가 제어 못 함 — 사용자에게 취소 방법 안내.
      Text(
        text = stringResource(R.string.settings_picker_cancel_hint),
        style = typography.caption,
        color = PanelyInkColors.Mute,
      )

      Spacer(Modifier.height(spacing.space4))
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(1.dp)
          .background(PanelyInkColors.Hairline),
      )
      Spacer(Modifier.height(spacing.space4))

      // 위험 영역 — 신규 사용자 상태로 복귀(Room/Prefs/SAF/캐시 모두 비움).
      GroupHeader(stringResource(R.string.settings_reset_group))
      Spacer(Modifier.height(spacing.space2))
      Text(
        text = stringResource(R.string.settings_reset_body),
        style = typography.body,
        color = PanelyInkColors.Mute,
      )
      Spacer(Modifier.height(spacing.space2))
      PanelyTextButton(
        label = stringResource(R.string.settings_reset_all),
        onClick = { resetDialogOpen = true },
        primary = false,
        modifier = Modifier.fillMaxWidth(),
      )

      Spacer(Modifier.height(spacing.space3))
    }
  }

  if (resetDialogOpen) {
    ConfirmResetDialog(
      onConfirm = {
        resetDialogOpen = false
        onResetAllData()
      },
      onDismiss = { resetDialogOpen = false },
    )
  }
}

@Composable
private fun RootRow(uri: Uri, onRemove: () -> Unit) {
  val typography = LocalPanelyInkTypography.current
  val spacing = LocalPanelyInkSpacing.current
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = spacing.space1),
  ) {
    Text(
      text = uri.lastPathSegment ?: uri.toString(),
      style = typography.list,
      color = PanelyInkColors.Ink,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = uri.toString(),
      style = typography.caption,
      color = PanelyInkColors.Mute,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(spacing.space1))
    Row(verticalAlignment = Alignment.CenterVertically) {
      PanelyTextButton(
        label = stringResource(R.string.library_remove_folder),
        onClick = onRemove,
        primary = false
      )
    }
  }
}

@Composable
private fun LanguageSegments(
  languageTag: String,
  onSelect: (AppLanguage) -> Unit,
) {
  val options = listOf(
    AppLanguage.System to stringResource(R.string.settings_language_system),
    AppLanguage.English to stringResource(R.string.settings_language_english),
    AppLanguage.Korean to stringResource(R.string.settings_language_korean),
  )
  Segments(
    options = options,
    isSelected = { it == AppLanguage.fromTag(languageTag) },
    labelOf = { value -> options.first { it.first == value }.second },
    onSelect = onSelect,
  )
}

@Composable
private fun ViewModeSegments(
  viewMode: ViewMode,
  onSelect: (ViewMode) -> Unit,
) {
  val options = listOf(
    ViewMode.List to stringResource(R.string.library_view_list),
    ViewMode.Cover to stringResource(R.string.library_view_cover),
    ViewMode.Grid to stringResource(R.string.library_view_grid),
  )
  Segments(
    options = options,
    isSelected = { it == viewMode },
    labelOf = { value -> options.first { it.first == value }.second },
    onSelect = onSelect,
  )
}

@Composable
private fun SectionLabel(text: String) {
  val typography = LocalPanelyInkTypography.current
  Text(
    text = text,
    style = typography.caption,
    color = PanelyInkColors.Mute,
  )
}

@Composable
private fun GroupSeparator(verticalSpace: androidx.compose.ui.unit.Dp) {
  Spacer(Modifier.height(verticalSpace))
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(1.dp)
      .background(PanelyInkColors.Hairline),
  )
  Spacer(Modifier.height(verticalSpace))
}
