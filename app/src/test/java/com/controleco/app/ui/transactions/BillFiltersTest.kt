package com.controleco.app.ui.transactions

import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账单筛选/搜索纯逻辑：商户匹配、金额（精确/范围）、类型、分类，以及四者的 AND 组合。
 * 金额一律按「分」比较，绝对值参与匹配（列表里支出显示成负数，用户照着输入负数也要能搜到）。
 */
class BillFiltersTest {

    private fun row(
        id: Long,
        counterparty: String = "",
        amountCents: Long = 100,
        type: TxType = TxType.EXPENSE,
        categoryId: Long = 1,
    ): TransactionRow = TransactionRow(
        id = id,
        type = type,
        amountCents = amountCents,
        timestamp = id * 1000L,
        note = "",
        counterparty = counterparty,
        source = TxSource.MANUAL,
        categoryId = categoryId,
        categoryName = "分类$categoryId",
        accountId = 1,
        accountName = "现金",
        refundMark = RefundMark.NONE,
    )

    // ---------- 商户 ----------

    @Test
    fun `商户_子串命中且忽略大小写`() {
        assertTrue(merchantMatches("Luckin Coffee", "luckin"))
        assertTrue(merchantMatches("luckin coffee", "COFFEE"))
        assertTrue(merchantMatches("瑞幸咖啡", "瑞幸"))
    }

    @Test
    fun `商户_忽略空白差异`() {
        assertTrue(merchantMatches("瑞幸咖啡", "瑞幸 咖啡"))
        assertTrue(merchantMatches("瑞幸 咖啡", "瑞幸咖啡"))
        assertTrue(merchantMatches("Luckin  Coffee", " luckin coffee "))
    }

    @Test
    fun `商户_空关键字命中全部_不命中则false`() {
        assertTrue(merchantMatches("瑞幸咖啡", ""))
        assertTrue(merchantMatches("", "   "))
        assertFalse(merchantMatches("瑞幸咖啡", "星巴克"))
        assertFalse(merchantMatches("", "瑞幸"))
    }

    // ---------- 金额：精确 ----------

    @Test
    fun `金额精确_元与分等价`() {
        val q = amountQueryOf(AmountMode.EXACT, "12.5", "", "")
        assertEquals(1250L, q.exactCents)
        assertTrue(amountMatches(1250, q))
        assertFalse(amountMatches(1251, q))
    }

    @Test
    fun `金额精确_支持货币符号与千分位`() {
        assertEquals(123456L, amountQueryOf(AmountMode.EXACT, "¥1,234.56", "", "").exactCents)
    }

    @Test
    fun `金额精确_负数输入按绝对值匹配支出`() {
        val q = amountQueryOf(AmountMode.EXACT, "-12.5", "", "")
        assertEquals(1250L, q.exactCents)
        assertTrue(amountMatches(1250, q))
    }

    @Test
    fun `金额精确_留空或非法则不限制`() {
        val blank = amountQueryOf(AmountMode.EXACT, "", "", "")
        assertFalse(blank.isActive)
        assertTrue(amountMatches(1, blank))
        val bad = amountQueryOf(AmountMode.EXACT, "abc", "", "")
        assertFalse(bad.isActive)
        assertTrue(amountMatches(999, bad))
    }

    // ---------- 金额：范围 ----------

    @Test
    fun `金额范围_上下限都填`() {
        val q = amountQueryOf(AmountMode.RANGE, "", "50", "100")
        assertTrue(amountMatches(5000, q))
        assertTrue(amountMatches(10000, q))
        assertTrue(amountMatches(7550, q))
        assertFalse(amountMatches(4999, q))
        assertFalse(amountMatches(10001, q))
    }

    @Test
    fun `金额范围_只填一端`() {
        val atLeast = amountQueryOf(AmountMode.RANGE, "", "50", "")
        assertTrue(amountMatches(5000, atLeast))
        assertTrue(amountMatches(999999, atLeast))
        assertFalse(amountMatches(4999, atLeast))

        val atMost = amountQueryOf(AmountMode.RANGE, "", "", "50")
        assertTrue(amountMatches(5000, atMost))
        assertTrue(amountMatches(1, atMost))
        assertFalse(amountMatches(5001, atMost))
    }

    @Test
    fun `金额范围_上下限填反按两者之间理解`() {
        val q = amountQueryOf(AmountMode.RANGE, "", "100", "50")
        assertTrue(amountMatches(5000, q))
        assertTrue(amountMatches(10000, q))
        assertFalse(amountMatches(4999, q))
        assertFalse(amountMatches(10001, q))
    }

    @Test
    fun `金额范围_两端都空则不限制`() {
        val q = amountQueryOf(AmountMode.RANGE, "", "", "")
        assertFalse(q.isActive)
        assertTrue(amountMatches(12345, q))
    }

    @Test
    fun `金额范围_模式切换后只看当前模式的条件`() {
        // 精确模式的输入框留着旧文本，但范围模式下不应生效
        val q = AmountQuery(mode = AmountMode.RANGE, exactCents = 1250, minCents = null, maxCents = null)
        assertFalse(q.isActive)
        assertTrue(amountMatches(999, q))
    }

    // ---------- 类型 / 分类 ----------

    @Test
    fun `类型筛选`() {
        val rows = listOf(
            row(1, type = TxType.EXPENSE),
            row(2, type = TxType.INCOME),
            row(3, type = TxType.EXPENSE),
        )
        assertEquals(3, applyTypeFilter(rows, TxFilter.ALL).size)
        assertEquals(listOf(1L, 3L), applyTypeFilter(rows, TxFilter.EXPENSE).map { it.id })
        assertEquals(listOf(2L), applyTypeFilter(rows, TxFilter.INCOME).map { it.id })
    }

    @Test
    fun `分类筛选_空集合不筛_多选取并集`() {
        val rows = listOf(row(1, categoryId = 1), row(2, categoryId = 2), row(3, categoryId = 3))
        assertEquals(3, applyCategoryFilter(rows, emptySet()).size)
        assertEquals(listOf(1L, 3L), applyCategoryFilter(rows, setOf(1L, 3L)).map { it.id })
        assertEquals(0, applyCategoryFilter(rows, setOf(9L)).size)
    }

    // ---------- 分类展开（一级分类包含二级分类） ----------

    private fun category(id: Long, name: String, parentId: Long? = null) =
        CategoryEntity(id = id, name = name, type = TxType.EXPENSE, parentId = parentId)

    private val categories = listOf(
        category(1, "餐饮"),
        category(2, "早餐", parentId = 1),
        category(3, "午餐", parentId = 1),
        category(4, "交通"),
        category(5, "公交", parentId = 4),
    )

    @Test
    fun `选一级分类_自动包含全部二级分类`() {
        assertEquals(setOf(1L, 2L, 3L), expandCategoryIds(setOf(1L), categories))
        assertEquals(setOf(4L, 5L), expandCategoryIds(setOf(4L), categories))
    }

    @Test
    fun `选二级分类_只保留自身不带上父级`() {
        assertEquals(setOf(2L), expandCategoryIds(setOf(2L), categories))
        assertEquals(setOf(2L, 3L), expandCategoryIds(setOf(2L, 3L), categories))
    }

    @Test
    fun `展开_空集合与无分类时原样返回`() {
        assertEquals(emptySet<Long>(), expandCategoryIds(emptySet(), categories))
        assertEquals(setOf(1L), expandCategoryIds(setOf(1L), emptyList()))
    }

    @Test
    fun `分类筛选_选一级分类能筛出挂在二级分类的账`() {
        val rows = listOf(
            row(1, categoryId = 2),
            row(2, categoryId = 3),
            row(3, categoryId = 5),
        )
        // 餐饮（1）下挂早餐/午餐：两笔都应该出现
        assertEquals(listOf(1L, 2L), applyCategoryFilter(rows, setOf(1L), categories).map { it.id })
        // 只看午餐（3）：只有一笔
        assertEquals(listOf(2L), applyCategoryFilter(rows, setOf(3L), categories).map { it.id })
        // 交通（4）本身没有账，其二级公交（5）有一笔 → 选一级能命中
        assertEquals(listOf(3L), applyCategoryFilter(rows, setOf(4L), categories).map { it.id })
        // 只选公交（5）：只命中那一笔
        assertEquals(listOf(3L), applyCategoryFilter(rows, setOf(5L), categories).map { it.id })
    }

    @Test
    fun `账单页筛选_一级分类与类型同时生效`() {
        val rows = listOf(
            row(1, type = TxType.EXPENSE, categoryId = 2),
            row(2, type = TxType.INCOME, categoryId = 3),
            row(3, type = TxType.EXPENSE, categoryId = 4),
        )
        assertEquals(listOf(1L), applyBillFilters(rows, TxFilter.EXPENSE, setOf(1L), categories).map { it.id })
        assertEquals(listOf(1L, 2L), applyBillFilters(rows, TxFilter.ALL, setOf(1L), categories).map { it.id })
    }

    @Test
    fun `账单页筛选_类型与分类是AND`() {
        val rows = listOf(
            row(1, type = TxType.EXPENSE, categoryId = 1),
            row(2, type = TxType.INCOME, categoryId = 1),
            row(3, type = TxType.EXPENSE, categoryId = 2),
        )
        assertEquals(listOf(1L), applyBillFilters(rows, TxFilter.EXPENSE, setOf(1L)).map { it.id })
        assertEquals(listOf(1L, 3L), applyBillFilters(rows, TxFilter.EXPENSE, emptySet()).map { it.id })
    }

    // ---------- 搜索组合 ----------

    private val rows = listOf(
        row(1, counterparty = "瑞幸咖啡", amountCents = 1250, categoryId = 1),
        row(2, counterparty = "瑞幸咖啡", amountCents = 3000, categoryId = 1),
        row(3, counterparty = "星巴克", amountCents = 1250, categoryId = 2),
        row(4, counterparty = "美团外卖", amountCents = 4500, type = TxType.INCOME, categoryId = 3),
    )

    @Test
    fun `搜索_一项都没填时不返回全量`() {
        val q = BillSearchQuery()
        assertFalse(q.isActive)
        assertEquals(0, searchBills(rows, q, 0L, Long.MAX_VALUE).size)
    }

    @Test
    fun `搜索_只填关键字`() {
        val q = BillSearchQuery(keyword = "瑞幸")
        assertTrue(q.isActive)
        assertEquals(listOf(1L, 2L), searchBills(rows, q, 0L, Long.MAX_VALUE).map { it.id })
    }

    @Test
    fun `搜索_关键字加金额是AND`() {
        val q = BillSearchQuery(keyword = "瑞幸", amount = amountQueryOf(AmountMode.EXACT, "12.5", "", ""))
        assertEquals(listOf(1L), searchBills(rows, q, 0L, Long.MAX_VALUE).map { it.id })
    }

    @Test
    fun `搜索_金额范围可单独使用`() {
        val q = BillSearchQuery(amount = amountQueryOf(AmountMode.RANGE, "", "30", "50"))
        assertTrue(q.isActive)
        assertEquals(listOf(2L, 4L), searchBills(rows, q, 0L, Long.MAX_VALUE).map { it.id })
    }

    @Test
    fun `搜索_类型与分类可与关键字叠加`() {
        val q = BillSearchQuery(keyword = "咖啡", type = TxFilter.EXPENSE, categoryIds = setOf(1L))
        assertTrue(q.isActive)
        assertEquals(listOf(1L, 2L), searchBills(rows, q, 0L, Long.MAX_VALUE).map { it.id })
        // 分类不匹配 → 空（关键字命中但分类被排除）
        assertEquals(0, searchBills(rows, BillSearchQuery(keyword = "咖啡", categoryIds = setOf(2L)), 0L, Long.MAX_VALUE).size)
    }

    @Test
    fun `搜索_只用类型或分类也算填了条件`() {
        assertEquals(listOf(4L), searchBills(rows, BillSearchQuery(type = TxFilter.INCOME), 0L, Long.MAX_VALUE).map { it.id })
        assertEquals(listOf(1L, 2L), searchBills(rows, BillSearchQuery(categoryIds = setOf(1L)), 0L, Long.MAX_VALUE).map { it.id })
    }

    @Test
    fun `搜索_选一级分类能命中挂在二级分类的账`() {
        val childRows = listOf(
            row(1, counterparty = "瑞幸咖啡", categoryId = 2),
            row(2, counterparty = "星巴克", categoryId = 3),
            row(3, counterparty = "地铁", categoryId = 4),
        )
        val query = BillSearchQuery(categoryIds = setOf(1L))
        assertEquals(
            listOf(1L, 2L),
            searchBills(childRows, query, 0L, Long.MAX_VALUE, categories).map { it.id },
        )
        // 只选二级分类：只命中对应那一笔
        assertEquals(
            listOf(2L),
            searchBills(childRows, BillSearchQuery(categoryIds = setOf(3L)), 0L, Long.MAX_VALUE, categories).map { it.id },
        )
    }

    @Test
    fun `搜索_结果顺序沿用原列表不倒序`() {
        val q = BillSearchQuery(amount = amountQueryOf(AmountMode.EXACT, "12.5", "", ""))
        assertEquals(listOf(1L, 3L), searchBills(rows, q, 0L, Long.MAX_VALUE).map { it.id })
    }

    @Test
    fun `搜索_商户为空的行不会被关键字命中`() {
        val withBlank = rows + row(9, counterparty = "", amountCents = 1250)
        assertEquals(listOf(1L, 2L), searchBills(withBlank, BillSearchQuery(keyword = "瑞幸"), 0L, Long.MAX_VALUE).map { it.id })
    }

    // ---------- 条件键 ----------

    @Test
    fun `搜索键_条件相同则键相同_分类顺序不影响`() {
        val a = BillSearchQuery(keyword = "瑞幸", categoryIds = setOf(1L, 2L))
        val b = BillSearchQuery(keyword = " 瑞幸 ", categoryIds = setOf(2L, 1L))
        assertEquals(billSearchKey(a, BillSort.TIME_DESC), billSearchKey(b, BillSort.TIME_DESC))
    }

    @Test
    fun `搜索键_任一条件变化则键变化`() {
        val base = BillSearchQuery(keyword = "瑞幸", type = TxFilter.EXPENSE)
        val keys = setOf(
            billSearchKey(base, BillSort.TIME_DESC),
            billSearchKey(base.copy(keyword = "星巴克"), BillSort.TIME_DESC),
            billSearchKey(base.copy(type = TxFilter.ALL), BillSort.TIME_DESC),
            billSearchKey(base.copy(categoryIds = setOf(1L)), BillSort.TIME_DESC),
            billSearchKey(base.copy(amount = amountQueryOf(AmountMode.EXACT, "1", "", "")), BillSort.TIME_DESC),
            billSearchKey(base.copy(amount = amountQueryOf(AmountMode.RANGE, "", "1", "2")), BillSort.TIME_DESC),
            billSearchKey(base, BillSort.AMOUNT_DESC),
        )
        assertEquals(7, keys.size)
    }

    @Test
    fun `搜索键_首次进入不回顶_键变了才回顶`() {
        val key = billSearchKey(BillSearchQuery(keyword = "瑞幸"), BillSort.TIME_DESC)
        assertFalse(shouldResetBillList(null, key))
        assertFalse(shouldResetBillList(key, key))
        assertTrue(shouldResetBillList(key, billSearchKey(BillSearchQuery(keyword = "星巴克"), BillSort.TIME_DESC)))
        // 只换排序也要回顶
        assertTrue(shouldResetBillList(key, billSearchKey(BillSearchQuery(keyword = "瑞幸"), BillSort.AMOUNT_ASC)))
    }

    // ---------- 排序 ----------

    private fun sortRow(id: Long, amountCents: Long, timestamp: Long) = TransactionRow(
        id = id,
        type = TxType.EXPENSE,
        amountCents = amountCents,
        timestamp = timestamp,
        note = "",
        counterparty = "m$id",
        source = TxSource.MANUAL,
        categoryId = 1,
        categoryName = "餐饮",
        accountId = 1,
        accountName = "现金",
        refundMark = RefundMark.NONE,
    )

    // 时间：3 最旧 → 1 最新；金额：20 / 50 / 30 / 50
    private val sortable = listOf(
        sortRow(1, 2000, 3000),
        sortRow(2, 5000, 2000),
        sortRow(3, 3000, 1000),
        sortRow(4, 5000, 500),
    )

    @Test
    fun `默认排序原样返回_不重排`() {
        val rows = listOf(sortRow(1, 100, 3000), sortRow(2, 200, 2000))
        // 同一个实例：默认排序就是 DAO 的顺序，不做无谓重排
        assertSame(rows, sortBills(rows, BillSort.TIME_DESC))
    }

    @Test
    fun `时间升序_最早在前`() {
        assertEquals(listOf(4L, 3L, 2L, 1L), sortBills(sortable, BillSort.TIME_ASC).map { it.id })
    }

    @Test
    fun `金额降序_同金额按时间倒序`() {
        // 5000 两笔（时间 2000 / 500）：新的在前；其余 3000 → 2000
        assertEquals(listOf(2L, 4L, 3L, 1L), sortBills(sortable, BillSort.AMOUNT_DESC).map { it.id })
    }

    @Test
    fun `金额升序_同金额按时间倒序`() {
        assertEquals(listOf(1L, 3L, 2L, 4L), sortBills(sortable, BillSort.AMOUNT_ASC).map { it.id })
    }

    @Test
    fun `金额排序取绝对值_历史脏数据里的负数也按大小排`() {
        val rows = listOf(sortRow(1, -9000, 1000), sortRow(2, 100, 2000))
        assertEquals(listOf(1L, 2L), sortBills(rows, BillSort.AMOUNT_DESC).map { it.id })
    }

    @Test
    fun `时间排序按天分组_金额排序平铺`() {
        assertTrue(BillSort.TIME_DESC.groupedByDay)
        assertTrue(BillSort.TIME_ASC.groupedByDay)
        assertFalse(BillSort.AMOUNT_DESC.groupedByDay)
        assertFalse(BillSort.AMOUNT_ASC.groupedByDay)
    }

    @Test
    fun `按天分组保持入参顺序`() {
        val rows = listOf(
            sortRow(1, 100, LocalDate.of(2026, 10, 2).toEpochDay() * 86_400_000L),
            sortRow(2, 100, LocalDate.of(2026, 10, 1).toEpochDay() * 86_400_000L),
            sortRow(3, 100, LocalDate.of(2026, 10, 2).toEpochDay() * 86_400_000L),
        )
        val grouped = groupRowsByDay(rows)
        assertEquals(listOf(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1)), grouped.keys.toList())
        assertEquals(listOf(1L, 3L), grouped.getValue(LocalDate.of(2026, 10, 2)).map { it.id })
    }

    @Test
    fun `账单页列表键_类型或排序变了才回顶`() {
        val key = billListKey(TxFilter.ALL, BillSort.TIME_DESC)
        assertFalse(shouldResetBillList(null, key))
        assertFalse(shouldResetBillList(key, billListKey(TxFilter.ALL, BillSort.TIME_DESC)))
        assertTrue(shouldResetBillList(key, billListKey(TxFilter.EXPENSE, BillSort.TIME_DESC)))
        assertTrue(shouldResetBillList(key, billListKey(TxFilter.ALL, BillSort.AMOUNT_DESC)))
    }
}
