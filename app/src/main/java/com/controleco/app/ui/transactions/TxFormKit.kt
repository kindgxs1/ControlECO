package com.controleco.app.ui.transactions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.WheelPicker
import com.controleco.app.ui.common.WheelTrigger
import com.controleco.app.ui.eco.EcoChip
import com.controleco.app.ui.eco.EcoField
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoSegmented
import com.controleco.app.ui.eco.EcoSheet
import com.controleco.app.ui.eco.EcoSwitch
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.accBook
import com.controleco.app.ui.theme.categoryColor
import com.controleco.app.ui.theme.eco
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 记账 / 编辑记录两页共用的表单件（只在 ui/transactions 内使用）。
 *
 * 版式对齐设计稿：小节抬头 9/690、字段标签 8.5/680、字段主体是
 * 「surface2 底 + 1dp 发丝描边 + 11dp 圆角」的行，行内不放 Material 输入框，
 * 避免两页出现两套描边/焦点样式。
 */

// ---------- 文本样式 ----------

private fun txText(size: Float, weight: Int, em: Float = 0f, lineHeight: Float = size * 1.25f) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = (size * em).sp,
    lineHeight = lineHeight.sp,
    fontFeatureSettings = "tnum",
)

internal val TxSectionLabelStyle = txText(9f, 690, 0.04f, 12f)
internal val TxCategoryNameStyle = txText(11f, 740, 0f, 15f)
internal val TxToggleStyle = txText(10f, 690, 0f, 13f)
internal val TxDeleteLinkStyle = txText(10f, 680, 0f, 13f)
internal val TxErrorStyle = txText(10f, 710, 0f, 14f)
internal val TxAmountLabelStyle = txText(9f, 740, 0.19f, 12f)
internal val TxCurrencyStyle = txText(20f, 790, 0f, 24f)
internal val TxEditCurrencyStyle = txText(19f, 700, 0f, 23f)
internal val TxKeypadStyle = txText(20f, 700, 0f, 24f)

private val TxDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")
private val TxTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

// 时间滚轮的三列选项（两位数字，避免滚轮宽度随位数跳动）
private fun txTwoDigits(value: Int): String = String.format(Locale.ROOT, "%02d", value)

private val txHourItems = (0..23).map(::txTwoDigits)
private val txMinuteItems = (0..59).map(::txTwoDigits)
private val txSecondItems = (0..59).map(::txTwoDigits)

/** 时间 → `HH:mm:ss`（滚轮与手动输入共用的展示口径） */
private fun txFormatTime(hour: Int, minute: Int, second: Int): String =
    String.format(Locale.ROOT, "%02d:%02d:%02d", hour, minute, second)

/**
 * 手动输入的时间解析（纯函数，`TxFormKitTest` 覆盖）。
 * 支持 `HH:mm:ss` / `HH:mm` / `HHmmss` / `HHmm` / 单个小时数字；
 * 任意非数字分隔（`: / . 空格 中文冒号`）都当分隔符。越界或解析失败返回 null。
 */
internal fun parseTimeInput(raw: String): LocalTime? {
    val text = raw.trim().replace('：', ':')
    if (text.isEmpty()) return null
    val groups = text.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }
    if (groups.isEmpty()) return null
    if (groups.size == 1 && groups[0].length > 2) {
        // 连写的 3~6 位：HHmm / HHmmss（"1230" = 12:30，"123045" = 12:30:45）
        val digits = groups[0]
        return when (digits.length) {
            3, 4 -> runCatching {
                LocalTime.of(digits.dropLast(2).toInt(), digits.takeLast(2).toInt())
            }.getOrNull()
            5, 6 -> runCatching {
                LocalTime.of(
                    digits.dropLast(4).toInt(),
                    digits.dropLast(2).takeLast(2).toInt(),
                    digits.takeLast(2).toInt(),
                )
            }.getOrNull()
            else -> null
        }
    }
    if (groups.size > 3) return null
    val hour = groups.getOrNull(0)?.toIntOrNull() ?: return null
    val minute = groups.getOrNull(1)?.toIntOrNull() ?: 0
    val second = groups.getOrNull(2)?.toIntOrNull() ?: 0
    return if (hour in 0..23 && minute in 0..59 && second in 0..59) {
        LocalTime.of(hour, minute, second)
    } else {
        null
    }
}

// ---------- 标题 / 标签 ----------

/** 分组抬头：类型 / 分类 / 账户与日期 …（9/690 灰） */
@Composable
fun TxSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TxSectionLabelStyle, color = MaterialTheme.eco.muted, modifier = modifier)
}

/** 字段标签：账户 / 日期 / 商户 / 备注（用设计系统的 FieldLabel，和 EcoField 完全一致） */
@Composable
fun TxFieldLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.eco.muted) {
    Text(text, style = EcoType.FieldLabel, color = color, modifier = modifier)
}

// ---------- 选择行 / 日期行 / 输入行 ----------

/**
 * 下拉选择行（账户、分类）。
 * [elevation] > 0 时带卡片阴影（快速记账的分类选择条）。
 */
@Composable
fun TxSelectField(
    value: String,
    options: List<Pair<Long, String>>,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "请选择",
    height: Dp = 43.dp,
    radius: Dp = 11.dp,
    leading: (@Composable () -> Unit)? = null,
    textStyle: TextStyle = EcoType.Input,
    container: Color = MaterialTheme.eco.surface2,
    borderColor: Color = MaterialTheme.eco.rule,
    elevation: Dp = 0.dp,
    chevronTint: Color = MaterialTheme.eco.muted,
    chevronSize: Dp = 14.dp,
    horizontalPadding: Dp = 10.dp,
    contentDescription: String = "选择$placeholder",
) {
    val c = MaterialTheme.eco
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(radius)
    Column(modifier) {
        if (label != null) {
            TxFieldLabel(label)
            Spacer(Modifier.height(6.dp))
        }
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .then(
                        if (elevation > 0.dp) {
                            Modifier.shadow(
                                elevation = elevation,
                                shape = shape,
                                clip = false,
                                ambientColor = c.ink.copy(alpha = if (c.isDark) 0.3f else 0.08f),
                                spotColor = c.ink.copy(alpha = if (c.isDark) 0.34f else 0.12f),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(shape)
                    .background(container)
                    .border(1.dp, borderColor, shape)
                    .clickable { expanded = true }
                    .padding(horizontal = horizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = value.ifBlank { placeholder },
                    style = textStyle,
                    color = if (value.isBlank()) c.quiet else c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    EcoIcons.ChevDown,
                    contentDescription = contentDescription,
                    tint = chevronTint,
                    modifier = Modifier.size(chevronSize),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name, style = EcoType.Input) },
                        onClick = {
                            onSelect(id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/** 日期行：yyyy/MM/dd + 日历图标，点击开系统日期面板 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxDateField(
    date: LocalDate,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    height: Dp = 43.dp,
    radius: Dp = 11.dp,
    icon: ImageVector = EcoIcons.Calendar,
) {
    val c = MaterialTheme.eco
    var show by remember { mutableStateOf(false) }
    if (show) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        onPick(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    show = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("取消") } },
        ) {
            DatePicker(state = state)
        }
    }
    val shape = RoundedCornerShape(radius)
    Column(modifier) {
        if (label != null) {
            TxFieldLabel(label)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(shape)
                .background(c.surface2)
                .border(1.dp, c.rule, shape)
                .clickable { show = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                date.format(TxDateFormatter),
                style = EcoType.Input,
                color = c.ink,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Icon(icon, contentDescription = "选择日期", tint = c.muted, modifier = Modifier.size(15.dp))
        }
    }
}

/**
 * 可选时间行：null = 留空使用当前时间；点击打开时分秒选择面板
 * （三列滚轮 + 手动输入 + 清空/确认）。
 */
@Composable
fun TxTimeField(
    time: LocalTime?,
    onPick: (LocalTime?) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    height: Dp = 43.dp,
    radius: Dp = 11.dp,
    icon: ImageVector = EcoIcons.Clock,
) {
    val c = MaterialTheme.eco
    var show by remember { mutableStateOf(false) }
    if (show) {
        TxTimeSheet(
            initial = time,
            onDismiss = { show = false },
            onPick = {
                onPick(it)
                show = false
            },
        )
    }
    val shape = RoundedCornerShape(radius)
    Column(modifier) {
        if (label != null) {
            TxFieldLabel(label)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(shape)
                .background(c.surface2)
                .border(1.dp, c.rule, shape)
                .clickable { show = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                time?.format(TxTimeFormatter) ?: "留空=当前时间",
                style = EcoType.Input,
                color = if (time != null) c.ink else c.quiet,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Icon(icon, contentDescription = "选择时间", tint = c.muted, modifier = Modifier.size(15.dp))
        }
    }
}

/**
 * 时间选择面板：左「时」、中「分」、右「秒」三列滚轮（上下滑动），
 * 下方可手动输入 `时:分:秒`，底部只有「清空」与「确认」两个动作。
 * 滚轮滚动与手动输入双向同步，避免两处数值各说各话。
 */
@Composable
private fun TxTimeSheet(
    initial: LocalTime?,
    onDismiss: () -> Unit,
    onPick: (LocalTime?) -> Unit,
) {
    val start = initial ?: LocalTime.now()
    var hour by remember { mutableIntStateOf(start.hour) }
    var minute by remember { mutableIntStateOf(start.minute) }
    var second by remember { mutableIntStateOf(start.second) }
    var manual by remember { mutableStateOf(initial?.format(TxTimeFormatter).orEmpty()) }
    var manualError by remember { mutableStateOf(false) }

    // 滚轮变化 → 回写手动输入框；手动输入合法 → 回写滚轮
    fun applyWheel(h: Int, m: Int, s: Int) {
        hour = h
        minute = m
        second = s
        manual = txFormatTime(h, m, s)
        manualError = false
    }

    EcoSheet(
        onDismiss = onDismiss,
        title = "选择时间",
        subtitle = "上下滑动选择时、分、秒，也可以直接输入",
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TimeWheelColumn("时", txHourItems, hour) { applyWheel(it, minute, second) }
                TimeSeparator()
                TimeWheelColumn("分", txMinuteItems, minute) { applyWheel(hour, it, second) }
                TimeSeparator()
                TimeWheelColumn("秒", txSecondItems, second) { applyWheel(hour, minute, it) }
            }
            Spacer(Modifier.height(14.dp))
            EcoField(
                label = "手动输入",
                value = manual,
                onValueChange = { raw ->
                    manual = raw
                    manualError = false
                    parseTimeInput(raw)?.let { picked ->
                        hour = picked.hour
                        minute = picked.minute
                        second = picked.second
                    }
                },
                placeholder = "例如 12:30:45（或 123045）",
                keyboardType = KeyboardType.Number,
                isError = manualError,
                supporting = if (manualError) {
                    "时间格式不对：按 时:分:秒 输入（分隔符可省略）"
                } else {
                    "留空点确认 = 使用滚轮选择的时间；点清空 = 留空用当前时刻"
                },
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EcoGhostButton(
                    text = "清空",
                    onClick = { onPick(null) },
                    modifier = Modifier.weight(1f),
                )
                EcoPrimaryButton(
                    text = "确认",
                    onClick = {
                        val picked = if (manual.isBlank()) {
                            LocalTime.of(hour, minute, second)
                        } else {
                            parseTimeInput(manual)
                        }
                        if (picked == null) {
                            manualError = true
                        } else {
                            onPick(picked)
                        }
                    },
                    modifier = Modifier.weight(1.4f),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 时间滚轮的一列：上方单位标签 + 可上下滑动的滚轮 */
@Composable
private fun TimeWheelColumn(
    label: String,
    items: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = EcoType.FieldLabel, color = MaterialTheme.eco.muted)
        Spacer(Modifier.height(4.dp))
        WheelPicker(
            items = items,
            selectedIndex = selected,
            onSelected = onSelected,
            modifier = Modifier.width(76.dp),
            label = label,
            itemHeight = 30.dp,
            rows = 3,
            expandedRows = 5,
            trigger = WheelTrigger.DRAG,
            updateWhileScrolling = true,
        )
    }
}

/** 时/分/秒之间的分隔符（与滚轮中心对齐） */
@Composable
private fun TimeSeparator() {
    Text(
        ":",
        style = EcoType.SectionTitle,
        color = MaterialTheme.eco.muted,
        modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 26.dp),
    )
}

/**
 * 紧凑开关行：左侧标题（可带副标）+ 右侧开关。
 * 记账 / 编辑记录 / 悬浮窗确认卡共用「是否计入资产统计」开关。
 */
@Composable
fun TxSwitchField(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val c = MaterialTheme.eco
    Row(
        modifier.fillMaxWidth().heightIn(min = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = TxToggleStyle, color = c.ink, maxLines = 1)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = EcoType.PageNote, color = c.quiet, maxLines = 2)
            }
        }
        Spacer(Modifier.width(8.dp))
        EcoSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ---------- 收支分段 / 分类 chip ----------

/** 支出 / 收入分段（编辑页；快速记账用更轻的 EcoFilterStrip，贴合各自设计稿） */
@Composable
fun TxTypeSegmented(
    type: TxType,
    onChange: (TxType) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
) {
    EcoSegmented(
        options = listOf("支出", "收入"),
        selectedIndex = if (type == TxType.EXPENSE) 0 else 1,
        onSelect = { onChange(if (it == 0) TxType.EXPENSE else TxType.INCOME) },
        modifier = modifier,
        height = height,
    )
}

/** 分类 chip：带分类色点，选中反白 */
@Composable
fun TxCategoryChip(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 33.dp,
    onLongClick: (() -> Unit)? = null,
) {
    EcoChip(
        text = name,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        height = height,
        onLongClick = onLongClick,
    )
}

// ---------- 数字键盘 ----------

/**
 * 3 列数字键盘：1-9 / . / 0 / 退格（退格长按清空）。
 * 键在按下时铺一层 blueSoft（设计稿 hover/active 态），不做外框以保持纸面感。
 */
@Composable
fun TxKeypad(
    onDigit: (String) -> Unit,
    onDot: () -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember {
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(".", "0", "⌫"),
        )
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { key ->
                    TxKeypadKey(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            when (key) {
                                "." -> onDot()
                                "⌫" -> onBackspace()
                                else -> onDigit(key)
                            }
                        },
                        onLongClick = if (key == "⌫") onClear else null,
                    ) {
                        if (key == "⌫") {
                            Icon(
                                EcoIcons.Backspace,
                                contentDescription = "退格",
                                tint = MaterialTheme.eco.blue,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            Text(key, style = TxKeypadStyle, color = MaterialTheme.eco.ink)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TxKeypadKey(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val c = MaterialTheme.eco
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .height(47.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (pressed) c.blueSoft else Color.Transparent)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
