package com.controleco.app.ui.transactions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.periodLabel
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.Money

/**
 * 「未设预算分类」专属账单页：只显示当前会计周期内、其一级支出分类没有设预算的账单。
 *
 * 入口是首页「分类」模式里的「未设预算分类」合成条。这里没有筛选/排序，纯展示 + 点击编辑，
 * 让用户能快速定位"预算没覆盖到的支出"，再决定是补预算还是调整分类。
 *
 * 预算只挂在一级分类上，所以二级分类的账单要先并回所属一级分类再判断是否"有预算"。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UnbudgetedBillsScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onEditTransaction: (Long) -> Unit,
) {
    val rows by vm.periodRows.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val mode by vm.periodMode.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val categoryBudgets by vm.categoryBudgets.collectAsStateWithLifecycle()

    val rowsReady = rows ?: return

    // 已设预算（>0）的一级分类 id 集合；空集合时所有支出都算"未设预算"
    val budgetedTopIds = remember(categoryBudgets) {
        categoryBudgets.entries.filter { it.value > 0L }.map { it.key }.toSet()
    }

    val byId = remember(categories) { categories.associateBy { it.id } }

    val unbudgeted = remember(rowsReady, budgetedTopIds, byId) {
        rowsReady.filter { row ->
            if (row.type != TxType.EXPENSE) return@filter false
            // 把二级分类并到所属一级分类；找不到所属一级的（分类被删等异常）也归入未设预算
            val topId = byId[row.categoryId]?.parentId ?: row.categoryId
            topId !in budgetedTopIds
        }
    }

    val listState = rememberLazyListState()
    val grouped = remember(unbudgeted) { groupRowsByDay(unbudgeted) }
    val allDays = grouped.keys.toList()
    val shownDays = remember(allDays, grouped) {
        // 这里列表短，直接全量显示，不做分页；用 pageDays 的全量视图
        pageDays(allDays, grouped, unbudgeted.size.coerceAtLeast(1)).first
    }

    val onRowClick by rememberUpdatedState<(Long) -> Unit> { onEditTransaction(it) }
    val periodText = periodLabel(month, mode)

    Column(Modifier.fillMaxSize()) {
        EcoTopBar(micro = "CATEGORY / $periodText", title = "未设预算分类", onBack = onBack)

        Text(
            "这些支出所属的一级分类没有设定预算。点击可编辑账单，或到「个人 → 分类」为该分类设定预算。",
            style = EcoType.PageNote.copy(fontSize = 11.sp),
            color = MaterialTheme.eco.quiet,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 10.dp),
        )

        when {
            unbudgeted.isEmpty() -> BillEmpty(
                title = "没有未设预算的支出",
                body = "当前周期所有支出分类都已设预算。",
                modifier = Modifier.weight(1f),
            )
            else -> {
                Box(Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        shownDays.forEach { day ->
                            val dayRows = grouped[day].orEmpty()
                            stickyHeader(key = "d${day.toEpochDay()}") {
                                DayHeader(day = day, dayRows = dayRows, onClickDay = null)
                            }
                            items(dayRows, key = { it.id }) { row ->
                                BillListItem(
                                    row = row,
                                    selecting = false,
                                    selected = false,
                                    showDate = false,
                                    onClick = { onRowClick(row.id) },
                                    onLongClick = {},
                                )
                            }
                        }
                    }
                }
            }
        }

        // 底部汇总：未设预算的支出合计
        val total = remember(unbudgeted) { unbudgeted.sumOf { it.amountCents } }
        if (unbudgeted.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                Text(
                    "未设预算支出合计",
                    style = EcoType.PageNote.copy(fontSize = 10.sp),
                    color = MaterialTheme.eco.quiet,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "¥${Money.formatYuan(total)}",
                    style = EcoType.CatAmount.copy(fontSize = 16.sp),
                    color = MaterialTheme.eco.clay,
                )
            }
        }
    }
}
