package moe.nepnep.hduhelper.ui

import java.time.LocalDate
import moe.nepnep.hduhelper.ui.screens.schedulePeriod
import org.junit.Assert.assertEquals
import org.junit.Test

class SchedulePeriodTest {
    @Test fun dayPartsHandleBoundariesAllDayAndOvernightEvents() {
        val date = LocalDate.of(2026, 9, 8)
        for ((time, expected) in mapOf("00:00" to "上午", "11:59" to "上午", "12:00" to "下午", "17:59" to "下午", "18:00" to "晚上", "23:59" to "晚上")) {
            assertEquals(expected, schedulePeriod(date.atTime(java.time.LocalTime.parse(time)), false, date))
        }
        assertEquals("全天", schedulePeriod(date.atStartOfDay(), true, date))
        assertEquals("时间待定", schedulePeriod(null, false, date))
        assertEquals("上午", schedulePeriod(date.minusDays(1).atTime(20, 0), false, date))
    }
}
