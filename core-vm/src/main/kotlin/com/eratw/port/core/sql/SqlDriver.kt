package com.eratw.port.core.sql

/**
 * 真实 SQLite 的**平台能力端口**（SQLite 迁移第一波）。
 *
 * 本文件只定义契约，不引用任何 Android / AndroidX / JDBC 类型：core-vm 仍是纯 JVM，
 * `AndroidApiCompatTest` 不会把 `androidx/sqlite` 包 当成缺失的 android.jar API。
 * 具体驱动由宿主注入：Android 用 `androidx.sqlite:sqlite-bundled`，桌面测试/vmdbg 用
 * 同版本的 `sqlite-bundled-jvm`；两边共用 `:sqlite-adapter` 的转换层。
 *
 * 线程口径：一个 [SqlDriver] 及其连接/语句只在创建它的 VM 线程使用（与 RuntimeAux 同口径）。
 * 关闭口径：[SqlConnection] / [SqlStatement] 必须幂等；异常关闭不得吞掉上一条 SQL 错误。
 */
interface SqlDriver {
    /**
     * 打开一个连接。入参是 ERB `SQL_CONNECT` 的原始连接串（例如
     * `Data Source=plugins/qol_data.db`）；空串/`null` 由调用方折成 `:memory:`。
     */
    fun open(connectionString: String): SqlConnection
}

/** 一条 SQLite 连接。 */
interface SqlConnection : AutoCloseable {
    /** 准备单条 SQL。SQLite 的多语句尾随文本不在本波支持范围（语料 0 用法）。 */
    fun prepare(sql: String): SqlStatement

    /** `sqlite3_total_changes()` 的读数；用于按 Microsoft.Data.Sqlite 口径计算 affected rows。 */
    fun totalChanges(): Long

    /** `sqlite3_changes()` 的读数；仅在 [totalChanges] 前后确实增长时读取。 */
    fun lastChanges(): Long

    override fun close()
}

/** 一条已准备的 SQLite 语句。 */
@Suppress("TooManyFunctions")
interface SqlStatement : AutoCloseable {
    /** 结果列数；在 [step] 之前也可读（DDL/DML 为 0）。 */
    val columnCount: Int

    /** 绑定 1 基参数；[index] 与 SQLite 的 `?NNN` / 位置参数一致。 */
    fun bind(index: Int, value: SqlBind)

    /** 推进一行；有下一行返回 true，执行完成/无结果返回 false。 */
    fun step(): Boolean

    fun reset()

    fun clearBindings()

    fun columnName(index: Int): String

    fun columnType(index: Int): SqlColumnType

    fun isNull(index: Int): Boolean

    fun getLong(index: Int): Long

    fun getDouble(index: Int): Double

    fun getText(index: Int): String

    fun getBlob(index: Int): ByteArray

    override fun close()
}

/** 绑定值。ERB 的 SQL_P_* 参数实际全按文本传入；数值/二进制留给后续函数面。 */
sealed interface SqlBind {
    data class Integer(val value: Long) : SqlBind
    data class Float(val value: Double) : SqlBind
    data class Text(val value: String) : SqlBind
    data class Blob(val value: ByteArray) : SqlBind
    data object Null : SqlBind
}

/** SQLite 存储类。 */
enum class SqlColumnType {
    INTEGER,
    FLOAT,
    TEXT,
    BLOB,
    NULL,
}

/**
 * 原生驱动错误。adapter 只做「把平台异常归一成这一种」，函数层再按函数名/语句文本
 * 包成 `CodeEE`；禁止在 adapter 里改写玩家面文案。
 */
class SqlNativeException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
