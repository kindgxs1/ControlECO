package com.controleco.app.csv

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType
import com.controleco.app.data.model.BillSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BillParsersTest {

    private fun millis(y: Int, m: Int, d: Int, h: Int, min: Int, s: Int): Long =
        LocalDateTime.of(y, m, d, h, min, s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ---------- 微信 ----------

    /** 旧版微信 CSV（文本时间）；含一条"已全额退款"支出行（旧版无独立退款收入行） */
    private val wechatCsv = """
        微信支付账单明细
        ---------------------
        微信昵称：测试用户
        起始时间：2024-01-01 00:00:00
        终止时间：2024-01-31 23:59:59
        ---------------------
        导出类型：全部
        导出时间：2024-02-01 10:00:00
        共 4 笔记录
        ---------------------
        交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
        2024-01-02 12:30:00,商户消费,"沙县小吃(XX店)",沙县小吃,支出,¥25.00,零钱,支付成功,4200012345,M10001,
        2024-01-03 09:00:00,微信红包,张三,红包,收入,¥8.88,零钱,已存入零钱,4200067890,M10002,新年红包
        2024-01-04 10:00:00,转账,李四,转账-扫码,/,¥100.00,零钱,已转账,4200099999,M10003,
        2024-01-05 20:00:00,商户消费,某电商,商品订单,支出,¥59.90,招商银行(1234),已全额退款,4200111111,M10004,
    """.trimIndent().trim()

    /** 2026 版微信 xlsx：交易时间为 Excel 日期序列号；退款为成对记录（退款收入行 + 原支出行） */
    private val wechatXlsxShared = listOf(
        "微信支付账单明细",
        "----------------------微信支付账单明细列表--------------------",
        "交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)", "支付方式", "当前状态", "交易单号", "商户单号", "备注",
        "商户消费", "luckin coffee", "订单付款", "支出", "零钱", "支付成功",
        "微信红包", "红包", "张三", "收入", "已存入零钱", "新年红包",
        "转账", "李四", "中性交易", "已转账",
        "拼多多", "拼多多平台商户", "拼多多平台商户-退款", "已全额退款",
        "测试外卖", "测试外卖-退款", "已退款(¥8.54)",
        "450000202610040001", "450000202610030002", "450000202610020003", "450000202610010004",
        "450000202609300005", "450000202609300006", "450000202609290007",
        "M10001", "M10002", "M10003", "M10004", "M10005",
    )

    private fun buildWechatXlsx(): ByteArray {
        val rows = listOf(
            listOf<Any?>("微信支付账单明细"),
            listOf<Any?>("----------------------微信支付账单明细列表--------------------"),
            listOf<Any?>(
                "交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)",
                "支付方式", "当前状态", "交易单号", "商户单号", "备注",
            ),
            // 正常支出
            listOf<Any?>(46299.627337962964, "商户消费", "luckin coffee", "订单付款", "支出", 17.25, "零钱", "支付成功", "450000202610040001", "M10001", null),
            // 正常收入
            listOf<Any?>(46298.5, "微信红包", "张三", "红包", "收入", 8.88, "零钱", "已存入零钱", "450000202610030002", "M10002", "新年红包"),
            // 全额退款对：原支出行 + 退款收入行
            listOf<Any?>(46297.9, "商户消费", "拼多多", "拼多多", "支出", 45.4, "零钱", "已全额退款", "450000202610020003", "M10003", null),
            listOf<Any?>(46297.8, "拼多多平台商户-退款", "拼多多平台商户", "拼多多", "收入", 45.4, "零钱", "已全额退款", "450000202610010004", null, null),
            // 部分退款对：原支出行保持普通支出（39.8 中退了 8.54），退款收入行单独记录
            listOf<Any?>(46296.9, "商户消费", "测试外卖", "订单付款", "支出", 39.8, "零钱", "已退款(¥8.54)", "450000202609300005", "M10004", null),
            listOf<Any?>(46296.8, "测试外卖-退款", "测试外卖", "测试外卖", "收入", 8.54, "零钱", "已退款(¥8.54)", "450000202609300006", null, null),
            // 中性交易：跳过
            listOf<Any?>(46295.0, "转账", "李四", "转账", "中性交易", 100.0, "零钱", "已转账", "450000202609290007", "M10005", null),
        )
        return buildXlsx(wechatXlsxShared, rows)
    }

    /** 在测试内构造最小 xlsx（zip 包含 sheet1 与 sharedStrings） */
    private fun buildXlsx(shared: List<String>, rows: List<List<Any?>>): ByteArray {
        val sharedXml = buildString {
            append("<sst>")
            shared.forEach { append("<si><t>").append(it).append("</t></si>") }
            append("</sst>")
        }
        val sheetXml = buildString {
            append("<worksheet><sheetData>")
            rows.forEachIndexed { r, cells ->
                append("<row r=\"").append(r + 1).append("\">")
                cells.forEachIndexed { c, v ->
                    val ref = "${'A' + c}${r + 1}"
                    when (v) {
                        is String -> append("<c r=\"$ref\" t=\"s\"><v>${shared.indexOf(v)}</v></c>")
                        is Double -> append("<c r=\"$ref\"><v>$v</v></c>")
                        is Int -> append("<c r=\"$ref\"><v>$v</v></c>")
                        else -> append("<c r=\"$ref\"/>")
                    }
                }
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheetXml.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write(sharedXml.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
        return out.toByteArray()
    }

    // ---------- 支付宝 ----------

    /** 2026 版支付宝 CSV：GBK、新增交易分类列、交易订单号带尾部制表符；含"交易关闭+退款"成对记录 */
    private val alipayCsv2026 = listOf(
        "------------------------------------------------------------------------------------",
        "导出信息：",
        "起始时间：[2026-07-04 00:00:00]    终止时间：[2026-10-04 23:59:59]",
        "共461笔记录",
        "------------------------支付宝支付科技有限公司  电子客户回单------------------------",
        "交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注,",
        "2026-10-04 17:33:04,餐饮美食,淘宝闪购,e50***@alibaba-inc.com,干饭人木桶饭外卖订单,支出,19.50,中国银行储蓄卡(8920),交易成功,2026100423001148451423351129\t,13170600726100475857272451365\t,,",
        "2026-10-03 23:11:22,文化休闲,Steam,sup***@nuvei.com,Steam Purchase,支出,34.80,中国银行储蓄卡(8920),交易关闭,2026100322001448451421675858\t,2026100319074101000920060298353\t,,",
        "2026-10-04 05:19:08,退款,Steam,sup***@nuvei.com,退款-Steam Purchase,不计收支,34.80,中国银行储蓄卡(8920),退款成功,2026100322001448451421675858_2026100319074102000920019200572\t,2026100319074101000920060298353\t,,",
        "2026-10-03 08:00:00,人情往来,王五,/,转账,收入,500.00,余额,交易成功,202401032188880002\t,,饭钱",
        "2026-10-01 09:00:00,提现,/,/,余额提现-提现到银行卡,不计收支,1000.00,零钱,提现成功,202610010900000001\t,,,",
    ).joinToString("\n")

    /** 旧版支付宝 CSV：交易创建时间/付款时间列、交易号 */
    private val alipayCsvLegacy = """
        支付宝交易记录明细查询
        账号:[test@example.com]
        ---------------------------------
        交易号,商家订单号,交易创建时间,付款时间,最近修改时间,交易来源地,类型,交易对方,商品名称,金额（元）,收/支,交易状态,服务费（元）,成功退款（元）,备注
        202401022188880001,TB100,2024-01-02 18:00:00,2024-01-02 18:00:01,2024-01-02 18:00:01,浙江省杭州市,交易,淘宝网,秋冬外套,129.90,支出,交易成功,0.00,0.00,
        202401032188880002,,2024-01-03 08:00:00,2024-01-03 08:00:00,2024-01-03 08:00:00,上海市,转账,王五,转账,500.00,收入,交易成功,0.00,0.00,饭钱
    """.trimIndent().trim()

    // ---------- 微信测试 ----------

    @Test
    fun `parse wechat legacy csv`() {
        val bill = BillParsers.parse(wechatCsv.toByteArray(Charsets.UTF_8))!!
        assertEquals(BillSource.WECHAT, bill.source)
        assertEquals(1, bill.skippedOtherType)   // "/" 不计收支
        assertEquals(0, bill.skippedStatus)
        assertEquals(1, bill.refundCount)        // 已全额退款支出行
        assertEquals(3, bill.rows.size)

        val r0 = bill.rows[0]
        assertEquals(TxType.EXPENSE, r0.type)
        assertEquals(2500L, r0.amountCents)
        assertEquals("沙县小吃(XX店)", r0.counterparty)
        assertEquals(millis(2024, 1, 2, 12, 30, 0), r0.timestamp)
        assertEquals("4200012345", r0.externalId)
        assertEquals(RefundMark.NONE, r0.refundMark)
        assertEquals("餐饮", r0.guessedCategoryName)

        val r2 = bill.rows[2]
        assertEquals(TxType.EXPENSE, r2.type)
        assertEquals(5990L, r2.amountCents)
        assertEquals("某电商", r2.counterparty)
        assertEquals(RefundMark.REFUNDED_EXPENSE, r2.refundMark)
    }

    @Test
    fun `parse wechat 2026 xlsx`() {
        val bill = BillParsers.parse(buildWechatXlsx())!!
        assertEquals(BillSource.WECHAT, bill.source)
        assertEquals(1, bill.skippedOtherType)   // 中性交易
        assertEquals(0, bill.skippedStatus)
        assertEquals(4, bill.refundCount)        // 全额退款对 2 条 + 部分退款对 2 条
        assertEquals(6, bill.rows.size)

        val r0 = bill.rows[0]
        assertEquals(TxType.EXPENSE, r0.type)
        assertEquals(1725L, r0.amountCents)   // 17.25 元
        assertEquals("luckin coffee", r0.counterparty)
        assertEquals(millis(2026, 10, 4, 15, 3, 22), r0.timestamp)
        assertEquals("450000202610040001", r0.externalId)
        assertEquals(RefundMark.NONE, r0.refundMark)
        assertEquals("餐饮", r0.guessedCategoryName)

        val r1 = bill.rows[1]
        assertEquals(TxType.INCOME, r1.type)
        assertEquals(888L, r1.amountCents)
        assertEquals(millis(2026, 10, 3, 12, 0, 0), r1.timestamp)
        assertEquals(RefundMark.NONE, r1.refundMark)

        // 全额退款对
        val r2 = bill.rows[2]
        assertEquals(TxType.EXPENSE, r2.type)
        assertEquals(4540L, r2.amountCents)
        assertEquals(RefundMark.REFUNDED_EXPENSE, r2.refundMark)

        val r3 = bill.rows[3]
        assertEquals(TxType.INCOME, r3.type)
        assertEquals(4540L, r3.amountCents)
        assertEquals(RefundMark.REFUND_INCOME, r3.refundMark)
        assertEquals("购物", r3.guessedCategoryName)   // 退款对方含"拼多多"

        // 部分退款对：支出保持普通（净支出 31.26 由用户在明细中自行理解），退款额单独成收入
        assertEquals(RefundMark.NONE, bill.rows[4].refundMark)
        assertEquals(3980L, bill.rows[4].amountCents)
        assertEquals(TxType.INCOME, bill.rows[5].type)
        assertEquals(854L, bill.rows[5].amountCents)
        assertEquals(RefundMark.REFUND_INCOME, bill.rows[5].refundMark)
    }

    // ---------- 支付宝测试 ----------

    @Test
    fun `parse alipay 2026 csv`() {
        val bill = BillParsers.parse(alipayCsv2026.toByteArray(Charset.forName("GBK")))!!
        assertEquals(BillSource.ALIPAY, bill.source)
        assertEquals(1, bill.skippedOtherType)   // 提现（不计收支，非退款）
        assertEquals(0, bill.skippedStatus)
        assertEquals(2, bill.refundCount)        // 交易关闭支出行 + 退款成功收入行
        assertEquals(4, bill.rows.size)

        val r0 = bill.rows[0]
        assertEquals(TxType.EXPENSE, r0.type)
        assertEquals(1950L, r0.amountCents)
        assertEquals("淘宝闪购", r0.counterparty)
        assertEquals(millis(2026, 10, 4, 17, 33, 4), r0.timestamp)
        // 单号尾部制表符应被剔除
        assertEquals("2026100423001148451423351129", r0.externalId)
        assertEquals("中国银行储蓄卡(8920)", r0.payMethod)
        assertEquals("餐饮", r0.guessedCategoryName)   // 交易分类"餐饮美食"映射
        assertEquals(RefundMark.NONE, r0.refundMark)

        // 交易关闭支出行 → 已退款
        val r1 = bill.rows[1]
        assertEquals(TxType.EXPENSE, r1.type)
        assertEquals(3480L, r1.amountCents)
        assertEquals(RefundMark.REFUNDED_EXPENSE, r1.refundMark)

        // 退款成功行（不计收支）→ 退款收入
        val r2 = bill.rows[2]
        assertEquals(TxType.INCOME, r2.type)
        assertEquals(3480L, r2.amountCents)
        assertEquals("2026100322001448451421675858_2026100319074102000920019200572", r2.externalId)
        assertEquals(RefundMark.REFUND_INCOME, r2.refundMark)

        val r3 = bill.rows[3]
        assertEquals(TxType.INCOME, r3.type)
        assertEquals(50000L, r3.amountCents)
        assertEquals("饭钱", r3.note)
        assertEquals("红包", r3.guessedCategoryName)   // 交易分类"人情往来"映射
        assertEquals(RefundMark.NONE, r3.refundMark)
    }

    @Test
    fun `parse alipay 等待确认收货计入支出`() {
        val csv = listOf(
            "导出信息：",
            "------------------------支付宝支付科技有限公司  电子客户回单------------------------",
            "交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注,",
            "2026-10-21 21:47:50,运动户外,tb**9,186******04,春秋新款袜子,支出,5.80,华夏银行信用卡(4362),等待确认收货,2026102123001160371429961339\t,T200P5127877873219042321\t,,",
        ).joinToString("\n")
        val bill = BillParsers.parse(csv.toByteArray(Charset.forName("GBK")))!!
        assertEquals(1, bill.rows.size)
        assertEquals(0, bill.skippedStatus)
        assertEquals(TxType.EXPENSE, bill.rows[0].type)
        assertEquals(580L, bill.rows[0].amountCents)
        assertEquals(RefundMark.NONE, bill.rows[0].refundMark)
    }

    @Test
    fun `parse alipay legacy csv`() {
        val bill = BillParsers.parse(alipayCsvLegacy.toByteArray(Charset.forName("GBK")))!!
        assertEquals(BillSource.ALIPAY, bill.source)
        assertEquals(0, bill.refundCount)
        assertEquals(2, bill.rows.size)

        val r0 = bill.rows[0]
        assertEquals(TxType.EXPENSE, r0.type)
        assertEquals(12990L, r0.amountCents)
        assertEquals("淘宝网", r0.counterparty)
        assertEquals(millis(2024, 1, 2, 18, 0, 1), r0.timestamp)   // 优先付款时间
        assertEquals("202401022188880001", r0.externalId)
        assertEquals("购物", r0.guessedCategoryName)   // 无交易分类列，回退关键词猜测
        assertEquals(RefundMark.NONE, r0.refundMark)

        val r1 = bill.rows[1]
        assertEquals(TxType.INCOME, r1.type)
        assertEquals(50000L, r1.amountCents)
        assertEquals("饭钱", r1.note)
    }

    // ---------- 其他 ----------

    @Test
    fun `unrecognized format returns null`() {
        assertNull(BillParsers.parse("hello,world\n1,2\n".toByteArray()))
    }
}
