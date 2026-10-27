package com.controleco.app.data.db

/**
 * 报销到账统一归入的收入分类（替公司/团队垫付后收回的钱）。
 *
 * 报销是收入，关联到"垫付的支出"后，支出与报销收入按差额抵扣——
 * 公司活动垫了 500，报销回来 500，实际自付 = 0。
 * 取不到「报销」时按"收入「其他」→ 第一个收入分类"兜底。
 */
object ReimbursementCategory {
    const val NAME = "报销"

    fun incomeCategoryId(categories: List<CategoryEntity>): Long? =
        categories.firstOrNull { it.type == TxType.INCOME && it.name == NAME }?.id
            ?: categories.firstOrNull { it.type == TxType.INCOME && it.name == "其他" }?.id
            ?: categories.firstOrNull { it.type == TxType.INCOME }?.id
}
