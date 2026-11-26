package com.eratw.port.modgen

import java.io.File
import java.nio.file.Path

/**
 * `check-mod` 的代码入口静态面：作者声明了代码面（`code/` 目录 / `code.mod`）时，
 * 代码产物的内层 `manifest.json` 必须有 `entrypoint`，且与作者源码里的入口类对得上。
 *
 * ## 判据
 *
 * 1. **触发面**：只看**代码产物面** —— 模块目录下有 `code/` 目录、或 `code.mod`/`code/code.mod` 产物，
 *    才算「作者声明了代码面」；都没有 ⇒ 不看（老 mod 静默，向后兼容）。源码里的 `class ... : EraMod`
 *    只用于第 4 条的一致性核对，**不作触发**：`new-mod` 骨架默认就带 `class MyMod : EraMod`，拿它当
 *    触发面会让所有还没编代码产物的模块一律红。
 * 2. **内层清单来源**（作者约定，按序找第一个存在的）：`code/manifest.json`（文本）、`code.mod`（zip）、
 *    `code/code.mod`（zip）。zip 只读其中 `manifest.json` 条目（口径与 `pack-mod --code` 同源）。
 * 3. **`CODE_ENTRYPOINT_MISSING`**：代码面存在但找不到内层清单，或清单里没有字符串 `entrypoint`。
 * 4. **`CODE_ENTRYPOINT_MISMATCH`**：`entrypoint` 形态不是 `<小写包名>.<大写类名>`，或与源码里
 *    `class <Name> ... : EraMod` 推出的 FQN（该文件 `package` 行 + 类名）全都对不上。
 *
 * 两条都是 ERROR：装载期 `ModDexModuleLoader` 拿不到入口类会整包 `InvalidEntrypoint` 跳过，
 * 出包后才暴露比 `check-mod` 当场红更贵。本条面只做静态文本核对，不解析 dex。
 */
internal object ModCheckCodeEntry {

    /** 作者随源码放内层清单的目录名。 */
    private const val CODE_DIR = "code"

    /** 内层清单文件名（与 `pack-mod --code` 的 zip 条目同名）。 */
    private const val INNER_MANIFEST_FILE = "manifest.json"

    /** 作者编译好的代码产物包默认名（模块根 / `code/` 下各认一个）。 */
    private const val CODE_MOD_FILE = "code.mod"

    /**
     * 扫一个模块并追加 findings。
     *
     * @param root 模块根目录（与 `ModCheck.check` 的 `projectDir` 同一个）；[sources] 是已扫描的源码。
     */
    fun check(root: Path, sources: List<SourceFile>, findings: MutableList<Finding>) {
        val module = root.toFile()
        val manifests = innerManifests(module)
        if (manifests.isEmpty() && !File(module, CODE_DIR).isDirectory) return
        val located = manifests.firstNotNullOfOrNull { file -> manifestText(module, file) }
        if (located == null) {
            findings += missingManifest(module)
            return
        }
        checkEntrypoint(located, ModCheckCodeEntrySources.declaredEntrypoints(sources), findings)
    }

    /** 按作者约定顺序列出**存在**的内层清单候选（文本清单与 zip 产物都认）。 */
    private fun innerManifests(module: File): List<File> =
        listOf(
            File(module, "$CODE_DIR/$INNER_MANIFEST_FILE"),
            File(module, CODE_MOD_FILE),
            File(module, "$CODE_DIR/$CODE_MOD_FILE"),
        ).filter { it.isFile }

    /** 读候选清单：`manifest.json` 直接读文本，`.mod` 走 zip 内条目；读不出/超限/非 UTF-8 ⇒ null。 */
    private fun manifestText(module: File, file: File): LocatedManifest? {
        val text = if (file.name == INNER_MANIFEST_FILE) {
            if (file.length() > MAX_CODE_MOD_MANIFEST_BYTES) null else strictManifestText(file)
        } else {
            manifestJsonOf(file)
        }
        return text?.let { LocatedManifest(labelOf(module, file), it) }
    }

    /** 文本清单按严格 UTF-8 读（与 zip 内同一条口径，非法字节 ⇒ 读不到）。 */
    private fun strictManifestText(file: File): String? {
        val bytes = file.readBytes()
        return if (bytes.size > MAX_CODE_MOD_MANIFEST_BYTES) null else strictUtf8OrNull(bytes)
    }

    /** 清单文本 ⇒ 入口 FQN；清单不是对象 / 缺键 / 值不是字符串 ⇒ null（由调用方按 MISSING 判）。 */
    private fun entrypointOf(text: String): String? {
        val fields = (parseStrictJson(text) as? JsonObject)?.fields ?: return null
        return (fields[CODE_ENTRYPOINT_KEY] as? JsonString)?.value?.takeIf { it.isNotBlank() }
    }

    private fun checkEntrypoint(
        located: LocatedManifest,
        declared: List<DeclaredEntrypoint>,
        findings: MutableList<Finding>,
    ) {
        val entrypoint = entrypointOf(located.text)
        when {
            entrypoint == null -> findings += missingEntrypoint(located.label)
            !CODE_ENTRYPOINT_SHAPE.matches(entrypoint) ->
                findings += mismatch(located.label, entrypoint, declared, "形态非法")
            declared.isNotEmpty() && declared.none { it.fqn == entrypoint } ->
                findings += mismatch(located.label, entrypoint, declared, "与源码入口类对不上")
            else -> Unit
        }
    }

    /** 模块相对路径标签（统一 `/`，与其它检查器的 `file` 字段同形）。 */
    private fun labelOf(module: File, file: File): String = file.toRelativeString(module).replace('\\', '/')

    private fun missingManifest(module: File): Finding = Finding(
        severity = Severity.ERROR,
        file = labelOf(module, File(module, CODE_DIR)),
        line = null,
        message = "CODE_ENTRYPOINT_MISSING：模块声明了代码面（code 目录 / code.mod 产物），但找不到内层清单：" +
            "期望 code/$INNER_MANIFEST_FILE、$CODE_MOD_FILE 或 code/$CODE_MOD_FILE 之一。" +
            "代码产物必须带 manifest.json 且填 entrypoint（装载期靠它实例化入口类）",
    )

    private fun missingEntrypoint(label: String): Finding = Finding(
        severity = Severity.ERROR,
        file = label,
        line = null,
        message = "CODE_ENTRYPOINT_MISSING：$label 缺字符串键 entrypoint（装载期靠它实例化入口类，" +
            "缺键的代码包会被当作坏清单整包跳过）。用法：\"entrypoint\":\"<小写包名>.<大写类名>\"",
    )

    private fun mismatch(
        label: String,
        entrypoint: String,
        declared: List<DeclaredEntrypoint>,
        reason: String,
    ): Finding {
        val expected = if (declared.isEmpty()) {
            "源码里没扫到 class <Name> ... : EraMod 声明"
        } else {
            declared.joinToString("、") { "${it.fqn}（${it.file}:${it.line}）" }
        }
        return Finding(
            severity = Severity.ERROR,
            file = label,
            line = null,
            message = "CODE_ENTRYPOINT_MISMATCH：$label 的 entrypoint「$entrypoint」$reason；" +
                "须匹配 <小写包名>.<大写类名> 并与源码入口类一致（$expected）",
        )
    }
}

/** 找到的内层清单：模块相对标签 + 文本。 */
private class LocatedManifest(val label: String, val text: String)
