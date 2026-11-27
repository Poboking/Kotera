package com.eratw.port.erbcc

import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.VarInfo
import com.eratw.port.core.stmt.BuiltinSymbols
import com.eratw.port.core.stmt.StmtResolver

/**
 * 宽松符号器（方案 2，CONTRACT-RUSTFULL §6.10/§6.11）：内建表优先；**未知函数名按
 * `(...)` 调用形态放行、返回 INT**，未知变量折成 1 维 INT。方案 2 的判据是「结构/表达式
 * 骨架一致」，不是「语义可解析」—— 未知名报「未声明」属方案 1（全语料符号预扫）。
 *
 * 两个夹具（`dump-stmt` / `dump-expr`）共用本实例，Rust 侧的 `LooseTables` 必须逐字对齐
 * 这里的四条判据（含 `canReturnFloat` 的 `BuiltinSymbols.function(name) == null` 前置）。
 */
internal class LooseResolver : StmtResolver {
    override fun resolveVariable(name: String, subKey: String?): VarInfo =
        BuiltinSymbols.variable(name)?.let {
            VarInfo(it.name, it.type, it.dimension, it.isCharacterData, it.isConst)
        } ?: VarInfo(name, OperandType.INT, 1, false, false)

    override fun resolveFunction(name: String): OperandType =
        BuiltinSymbols.function(name) ?: OperandType.INT

    override fun canReturnFloat(name: String): Boolean =
        BuiltinSymbols.function(name) == null && BuiltinSymbols.canReturnFloat(name)

    override fun canBeReferencedBare(name: String): Boolean =
        BuiltinSymbols.function(name) != null || !BuiltinSymbols.isBuiltinMethod(name)

    override fun isUserMethod(name: String): Boolean = false
}
