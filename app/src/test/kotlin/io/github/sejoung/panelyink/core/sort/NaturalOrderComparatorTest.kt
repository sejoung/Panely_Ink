package io.github.sejoung.panelyink.core.sort

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderComparatorTest {

    @Test
    fun sortsNumericallyWithinDigitChunks() {
        val input = listOf("page_10.jpg", "page_2.jpg", "page_1.jpg")
        val expected = listOf("page_1.jpg", "page_2.jpg", "page_10.jpg")
        assertEquals(expected, input.sortedWith(NaturalOrderComparator))
    }

    @Test
    fun zeroPaddedShorterFormSortsFirstWhenValuesEqual() {
        val input = listOf("p1", "p01", "p001")
        val expected = listOf("p1", "p01", "p001")
        assertEquals(expected, input.sortedWith(NaturalOrderComparator))
    }

    @Test
    fun isCaseInsensitiveOnLetters() {
        val input = listOf("Banana", "apple", "Cherry")
        val expected = listOf("apple", "Banana", "Cherry")
        assertEquals(expected, input.sortedWith(NaturalOrderComparator))
    }

    @Test
    fun shorterPrefixSortsBeforeLongerOne() {
        val input = listOf("page_2_alt.jpg", "page_2.jpg")
        val expected = listOf("page_2.jpg", "page_2_alt.jpg")
        assertEquals(expected, input.sortedWith(NaturalOrderComparator))
    }
}
