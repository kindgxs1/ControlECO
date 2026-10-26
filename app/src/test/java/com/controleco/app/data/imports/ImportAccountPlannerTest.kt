package com.controleco.app.data.imports

import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType
import com.controleco.app.data.model.BillSource
import com.controleco.app.data.model.ImportedBillRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入账单的账户自动匹配：
 * - 银行卡按「银行 + 尾号」匹配到同名账户；
 * - 余额宝 / 零钱按渠道匹配到对应钱包账户；
 * - 账户里没有的银行卡不做渠道兜底，保持未匹配等用户确认。
 */
class ImportAccountPlannerTest {

    private val accounts = listOf(
        AccountEntity(id = 1, name = "现金"),
        AccountEntity(id = 2, name = "微信零钱"),
        AccountEntity(id = 3, name = "支付宝"),
        AccountEntity(id = 4, name = "华夏银行信用卡(4362)"),
    )

    private fun row(pay: String, type: TxType = TxType.EXPENSE) = ImportedBillRow(
        type = type,
        amountCents = 1_000,
        timestamp = 0,
        counterparty = "测试商户",
        goods = "商品",
        note = "",
        payMethod = pay,
        externalId = null,
        guessedCategoryName = "餐饮",
        refundMark = RefundMark.NONE,
    )

    private fun plan(vararg pays: String, source: BillSource = BillSource.ALIPAY) =
        ImportAccountPlanner.plan(source, pays.map { row(it) }, accounts)

    @Test
    fun `银行卡按尾号自动匹配账户`() {
        val g = plan("华夏银行信用卡(4362)").groups.single()
        assertTrue(g.matched)
        assertEquals(4L, g.accountId)
        assertEquals("华夏银行信用卡(4362)", g.accountName)
        assertEquals(1, g.rowCount)
    }

    @Test
    fun `钱包与渠道产品自动匹配账户`() {
        val alipay = plan("余额宝", "余额宝&碰一下立减").groups
        assertTrue(alipay.all { it.matched })
        assertTrue(alipay.all { it.accountId == 3L })

        val wechat = plan("零钱", source = BillSource.WECHAT).groups.single()
        assertTrue(wechat.matched)
        assertEquals(2L, wechat.accountId)
    }

    @Test
    fun `账户里没有的银行卡不按渠道兜底`() {
        val p = plan("工商银行储蓄卡(7492)")
        val g = p.groups.single()
        assertFalse(g.matched)
        assertNull(g.accountId)
        assertEquals(1, p.unmatchedRows)
        // 未确认前不可导入
        assertFalse(p.isResolved(emptyMap()))
    }

    @Test
    fun `未匹配的支付方式确认后才能导入_并支持批量`() {
        val p = plan("工商银行储蓄卡(7492)", "华夏银行信用卡(4362)")
        // 银行卡已自动匹配，只剩一种未匹配
        assertEquals(1, p.groups.count { !it.matched })

        val manual = p.groups.filter { !it.matched }.associate { it.payMethod to 3L }
        assertTrue(p.isResolved(manual))
        val assignments = p.assignments(manual)
        assertEquals(2, assignments.size)
        assertEquals(4L, assignments["华夏银行信用卡(4362)"])
        assertEquals(3L, assignments["工商银行储蓄卡(7492)"])
    }

    @Test
    fun `同一支付方式只分一组_确认一次即可`() {
        val p = plan("工商银行储蓄卡(7492)", "工商银行储蓄卡(7492)", "工商银行储蓄卡(7492)")
        val g = p.groups.single()
        assertEquals(3, g.rowCount)
        assertEquals(3, p.unmatchedRows)
    }

    @Test
    fun `账单没写支付方式时保持未匹配`() {
        val p = plan("", "")
        val g = p.groups.single()
        assertFalse(g.matched)
        assertEquals("账单未写支付方式", g.label)
        assertEquals(2, p.unmatchedRows)
    }
}
