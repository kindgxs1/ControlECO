package com.controleco.app.ui.transactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

/**
 * 时间面板手动输入的解析规则：
 * 支持 `9`、`9:30`、`09:30:15`、全角冒号，越界分量收敛到合法范围，非法输入返回 null。
 */
class TimeInputTest {

    @Test
    fun `hour only fills zero minute and second`() {
        assertEquals(LocalTime.of(9, 0, 0), parseTimeInput("9"))
        assertEquals(LocalTime.of(23, 0, 0), parseTimeInput("23"))
    }

    @Test
    fun `hour and minute`() {
        assertEquals(LocalTime.of(9, 30, 0), parseTimeInput("9:30"))
        assertEquals(LocalTime.of(9, 5, 0), parseTimeInput("09:5"))
    }

    @Test
    fun `hour minute second`() {
        assertEquals(LocalTime.of(9, 30, 15), parseTimeInput("09:30:15"))
        assertEquals(LocalTime.of(0, 0, 0), parseTimeInput("00:00:00"))
        assertEquals(LocalTime.of(23, 59, 59), parseTimeInput("23:59:59"))
    }

    @Test
    fun `full width colon is normalized`() {
        assertEquals(LocalTime.of(18, 42, 7), parseTimeInput("18：42：07"))
    }

    @Test
    fun `out of range components are coerced`() {
        assertEquals(LocalTime.of(23, 59, 59), parseTimeInput("99:99:99"))
        assertEquals(LocalTime.of(10, 59, 0), parseTimeInput("10:70"))
    }

    @Test
    fun `invalid input returns null`() {
        assertNull(parseTimeInput(""))
        assertNull(parseTimeInput("  "))
        assertNull(parseTimeInput("ab"))
        assertNull(parseTimeInput(":"))
    }

    @Test
    fun `whitespace is ignored`() {
        assertEquals(LocalTime.of(8, 5, 0), parseTimeInput(" 08:05 "))
    }
}
