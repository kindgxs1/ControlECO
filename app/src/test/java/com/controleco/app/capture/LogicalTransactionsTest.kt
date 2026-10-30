package com.controleco.app.capture

import com.controleco.app.data.db.PendingStatus
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.TxType
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 终态记忆（上游 v1.0.5 移植）：同一笔确认/忽略后，捕获窗口内后到的重复捕获必须被吸收，
 * 而金额不同、通道实例身份不同的真实交易绝不能被吞掉。
 */
class LogicalTransactionsTest {

    private val base = 1_000_000L

    @Before
    fun reset() = LogicalTransactions.clearAll()

    @After
    fun clean() = LogicalTransactions.clearAll()

    private fun pending(
        createdAt: Long = base,
        identity: String = "n:abc",
        amount: Long = 1290,
        status: PendingStatus = PendingStatus.PENDING,
    ) = PendingTransactionEntity(
        id = 1,
        type = TxType.EXPENSE,
        amountCents = amount,
        timestamp = createdAt,
        note = "微信支付",
        sourcePackage = NotificationRules.WECHAT,
        ruleName = "微信·支付",
        dedupKey = "${NotificationRules.WECHAT}|EXPENSE|$amount|${createdAt / 60_000}@$identity",
        notificationInstanceId = identity,
        status = status,
        createdAt = createdAt,
    )

    private fun observation(
        capturedAt: Long,
        identity: String = "n:abc",
        amount: Long = 1290,
    ) = CaptureObservation(
        CrossAppLinker.Evidence(
            sourcePackage = NotificationRules.WECHAT,
            amountCents = amount,
            type = TxType.EXPENSE,
            isRefund = false,
            time = capturedAt,
            note = "微信支付",
            ruleName = "微信·支付",
        ),
        identity = identity,
        dedupKey = "${NotificationRules.WECHAT}|EXPENSE|$amount|${capturedAt / 60_000}@$identity",
    )

    @Test
    fun resolvedEntryAbsorbsLateDuplicate() {
        LogicalTransactions.markResolved(pending(), CaptureResolution.HANDLED, now = base)
        assertTrue(
            "窗口内同笔的迟到重复应被终态吸收",
            LogicalTransactions.findResolved(observation(base + 30_000), base + 30_000) != null,
        )
    }

    @Test
    fun differentAmountIsAnotherTransaction() {
        LogicalTransactions.markResolved(pending(), CaptureResolution.HANDLED, now = base)
        assertNull(
            "金额不同绝不能被终态记忆吞掉",
            LogicalTransactions.findResolved(observation(base + 30_000, amount = 990), base + 30_000),
        )
    }

    @Test
    fun differentNotificationInstanceIsAnotherTransaction() {
        LogicalTransactions.markResolved(pending(), CaptureResolution.HANDLED, now = base)
        assertNull(
            "另一条通知实例是第二笔真实交易",
            LogicalTransactions.findResolved(observation(base + 30_000, identity = "n:def"), base + 30_000),
        )
    }

    @Test
    fun outsideWindowIsNotAbsorbed() {
        LogicalTransactions.markResolved(pending(), CaptureResolution.HANDLED, now = base)
        assertNull(
            "超出捕获窗口后不再吸收",
            LogicalTransactions.findResolved(observation(base + 200_000), base + 200_000),
        )
    }

    @Test
    fun rejectedEntryIsRemembered() {
        LogicalTransactions.markResolved(pending(), CaptureResolution.REJECTED, now = base)
        val hit = LogicalTransactions.findResolved(observation(base + 10_000), base + 10_000)
        assertTrue(hit != null && hit.resolution == CaptureResolution.REJECTED)
    }

    @Test
    fun forgetClearsTerminalMemory() {
        LogicalTransactions.markResolved(pending(), CaptureResolution.HANDLED, now = base)
        val key = pending().dedupKey
        LogicalTransactions.forget(key)
        assertNull(LogicalTransactions.findResolved(observation(base + 10_000), base + 10_000))
    }

    @Test
    fun findActiveNeedsUniqueCandidate() {
        val one = pending()
        assertSame(one, LogicalTransactions.findActive(listOf(one), observation(base + 5_000), base + 5_000))
        val second = pending().copy(id = 2)
        assertNull(
            "两个同额候选不自动合并（防止串账）",
            LogicalTransactions.findActive(listOf(one, second), observation(base + 5_000), base + 5_000),
        )
    }

    @Test
    fun askEntryNeverAutoAbsorbed() {
        val ask = pending().copy(
            status = PendingStatus.ASK,
            dedupKey = "ASK|${NotificationRules.WECHAT}|EXPENSE|1290@n:abc",
        )
        LogicalTransactions.markResolved(ask, CaptureResolution.REJECTED, now = base)
        assertNull(
            "微信红包 ASK 条目不能被同额转账自动认领",
            LogicalTransactions.findResolved(observation(base + 10_000), base + 10_000),
        )
    }
}
