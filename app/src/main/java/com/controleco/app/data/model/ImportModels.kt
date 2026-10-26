package com.controleco.app.data.model

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType

/** 账单来源（阶段一：CSV 导出文件；后续阶段扩展通知、短信等） */
enum class BillSource(val displayName: String) {
    WECHAT("微信支付"),
    ALIPAY("支付宝"),
}

/** 解析后待确认的单条账单 */
data class ImportedBillRow(
    val type: TxType,
    val amountCents: Long,
    val timestamp: Long,
    val counterparty: String,
    val goods: String,
    val note: String,
    val payMethod: String,
    val externalId: String?,
    val guessedCategoryName: String,
    val refundMark: RefundMark = RefundMark.NONE,
)

/** 一个账单文件的解析结果 */
data class ParsedBill(
    val source: BillSource,
    val rows: List<ImportedBillRow>,
    /** 不计收支（如零钱充值、提现、理财申购）跳过的条数 */
    val skippedOtherType: Int,
    /** 状态未完成（如等待付款）而跳过的条数 */
    val skippedStatus: Int,
    /** 退款相关记录数（始终入库） */
    val refundCount: Int,
    /** 表头之后非空的数据行数（= 入账 + 跳过），用于空状态时说明文件确实读到了内容 */
    val dataRowCount: Int = rows.size + skippedOtherType + skippedStatus,
) {
    /** 跳过的明细行（表头之后且被跳过的条数） */
    val skippedTotal: Int get() = skippedOtherType + skippedStatus
}

/**
 * 一种「收/付款方式」在本次导入里的账户匹配结果。
 *
 * 同一种支付方式文本对应同一个支付工具（如"华夏银行信用卡(4362)"），
 * 因此按文本分组：已匹配的自动带入账户，未匹配的交给用户逐条或批量确认。
 */
data class ImportAccountGroup(
    /** 账单里的原始支付方式文本（空文本 = 账单没写支付方式） */
    val payMethod: String,
    val rowCount: Int,
    val accountId: Long?,
    val accountName: String?,
    /** 匹配原因（"卡尾号"/"渠道"…；未匹配为"未匹配"） */
    val reason: String,
) {
    val matched: Boolean get() = accountId != null

    /** 展示用名称：空文本给出可读占位 */
    val label: String get() = payMethod.ifBlank { "账单未写支付方式" }
}

data class ImportAccountPlan(
    val groups: List<ImportAccountGroup>,
) {
    val matchedRowCount: Int get() = groups.filter { it.matched }.sumOf { it.rowCount }
    val unmatchedRows: Int get() = groups.filter { !it.matched }.sumOf { it.rowCount }

    /** 自动匹配结果：支付方式文本 → 账户 id */
    fun autoAssignments(): Map<String, Long> =
        groups.mapNotNull { g -> g.accountId?.let { g.payMethod to it } }.toMap()

    /** 合并自动匹配与人工确认后，所有支付方式是否都有着落 */
    fun isResolved(manual: Map<String, Long>): Boolean =
        groups.all { it.matched || manual.containsKey(it.payMethod) }

    /** 支付方式文本 → 账户 id（自动匹配 + 人工确认） */
    fun assignments(manual: Map<String, Long>): Map<String, Long> =
        autoAssignments() + manual
}

data class ImportCommitResult(
    val inserted: Int,
    val duplicated: Int,
    /** 本次写入涉及的不同账户数 */
    val accountCount: Int = 0,
)
