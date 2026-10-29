package com.controleco.app.capture.overlay

import com.controleco.app.capture.NotificationRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮确认卡的显示条件判定（纯函数）：任一条不满足都回落横幅通知（通知是兜底通道，永远保留）。
 */
class OverlayDecisionTest {

    private fun conditions(
        enabled: Boolean = true,
        canDrawOverlays: Boolean = true,
        appInForeground: Boolean = false,
        screenInteractive: Boolean = true,
        keyguardLocked: Boolean = false,
        foregroundPackage: String? = NotificationRules.WECHAT,
        sourcePackage: String = NotificationRules.WECHAT,
        foregroundIsPaymentContext: Boolean = false,
    ) = OverlayConditions(
        enabled = enabled,
        canDrawOverlays = canDrawOverlays,
        appInForeground = appInForeground,
        screenInteractive = screenInteractive,
        keyguardLocked = keyguardLocked,
        foregroundPackage = foregroundPackage,
        sourcePackage = sourcePackage,
        foregroundIsPaymentContext = foregroundIsPaymentContext,
    )

    @Test
    fun `支付应用前台且屏幕可用时弹卡`() {
        assertTrue(shouldShowOverlay(conditions()))
    }

    @Test
    fun `开关关闭不弹卡`() {
        assertFalse(shouldShowOverlay(conditions(enabled = false)))
    }

    @Test
    fun `没有悬浮窗权限不弹卡`() {
        assertFalse(shouldShowOverlay(conditions(canDrawOverlays = false)))
    }

    @Test
    fun `本应用在前台不弹卡（用应用内列表）`() {
        assertFalse(shouldShowOverlay(conditions(appInForeground = true)))
    }

    @Test
    fun `屏幕熄灭不弹卡`() {
        assertFalse(shouldShowOverlay(conditions(screenInteractive = false)))
    }

    @Test
    fun `锁屏不弹卡`() {
        assertFalse(shouldShowOverlay(conditions(keyguardLocked = true)))
    }

    @Test
    fun `判不出前台应用时放行`() {
        assertTrue(shouldShowOverlay(conditions(foregroundPackage = null)))
    }

    @Test
    fun `前台应用与来源不一致（已切到无关应用）不弹卡`() {
        assertFalse(
            shouldShowOverlay(
                conditions(foregroundPackage = NotificationRules.ALIPAY, sourcePackage = NotificationRules.WECHAT),
            ),
        )
    }

    @Test
    fun `前台是别的支付购物应用时仍弹卡（跳转支付）`() {
        // 京东下单 → 拉起微信支付：通知到达时前台是京东（监听目录内），仍在支付语境
        assertTrue(
            shouldShowOverlay(
                conditions(
                    foregroundPackage = "com.jingdong.app.mall",
                    sourcePackage = NotificationRules.WECHAT,
                    foregroundIsPaymentContext = true,
                ),
            ),
        )
    }

    @Test
    fun `银行短信到达时不做前台匹配（人在收银台或别处）`() {
        assertTrue(
            shouldShowOverlay(
                conditions(
                    foregroundPackage = NotificationRules.WECHAT,
                    sourcePackage = "SMS:工商银行",
                ),
            ),
        )
    }

    @Test
    fun `银行推送到达时不做前台匹配`() {
        assertTrue(
            shouldShowOverlay(
                conditions(
                    foregroundPackage = "com.tencent.mm",
                    sourcePackage = "com.icbc",
                ),
            ),
        )
    }

    // ---------- 系统弹层/桌面（支付保护、安全键盘、付完回桌面） ----------

    @Test
    fun `系统弹层占据前台时按判不出处理（支付保护或安全键盘）`() {
        // 支付瞬间前台窗口常是系统组件：此时不能因"前台与来源不符"把卡片拒掉
        assertTrue(
            shouldShowOverlay(
                conditions(foregroundPackage = "com.android.systemui", sourcePackage = NotificationRules.ALIPAY),
            ),
        )
        assertTrue(
            shouldShowOverlay(
                conditions(foregroundPackage = "com.miui.securitycenter", sourcePackage = NotificationRules.ALIPAY),
            ),
        )
    }

    @Test
    fun `付完款回到桌面时放行`() {
        assertTrue(
            shouldShowOverlay(
                conditions(foregroundPackage = "com.miui.home", sourcePackage = NotificationRules.ALIPAY),
            ),
        )
        assertTrue(
            shouldShowOverlay(
                conditions(foregroundPackage = "com.huawei.android.launcher", sourcePackage = NotificationRules.WECHAT),
            ),
        )
    }

    @Test
    fun `别的普通应用在前台仍然不弹卡`() {
        // 系统弹层之外的陌生应用（用户已切走）依旧不打扰
        assertFalse(
            shouldShowOverlay(
                conditions(foregroundPackage = "com.xingin.xhs", sourcePackage = NotificationRules.ALIPAY),
            ),
        )
    }

    @Test
    fun `系统界面识别按包含匹配`() {
        assertTrue(isSystemSurface("com.android.systemui"))
        assertTrue(isSystemSurface("com.miui.home"))
        assertTrue(isSystemSurface("com.sec.android.app.launcher"))
        assertTrue(isSystemSurface("com.miui.securitycenter"))
        assertFalse(isSystemSurface("com.eg.android.AlipayGphone"))
        assertFalse(isSystemSurface("com.tencent.mm"))
    }

    // ---------- 前台包名的有效期（无障碍只在窗口切换时上报） ----------

    @Test
    fun `刚刚上报的前台包名有效`() {
        assertEquals(
            NotificationRules.WECHAT,
            effectiveForegroundPackage(NotificationRules.WECHAT, seenAtElapsed = 1_000L, nowElapsed = 21_000L),
        )
    }

    @Test
    fun `超过有效期或从未上报视为判不出（放行弹卡）`() {
        assertNull(
            effectiveForegroundPackage(
                NotificationRules.WECHAT,
                seenAtElapsed = 1_000L,
                nowElapsed = 1_000L + FOREGROUND_STALE_MS + 1,
            ),
        )
        assertNull(effectiveForegroundPackage(null, seenAtElapsed = 0L, nowElapsed = 1_000L))
        assertNull(effectiveForegroundPackage(NotificationRules.WECHAT, seenAtElapsed = 0L, nowElapsed = 1_000L))
    }

    @Test
    fun `过期前台包名不再挡住悬浮卡（按判不出放行）`() {
        // 无障碍的窗口事件丢失后前台包名会一直停在某个无关应用上：
        // 若不做有效期判定，之后的卡片会全部被"前台不符"拒绝
        val stale = effectiveForegroundPackage(
            "com.xingin.xhs", seenAtElapsed = 0L, nowElapsed = FOREGROUND_STALE_MS + 1,
        )
        assertNull(stale)
        assertTrue(shouldShowOverlay(conditions(foregroundPackage = stale, sourcePackage = NotificationRules.ALIPAY)))
    }
}

/**
 * 关闭卡片的处置判定：关闭（✕/点卡片外/长时间无操作）= 视为入账，只有点「忽略」才不入账；
 * 金额缺失或分类/账户不全时不能入账，保留待确认。
 */
class OverlayDismissOutcomeTest {

    private fun snapshot(
        amountCents: Long = 2060L,
        categoryId: Long? = 3L,
        accountId: Long? = 1L,
        counterparty: String = "示例商户",
    ) = DismissSnapshot(amountCents, categoryId, accountId, counterparty)

    @Test
    fun `金额分类账户齐备时关闭即入账`() {
        assertSame(DismissOutcome.ENTER, dismissOutcome(snapshot()))
    }

    @Test
    fun `金额为 0 的待补录条目关闭不入账（保留待确认）`() {
        assertSame(DismissOutcome.KEEP_PENDING, dismissOutcome(snapshot(amountCents = 0L)))
    }

    @Test
    fun `缺分类或账户时保留待确认`() {
        assertSame(DismissOutcome.KEEP_PENDING, dismissOutcome(snapshot(categoryId = null)))
        assertSame(DismissOutcome.KEEP_PENDING, dismissOutcome(snapshot(accountId = null)))
    }

    @Test
    fun `金额非法（负数）不入账`() {
        assertSame(DismissOutcome.KEEP_PENDING, dismissOutcome(snapshot(amountCents = -1L)))
    }

    // ---------- 已入账卡片（先入账、后修改）的编辑同步判定 ----------

    @Test
    fun `已入账条目：卡内值齐备可同步回账目`() {
        assertTrue(canSyncOverlayEdits(snapshot()))
    }

    @Test
    fun `已入账条目：金额被清空则保持原记录不动`() {
        assertFalse(canSyncOverlayEdits(snapshot(amountCents = 0L)))
    }

    @Test
    fun `已入账条目：分类或账户缺失则保持原记录不动`() {
        assertFalse(canSyncOverlayEdits(snapshot(categoryId = null)))
        assertFalse(canSyncOverlayEdits(snapshot(accountId = null)))
    }

    @Test
    fun `关闭快照携带是否改过标记`() {
        assertTrue(snapshot().copy(edited = true).edited)
        assertFalse(snapshot().edited)
    }
}

/**
 * 「已记入 · 撤销」细条的停留时长：默认 6 秒，设置里可选 3/6/10/15/30 秒；
 * 异常值（0、负值、特别大）回落到默认，避免细条瞬间收掉或一直挂着。
 */
class SavedStripDismissTest {

    @Test
    fun `默认时长在可选项里`() {
        assertEquals(6_000L, SAVED_STRIP_DISMISS_MS)
        assertTrue(SAVED_STRIP_DISMISS_MS in SAVED_STRIP_DISMISS_OPTIONS_MS)
    }

    @Test
    fun `可选项都原样保留`() {
        SAVED_STRIP_DISMISS_OPTIONS_MS.forEach { ms ->
            assertEquals(ms, clampSavedStripDismissMs(ms))
        }
    }

    @Test
    fun `异常值回落到默认`() {
        assertEquals(SAVED_STRIP_DISMISS_MS, clampSavedStripDismissMs(0L))
        assertEquals(SAVED_STRIP_DISMISS_MS, clampSavedStripDismissMs(-1L))
        assertEquals(SAVED_STRIP_DISMISS_MS, clampSavedStripDismissMs(999L))
        assertEquals(SAVED_STRIP_DISMISS_MS, clampSavedStripDismissMs(60_001L))
    }
}

/**
 * 「已成功记录一笔」提示条与「已记入 · 撤销」细条的停留时长**分开控制**：
 * 两者各有独立的默认值/可选项/取值校验，互不影响。
 */
class RecordedPromptDismissTest {

    @Test
    fun `默认时长在可选项里`() {
        assertEquals(5_000L, RECORDED_PROMPT_DISMISS_MS)
        assertTrue(RECORDED_PROMPT_DISMISS_MS in RECORDED_PROMPT_DISMISS_OPTIONS_MS)
    }

    @Test
    fun `可选项都原样保留`() {
        RECORDED_PROMPT_DISMISS_OPTIONS_MS.forEach { ms ->
            assertEquals(ms, clampRecordedPromptDismissMs(ms))
        }
    }

    @Test
    fun `异常值回落到默认`() {
        assertEquals(RECORDED_PROMPT_DISMISS_MS, clampRecordedPromptDismissMs(0L))
        assertEquals(RECORDED_PROMPT_DISMISS_MS, clampRecordedPromptDismissMs(-1L))
        assertEquals(RECORDED_PROMPT_DISMISS_MS, clampRecordedPromptDismissMs(999L))
        assertEquals(RECORDED_PROMPT_DISMISS_MS, clampRecordedPromptDismissMs(60_001L))
    }

    @Test
    fun `与撤销条互不干扰`() {
        // 改「已成功记录一笔」的时长不会把撤销条的默认值带走
        assertEquals(6_000L, clampSavedStripDismissMs(SAVED_STRIP_DISMISS_MS))
        assertEquals(5_000L, clampRecordedPromptDismissMs(RECORDED_PROMPT_DISMISS_MS))
        // 两者都是合法值时各自保留，不会互相覆盖
        assertEquals(2_000L, clampRecordedPromptDismissMs(2_000L))
        assertEquals(30_000L, clampSavedStripDismissMs(30_000L))
    }

    @Test
    fun `可选项均为正且升序`() {
        RECORDED_PROMPT_DISMISS_OPTIONS_MS.zipWithNext().forEach { (a, b) ->
            assertTrue("${a}ms 与 ${b}ms 应递增", b > a)
        }
        RECORDED_PROMPT_DISMISS_OPTIONS_MS.forEach { assertTrue(it >= 1_000L) }
    }
}
