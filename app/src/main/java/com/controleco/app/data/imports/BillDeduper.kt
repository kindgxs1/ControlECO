package com.controleco.app.data.imports

import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.data.model.ImportedBillRow
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** 一次导入比对的结果：真正需要写入的新记录 + 因重复而跳过的条数 */
data class ImportPartition(
    val newRows: List<ImportedBillRow>,
    val duplicateCount: Int,
)

/**
 * 账单导入去重：把解析出的账单与账本里已有的记录逐条比对。
 *
 * 判定顺序（命中任一即视为同一笔，已匹配的记录不会被第二条重复使用）：
 * 1. 外部单号相同（同一文件/同一笔账单重复导出）；
 * 2. 方向 + 金额相同，且时间相差不超过 10 分钟（自动捕获通常在支付后几秒内落库）；
 * 3. 方向 + 金额相同，且同一天、商户/商品名可对应（手动记账的时间可能与支付时间不同）。
 *
 * 只在方向与金额都相同时才比较时间/商户，避免把金额相近的不同账单误判为重复。
 */
object BillDeduper {

    /** 时间相近判定窗口：10 分钟 */
    private const val NEAR_TIME_MILLIS = 10 * 60 * 1000L

    /** 商户名切分符：账单里的商品说明常见 "/"、"｜"、"-" 分隔 */
    private val MERCHANT_SPLIT = Regex("[/|｜\\-—_·,，.。:：;；()（）\\[\\]【】\\s]+")

    fun partition(
        rows: List<ImportedBillRow>,
        existing: List<TransactionEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ImportPartition {
        val used = BooleanArray(existing.size)
        val byExternalId = HashMap<String, Int>()
        existing.forEachIndexed { index, tx ->
            tx.externalId?.trim()?.takeIf { it.isNotEmpty() }?.let { byExternalId.putIfAbsent(it, index) }
        }
        val byTypeAmount = existing.indices.groupBy { existing[it].type to existing[it].amountCents }

        val seenExternalIds = HashSet<String>()
        val newRows = ArrayList<ImportedBillRow>(rows.size)
        var duplicates = 0

        // 按时间升序比对：同一笔账单的候选按时间最近优先，先到先得
        for (row in rows.sortedBy { it.timestamp }) {
            val externalId = row.externalId?.trim()?.takeIf { it.isNotEmpty() }
            if (externalId != null && !seenExternalIds.add(externalId)) {
                duplicates++
                continue
            }

            val exact = externalId?.let { byExternalId[it] }?.takeIf { !used[it] }
            if (exact != null) {
                used[exact] = true
                duplicates++
                continue
            }

            val candidates = byTypeAmount[row.type to row.amountCents].orEmpty()

            val nearTime = candidates.firstOrNull { index ->
                !used[index] && abs(existing[index].timestamp - row.timestamp) <= NEAR_TIME_MILLIS
            }
            if (nearTime != null) {
                used[nearTime] = true
                duplicates++
                continue
            }

            val sameDayMerchant = candidates.firstOrNull { index ->
                !used[index] &&
                    sameDay(existing[index].timestamp, row.timestamp, zone) &&
                    merchantSimilar(existing[index], row)
            }
            if (sameDayMerchant != null) {
                used[sameDayMerchant] = true
                duplicates++
                continue
            }

            newRows.add(row)
        }
        return ImportPartition(newRows = newRows, duplicateCount = duplicates)
    }

    private fun sameDay(a: Long, b: Long, zone: ZoneId): Boolean {
        val dateA = Instant.ofEpochMilli(a).atZone(zone).toLocalDate()
        val dateB = Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
        return dateA == dateB
    }

    /** 商户/商品名是否有可对应的片段：完全一致，或一方包含另一方（如「美团」与「美团平台商户」） */
    private fun merchantSimilar(tx: TransactionEntity, row: ImportedBillRow): Boolean {
        val left = merchantTokens(tx.counterparty) + merchantTokens(tx.note)
        if (left.isEmpty()) return false
        val right = merchantTokens(row.counterparty) + merchantTokens(row.goods) + merchantTokens(row.note)
        if (right.isEmpty()) return false
        return left.any { a -> right.any { b -> a == b || (a.length >= 2 && b.length >= 2 && (a.contains(b) || b.contains(a))) } }
    }

    /** 去掉脱敏（含 *）与过短片段，避免「tb**1」之类账号别名造成误判 */
    private fun merchantTokens(text: String): List<String> =
        text.split(MERCHANT_SPLIT).mapNotNull { segment ->
            val raw = segment.trim()
            if (raw.isEmpty() || raw.contains('*')) return@mapNotNull null
            raw.lowercase().filter { it.isLetterOrDigit() }.takeIf { it.length >= 2 }
        }
}
