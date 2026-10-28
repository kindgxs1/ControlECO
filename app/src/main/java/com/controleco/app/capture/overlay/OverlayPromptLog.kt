package com.controleco.app.capture.overlay

import com.controleco.app.util.AppLog

/**
 * 同一笔待确认条目**只成功展示一次**悬浮卡的登记簿（纯内存）。
 *
 * 问题：一笔消费被多个通道先后捕获时，首次弹卡后，后续通道（页面回填 / 银行动账）会再次调用
 * `retryCardIfNoCard` 补弹——用户在电商结果页看到「第二张卡」会以为又抓了一笔，点「忽略」
 * 就把唯一那笔已入账的账删掉。
 *
 * 规则：
 * - 按 `dedupKey` 登记「**成功展示过**」（`addView` 成功才算，被支付保护层拦截不算）；
 * - 后续富化时若已展示过，只静默刷新正在显示的卡片 / 回填已入账条目，不再补弹；
 * - 确认入账 / 忽略 / 撤销时清理，避免同一 `dedupKey`（id 会被复用）的新交易被误判为已展示；
 * - 上限 500 条，进程重启后丢失（可接受的边界：重启后同笔重新弹一次无妨）。
 */
internal object OverlayPromptLog {
    private const val MAX = 500
    private val shown = LinkedHashSet<String>()

    @Synchronized
    fun markShown(dedupKey: String) {
        shown.add(dedupKey)
        while (shown.size > MAX) {
            shown.remove(shown.first())
        }
    }

    @Synchronized
    fun wasShown(dedupKey: String): Boolean = shown.contains(dedupKey)

    @Synchronized
    fun clear(dedupKey: String) {
        shown.remove(dedupKey)
    }

    @Synchronized
    fun clearAll() {
        shown.clear()
        AppLog.d("Overlay", "prompt log cleared")
    }
}
