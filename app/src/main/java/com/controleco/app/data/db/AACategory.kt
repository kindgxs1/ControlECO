package com.controleco.app.data.db

/**
 * AA 收款统一归入的收入分类（聚餐/AA 活动中收回的钱）。
 *
 * AA 是收入，关联到"自己花的那份"那条支出后，支出与 AA 收入按差额抵扣——
 * 聚餐 300 元，AA 收回来 200，实际自付 = 100。
 * 取不到「AA」时按"收入「其他」→ 第一个收入分类"兜底。
 */
object AACategory {
    const val NAME = "AA"

    fun incomeCategoryId(categories: List<CategoryEntity>): Long? =
        categories.firstOrNull { it.type == TxType.INCOME && it.name == NAME }?.id
            ?: categories.firstOrNull { it.type == TxType.INCOME && it.name == "其他" }?.id
            ?: categories.firstOrNull { it.type == TxType.INCOME }?.id
}
