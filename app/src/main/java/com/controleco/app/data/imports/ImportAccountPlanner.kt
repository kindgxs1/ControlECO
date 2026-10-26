package com.controleco.app.data.imports

import com.controleco.app.capture.page.InstrumentKind
import com.controleco.app.capture.page.PaymentAccountResolver
import com.controleco.app.capture.page.PaymentInstrumentParser
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.model.BillSource
import com.controleco.app.data.model.ImportAccountGroup
import com.controleco.app.data.model.ImportAccountPlan
import com.controleco.app.data.model.ImportedBillRow

/**
 * 导入账单 → 账户的自动匹配（纯函数，便于单测）。
 *
 * 微信 / 支付宝账单每行都带「支付方式 / 收/付款方式」，例如
 * `华夏银行信用卡(4362)`、`余额宝`、`零钱`、`工商银行储蓄卡(7492)`。
 * 这里把同一段文本解析成支付工具，再用 [PaymentAccountResolver] 在用户的账户里找对应账户：
 * 找到就自动带入，找不到就保持未匹配，交给导入页让用户逐条 / 批量确认。
 *
 * 为什么按文本分组：同一段支付方式文本必然对应同一个支付工具，
 * 所以一组只需确认一次；账单没写支付方式的行单独成组（空文本强制定为未匹配）。
 */
object ImportAccountPlanner {

    fun channelLabel(source: BillSource): String = when (source) {
        BillSource.WECHAT -> "微信"
        BillSource.ALIPAY -> "支付宝"
    }

    fun plan(
        source: BillSource,
        rows: List<ImportedBillRow>,
        accounts: List<AccountEntity>,
    ): ImportAccountPlan {
        if (rows.isEmpty()) return ImportAccountPlan(emptyList())
        val channel = channelLabel(source)
        val groups = rows
            .groupBy { it.payMethod.trim() }
            .map { (payMethod, groupRows) ->
                // 账单没写支付方式时不做渠道兜底：无法据此判断走的是零钱还是银行卡
                val instrument = if (payMethod.isBlank()) null else PaymentInstrumentParser.parse(payMethod, channel)
                // 明确是银行卡的行不做渠道兜底：账户里没有这张卡时应交给用户确认，
                // 而不是把银行卡消费一律记到「微信零钱 / 支付宝」。
                // 注意 resolver 在 channelLabel 为空时会回落到 instrument.channel，
                // 所以这里连同 instrument.channel 一起清空，否则兜底仍会命中渠道账户。
                val channelFallback = if (instrument == null || instrument.kind == InstrumentKind.BANK_CARD) "" else channel
                val effective = instrument?.copy(channel = channelFallback)
                val match = if (payMethod.isBlank()) {
                    null
                } else {
                    PaymentAccountResolver.resolve(
                        accounts = accounts,
                        instrument = effective,
                        channelLabel = channelFallback,
                    )
                }
                ImportAccountGroup(
                    payMethod = payMethod,
                    rowCount = groupRows.size,
                    accountId = match?.accountId,
                    accountName = match?.accountName,
                    reason = match?.reason?.label ?: PaymentAccountResolver.MatchReason.NONE.label,
                )
            }
            // 匹配到账户的在前（条数多的再靠前），未匹配的沉到后面
            .sortedWith(compareByDescending<ImportAccountGroup> { it.matched }.thenByDescending { it.rowCount })
        return ImportAccountPlan(groups)
    }
}
