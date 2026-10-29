package com.controleco.app.capture.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.controleco.app.capture.GuardedField
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.TxType
import com.controleco.app.util.AppLog
import com.controleco.app.util.Money

/**
 * 悬浮确认卡（底部卡片）与「已记入 · 撤销」细条的窗口宿主。
 *
 * 设计要点（见 docs/自动记账捕获机制.md 第二节）：
 * - **底部**卡片：顶部 placement 在 MIUI 上会被压到状态栏下方（v0.4.13 灵动岛失败的原因），
 *   底部卡片天然绕开该问题；
 * - 全屏透明窗口 + `NOT_TOUCH_MODAL|LAYOUT_IN_SCREEN|LAYOUT_INSET_DECOR|WATCH_OUTSIDE_TOUCH`：
 *   **卡片外**的透明区点按 = 关闭卡片（关闭 = 视为入账，卡内当前值经 [showConfirm] 的 onDismiss 交回调用方）；
 *   卡片内部由卡片自己消费点按，空白区不会再误触关闭（P0 修复）；
 * - 窗口可聚焦，卡内输入框可直接调键盘（悬浮条 [showSaved] 仍是 NOT_FOCUSABLE，不抢 IME）；
 * - `addView` 抛异常（无权限/被支付保护层拦截）→ [showConfirm] 返回 false，调用方回落横幅通知
 *   （Toast 可见而卡片不可见 = 被拦截的铁证，v0.4.13 起用这套判定）。
 *
 * UI 全部用本应用自己的 Compose 设计组件（[ConfirmCardContent]）。
 */
internal object ConfirmCardWindow {

    private const val TAG = "Overlay"

    private lateinit var appContext: Context
    private val handler = Handler(Looper.getMainLooper())

    private var confirmView: ComposeView? = null
    private var confirmHost: OverlayComposeHost? = null
    private var savedView: ComposeView? = null
    private var savedHost: OverlayComposeHost? = null
    private var recordedView: ComposeView? = null
    private var recordedHost: OverlayComposeHost? = null

    private var autoDismiss: Runnable? = null
    private var savedDismiss: Runnable? = null
    private var recordedDismiss: Runnable? = null
    private var dismissCallback: ((DismissSnapshot) -> Unit)? = null

    /** 卡片当前展示的待确认条目 id（-1 = 未展示） */
    var showingPendingId: Long = -1L
        private set

    /** 卡片内容状态（编辑中的金额/分类/账户都在这） */
    val state = ConfirmCardState()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isVisible(): Boolean = confirmView != null || savedView != null || recordedView != null

    // ---------- 确认卡 ----------

    /**
     * 展示确认卡。返回 false = 窗口没弹出来（无权限/被拦截），调用方必须回落通知。
     * [onConfirm] 的参数：金额分、分类 id、账户 id、商户/备注、是否不计入资产统计、是否计入收支统计；
     * [onDismiss] = 关闭（✕ / 点卡片**外**的透明区 / 标题与把手下拉 / 60s 无操作），携带卡内当前值
     * —— 关闭视为入账，由调用方按 [dismissOutcome] 决定落账或保留待确认。
     */
    fun showConfirm(
        pending: PendingTransactionEntity,
        categories: List<CategoryEntity>,
        accounts: List<AccountEntity>,
        learnedRules: List<com.controleco.app.data.db.MerchantRuleEntity> = emptyList(),
        alreadyEntered: Boolean = false,
        askMode: Boolean = false,
        onConfirm: (
            amountCents: Long,
            categoryId: Long,
            accountId: Long,
            counterparty: String,
            note: String,
            excludeFromAssets: Boolean,
            countInStats: Boolean,
            editedFields: Set<GuardedField>,
        ) -> Unit,
        onIgnore: () -> Unit,
        onDismiss: (DismissSnapshot) -> Unit,
    ): Boolean {
        if (!this::appContext.isInitialized) return false
        removeConfirmWindow() // 重入时先清旧窗口（新的一笔直接替换展示）

        state.bind(pending, categories, accounts, learnedRules)

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val host = OverlayComposeHost()
        val view = buildOverlayComposeView(appContext, host) {
            ConfirmCardContent(
                state = state,
                alreadyEntered = alreadyEntered,
                askMode = askMode,
                onConfirm = { amountCents, categoryId, accountId, counterparty, note, excludeFromAssets, countInStats, editedFields ->
                    onConfirm(amountCents, categoryId, accountId, counterparty, note, excludeFromAssets, countInStats, editedFields)
                },
                onIgnore = onIgnore,
                onDismiss = { handleDismiss() },
                // P0：任何触摸/拖动/字段编辑都重置 60s 自动收起计时，避免编辑中途被收起
                onUserActivity = { noteUserActivity() },
            )
        }
        // 注意：确认卡内有可编辑的金额 / 商户备注，窗口必须保持可聚焦（否则输入法无法弹出），
        // 因此这里不加 FLAG_NOT_FOCUSABLE；「已记入 · 撤销」细条才是 NOT_FOCUSABLE。
        // 卡外点击由 ConfirmCardContent 的透明遮罩处理，卡内点击由卡片自己消费。
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        var added = false
        return try {
            wm.addView(view, params)
            added = true
            host.attach()
            confirmView = view
            confirmHost = host
            showingPendingId = pending.id
            dismissCallback = onDismiss
            scheduleAutoDismiss()
            // 成功展示过一次后，后续通道（页面回填 / 银行动账）只静默富化，不再补弹第二张卡
            OverlayPromptLog.markShown(pending.dedupKey)
            AppLog.i(TAG, "confirm card shown pending=#${pending.id} ${pending.ruleName}")
            true
        } catch (e: Exception) {
            // 无悬浮窗权限 / 被支付保护层拦截：调用方回落通知（Toast 可见而卡片不可见 = 被拦截的铁证）。
            // 注意 addView 成功但后续步骤抛异常时必须把窗口移除，否则会留下一个看不见的全屏
            // 透明窗口一直吃掉触摸事件（表现为"应用卡死/点不动"）。
            AppLog.w(TAG, "confirm card addView failed, fallback to notification", e)
            if (added) runCatching { wm.removeViewImmediate(view) }
            runCatching { host.destroy() }
            false
        }
    }

    /**
     * 后续捕获把同笔条目富化后（页面合并/跨通道归并）刷新卡片；
     * 自动回填只在用户没有手改过该字段时生效（用户手改过的值优先）。
     */
    fun updatePending(
        pending: PendingTransactionEntity,
        categories: List<CategoryEntity>,
        accounts: List<AccountEntity>,
        learnedRules: List<com.controleco.app.data.db.MerchantRuleEntity> = emptyList(),
    ) {
        if (confirmView == null || showingPendingId != pending.id) return
        state.mergeFrom(pending, categories, accounts, learnedRules)
        AppLog.i(TAG, "confirm card updated pending=#${pending.id}")
    }

    /**
     * 移除确认卡，**不触发** onDismiss（数据不动）：进入应用、关闭「悬浮窗入账」开关等场景用。
     * 返回 true = 之前确实有卡片在展示。用户主动关闭（✕/卡片外空白/下拉/超时）走 [handleDismiss]。
     */
    fun dismissConfirm(): Boolean {
        val wasVisible = confirmView != null
        removeConfirmWindow()
        return wasVisible
    }

    // ---------- 顶端细条（已记入 · 撤销 / 待确认 · 处理） ----------

    /**
     * 确认入账后的非阻塞细条：**屏幕顶端**小条 + 撤销按钮，[dismissMs] 后自动消失。
     * 返回 false = 窗口没弹出来（无权限/被拦截），调用方需保留通知兜底。
     */
    fun showSaved(
        type: TxType,
        amountCents: Long,
        dismissMs: Long = SAVED_STRIP_DISMISS_MS,
        onUndo: () -> Unit,
    ): Boolean = showTopStrip(
        text = "已记入 " + (if (type == TxType.EXPENSE) "支出 " else "收入 ") + Money.formatYuan(amountCents),
        actionLabel = "撤销",
        durationMs = clampSavedStripDismissMs(dismissMs),
        onAction = onUndo,
    )

    /**
     * 待确认细条：确认卡没能弹出来时的**可视兜底**。
     * 不依赖通知权限（通知被关/被拒时用户完全看不到这笔捕获），只在屏幕顶端占一行；
     * 点「处理」= 直接在应用内弹出确认卡。返回 false = 窗口没弹出来（无悬浮窗权限/被拦截）。
     */
    fun showPending(type: TxType, amountCents: Long, counterparty: String, onClick: () -> Unit): Boolean {
        val who = counterparty.trim().take(12)
        return showTopStrip(
            text = "待确认 " + (if (type == TxType.EXPENSE) "支出 " else "收入 ") + Money.formatYuan(amountCents) +
                (if (who.isNotEmpty()) " · $who" else ""),
            actionLabel = "处理",
            durationMs = PENDING_STRIP_DISMISS_MS,
            onAction = onClick,
        )
    }

    // ---------- 自动入账成功提示（置顶细条 · 可修改） ----------

    /**
     * 自动入账成功后的**屏幕顶端细条**：「已成功记录一笔 支出 ¥20.06 · 修改」。
     * - 只保留「修改」一个动作（弹出该笔的编辑卡）；超时自动消失 = 保留这笔。
     * - 形态与「已记入 · 撤销」细条完全一致（[SavedStripContent]），非阻塞、不抢焦点。
     * - [dismissMs] 由设置里独立的「已成功记录一笔提示停留时长」控制。
     * 返回 false = 窗口没弹出来（无权限/被拦截），调用方需保留通知兜底。
     */
    fun showRecordedPrompt(
        type: TxType,
        amountCents: Long,
        dismissMs: Long = RECORDED_PROMPT_DISMISS_MS,
        onModify: () -> Unit,
    ): Boolean {
        if (!this::appContext.isInitialized) return false
        val durationMs = clampRecordedPromptDismissMs(dismissMs)
        removeRecordedWindow()
        removeSavedWindow() // 互斥：与「已记入 · 撤销」细条不同时出现

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val host = OverlayComposeHost()
        val kind = if (type == TxType.EXPENSE) "支出" else "收入"
        val view = buildOverlayComposeView(appContext, host) {
            SavedStripContent(
                // Money.formatYuan 自带货币符号，这里不要再拼 ¥（否则出现「¥¥」）
                text = "已成功记录一笔 $kind ${Money.formatYuan(amountCents)}",
                actionLabel = "修改",
                durationMs = durationMs,
                onAction = {
                    removeRecordedWindow()
                    onModify()
                },
            )
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // 与「已记入 · 撤销」细条同一位置：顶端居中，落在状态栏下方
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight() + dp(8)
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        return try {
            wm.addView(view, params)
            host.attach()
            recordedView = view
            recordedHost = host
            val r = Runnable { removeRecordedWindow() }
            recordedDismiss = r
            if (durationMs > 0L) handler.postDelayed(r, durationMs)
            AppLog.i(TAG, "recorded strip shown (${durationMs}ms)")
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "recorded strip addView failed", e)
            runCatching { host.destroy() }
            false
        }
    }

    /** 顶端细条通用实现（「已记入 · 撤销」与「待确认 · 处理」共用同一套窗口、计时与兜底） */
    private fun showTopStrip(
        text: String,
        actionLabel: String,
        durationMs: Long,
        onAction: () -> Unit,
    ): Boolean {
        if (!this::appContext.isInitialized) return false
        removeSavedWindow()

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val host = OverlayComposeHost()
        val view = buildOverlayComposeView(appContext, host) {
            SavedStripContent(
                text = text,
                actionLabel = actionLabel,
                durationMs = durationMs,
                onAction = {
                    onAction()
                    removeSavedWindow()
                },
            )
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // 顶端居中：落在状态栏下方，避开系统图标与刘海
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight() + dp(8)
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        return try {
            wm.addView(view, params)
            host.attach()
            savedView = view
            savedHost = host
            val r = Runnable { removeSavedWindow() }
            savedDismiss = r
            if (durationMs > 0L) handler.postDelayed(r, durationMs)
            AppLog.i(TAG, "top strip shown: $actionLabel (${durationMs}ms)")
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "top strip addView failed", e)
            runCatching { host.destroy() }
            false
        }
    }

    /** 收起全部窗口（进入应用、屏幕关闭等场景） */
    fun dismissAll() {
        removeConfirmWindow()
        removeSavedWindow()
        removeRecordedWindow()
    }

    // ---------- 内部 ----------

    /**
     * ✕ / 卡片外空白 / 把标题（或把手）向下拖 / 长时间无操作：关闭卡片。
     * 卡内当前值（含"是否改过"标记）打包成 [DismissSnapshot] 交给 onDismiss；
     * 窗口先移除再回调（回调可能要弹「已记入」细条）。
     */
    private fun handleDismiss() {
        val cb = dismissCallback ?: return
        val snapshot = DismissSnapshot(
            amountCents = state.amountCents ?: 0L,
            categoryId = state.categoryId,
            accountId = state.accountId,
            counterparty = state.counterparty,
            note = state.note,
            excludeFromAssets = state.excludeFromAssets,
            countInStats = state.countInStats,
            edited = state.userEdited,
            editedFields = state.editedFields,
        )
        if (dismissConfirm()) cb(snapshot)
    }

    private fun scheduleAutoDismiss() {
        autoDismiss?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            AppLog.i(TAG, "confirm card auto dismiss (no interaction, treated as entered)")
            handleDismiss()
        }
        autoDismiss = r
        handler.postDelayed(r, OVERLAY_AUTO_DISMISS_MS)
    }

    /**
     * 卡片内有交互（改金额/备注、点分类/账户、拖动、任意触摸）时重置自动收起计时，
     * 避免编辑中被 60s 超时收起。由 [ConfirmCardContent] 的 onUserActivity 逐次触发。
     */
    fun noteUserActivity() {
        if (confirmView != null) scheduleAutoDismiss()
    }

    private fun removeConfirmWindow() {
        autoDismiss?.let { handler.removeCallbacks(it) }
        autoDismiss = null
        dismissCallback = null
        val view = confirmView ?: return
        confirmView = null
        showingPendingId = -1L
        runCatching { windowManagerOrNull()?.removeViewImmediate(view) }
            .onFailure { AppLog.w(TAG, "remove confirm view failed", it) }
        runCatching { confirmHost?.destroy() }
        confirmHost = null
    }

    private fun removeSavedWindow() {
        savedDismiss?.let { handler.removeCallbacks(it) }
        savedDismiss = null
        val view = savedView ?: return
        savedView = null
        runCatching { windowManagerOrNull()?.removeViewImmediate(view) }
            .onFailure { AppLog.w(TAG, "remove saved view failed", it) }
        runCatching { savedHost?.destroy() }
        savedHost = null
    }

    private fun removeRecordedWindow() {
        recordedDismiss?.let { handler.removeCallbacks(it) }
        recordedDismiss = null
        val view = recordedView ?: return
        recordedView = null
        runCatching { windowManagerOrNull()?.removeViewImmediate(view) }
            .onFailure { AppLog.w(TAG, "remove recorded view failed", it) }
        runCatching { recordedHost?.destroy() }
        recordedHost = null
    }

    /** appContext 未初始化（进程刚拉起、CaptureCenter.init 之前）时返回 null，避免 lateinit 崩溃 */
    private fun windowManagerOrNull(): WindowManager? =
        if (this::appContext.isInitialized) {
            appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        } else {
            null
        }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt()

    /**
     * 状态栏高度：顶端悬浮条必须落在状态栏下方，否则会被系统图标/刘海盖住。
     * 取不到系统 dimen 时回落到 28dp（常见状态栏高度），避免细条贴到屏幕最上沿。
     */
    private fun statusBarHeight(): Int = runCatching {
        val id = appContext.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) appContext.resources.getDimensionPixelSize(id) else dp(28)
    }.getOrDefault(dp(28))
}
