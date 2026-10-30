package com.controleco.app.capture

import com.controleco.app.data.db.TxType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同一家银行多通道去重（上游 v1.0.5 移植）：短信 ⇄ App 推送 ⇄ App 页面。
 * 同银行 + 同额 + 同向 + 同退款 + 尾号兼容才算同笔；1:1 配对避免吞掉两笔真实同额消费。
 */
class BankEventDedupTest {

    private val base = 1_000_000L

    private fun key(bank: String = "招商银行", amount: Long = 1290, tail: String? = "1234") =
        BankEventDedup.keyOf(bank, amount, TxType.EXPENSE, false, tail)!!

    @Test
    fun standardBankNameIsNormalized() {
        assertEquals("招商银行", BankEventDedup.standardBankName("招行"))
        assertTrue(BankEventDedup.matches(key(bank = "招行"), key(bank = "招商银行")))
    }

    @Test
    fun differentBankOrAmountDoesNotMatch() {
        assertFalse(BankEventDedup.matches(key(bank = "招商银行"), key(bank = "工商银行")))
        assertFalse(BankEventDedup.matches(key(amount = 1290), key(amount = 1990)))
    }

    @Test
    fun missingCardTailIsWildcard() {
        assertTrue(BankEventDedup.tailsCompatible(null, "1234"))
        assertTrue(BankEventDedup.tailsCompatible("1234", null))
        assertTrue(BankEventDedup.tailsCompatible("1234", "1234"))
        assertFalse(BankEventDedup.tailsCompatible("1234", "5678"))
    }

    @Test
    fun claimsPairDifferentChannels() {
        val claims = BankEventClaims()
        claims.claim(1, key(), BankEventDedup.Channel.SMS, base, now = base)
        assertEquals(
            1L,
            claims.find(key(), BankEventDedup.Channel.PUSH, base + 30_000, now = base + 30_000)!!,
        )
        assertNull(
            "同通道的重复由 bankInstanceId 负责",
            claims.find(key(), BankEventDedup.Channel.SMS, base + 30_000, now = base + 30_000),
        )
    }

    @Test
    fun claimsAreOneToOneWithinWindow() {
        val claims = BankEventClaims()
        claims.claim(1, key(), BankEventDedup.Channel.SMS, base, now = base)
        claims.claim(1, key(), BankEventDedup.Channel.PUSH, base, now = base)
        val at = base + 40_000
        // 第一条已吸收 SMS + PUSH：第二条真实同额推送不能被它认领（1:1 配对）
        assertNull(claims.find(key(), BankEventDedup.Channel.PUSH, at, now = at))
        claims.claim(2, key(), BankEventDedup.Channel.SMS, at, now = at)
        assertEquals(2L, claims.find(key(), BankEventDedup.Channel.PUSH, at, now = at)!!)
    }

    @Test
    fun claimsExpireOutsideWindow() {
        val claims = BankEventClaims()
        claims.claim(1, key(), BankEventDedup.Channel.SMS, base, now = base)
        val at = base + 200_000
        assertNull(claims.find(key(), BankEventDedup.Channel.PUSH, at, now = at))
    }

    @Test
    fun forgetDropsClaim() {
        val claims = BankEventClaims()
        claims.claim(1, key(), BankEventDedup.Channel.SMS, base, now = base)
        claims.forget(1)
        val at = base + 10_000
        assertNull(claims.find(key(), BankEventDedup.Channel.PUSH, at, now = at))
    }
}
