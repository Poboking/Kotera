package com.eratw.port.modgen

/** manifest 里的身份键：与模块 `const val PACKAGE_ID` 比对的就是它。 */
private const val PACKAGE_ID_KEY = "packageId"

/** 内层清单的入口类键：装载期读同一键，缺键的包会被当作坏清单整包跳过。 */
internal const val CODE_ENTRYPOINT_KEY = "entrypoint"

/** 内层清单的可选作者面版本键（导入端不消费，本面只判值类型）。 */
private const val API_VERSION_KEY = "apiVersion"

/** 内层 zip 里的包自述清单条目名：扫描与 [manifestJsonOf] 共用同一个名字。 */
internal const val INNER_MANIFEST_ENTRY = "manifest.json"

/** 包身份形态闸：与模块侧 packageId / 资源目录名同一档形状，回显前先过它。 */
private val PACKAGE_ID_SHAPE = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

/**
 * 代码入口类全限定名形态闸：`<小写包名>.<大写类名>`。
 *
 * 粗校验（作者自述面），只挡明显写错（空值、没有包名段、类名小写开头）；真正的可装载性由 app 侧
 * `ModDexModuleLoader` 在装载期判。`pack-mod --entrypoint` 与内层清单共用这一条形状判据。
 */
internal val CODE_ENTRYPOINT_SHAPE = Regex("^[a-z][a-z0-9_.]*\\.[A-Z][A-Za-z0-9_]*$")

private const val NOT_UTF8_REASON =
    "code.mod 里的 manifest.json 不是合法 UTF-8 字节序列（严格解码失败）"

private const val INVALID_JSON_REASON =
    "code.mod 里的 manifest.json 不是合法 JSON（严格解析失败）"

private const val NOT_JSON_OBJECT_REASON =
    "code.mod 里的 manifest.json 顶层必须是 JSON 对象"

private const val MISSING_PACKAGE_ID_REASON =
    "code.mod 里的 manifest.json 缺字符串键 packageId（无法校验代码产物身份）"

private const val MISSING_ENTRYPOINT_REASON =
    "code.mod 里的 manifest.json 缺字符串键 entrypoint（装载期靠它实例化入口类，缺键的包会被当作坏清单跳过）"

private const val API_VERSION_TYPE_REASON =
    "code.mod 的 manifest.json 里 apiVersion 必须是 JSON 数字（作者自述的 mod-api 版本；省缺 = 不声明）"

/** manifest 字节 ⇒ 验证结论：UTF-8 严格解码 + 七道判据（null = 通过）。 */
internal fun declaredManifestProblem(manifest: ByteArray, packageId: String?, entrypoint: String?): String? {
    val text = strictUtf8OrNull(manifest)
    return if (text == null) NOT_UTF8_REASON else parsedManifestProblem(text, packageId, entrypoint)
}

/** 严格解析 + 顶层对象/键型/形态/身份/入口/可选版本七道判据（判据顺序见 [PackModCode] 的 KDoc）。 */
private fun parsedManifestProblem(text: String, packageId: String?, entrypoint: String?): String? {
    val root = parseStrictJson(text)
    val fields = (root as? JsonObject)?.fields
    return if (fields == null) {
        if (root == null) INVALID_JSON_REASON else NOT_JSON_OBJECT_REASON
    } else {
        packageIdProblem(fields, packageId)
            ?: entrypointProblem(fields, entrypoint)
            ?: apiVersionProblem(fields)
    }
}

/** `packageId` 判据：缺键 → 形态非法 → 模块取不到身份 → 与模块声明不一致（顺序即优先级）。 */
private fun packageIdProblem(fields: Map<String, JsonValue>, packageId: String?): String? {
    val declared = fields[PACKAGE_ID_KEY] as? JsonString ?: return MISSING_PACKAGE_ID_REASON
    return when {
        !PACKAGE_ID_SHAPE.matches(declared.value) -> invalidPackageIdReason(declared.value)
        packageId == null -> noModulePackageIdReason(declared.value)
        declared.value != packageId -> mismatchReason(declared.value, packageId)
        else -> null
    }
}

/** `entrypoint` 判据：缺键/非字符串 → 形态非法 → 与命令行 `--entrypoint` 不一致。 */
private fun entrypointProblem(fields: Map<String, JsonValue>, entrypoint: String?): String? {
    val declared = fields[CODE_ENTRYPOINT_KEY] as? JsonString ?: return MISSING_ENTRYPOINT_REASON
    return when {
        !CODE_ENTRYPOINT_SHAPE.matches(declared.value) -> invalidEntrypointReason(declared.value)
        entrypoint != null && declared.value != entrypoint -> entrypointMismatchReason(declared.value, entrypoint)
        else -> null
    }
}

/** 可选的 `apiVersion` 键：存在时只判「是不是 JSON 数字」（数值上下界属引擎侧装载判据）。 */
private fun apiVersionProblem(fields: Map<String, JsonValue>): String? {
    val apiVersion = fields[API_VERSION_KEY]
    return if (apiVersion != null && apiVersion !is JsonNumberValue) API_VERSION_TYPE_REASON else null
}

private fun invalidPackageIdReason(value: String): String =
    "code.mod 的 manifest.json 里 packageId「$value」形态非法" +
        "（须匹配 [A-Za-z0-9][A-Za-z0-9._-]*）"

private fun invalidEntrypointReason(value: String): String =
    "code.mod 的 manifest.json 里 entrypoint「$value」形态非法" +
        "（须匹配 ^[a-z][a-z0-9_.]*\\.[A-Z][A-Za-z0-9_]*$）"

private fun entrypointMismatchReason(declared: String, cli: String): String =
    "code.mod 的 entrypoint 与命令行 --entrypoint 不一致：manifest.json 里是「$declared」、参数是「$cli」"

private fun noModulePackageIdReason(value: String): String =
    "模块源码里取不到 const val PACKAGE_ID（code.mod 自述 packageId=$value），无法校验代码产物身份"

private fun mismatchReason(declared: String, module: String): String =
    "code.mod 的 packageId 与模块 PACKAGE_ID 不一致：code.mod 是「$declared」、模块是「$module」"
