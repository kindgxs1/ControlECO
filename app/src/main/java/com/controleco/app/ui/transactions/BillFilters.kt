package com.controleco.app.ui.transactions

import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TxType
import com.controleco.app.util.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * 账单列表的筛选与搜索纯逻辑（不依赖 Compose，便于单测）。
 *
 * 两个入口共用同一套判定：
 * - 账单页工具条：类型（全部/支出/收入）+ 分类多选，两者 AND；
 * - 搜索页：商户关键字 + 金额（精确/范围）+ 时间（精确/范围）+ 类型 + 分类，全部 AND。
 *
 * 时间范围：空 = 当前会计周期（数据由调用方用 periodRows 提供）；非空 = 跨月，
 * 调用方改用 allRows，再由这里按毫秒区间过滤。
 */

/** 收支类型筛选：三段式（全部 / 支出 / 收入），账单页与搜索页共用 */
internal enum class TxFilter(val label: String) {
    ALL("全部"),
    EXPENSE("支出"),
    INCOME("收入"),
}

/** 金额条件的形式：精确一笔 / 区间 */
internal enum class AmountMode(val label: String) {
    EXACT("精确"),
    RANGE("范围"),
}

/**
 * 金额条件。两种模式互斥：
 * - [AmountMode.EXACT] 只比 [exactCents]；
 * - [AmountMode.RANGE] 比 [minCents] ~ [maxCents]，两端都可留空（只填一端 = ≥ / ≤）。
 *
 * 解析失败或留空的输入一律当「该端不限制」，不报错、不影响其它条件。
 */
internal data class AmountQuery(
    val mode: AmountMode = AmountMode.EXACT,
    val exactCents: Long? = null,
    val minCents: Long? = null,
    val maxCents: Long? = null,
) {
    /** 是否填了任何金额条件 */
    val isActive: Boolean
        get() = when (mode) {
            AmountMode.EXACT -> exactCents != null
            AmountMode.RANGE -> minCents != null || maxCents != null
        }
}

/**
 * 输入框文本 → 金额条件。金额统一取绝对值：列表里支出显示成 `-12.50`，
 * 用户照着输入 `-12.5` 也应该能搜到那笔 12.50 的支出。
 */
internal fun amountQueryOf(mode: AmountMode, exactText: String, minText: String, maxText: String): AmountQuery =
    when (mode) {
        AmountMode.EXACT -> AmountQuery(mode = mode, exactCents = Money.parseCents(exactText)?.let { abs(it) })
        AmountMode.RANGE -> AmountQuery(
            mode = mode,
            minCents = Money.parseCents(minText)?.let { abs(it) },
            maxCents = Money.parseCents(maxText)?.let { abs(it) },
        )
    }

/** 商户匹配：忽略大小写与所有空白后做子串匹配（「瑞幸 咖啡」能命中「瑞幸咖啡」） */
internal fun merchantMatches(counterparty: String, keyword: String): Boolean {
    val key = normalizeMerchant(keyword)
    if (key.isEmpty()) return true
    return normalizeMerchant(counterparty).contains(key)
}

private fun normalizeMerchant(text: String): String =
    text.filterNot { it.isWhitespace() }.lowercase(Locale.ROOT)

/**
 * 时间范围。[start] / [end] 都是**包含**当天（按本地时区从 00:00 到 23:59:59）。
 * 精确模式下 start == end。
 */
internal data class DateRange(val start: LocalDate, val end: LocalDate)

/** 把日期范围转成「左闭右开」的毫秒区间（本地时区），便于和 [TransactionRow.timestamp] 比较 */
internal fun DateRange.toMillisRange(zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
    val s = start.atStartOfDay(zone).toInstant().toEpochMilli()
    val e = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return s to e
}

/** 金额匹配；条件为空 = 全部命中 */
internal fun amountMatches(amountCents: Long, query: AmountQuery): Boolean {
    if (!query.isActive) return true
    val amount = abs(amountCents)
    return when (query.mode) {
        AmountMode.EXACT -> amount == query.exactCents
        AmountMode.RANGE -> {
            val min = query.minCents
            val max = query.maxCents
            // 上下限填反了按「两者之间」理解，不让用户对着空列表猜哪里错了
            if (min != null && max != null && min > max) {
                amount >= max && amount <= min
            } else {
                (min == null || amount >= min) && (max == null || amount <= max)
            }
        }
    }
}

/** 类型筛选 */
internal fun applyTypeFilter(rows: List<TransactionRow>, filter: TxFilter): List<TransactionRow> =
    when (filter) {
        TxFilter.ALL -> rows
        TxFilter.EXPENSE -> rows.filter { it.type == TxType.EXPENSE }
        TxFilter.INCOME -> rows.filter { it.type == TxType.INCOME }
    }

/** 分类筛选：空集合 = 不筛 */
internal fun applyCategoryFilter(rows: List<TransactionRow>, categoryIds: Set<Long>): List<TransactionRow> =
    if (categoryIds.isEmpty()) rows else rows.filter { it.categoryId in categoryIds }

/** 账单页筛选：类型 + 分类（AND） */
internal fun applyBillFilters(
    rows: List<TransactionRow>,
    filter: TxFilter,
    categoryIds: Set<Long>,
): List<TransactionRow> = applyCategoryFilter(applyTypeFilter(rows, filter), categoryIds)

/**
 * 搜索页条件：商户关键字 + 金额 + 时间 + 类型 + 分类，全部 AND。
 *
 * [dateRange] 为 null = 未显式指定时间，搜索范围就是当前会计周期（调用方用 periodRows）；
 * 非 null = 跨月（调用方改用 allRows，再由 [searchBills] 按毫秒区间过滤）。
 *
 * [isActive] = 至少填了一项。一项都没填时不展示「当前周期全量」——
 * 那与账单页完全重复，也失去搜索页的意义；页面改为给一句引导文案。
 */
internal data class BillSearchQuery(
    val keyword: String = "",
    val amount: AmountQuery = AmountQuery(),
    val dateRange: DateRange? = null,
    val type: TxFilter = TxFilter.ALL,
    val categoryIds: Set<Long> = emptySet(),
) {
    val isActive: Boolean
        get() = keyword.isNotBlank() || amount.isActive || dateRange != null ||
            type != TxFilter.ALL || categoryIds.isNotEmpty()
}

/**
 * 搜索：先按类型/分类筛，再按时间/商户/金额筛；顺序沿用 DAO 的时间倒序，不做重排。
 *
 * [rangeStartMillis] / [rangeEndMillis] 由调用方根据 [BillSearchQuery.dateRange] 计算：
 * 未指定时间时取当前会计周期边界，指定了则取所选日期。
 */
internal fun searchBills(
    rows: List<TransactionRow>,
    query: BillSearchQuery,
    rangeStartMillis: Long,
    rangeEndMillis: Long,
): List<TransactionRow> {
    if (!query.isActive) return emptyList()
    return applyBillFilters(rows, query.type, query.categoryIds)
        .filter { it.timestamp >= rangeStartMillis && it.timestamp < rangeEndMillis }
        .filter { merchantMatches(it.counterparty, query.keyword) && amountMatches(it.amountCents, query.amount) }
}

/**
 * 搜索条件的稳定键：只有它真的变了才回顶并重置分页。
 *
 * 与账单页 [shouldResetBillList] 配合使用——搜索页的 `LaunchedEffect(键)` 在
 * 「从编辑页返回」时也会重跑，无条件回顶会把恢复好的滚动位置抹掉（见 BillListMemory.kt）。
 * 排序也在键里：换了顺序，旧的滚动位置就没有意义了。
 */
internal fun billSearchKey(query: BillSearchQuery, sort: BillSort): String = buildString {
    append(query.keyword.trim())
    append('|').append(query.type.name)
    append('|').append(query.amount.mode.name)
    append(':').append(query.amount.exactCents)
    append(':').append(query.amount.minCents)
    append(':').append(query.amount.maxCents)
    append('|').append(query.dateRange?.start)
    append('-').append(query.dateRange?.end)
    append('|').append(query.categoryIds.sorted().joinToString(","))
    append('|').append(sort.name)
}

// ---------- 排序 ----------

/**
 * 排序方式：时间两个方向 + 金额两个方向。
 *
 * 金额排序**不按天分组**（要的是整个周期的金额排名，分组会把排名切碎），
 * 改为平铺 + 行内补日期（见 [BillSort.groupedByDay] / `BillRow(showDate = true)`）。
 */
internal enum class BillSort(val title: String, val sub: String) {
    TIME_DESC("时间（新→旧）", "按天分组，最近的在前"),
    TIME_ASC("时间（旧→新）", "按天分组，最早的在前"),
    AMOUNT_DESC("金额（大→小）", "不分天，整个周期从大到小"),
    AMOUNT_ASC("金额（小→大）", "不分天，整个周期从小到大"),
}

/** 时间排序保留「按天分组 + 日期头」；金额排序平铺 */
internal val BillSort.groupedByDay: Boolean
    get() = this == BillSort.TIME_DESC || this == BillSort.TIME_ASC

/**
 * 排序。[TransactionRow.amountCents] 收支都存正数（支出只靠 `type` 区分方向），
 * 所以按金额排就是按「金额大小」排；这里仍取绝对值，避免历史脏数据里出现负数时排到两端。
 * 同金额按时间倒序（稳定、可预期）。
 */
internal fun sortBills(rows: List<TransactionRow>, sort: BillSort): List<TransactionRow> = when (sort) {
    // DAO 的 ORDER BY 就是 timestamp DESC, id DESC，默认排序原样返回、不做无谓重排
    BillSort.TIME_DESC -> rows
    BillSort.TIME_ASC -> rows.sortedWith(compareBy({ it.timestamp }, { it.id }))
    BillSort.AMOUNT_DESC -> rows.sortedWith(
        compareByDescending<TransactionRow> { abs(it.amountCents) }.thenByDescending { it.timestamp },
    )
    BillSort.AMOUNT_ASC -> rows.sortedWith(
        compareBy<TransactionRow> { abs(it.amountCents) }.thenByDescending { it.timestamp },
    )
}

/** 按本地日期分组；`groupBy` 保持入参顺序，所以「先排序再分组」才能决定天与天内行的顺序 */
internal fun groupRowsByDay(
    rows: List<TransactionRow>,
    zone: ZoneId = ZoneId.systemDefault(),
): Map<LocalDate, List<TransactionRow>> =
    rows.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }

/**
 * 账单页列表键：类型筛选或排序变了才回顶/重置分页。
 *
 * 分类筛选**刻意不进键**：它只是把列表收窄，用户在 sheet 里点选完回到列表，
 * 位置保持原样比被弹回顶部更自然（见 03-账单.md）。
 */
internal fun billListKey(filter: TxFilter, sort: BillSort): String = "${filter.name}|${sort.name}"

