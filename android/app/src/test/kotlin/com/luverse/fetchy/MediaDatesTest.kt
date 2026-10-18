package com.luverse.fetchy

import org.junit.Assert.*
import org.junit.Test

class MediaDatesTest {
    @Test fun acceptsOnlyRealSourceDatesAndConvertsSeconds() {
        assertEquals(1700000000125L, sourceMediaDate(1700000000.125))
        assertEquals(1577836800000L, sourceMediaDate(Double.NaN, "20200101"))
        assertNotNull(sourceMediaDate(Double.NaN, "20240229"))
        for (date in listOf("20240230", "20241301", "2024-01-01", "garbage", "")) assertNull(sourceMediaDate(Double.NaN, date))
        for (number in listOf(Double.POSITIVE_INFINITY, -1.0, 0.0, .0001, 253402300800.0)) assertNull(sourceMediaDate(number))
    }
    @Test fun sourceDateIsOptionalAndDownloadDateIsAlwaysCurrent() {
        assertEquals(1234L, selectedMediaDate("source", 1234L, 9999L))
        assertEquals(9999L, selectedMediaDate("source", null, 9999L))
        assertEquals(9999L, selectedMediaDate("download", 1234L, 9999L))
        assertThrows(IllegalArgumentException::class.java) { selectedMediaDate("invalid", null, 9999L) }
    }
}
