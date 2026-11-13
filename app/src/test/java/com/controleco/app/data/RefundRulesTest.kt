package com.controleco.app.data

import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.data.db.TxType
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RefundRulesTest {
    private val expense = TransactionEntity(
        id = 1, type = TxType.EXPENSE, amountCents = 2500,
        categoryId = 1, accountId = 1, timestamp = 1000,
    )
    private val ordinaryIncome = CategoryEntity(id = 2, name = "其他", type = TxType.INCOME)
    private val refundCategory = CategoryEntity(id = 3, name = "退款", type = TxType.INCOME)
    private val expenseCategory = CategoryEntity(id = 1, name = "餐饮", type = TxType.EXPENSE)

    @Test
    fun `captured refund requires exact amount while amountless refund accepts any valid expense`() {
        assertNull(RefundRules.expenseError(expense))
        assertNull(RefundRules.expenseError(expense, 2500))
        assertNotNull(RefundRules.expenseError(expense, 2499))
        assertNotNull(RefundRules.expenseError(expense, 2501))
        assertNotNull(RefundRules.expenseError(expense, -1))
        assertNotNull(RefundRules.expenseError(expense, 2500, hasLinkedIncome = true))
    }

    @Test
    fun `refund selection rejects deleted income refunded and invalid bills`() {
        assertNotNull(RefundRules.expenseError(null))
        assertNotNull(RefundRules.expenseError(expense.copy(type = TxType.INCOME)))
        assertNotNull(RefundRules.expenseError(expense.copy(refundMark = RefundMark.REFUNDED_EXPENSE)))
        assertNotNull(RefundRules.expenseError(expense.copy(amountCents = 0)))
        assertNotNull(RefundRules.expenseError(expense.copy(amountCents = -1)))
    }

    @Test
    fun `undo only accepts one exact linked full refund`() {
        val refunded = expense.copy(refundMark = RefundMark.REFUNDED_EXPENSE)
        val refund = TransactionEntity(
            id = 9, type = TxType.INCOME, amountCents = expense.amountCents,
            categoryId = refundCategory.id, accountId = expense.accountId, timestamp = 2000,
            refundMark = RefundMark.REFUND_INCOME, refundLinkId = expense.id,
        )
        assertNull(RefundRules.undoError(refunded, listOf(refund)))
        assertNotNull(RefundRules.undoError(refunded, emptyList()))
        assertNotNull(RefundRules.undoError(refunded, listOf(refund, refund.copy(id = 10))))
        assertNotNull(RefundRules.undoError(refunded, listOf(refund.copy(amountCents = 2499))))
        assertNotNull(RefundRules.undoError(refunded, listOf(refund.copy(refundLinkId = 99))))
        assertNotNull(RefundRules.undoError(refunded, listOf(refund.copy(refundMark = RefundMark.NONE))))
        assertNotNull(RefundRules.undoError(expense, listOf(refund)))
        assertNotNull(RefundRules.undoError(null, listOf(refund)))
    }

    @Test
    fun `quick add and ordinary editing cannot create refund income`() {
        assertNotNull(RefundRules.editError(null, TxType.INCOME, refundCategory))
        assertNotNull(RefundRules.editError(expense, TxType.INCOME, refundCategory))
        assertNotNull(RefundRules.editError(expense.copy(type = TxType.INCOME), TxType.INCOME, refundCategory))
        assertNull(RefundRules.editError(null, TxType.INCOME, ordinaryIncome))
        assertNull(RefundRules.editError(expense, TxType.EXPENSE, expenseCategory))
    }

    @Test
    fun `refund markers cannot be left on opposite direction or ordinary income category`() {
        val refund = expense.copy(type = TxType.INCOME, categoryId = refundCategory.id, refundMark = RefundMark.REFUND_INCOME)
        val refunded = expense.copy(refundMark = RefundMark.REFUNDED_EXPENSE)
        assertNull(RefundRules.editError(refund, TxType.INCOME, refundCategory))
        assertNotNull(RefundRules.editError(refund, TxType.INCOME, ordinaryIncome))
        assertNotNull(RefundRules.editError(refund, TxType.EXPENSE, expenseCategory))
        assertNull(RefundRules.editError(refunded, TxType.EXPENSE, expenseCategory))
        assertNotNull(RefundRules.editError(refunded, TxType.INCOME, ordinaryIncome))
        assertNotNull(RefundRules.editError(null, TxType.EXPENSE, ordinaryIncome))
    }
}
