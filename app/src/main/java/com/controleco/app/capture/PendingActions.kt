package com.controleco.app.capture

import androidx.room.withTransaction
import com.controleco.app.data.db.AccBookDatabase
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.MerchantRuleDao
import com.controleco.app.data.db.PendingDao
import com.controleco.app.data.db.PendingStatus
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.RefundCategory
import com.controleco.app.data.db.TransactionDao
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.data.db.TxType
import com.controleco.app.util.AppLog

/**
 * 待确认条目的入账/丢弃动作（采集层与 UI 层共用）。
 *
 * 为什么抽共享层：悬浮确认卡没有 Activity/ViewModel 宿主，确认动作必须在采集层独立可执行；
 * 待确认列表（ViewModel）也要做同一件事。放一处，保证两侧口径一致——
 * externalId 规则、来源标记（TxSource）、退款标记、重复插入的处理方式、**商户归类学习**都只有一份。
 */
internal object PendingActions {

    /** 普通确认/悬浮编辑不能代替选支出退款，也不能把普通收入伪装成退款。 */
    internal fun ordinaryCategoryAllowed(
        type: TxType,
        refundMark: RefundMark,
        category: CategoryEntity?,
    ): Boolean =
        refundMark == RefundMark.NONE && category != null && category.type == type &&
            !(category.type == TxType.INCOME && category.name == RefundCategory.NAME)

    /**
     * 确认入账：写交易（externalId = "NTF:" + dedupKey，唯一索引兜底防重）+ 删除待确认条目
     * + 学习商户归类（越用越准）。
     * 返回新交易 id；若该笔已经入过账（重复通知/双通道竞态），唯一索引冲突按"已记录"处理，
     * 返回 null 且不抛异常、不重复写账。
     */
    suspend fun confirm(
        db: AccBookDatabase,
        txDao: TransactionDao,
        pendingDao: PendingDao,
        merchantRuleDao: MerchantRuleDao? = null,
        item: PendingTransactionEntity,
        categoryId: Long,
        accountId: Long,
        counterparty: String = item.counterparty,
        note: String = item.note,
        amountCents: Long = item.amountCents,
        /** 不计入资产统计：自动入账默认 false（计入） */
        excludeFromAssets: Boolean = false,
        /** 计入账单和收支统计：自动入账默认 true（计入） */
        countInStats: Boolean = true,
    ): Long? {
        val externalId = "NTF:${item.dedupKey}"
        val counterpartyValue = counterparty.trim().ifBlank { item.counterparty }
        val noteValue = note.ifBlank { item.note.ifBlank { item.ruleName } }
        // 先占住内存终态，数据库失败时撤销，避免确认与迟到捕获之间出现可重新入队的空窗。
        var resolved = false
        val txId = try {
            db.withTransaction {
                // UI/悬浮卡可能持有旧快照，必须在写入事务内确认条目仍可处理。
                val fresh = pendingDao.getById(item.id) ?: return@withTransaction null
                if (fresh.status !in setOf(PendingStatus.PENDING, PendingStatus.ASK) ||
                    fresh.dedupKey != item.dedupKey || fresh.type != item.type
                ) {
                    return@withTransaction null
                }
                require(
                    ordinaryCategoryAllowed(
                        fresh.type,
                        fresh.refundMark,
                        db.categoryDao().getAll().find { it.id == categoryId },
                    ),
                ) { "请选择有效分类；退款请先选择支出账单" }
                require(amountCents > 0) { "请输入有效金额" }
                LogicalTransactions.markResolved(fresh, CaptureResolution.HANDLED)
                resolved = true
                val id = txDao.insert(
                    TransactionEntity(
                        type = item.type,
                        amountCents = amountCents,
                        categoryId = categoryId,
                        accountId = accountId,
                        timestamp = item.timestamp,
                        note = noteValue,
                        counterparty = counterpartyValue,
                        source = CaptureDefaults.txSource(item.sourcePackage),
                        externalId = externalId,
                        // 普通确认只处理普通收支；捕获退款必须走 RefundActions 选择原支出。
                        refundMark = RefundMark.NONE,
                        refundPending = false,
                        flowId = item.flowId,
                        excludeFromAssets = excludeFromAssets,
                        countInStats = countInStats,
                    ),
                )
                pendingDao.deleteById(item.id)
                id
            }
        } catch (e: Exception) {
            if (e.message?.contains("UNIQUE", ignoreCase = true) == true) {
                // 撞唯一约束后必须先确认账目真的存在，才能删条目，避免丢账。
                if (txDao.findIdByExternalId(externalId) != null) {
                    pendingDao.deleteById(item.id)
                    AppLog.i("pending", "already recorded, skip duplicate externalId=$externalId")
                    null
                } else {
                    if (resolved) LogicalTransactions.forget(item.dedupKey)
                    AppLog.w(
                        "pending",
                        "unique conflict without existing tx, keep pending #${item.id} externalId=$externalId",
                        e,
                    )
                    null
                }
            } else {
                if (resolved) LogicalTransactions.forget(item.dedupKey)
                throw e
            }
        }
        if (txId != null) {
            merchantRuleDao?.let {
                MerchantRules.learn(it, item.type, categoryId, accountId, counterpartyValue, noteValue)
            }
            AppLog.i("pending", "confirmed #${item.id} externalId=$externalId tx=$txId")
        }
        return txId
    }

    /** 丢弃（误报纠错）：只删待确认条目，不产生账目 */
    suspend fun discard(pendingDao: PendingDao, id: Long) {
        pendingDao.deleteById(id)
        AppLog.i("pending", "discarded #$id")
    }

    /**
     * 已入账条目的编辑同步（悬浮卡"先入账、后修改"）：卡内当前值写回交易与 AUTO 条目，
     * 并学习商户归类（与 [confirm] 同一口径）。
     *
     * 用户改过的字段由内存里的 [OverlayFieldGuards] 保护（后续富化按字段跳过），
     * 不再写 `pending_transactions.userEdited`（旧的整条锁定位留给升级前的历史条目）。
     * 返回 false = 找不到该笔交易（可能已被撤销），调用方只需静默收场。
     */
    suspend fun updateEntered(
        db: AccBookDatabase,
        txDao: TransactionDao,
        pendingDao: PendingDao,
        merchantRuleDao: MerchantRuleDao? = null,
        item: PendingTransactionEntity,
        categoryId: Long,
        accountId: Long,
        counterparty: String,
        note: String,
        amountCents: Long,
        excludeFromAssets: Boolean = false,
        /** 计入账单和收支统计：默认 true（计入） */
        countInStats: Boolean = true,
    ): Boolean {
        if (!ordinaryCategoryAllowed(
                item.type,
                item.refundMark,
                db.categoryDao().getAll().find { it.id == categoryId },
            ) || amountCents <= 0
        ) {
            return false
        }
        val externalId = "NTF:${item.dedupKey}"
        val txId = txDao.findIdByExternalId(externalId) ?: return false
        val transaction = txDao.getById(txId) ?: return false
        if (transaction.refundMark != RefundMark.NONE || transaction.type != item.type) return false
        val counterpartyValue = counterparty.trim().ifBlank { item.counterparty }
        // 用数据库里的最新备注/时间：卡片打开后页面可能又补了信息，用户只改了金额/商户，
        // 不能拿打开卡片时的旧快照把后到的富化结果覆盖回去
        val fresh = pendingDao.getById(item.id) ?: item
        db.withTransaction {
            txDao.updateEnteredFields(
                txId, amountCents, categoryId, accountId, counterpartyValue, note, excludeFromAssets, countInStats,
            )
            // AUTO 条目同步：页面/银行通道后续富化以它为准，不能留旧值
            pendingDao.updateFromPage(
                item.id, amountCents, fresh.timestamp, counterpartyValue, fresh.note, fresh.ruleName,
            )
        }
        merchantRuleDao?.let {
            MerchantRules.learn(it, item.type, categoryId, accountId, counterpartyValue, note)
        }
        AppLog.i("pending", "updated entered tx=#$txId amount=$amountCents externalId=$externalId")
        return true
    }

    /**
     * 撤销已入账（悬浮卡「忽略」/「撤销」/ 通知栏「撤销」）：把这笔账**退回待确认**，而不是连捕获一起删掉。
     *
     * 单次误触只丢掉一次入账结果，捕获本身仍留在待确认队列 —— 用户可以重新入账，也可以显式丢弃。
     * 旧实现直接删交易 + 删条目，误触后连"这笔账去哪儿了"都查不到，正是记账丢失的直接原因。
     *
     * @param dedupKey 该笔的捕获去重键（= 交易 externalId 的 `NTF:` 后缀）
     * @param txIdHint 通知栏撤销只带交易 id（键可能已被页面合并改写），键找不到时用它兜底
     * @param fallbackSourcePackage 条目已被清理时，重建待确认条目用的来源包名
     * @return true = 之前确实有这笔账（已退回待确认）
     */
    suspend fun revertEntered(
        db: AccBookDatabase,
        txDao: TransactionDao,
        pendingDao: PendingDao,
        dedupKey: String,
        txIdHint: Long = -1L,
        fallbackSourcePackage: String = "",
    ): Boolean {
        if (dedupKey.isBlank()) return false
        var reverted = false
        db.withTransaction {
            val tx = txDao.findIdByExternalId("NTF:$dedupKey")?.let { txDao.getById(it) }
                ?: txIdHint.takeIf { it > 0 }?.let { txDao.getById(it) }
                ?: return@withTransaction
            val pending = pendingDao.findByDedupKey(dedupKey)
            if (pending != null) {
                // 条目还在（自动入账的 AUTO 条目保留一天）：直接退回待确认，保留用户已改的字段
                pendingDao.setStatus(pending.id, PendingStatus.PENDING)
            } else {
                // 条目已被清理：按交易重建一条待确认，保证撤销后这笔捕获不会凭空消失
                pendingDao.insert(
                    PendingTransactionEntity(
                        type = tx.type,
                        amountCents = tx.amountCents,
                        timestamp = tx.timestamp,
                        counterparty = tx.counterparty,
                        note = tx.note,
                        sourcePackage = fallbackSourcePackage,
                        ruleName = tx.note.ifBlank { "撤销回退" },
                        dedupKey = dedupKey,
                        refundMark = tx.refundMark,
                        flowId = tx.flowId,
                    ),
                )
            }
            txDao.deleteById(tx.id)
            reverted = true
        }
        AppLog.i("pending", "revert entered externalId=NTF:$dedupKey reverted=$reverted")
        return reverted
    }
}
