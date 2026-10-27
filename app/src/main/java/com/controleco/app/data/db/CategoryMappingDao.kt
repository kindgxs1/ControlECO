package com.controleco.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.controleco.app.data.model.BillSource
import kotlinx.coroutines.flow.Flow

/**
 * 账单导入的分类映射规则 DAO。
 *
 * 规则匹配优先级（从高到低）：
 * 1. source 精确匹配（WECHAT/ALIPAY）+ matchType + matchValue + txType
 * 2. source = ""（通用）+ matchType + matchValue + txType
 * 3. 不命中 → 回落到按分类名匹配（CommitImport 里的 `cats.firstOrNull { it.name == guessedCategoryName }`）
 */
@Dao
interface CategoryMappingDao {

    @Query("SELECT * FROM category_mappings ORDER BY source, matchType, matchValue")
    suspend fun getAll(): List<CategoryMappingEntity>

    @Query("SELECT * FROM category_mappings ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CategoryMappingEntity>>

    @Query("SELECT * FROM category_mappings WHERE source = :source OR source = '' ORDER BY source DESC")
    suspend fun bySource(source: String): List<CategoryMappingEntity>

    /**
     * 按优先级查找第一条匹配规则。
     * 先找精确来源 + txType 的，找不到再找通用（source = ''）+ txType 的。
     */
    @Query(
        """SELECT * FROM category_mappings
           WHERE source = :source OR source = ''
             AND matchType = :matchType AND LOWER(TRIM(matchValue)) = LOWER(TRIM(:matchValue))
             AND txType = :txType
           ORDER BY CASE WHEN source = :source THEN 0 ELSE 1 END, updatedAt DESC
           LIMIT 1""",
    )
    suspend fun findMatch(
        source: String,
        matchType: String,
        matchValue: String,
        txType: TxType,
    ): CategoryMappingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: CategoryMappingEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<CategoryMappingEntity>)

    @Update
    suspend fun update(rule: CategoryMappingEntity)

    @Query("DELETE FROM category_mappings WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM category_mappings")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM category_mappings")
    suspend fun countAll(): Int
}

/** 匹配类型常量：账单「交易分类」字段 vs 账单「交易类型」字段 */
object CategoryMatchType {
    const val CATEGORY = "CATEGORY"
    const val TRADE_TYPE = "TRADE_TYPE"
}

/** 账单来源的 DAO 内部字符串（对应 BillSource.name） */
fun BillSource.toDaoSource(): String = name
