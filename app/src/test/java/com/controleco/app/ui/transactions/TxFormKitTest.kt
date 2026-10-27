package com.controleco.app.ui.transactions

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 时间手输解析（[parseTimeInput]）的纯函数测试。
 * 面板里的「手动输入」与滚轮共用同一个口径：支持「时:分:秒」「时:分」以及连写的
 * `HHmmss` / `HHmm`，任意非数字分隔都当分隔符；越界一律拒绝（避免把 25 点写成第二天）。
 */
class TxFormKitTest {

    @Test
    fun `冒号分隔的时分秒`() {
        assertEquals(LocalTime.of(12, 30, 45), parseTimeInput("12:30:45"))
    }

    @Test
    fun `连写六位为时分秒`() {
        assertEquals(LocalTime.of(12, 30, 45), parseTimeInput("123045"))
    }

    @Test
    fun `四位连写为时分`() {
        assertEquals(LocalTime.of(12, 30), parseTimeInput("1230"))
        // 三位按「1 时 23 分」理解，与四位同一条分支
        assertEquals(LocalTime.of(1, 23), parseTimeInput("123"))
    }

    @Test
    fun `省略秒时按零秒`() {
        assertEquals(LocalTime.of(12, 30, 0), parseTimeInput("12:30"))
    }

    @Test
    fun `单个小时数字`() {
        assertEquals(LocalTime.of(9, 0), parseTimeInput("9"))
    }

    @Test
    fun `中文冒号与空格也算分隔符`() {
        assertEquals(LocalTime.of(12, 30, 45), parseTimeInput("12：30：45"))
        assertEquals(LocalTime.of(12, 30, 45), parseTimeInput("12 30 45"))
    }

    @Test
    fun `越界或非法输入返回空`() {
        assertNull(parseTimeInput("24:00:00"))
        assertNull(parseTimeInput("12:60"))
        assertNull(parseTimeInput("12:30:61"))
        assertNull(parseTimeInput("abc"))
        assertNull(parseTimeInput(""))
        assertNull(parseTimeInput("1:2:3:4"))
    }
}
