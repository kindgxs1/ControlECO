package com.controleco.app.data.calendar

import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.deposit.DepositMath
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 某一天的收支（日历格子里显示的两行金额 / 月汇总都用它） */
data class DayMoney(
    val incomeCents: Long = 0L,
    val expenseCents: Long = 0L,
) {
    val netCents: Long get() = incomeCents - expenseCents
    val isEmpty: Boolean get() = incomeCents == 0L && expenseCents == 0L
}

/**
 * 日历口径（纯函数，可单测）。
 *
 * 与首页 / 统计 / 存款共用同一套统计口径（[DepositMath]）：
 * - 收入 = 计入统计的收入（含退款到账）；已关联原支出的退款只计超出部分；
 * - 支出 = 计入统计且未被标记「已退款」的支出，并扣除已关联退款；
 * - 不计入账单和收支统计的记录对日历收支没有任何影响。
 */
object CalendarMath {

    fun byDay(rows: List<TransactionRow>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, DayMoney> {
        val map = LinkedHashMap<LocalDate, DayMoney>()
        rows.forEach { row ->
            val date = Instant.ofEpochMilli(row.timestamp).atZone(zone).toLocalDate()
            val old = map[date] ?: DayMoney()
            map[date] = DayMoney(
                incomeCents = old.incomeCents + DepositMath.incomeCentsOf(row),
                expenseCents = old.expenseCents + DepositMath.expenseCentsOf(row),
            )
        }
        return map
    }

    /** 整月汇总（底部选项下方的月收入 / 月支出 / 月结余） */
    fun totals(rows: List<TransactionRow>): DayMoney {
        var income = 0L
        var expense = 0L
        rows.forEach { row ->
            income += DepositMath.incomeCentsOf(row)
            expense += DepositMath.expenseCentsOf(row)
        }
        return DayMoney(income, expense)
    }
}
