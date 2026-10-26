package com.controleco.app.data.backup

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 还原时退付款关联的修复：
 * id 原样保留 → 不改写；id 被重排 → 关联跟着指向新 id；指向不存在的支出 → 清零。
 */
class BackupRefundLinkTest {

    private fun expense(id: Long) = TransactionEntity(
        id = id,
        type = TxType.EXPENSE,
        amountCents = 2_000,
        categoryId = 1,
        accountId = 1,
        timestamp = 1_000L,
        refundMark = RefundMark.REFUNDED_EXPENSE,
    )

    private fun refund(id: Long, link: Long) = TransactionEntity(
        id = id,
        type = TxType.INCOME,
        amountCents = 2_000,
        categoryId = 1,
        accountId = 1,
        timestamp = 2_000L,
        refundMark = RefundMark.REFUND_INCOME,
        refundLinkId = link,
    )

    @Test
    fun `id 原样保留时无需改写关联`() {
        val txs = listOf(expense(10), refund(11, 10))
        assertTrue(BackupStore.remapRefundLinks(txs, listOf(10, 11)).isEmpty())
    }

    @Test
    fun `id 重排后关联指向新 id`() {
        val txs = listOf(expense(10), refund(11, 10))
        val fixed = BackupStore.remapRefundLinks(txs, listOf(3, 4))
        assertEquals(1, fixed.size)
        assertEquals(4L, fixed[0].id)
        assertEquals(3L, fixed[0].refundLinkId)
    }

    @Test
    fun `指向不存在支出的关联被清零`() {
        val txs = listOf(expense(10), refund(11, 99))
        val fixed = BackupStore.remapRefundLinks(txs, listOf(10, 11))
        assertEquals(1, fixed.size)
        assertEquals(11L, fixed[0].id)
        assertEquals(0L, fixed[0].refundLinkId)
    }

    @Test
    fun `只有支出能作为关联目标`() {
        // 关联指向另一条收入（异常数据）时清空
        val txs = listOf(refund(11, 12), refund(12, 0))
        val fixed = BackupStore.remapRefundLinks(txs, listOf(11, 12))
        assertEquals(1, fixed.size)
        assertEquals(0L, fixed[0].refundLinkId)
    }
}
