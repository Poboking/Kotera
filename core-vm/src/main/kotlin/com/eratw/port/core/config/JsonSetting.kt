package com.eratw.port.core.config

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 原版 `setting.json`（`JSONConfigData`）里本引擎**有消费点**的开关。
 *
 * 原版把这几项放在 `<gameRoot>/setting.json` 而不是 `emuera.config`：`JSONConfigData.cs:13`
 * 的 `UseScopedVariableInstruction` 由 `FunctionIdentifier.cs:4123-4127` 决定 VARI/VARS
 * 是否注册进指令表、由 `IdentifierDictionary.cs:624` 决定关闭时的 CanNotUseVAR 报错；
 * `JSONConfig.Load` 在启动期读盘，缺文件/缺键都取 C# `bool` 缺省 false。
 *
 * 移植侧必须同样按**每个游戏自己的** setting.json 取值：eratw 4.980 是 false（语料零
 * VARI/VARS），eraFL 是 true（`EQUIP_MENU.ERB` 一族大量用 VARI/VARS）。把任一侧写死
 * 都会让另一边在运行期抛/误拒 —— 这是兼容层要拆掉的引擎耦合。
 *
 * 解析只认本类声明的布尔键，其余键与未知内容原样忽略（原版 System.Text.Json 容忍
 * 未知字段）；文件缺失、读失败、非法 JSON 一律回落缺省，不阻断游戏启动。
 */
data class JsonSetting(
    /** `UseScopedVariableInstruction`。spec: JSONConfigData.cs:13 + FunctionIdentifier.cs:4123-4127 */
    val useScopedVariableInstruction: Boolean = false,
) {
    companion object {
        /** `setting.json` 内的键名 → 字段；只收有消费点的键。 */
        private val BOOL_KEYS: Map<String, (JsonSetting, Boolean) -> JsonSetting> = mapOf(
            "UseScopedVariableInstruction" to { s, v -> s.copy(useScopedVariableInstruction = v) },
        )

        /** 逐键正则（`"Key" : true|false`，大小写不敏感、允许任意空白）。 */
        private val KEY_PATTERNS: Map<String, Regex> = BOOL_KEYS.keys.associateWith { key ->
            Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
        }

        fun parse(text: String): JsonSetting {
            var out = JsonSetting()
            for ((key, pattern) in KEY_PATTERNS) {
                val m = pattern.find(text) ?: continue
                val value = m.groupValues[1].equals("true", ignoreCase = true)
                out = BOOL_KEYS.getValue(key)(out, value)
            }
            return out
        }

        fun loadFile(path: Path): JsonSetting {
            // Files.readString 是 Java 11 API（android.jar 编译面没有，真机必 NoSuchMethodError）
            // —— 平台 API 兼容闸（AndroidApiCompatTest）已抓过 EmueraConfigParser 的同形病灶
            // （见该文件 readConfigLines 的注释，2026-10-22）。这里改走同一口径：
            // readAllBytes + UTF-8 严格解码（REPORT），非法字节回落缺省 —— 与下方 catch 的
            // 「读失败一律回落」语义一致（setting.json 原版就是 System.Text.Json 容忍缺文件/坏文件）。
            val bytes = if (Files.isRegularFile(path)) {
                runCatching { Files.readAllBytes(path) }.getOrNull() ?: return JsonSetting()
            } else {
                return JsonSetting()
            }
            val text = runCatching {
                StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString()
            }.getOrNull() ?: return JsonSetting()
            return parse(text)
        }

        /** `<gameRoot>/setting.json`（原版 `Program.ExeDir + "setting.json"`）。 */
        fun loadGameDir(gameRoot: Path): JsonSetting = loadFile(gameRoot.resolve(FILE_NAME))

        const val FILE_NAME: String = "setting.json"
    }
}
