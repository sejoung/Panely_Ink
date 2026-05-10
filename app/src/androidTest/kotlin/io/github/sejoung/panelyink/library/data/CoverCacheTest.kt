package io.github.sejoung.panelyink.library.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoverCacheTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  @After
  fun tearDown() {
    context.filesDir.resolve("covers").deleteRecursively()
  }

  @Test
  fun cacheFileUsesJpegExtensionAndRoundTripsBitmap() {
    val bitmap = Bitmap.createBitmap(12, 16, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.WHITE)
    }
    val file = CoverCache.cacheFile(context, "book")

    assertTrue(file.name.endsWith(".jpg"))
    assertEquals(true, CoverCache.saveBitmap(file, bitmap))

    val loaded = CoverCache.loadBitmap(file)
    assertNotNull(loaded)
    assertEquals(12, loaded!!.width)
    assertEquals(16, loaded.height)
  }
}
