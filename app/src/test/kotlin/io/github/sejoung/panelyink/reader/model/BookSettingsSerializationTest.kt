package io.github.sejoung.panelyink.reader.model

import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.core.preferences.ReaderOrientation
import io.github.sejoung.panelyink.core.preferences.ReadingDirection
import io.github.sejoung.panelyink.core.preferences.deserializeOrientation
import org.junit.Assert.assertEquals
import org.junit.Test

class BookSettingsSerializationTest {

    @Test
    fun fitScreenRoundTrip() {
        assertEquals(FitMode.FitScreen, deserializeFitMode(serializeFitMode(FitMode.FitScreen)))
    }

    @Test
    fun fitWidthRoundTrip() {
        assertEquals(FitMode.FitWidth, deserializeFitMode(serializeFitMode(FitMode.FitWidth)))
    }

    @Test
    fun fitHeightRoundTrip() {
        assertEquals(FitMode.FitHeight, deserializeFitMode(serializeFitMode(FitMode.FitHeight)))
    }

    @Test
    fun zoomFactorRoundTrip() {
        val original = FitMode.Zoom(1.5f)
        assertEquals(original, deserializeFitMode(serializeFitMode(original)))
    }

    @Test
    fun unknownFitFallsBackToFitScreen() {
        assertEquals(FitMode.FitScreen, deserializeFitMode("garbage"))
        assertEquals(FitMode.FitScreen, deserializeFitMode(""))
    }

    @Test
    fun negativeZoomFactorFallsBackToFitScreen() {
        // Zoom 자체는 음수/0을 require로 막지만 직렬값이 깨졌을 때 안전.
        assertEquals(FitMode.FitScreen, deserializeFitMode("Zoom:-1.0"))
        assertEquals(FitMode.FitScreen, deserializeFitMode("Zoom:abc"))
    }

    @Test
    fun directionRoundTrip() {
        assertEquals(ReadingDirection.Ltr, deserializeDirection(ReadingDirection.Ltr.name))
        assertEquals(ReadingDirection.Rtl, deserializeDirection(ReadingDirection.Rtl.name))
        assertEquals(
            ReadingDirection.VerticalScroll,
            deserializeDirection(ReadingDirection.VerticalScroll.name),
        )
    }

    @Test
    fun unknownDirectionFallsBackToLtr() {
        assertEquals(ReadingDirection.Ltr, deserializeDirection("Diagonal"))
        assertEquals(ReadingDirection.Ltr, deserializeDirection(""))
    }

    @Test
    fun orientationRoundTrip() {
        assertEquals(ReaderOrientation.Portrait, deserializeOrientation(ReaderOrientation.Portrait.name))
        assertEquals(ReaderOrientation.Landscape, deserializeOrientation(ReaderOrientation.Landscape.name))
    }

    @Test
    fun unknownOrientationFallsBackToPortrait() {
        // 레거시 v1.1 베타 값 "Auto" 와 임의의 깨진 값 모두 Portrait로 안전 폴백 (v1.0 동작 보존).
        assertEquals(ReaderOrientation.Portrait, deserializeOrientation("Auto"))
        assertEquals(ReaderOrientation.Portrait, deserializeOrientation("Tilted"))
        assertEquals(ReaderOrientation.Portrait, deserializeOrientation(""))
    }
}
