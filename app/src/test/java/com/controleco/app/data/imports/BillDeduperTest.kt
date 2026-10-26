package com.controleco.app.data.imports

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.data.db.TxType
import com.controleco.app.data.model.ImportedBillRow
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** 账单导入去重单测：只跳过账本里确实已有的记录，金额/方向不同的一律保留。 */
class BillDeduperTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun millis(hour: Int, minute: Int, day: Int = 20): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun existing(
        type: TxType = TxType.EXPENSE,
        cents: Long = 1000,
        at: Long = millis(12, 0),
        counterparty: String = "",
        note: String = "",
        externalId: String? = null,
    ) = TransactionEntity(
        type = type,
        amountCents = cents,
        categoryId = 1,
        accountId = 1,
        timestamp = at,
        note = note,
        counterparty = counterparty,
        externalId = externalId,
    )

    private fun billRow(
        type: TxType = TxType.EXPENSE,
        cents: Long = 1000,
        at: Long = millis(12, 0),
        counterparty: String = "",
        goods: String = "",
        note: String = "",
        externalId: String? = null,
    ) = ImportedBillRow(
        type = type,
        amountCents = cents,
        timestamp = at,
        counterparty = counterparty,
        goods = goods,
        note = note,
        payMethod = "",
        externalId = externalId,
        guessedCategoryName = "其他",
        refundMark = RefundMark.NONE,
    )

    @Test
    fun `外部单号相同直接判重`() {
        val result = BillDeduper.partition(
            rows = listOf(billRow(externalId = "WX-001")),
            existing = listOf(existing(externalId = "WX-001")),
            zone = zone,
        )
        assertEquals(0, result.newRows.size)
        assertEquals(1, result.duplicateCount)
    }

    @Test
    fun `同方向同金额且时间相近判重`() {
        val result = BillDeduper.partition(
            rows = listOf(billRow(at = millis(12, 5), counterparty = "便利店")),
            existing = listOf(existing(at = millis(12, 0), counterparty = "便利店")),
            zone = zone,
        )
        assertEquals(0, result.newRows.size)
        assertEquals(1, result.duplicateCount)
    }

    @Test
    fun `同一天同商户但时间相差较远也判重`() {
        val result = BillDeduper.partition(
            rows = listOf(billRow(at = millis(18, 30), counterparty = "美团平台商户")),
            existing = listOf(existing(at = millis(12, 0), counterparty = "美团")),
            zone = zone,
        )
        assertEquals(0, result.newRows.size)
        assertEquals(1, result.duplicateCount)
    }

    @Test
    fun `同金额同时间但商户不同仍作为新记录`() {
        val result = BillDeduper.partition(
            rows = listOf(billRow(at = millis(18, 30), counterparty = "肯德基")),
            existing = listOf(existing(at = millis(12, 0), counterparty = "麦当劳")),
            zone = zone,
        )
        assertEquals(1, result.newRows.size)
        assertEquals(0, result.duplicateCount)
    }

    @Test
    fun `金额相同但收支配方向不同不判重`() {
        val result = BillDeduper.partition(
            rows = listOf(billRow(type = TxType.INCOME, at = millis(12, 1))),
            existing = listOf(existing(type = TxType.EXPENSE, at = millis(12, 0))),
            zone = zone,
        )
        assertEquals(1, result.newRows.size)
        assertEquals(0, result.duplicateCount)
    }

    @Test
    fun `多条同额记录按条数一对一抵消`() {
        val result = BillDeduper.partition(
            rows = listOf(
                billRow(at = millis(9, 0), counterparty = "起点读书"),
                billRow(at = millis(9, 30), counterparty = "起点读书"),
                billRow(at = millis(10, 0), counterparty = "起点读书"),
            ),
            existing = listOf(
                existing(at = millis(9, 2), counterparty = "起点读书"),
                existing(at = millis(9, 32), counterparty = "起点读书"),
            ),
            zone = zone,
        )
        assertEquals(1, result.newRows.size)
        assertEquals(2, result.duplicateCount)
    }

    @Test
    fun `文件内同一单号出现两次时只保留一条`() {
        val result = BillDeduper.partition(
            rows = listOf(
                billRow(externalId = "ALI-001"),
                billRow(externalId = "ALI-001"),
            ),
            existing = emptyList(),
            zone = zone,
        )
        assertEquals(1, result.newRows.size)
        assertEquals(1, result.duplicateCount)
    }

    @Test
    fun `脱敏账号别名不参与商户匹配`() {
        val result = BillDeduper.partition(
            rows = listOf(billRow(at = millis(15, 0), counterparty = "tb**1")),
            existing = listOf(existing(at = millis(12, 0), counterparty = "tb**2")),
            zone = zone,
        )
        assertEquals(1, result.newRows.size)
        assertEquals(0, result.duplicateCount)
    }
}
