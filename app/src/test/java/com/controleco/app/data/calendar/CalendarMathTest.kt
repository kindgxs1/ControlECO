package com.controleco.app.data.calendar

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** 日历收支口径单测（功能1）：收入含退款到账、支出排除已退款。 */
class CalendarMathTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun row(
        type: TxType,
        cents: Long,
        date: LocalDate,
        refund: RefundMark = RefundMark.NONE,
        countInStats: Boolean = true,
        refundLinkId: Long = 0L,
        linkedRefundCents: Long = 0L,
        linkedExpenseCents: Long = 0L,
    ) = TransactionRow(
        id = 0,
        type = type,
        amountCents = cents,
        timestamp = date.atTime(9, 30).atZone(zone).toInstant().toEpochMilli(),
        note = "",
        counterparty = "",
        source = TxSource.MANUAL,
        categoryId = 1,
        categoryName = "分类",
        accountId = 1,
        accountName = "账户",
        refundMark = refund,
        countInStats = countInStats,
        refundLinkId = refundLinkId,
        linkedRefundCents = linkedRefundCents,
        linkedExpenseCents = linkedExpenseCents,
    )

    @Test
    fun `按天汇总收入与支出`() {
        val day = LocalDate.of(2026, 10, 24)
        val rows = listOf(
            row(TxType.INCOME, 100_000, day),
            row(TxType.EXPENSE, 30_000, day),
            row(TxType.EXPENSE, 20_000, day),
        )
        val byDay = CalendarMath.byDay(rows, zone)[day]!!
        assertEquals(100_000L, byDay.incomeCents)
        assertEquals(50_000L, byDay.expenseCents)
        assertEquals(50_000L, byDay.netCents)
    }

    @Test
    fun `已退款的支出不计入日历支出_退款收入照常计入`() {
        val day = LocalDate.of(2026, 10, 24)
        val rows = listOf(
            row(TxType.EXPENSE, 30_000, day, RefundMark.REFUNDED_EXPENSE),
            row(TxType.INCOME, 30_000, day, RefundMark.REFUND_INCOME),
        )
        val byDay = CalendarMath.byDay(rows, zone)[day]!!
        assertEquals(30_000L, byDay.incomeCents)
        assertEquals(0L, byDay.expenseCents)
    }

    @Test
    fun `月汇总与按天汇总口径一致`() {
        val rows = listOf(
            row(TxType.INCOME, 100_000, LocalDate.of(2026, 10, 1)),
            row(TxType.EXPENSE, 30_000, LocalDate.of(2026, 10, 2)),
            row(TxType.EXPENSE, 10_000, LocalDate.of(2026, 10, 3), RefundMark.REFUNDED_EXPENSE),
        )
        val totals = CalendarMath.totals(rows)
        assertEquals(100_000L, totals.incomeCents)
        assertEquals(30_000L, totals.expenseCents)
        assertEquals(70_000L, totals.netCents)
    }

    @Test
    fun `没有账单的日期不出现在结果里`() {
        val rows = listOf(row(TxType.EXPENSE, 10_000, LocalDate.of(2026, 10, 5)))
        assertEquals(setOf(LocalDate.of(2026, 10, 5)), CalendarMath.byDay(rows, zone).keys)
    }

    @Test
    fun `已关联退款按差额计入日历收支`() {
        val expenseDay = LocalDate.of(2026, 10, 20)
        val refundDay = LocalDate.of(2026, 10, 22)
        val rows = listOf(
            row(TxType.EXPENSE, 10_000, expenseDay, linkedRefundCents = 4_000),
            row(
                TxType.INCOME, 4_000, refundDay, RefundMark.REFUND_INCOME,
                refundLinkId = 1, linkedExpenseCents = 10_000,
            ),
        )
        val byDay = CalendarMath.byDay(rows, zone)
        // 部分退款：支出只计差额 60，退款不计收入
        assertEquals(6_000L, byDay[expenseDay]!!.expenseCents)
        assertEquals(0L, byDay[refundDay]!!.incomeCents)
        val totals = CalendarMath.totals(rows)
        assertEquals(0L, totals.incomeCents)
        assertEquals(6_000L, totals.expenseCents)
    }

    @Test
    fun `退还超额_支出为0_超出部分计入日历收入`() {
        val day = LocalDate.of(2026, 10, 24)
        val rows = listOf(
            row(TxType.EXPENSE, 10_000, day, linkedRefundCents = 12_000),
            row(
                TxType.INCOME, 12_000, day, RefundMark.REFUND_INCOME,
                refundLinkId = 1, linkedExpenseCents = 10_000,
            ),
        )
        val byDay = CalendarMath.byDay(rows, zone)[day]!!
        assertEquals(2_000L, byDay.incomeCents)
        assertEquals(0L, byDay.expenseCents)
        assertEquals(2_000L, byDay.netCents)
    }

    @Test
    fun `不计入统计的记录不影响日历收支`() {
        val day = LocalDate.of(2026, 10, 24)
        val rows = listOf(
            row(TxType.INCOME, 100_000, day, countInStats = false),
            row(TxType.EXPENSE, 30_000, day, countInStats = false),
        )
        val byDay = CalendarMath.byDay(rows, zone)[day]!!
        assertEquals(0L, byDay.incomeCents)
        assertEquals(0L, byDay.expenseCents)
    }
}
