package com.eratw.port.modapi

/**
 * 声明 DSL 的数据段：全局/私有变量、CSV 补丁、角色变量路由、导出宏。
 *
 * 继承链位置见 `DeclDsl.kt` 顶部「按域拆分」：只追加声明，校验在各 `*Decl` 的 `init` 与
 * core 的声明事务。
 */
open class ModManifestDataBuilder internal constructor(
    packageId: String,
    version: String,
) : ModManifestSettingsBuilder(packageId, version) {
    /** 声明全局变量（[dims] 空 = 标量）。 */
    fun global(
        key: GlobalKey,
        type: DimType = DimType.INT,
        dims: List<Int> = emptyList(),
        isSaved: Boolean = true,
    ) {
        globalDims += GlobalDimDecl(key.name, type, dims, isSaved)
    }

    /** 声明函数私有 `#DIM`。 */
    fun privateDim(
        function: FunctionKey,
        name: String,
        type: DimType = DimType.INT,
        dims: List<Int> = emptyList(),
    ) {
        privateDims.getOrPut(function.name) { mutableListOf() } += PrivateDimDecl(name, type, dims)
    }

    /** CSV 关键字注入（[index] = -1 请求自动分配空闲位）。 */
    fun csvKeyword(table: CsvTable, keyword: String, index: Int = -1, isAlias: Boolean = false) {
        csvKeywords += CsvKeywordDecl(table.name, index, keyword, isAlias)
    }

    /** CSV 值补丁（按稳定行身份，不固化物理行号）。 */
    fun csvValue(table: CsvTable, row: String, column: String, op: CsvOp, value: String? = null) {
        csvValues += CsvValueDecl(table.name, row, column, op, value)
    }

    /** 把若干个角色变量族整族声明为「随实体存活」（全族全跨度）。 */
    fun charaRoutes(vararg variables: CharaVar) {
        variables.forEach { charaRoutes += CharaRouteDecl(it.name) }
    }

    /** 声明单个角色变量族的显式格（格级逐格派生）。 */
    fun charaRoute(variable: CharaVar, vararg cells: Int) {
        charaRoutes += CharaRouteDecl(variable.name, cells.toList())
    }

    /**
     * 声明一个原版全局变量的受控写授权（`write-grants` 域，ModDX4-B1）。
     *
     * [key] = 原版全局变量键（大小写不敏感）；[type] 必须与该键在引擎里的实际类型一致。
     * 未声明的键经 [GameStateApi.setIntVar] / [GameStateApi.setStrVar] 写会当场抛
     * [IllegalArgumentException]（fail-closed，文案对齐 [SettingsApi] 的未声明键口径）；
     * 本包 [global] 声明的变量不需要授权。角色变量与 2 维以上变量不可按名寻址，
     * 声明期（装载/导入）整包拒。
     */
    fun writeGrant(key: String, type: DimType = DimType.INT) {
        writeGrants += WriteGrantDecl(key, type)
    }

    /**
     * 导出 `.ERH` 宏（改值会进 LinkFingerprint）。
     *
     * 宏域没有 `*Decl` 载体（String→String map）：同名重复声明在收集处就会把前一条覆盖吃掉，
     * core 声明事务收到的已是覆盖后的单值、无从再闸 —— 键唯一这条不变量唯一可落的闸位就在
     * 本方法（与 settings / writeGrants 的「键在本包内必须唯一」同口径）；空白名则透传
     * manifest、线格式与指纹全链无闸。都在声明期拒绝，不留到「指纹与产物对不上」才暴露。
     */
    fun exportedMacro(name: String, expansion: String) {
        require(name.isNotBlank()) { "导出宏名不能为空" }
        require(name !in exportedMacros) {
            "导出宏在本包内必须唯一：$name（已声明为「${exportedMacros[name]}」；重复声明 = 末位静默生效，判为笔误拒绝）"
        }
        exportedMacros[name] = expansion
    }
}
