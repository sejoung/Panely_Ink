package io.github.sejoung.panelyink.reader.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Constraints
import io.github.sejoung.panelyink.core.preferences.ReaderOrientation

/**
 * 본문 트리 전체(페이지 + 오버레이)를 사용자가 선택한 [ReaderOrientation]에 맞춰 회전.
 *
 * **왜 Compose 레벨에서 회전?**
 * Meebook M7 같은 e-ink OEM은 `Activity.requestedOrientation`을 무시한다
 * ([io.github.sejoung.panelyink.reader.ui.ReaderOrientationEffect]가 1차로 시도해도 실패).
 * fallback으로 SW 회전이 필요한데, 페이지(Canvas)만 회전하면 메뉴/탭 영역(Compose)이 따라가지 않아
 * 클릭 좌표가 어긋난다. 트리 전체를 한 번에 돌리면 Compose hit-test가 inverse transform을 통해
 * pointer 좌표를 자동 재맵핑한다.
 *
 * **회전 방향:**
 * 90° CW 한 가지만 사용. 사용자는 단말을 90° CCW(상단을 자신의 왼쪽으로) 잡으면 정상 시야.
 * Portrait/Landscape 어느 쪽을 원하든 물리 viewport와 mismatch이면 동일하게 90° CW 적용.
 *
 * **OS가 회전을 허용하는 경우:**
 * [ReaderOrientationEffect]가 hardware-rotate에 성공하면 `LocalConfiguration.orientation`이
 * 갱신되어 mismatch가 사라지므로 SW 회전은 자동 비활성. 이중 회전 없음.
 */
@Composable
internal fun ReaderRotationLayout(
  orientation: ReaderOrientation,
  content: @Composable () -> Unit,
) {
  val physicalOrientation = LocalConfiguration.current.orientation
  val needsSwRotate = when (orientation) {
    ReaderOrientation.Landscape -> physicalOrientation == Configuration.ORIENTATION_PORTRAIT
    ReaderOrientation.Portrait -> physicalOrientation == Configuration.ORIENTATION_LANDSCAPE
  }
  // 회전 여부와 무관하게 content()의 호출 위치는 하나로 유지한다. 분기마다 다른 call site에서 부르면
  // needsSwRotate가 바뀔 때 Compose가 하위 트리를 통째로 버리고 새로 만들어, ReaderView 재생성·페이지 캐시
  // 재디코드·설정 화면 스크롤 위치 초기화가 일어난다(OS가 회전을 받아주는 기기에선 토글 한 번에 두 번).
  Layout(
    modifier = Modifier.fillMaxSize(),
    // 다자식 stacking이 Box와 동일하도록 단일 Box로 래핑 — 측정/배치를 1개 placeable로 다룬다.
    content = { Box(modifier = Modifier.fillMaxSize()) { content() } },
  ) { measurables, constraints ->
    val pw = constraints.maxWidth
    val ph = constraints.maxHeight
    if (!needsSwRotate) {
      val placeable = measurables.first().measure(Constraints.fixed(pw, ph))
      return@Layout layout(pw, ph) { placeable.place(0, 0) }
    }
    // 자식은 회전후 logical 차원으로 측정 — landscape는 (ph × pw), 즉 차원 swap.
    val placeable = measurables.first().measure(Constraints.fixed(ph, pw))
    layout(pw, ph) {
      placeable.placeWithLayer(x = 0, y = 0) {
        // 90° CW 회전을 top-left pivot에 적용 후 X를 pw만큼 translate.
        // 변환 결과: 자식의 (0,0)..(ph, pw)이 물리 (0,0)..(pw, ph)에 정확히 매핑.
        rotationZ = 90f
        transformOrigin = TransformOrigin(0f, 0f)
        translationX = pw.toFloat()
      }
    }
  }
}
