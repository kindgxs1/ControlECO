package com.controleco.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.CategoryTree
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.CategoryMappingEntity
import com.controleco.app.data.db.CategoryMatchType
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.eco.EcoChip
import com.controleco.app.ui.eco.EcoConfirm
import com.controleco.app.ui.eco.EcoEmpty
import com.controleco.app.ui.eco.EcoField
import com.controleco.app.ui.eco.EcoIconButton
import com.controleco.app.ui.eco.EcoPageNote
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoRowDivider
import com.controleco.app.ui.eco.EcoSheet
import com.controleco.app.ui.eco.EcoToast
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco

/**
 * 账单分类映射规则管理页。
 *
 * 每条规则 = 账单来源（可通用）+ 匹配类型（分类名 / 交易类型）+ 匹配值 + 收支方向 → 软件内分类。
 * 导入时先查此表，命中则用映射的分类 id；不命中再按分类名匹配兜底。
 *
 * 点击列表项可编辑；规则按（来源 + 匹配类型 + 匹配值）唯一，保存时幂等覆盖。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryMappingScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val mappings by vm.categoryMappings.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    // editingRule == null → 新增；editingRule != null → 编辑
    var editingRule by remember { mutableStateOf<CategoryMappingEntity?>(null) }
    var showDeleteId by remember { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize()) {
        EcoTopBar(micro = "CATEGORY MAPPING", title = "分类映射规则", onBack = onBack)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            EcoPrimaryButton(
                text = "添加规则",
                leading = EcoIcons.Plus,
                onClick = { editingRule = CategoryMappingEntity() },
                modifier = Modifier.padding(end = 0.dp),
                height = 38.dp,
            )
        }
        if (mappings.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 28.dp),
                ) {
                    EcoEmpty(
                        title = "暂无映射规则",
                        body = "默认按账单自带的分类名匹配软件内同名分类。\n添加规则可让导入自动命中软件内的对应分类（一级或二级均可）。",
                        icon = EcoIcons.Tag,
                    )
                    Spacer(Modifier.height(16.dp))
                    EcoPrimaryButton(
                        text = "添加第一条规则",
                        onClick = { editingRule = CategoryMappingEntity() },
                        height = 42.dp,
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
            ) {
                item { EcoPageNote("导入命中时优先用本表，不命中再按分类名匹配；规则按（来源 + 匹配类型 + 匹配值）唯一。点击规则可编辑。") }
                items(mappings, key = { it.id }) { rule ->
                    MappingRow(
                        rule = rule,
                        category = categories.firstOrNull { it.id == rule.targetCategoryId },
                        allCategories = categories,
                        onClick = { editingRule = rule },
                        onDelete = { showDeleteId = rule.id },
                    )
                }
            }
        }
    }

    if (editingRule != null) {
        MappingRuleEditor(
            categories = categories,
            initial = editingRule,
            onSave = { rule ->
                vm.upsertCategoryMapping(rule)
                editingRule = null
                EcoToast.show(if (rule.id > 0) "已保存修改" else "已添加")
            },
            onDismiss = { editingRule = null },
        )
    }
    val deleteId = showDeleteId
    if (deleteId != null) {
        EcoConfirm(
            title = "删除这条规则",
            body = "删除后下次导入不再按这条规则匹配，将回落到按分类名匹配。",
            confirmText = "删除",
            onConfirm = {
                vm.deleteCategoryMapping(deleteId)
                showDeleteId = null
                EcoToast.show("已删除")
            },
            onDismiss = { showDeleteId = null },
        )
    }
}

@Composable
private fun MappingRow(
    rule: CategoryMappingEntity,
    category: CategoryEntity?,
    allCategories: List<CategoryEntity>,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = MaterialTheme.eco
    EcoPane(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val sourceLabel = when (rule.source) {
                    "WECHAT" -> "微信支付"
                    "ALIPAY" -> "支付宝"
                    "" -> "通用"
                    else -> rule.source
                }
                val typeLabel = when (rule.txType) {
                    TxType.EXPENSE -> "支出"
                    TxType.INCOME -> "收入"
                }
                Text(
                    "$sourceLabel · $typeLabel · ${rule.matchValue}",
                    style = EcoType.PageLead,
                    color = c.ink,
                )
                val targetLabel = category?.let { CategoryTree.displayName(allCategories, it.id) }
                    ?: "(分类已删除)"
                val matchLabel = when (rule.matchType) {
                    CategoryMatchType.TRADE_TYPE -> "交易类型"
                    else -> "分类名"
                }
                Text(
                    "账单$matchLabel → 软件内「$targetLabel」",
                    style = EcoType.PageNote,
                    color = c.muted,
                )
                if (rule.note.isNotBlank()) {
                    Text(rule.note, style = EcoType.PageNote, color = c.muted)
                }
            }
            EcoIconButton(
                icon = EcoIcons.Close,
                onClick = onDelete,
                contentDescription = "删除这条规则",
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MappingRuleEditor(
    categories: List<CategoryEntity>,
    initial: CategoryMappingEntity?,
    onSave: (CategoryMappingEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.eco
    var source by remember { mutableStateOf(initial?.source ?: "") }
    var matchType by remember { mutableStateOf(initial?.matchType ?: CategoryMatchType.CATEGORY) }
    var matchValue by remember { mutableStateOf(initial?.matchValue ?: "") }
    var txType by remember { mutableStateOf(initial?.txType ?: TxType.EXPENSE) }
    var targetCategoryId by remember { mutableStateOf(initial?.targetCategoryId ?: 0L) }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var targetExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 收支方向切换时，重置目标分类到同方向的第一个分类
    LaunchedEffect(txType) {
        val sameDir = categories.firstOrNull { it.type == txType && it.id == targetCategoryId }
            ?: categories.firstOrNull { it.type == txType }
        if (sameDir != null) targetCategoryId = sameDir.id
    }
    LaunchedEffect(categories) {
        if (targetCategoryId == 0L) {
            targetCategoryId = categories.firstOrNull { it.type == txType }?.id ?: 0L
        }
    }

    EcoSheet(
        onDismiss = onDismiss,
        title = if (initial == null) "添加映射规则" else "编辑规则",
        subtitle = "账单分类名 / 交易类型 → 软件内分类",
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 来源
            Text("账单来源", style = EcoType.FieldLabel, color = c.muted)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("" to "通用", "WECHAT" to "微信支付", "ALIPAY" to "支付宝").forEach { (v, l) ->
                    EcoChip(
                        text = l,
                        selected = source == v,
                        onClick = { source = v },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            // 收支方向
            Text("收支方向", style = EcoType.FieldLabel, color = c.muted)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TxType.EXPENSE to "支出", TxType.INCOME to "收入").forEach { (v, l) ->
                    EcoChip(
                        text = l,
                        selected = txType == v,
                        onClick = { txType = v },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            // 匹配类型
            Text("匹配类型", style = EcoType.FieldLabel, color = c.muted)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    CategoryMatchType.CATEGORY to "分类名（账单自带）",
                    CategoryMatchType.TRADE_TYPE to "交易类型（账单自带）",
                ).forEach { (v, l) ->
                    EcoChip(
                        text = l,
                        selected = matchType == v,
                        onClick = { matchType = v },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            // 匹配值
            EcoField(
                label = "匹配值",
                value = matchValue,
                onValueChange = { matchValue = it; error = null },
                placeholder = "例如：服饰美容 / 餐饮美食",
                minHeight = 38.dp,
            )
            Spacer(Modifier.height(14.dp))

            // 目标分类（可点击的只读字段）
            Text("映射到软件内分类", style = EcoType.FieldLabel, color = c.muted)
            Spacer(Modifier.height(4.dp))
            val targetLabel = run {
                val cat = categories.firstOrNull { it.id == targetCategoryId && it.type == txType }
                if (cat != null) CategoryTree.displayName(categories, cat.id)
                else categories.filter { it.type == txType }.firstOrNull()?.let {
                    CategoryTree.displayName(categories, it.id)
                } ?: "(请先在分类管理添加分类)"
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(43.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(c.surface2)
                    .border(1.dp, c.rule, RoundedCornerShape(11.dp))
                    .clickable { targetExpanded = true }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = targetLabel,
                    style = EcoType.Input,
                    color = c.ink,
                )
            }
            Spacer(Modifier.height(14.dp))

            // 备注
            EcoField(
                label = "备注（可选）",
                value = note,
                onValueChange = { note = it },
                placeholder = "说明这条规则的用途，便于日后维护",
                minHeight = 38.dp,
            )

            if (error != null) {
                Text(error!!, style = EcoType.PageNote, color = c.clay)
            }
            Spacer(Modifier.height(18.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                EcoPrimaryButton(
                    text = if (initial != null && initial.id > 0) "保存修改" else "保存",
                    onClick = {
                        val v = matchValue.trim()
                        when {
                            v.isBlank() -> error = "匹配值不能为空"
                            targetCategoryId == 0L -> error = "请选择目标分类"
                            else -> {
                                // 编辑模式保留原 id + createdAt；新增时走 autoGenerate
                                val base = initial?.takeIf { it.id > 0 } ?: CategoryMappingEntity()
                                onSave(
                                    base.copy(
                                        source = source,
                                        matchType = matchType,
                                        matchValue = v,
                                        txType = txType,
                                        targetCategoryId = targetCategoryId,
                                        note = note.trim(),
                                        updatedAt = System.currentTimeMillis(),
                                    ),
                                )
                            }
                        }
                    },
                    modifier = Modifier.width(140.dp),
                    height = 40.dp,
                )
            }

            if (targetExpanded) {
                EcoSheet(
                    onDismiss = { targetExpanded = false },
                    title = "选择目标分类",
                    subtitle = "${if (txType == TxType.EXPENSE) "支出" else "收入"}分类均可（一级或二级都行）",
                ) {
                    val dirCats = categories.filter { it.type == txType }
                    val topLevel = CategoryTree.topLevel(dirCats, txType)
                    Column {
                        topLevel.forEachIndexed { idx, parent ->
                            val selected = targetCategoryId == parent.id
                            EcoChip(
                                text = CategoryTree.displayName(dirCats, parent.id),
                                selected = selected,
                                onClick = {
                                    targetCategoryId = parent.id
                                    targetExpanded = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            CategoryTree.childrenOf(dirCats, parent.id).forEach { child ->
                                val childSelected = targetCategoryId == child.id
                                EcoChip(
                                    text = "  · ${child.name}",
                                    selected = childSelected,
                                    onClick = {
                                        targetCategoryId = child.id
                                        targetExpanded = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (idx != topLevel.lastIndex) EcoRowDivider()
                        }
                    }
                }
            }
        }
    }
}
