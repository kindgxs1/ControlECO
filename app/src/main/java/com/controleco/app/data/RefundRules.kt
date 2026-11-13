package com.controleco.app.data

import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.CategoryRules
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.data.db.TxType

/** 退款只由支出操作产生；校验同样应用于持久化入口，不能仅依赖页面筛选。 */
internal object RefundRules {
    fun expenseError(
        expense: TransactionEntity?,
        capturedAmount: Long = 0,
        hasLinkedIncome: Boolean = false,
    ): String? = when {
        expense == null -> "原支出记录不存在"
        expense.type != TxType.EXPENSE -> "只能选择支出账单"
        expense.refundMark != RefundMark.NONE -> "该账单已退款"
        hasLinkedIncome -> "该账单已关联退款或其他收入，不能重复退款"
        expense.amountCents <= 0 -> "支出金额无效"
        capturedAmount < 0 -> "退款金额无效"
        capturedAmount > 0 && capturedAmount != expense.amountCents -> "退款金额与支出不符"
        else -> null
    }

    fun undoError(expense: TransactionEntity?, linkedRefunds: List<TransactionEntity>): String? {
        if (expense == null) return "原支出记录不存在"
        if (expense.type != TxType.EXPENSE || expense.refundMark != RefundMark.REFUNDED_EXPENSE) {
            return "该账单未退款"
        }
        if (linkedRefunds.isEmpty()) return "未找到关联的退款收入，请手动处理"
        if (linkedRefunds.size != 1) return "关联退款不唯一，请手动处理"
        val refund = linkedRefunds.single()
        return when {
            refund.type != TxType.INCOME || refund.refundMark != RefundMark.REFUND_INCOME ||
                refund.refundLinkId != expense.id -> "退款关联无效，请手动处理"
            refund.amountCents != expense.amountCents -> "关联退款金额不一致，请手动处理"
            else -> null
        }
    }

    fun editError(old: TransactionEntity?, type: TxType, category: CategoryEntity): String? {
        val refundCategory = CategoryRules.isRefundIncome(category.type, category.name)
        return when {
            category.type != type -> "分类与收支方向不符"
            refundCategory && old?.refundMark != RefundMark.REFUND_INCOME -> "请从支出账单退款"
            old?.refundMark == RefundMark.REFUND_INCOME && (type != TxType.INCOME || !refundCategory) -> "退款账单需保留退款分类"
            old?.refundMark == RefundMark.REFUNDED_EXPENSE && type != TxType.EXPENSE -> "请先撤销退款"
            else -> null
        }
    }
}
