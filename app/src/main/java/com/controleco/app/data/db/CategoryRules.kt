package com.controleco.app.data.db

/**
 * 分类的**保留名与查重规则**（纯函数，可单测；VM 与界面共用同一套判断）。
 *
 * 被「锁」住的分类不允许改名 / 改类型 / 删除：
 * 1. 兜底分类「其他」：删除别的分类时，其流水要改挂到同类型的「其他」，改名/改类型会让兜底失效；
 * 2. 收入「退款」「AA」「报销」：这三类收入是关联抵扣入账的固定去向（见 [RefundCategory] /
 *    [AACategory] / [ReimbursementCategory]），改名/改类型/删除会让这些收入项在统计里消失，
 *    历史与后续关联也没法归位。
 *
 * 查重只比**同一类型**：支出与收入各自可以有一个「餐饮」，互不影响；名称按 `trim()` 后比较。
 */
object CategoryRules {

    /** 兜底分类名（支出、收入各一个） */
    const val FALLBACK_NAME = "其他"

    fun isFallbackName(name: String): Boolean = name.trim() == FALLBACK_NAME

    /** 收入侧的保留分类（退款 / AA / 报销）：与「其他」一样锁定 */
    private val RESERVED_INCOME_NAMES = setOf(
        RefundCategory.NAME,
        AACategory.NAME,
        ReimbursementCategory.NAME,
    )

    fun isReservedIncome(type: TxType, name: String): Boolean =
        type == TxType.INCOME && name.trim() in RESERVED_INCOME_NAMES

    /** 界面上锁：不渲染名称输入框、类型分段与删除按钮 */
    fun isLocked(category: CategoryEntity): Boolean =
        isFallbackName(category.name) || isReservedIncome(category.type, category.name)

    /**
     * 改名 / 改类型校验：返回错误文案，`null` = 允许。
     * [current] 为 null（新增）时永远允许。
     */
    fun editError(current: CategoryEntity?, newName: String, newType: TxType): String? {
        if (current == null) return null
        val name = newName.trim()
        if (isFallbackName(current.name) && (name != FALLBACK_NAME || newType != current.type)) {
            return "「$FALLBACK_NAME」不能改名或修改类型"
        }
        if (isReservedIncome(current.type, current.name)) {
            if (name != current.name.trim() || newType != current.type) {
                return "「${current.name.trim()}」不能改名或修改类型"
            }
        }
        return null
    }

    /** 删除校验：返回错误文案，`null` = 允许 */
    fun deleteError(category: CategoryEntity): String? = when {
        isFallbackName(category.name) -> "「$FALLBACK_NAME」不能删除"
        isReservedIncome(category.type, category.name) -> "「${category.name.trim()}」不能删除"
        else -> null
    }

    /** 同类型重名校验：返回错误文案，`null` = 允许（[id] 是正在编辑的分类，新增传 0） */
    fun duplicateNameError(
        all: List<CategoryEntity>,
        id: Long,
        newName: String,
        newType: TxType,
    ): String? =
        if (all.any { it.id != id && it.type == newType && it.name.trim() == newName.trim() }) {
            "已存在同名分类"
        } else {
            null
        }
}
