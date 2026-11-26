package com.eratw.port.modgen

import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.CsvOp
import com.eratw.port.modapi.CsvValueDecl
import com.eratw.port.modapi.FieldDecl
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

object ManifestBuild {
    fun build(source: ManifestSource): ByteArray =
        ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(ManifestFactory.create(source)))

    fun write(source: ManifestSource, output: Path) {
        val bytes = build(source)
        val target = output.toAbsolutePath()
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
    }
}

/**
 * modgen 进程入口：按首个实参分派子命令（旧的单参数用法原样保留）。
 *
 * - `modgen new-mod --package <id> --name <Name> --out <dir>`：生成 mod 骨架（NewModScaffold）。
 * - `modgen check-mod <mod目录> [--schema <schema.tsv>]... [--json]`：mod 作者自检「schema TSV ↔
 *   `src/generated` 生成物 ↔ Declarations/manifest」三面一致。缺省扫描 `<mod目录>/schemas`；
 *   schema 住在别处（family/memory/livingworld 这类生成物入库的载体）就用可重复的
 *   `--schema` 显式传，**同一模块有多份 schema 时必须一次传齐**（字段键按并集核对）。
 *   `--json` 把发现序列化成一份机器可读文档（schema 见 [ModCheckJson.SCHEMA]），供 CI/编辑器
 *   消费；缺省或 `--human` 是人类可读文本，两者互斥。
 *   退出码：`0` = 无 ERROR；`1` = 有 ERROR；`2` = 用法或 IO 问题。
 *   典型：`modgen check-mod mod-memory --schema tools/modgen-accessors/schemas/memory.schema.tsv`
 * - `modgen pack-mod <mod目录> --out <zip路径> [--schema <schema.tsv>]... [--min-api <N>]
 *   [--resources <目录名>]`：先跑 check-mod 全套
 *   （ERROR 拒绝、只 WARN 放行），再把递归 `.ERB`/`.ERH` 与 `<packageId>.assets/` 资源打成
 *   **确定性** zip（条目字典序、固定 1980-01-01 时间戳、不写目录条目、同输入两次 sha256 相同）。
 *   `--min-api <N>` 把作者要求的最低 mod-api 版本写进包元数据的 `minApi` 键（省缺 / 0 = 不写该键，
 *   元数据字节与老包逐字相同）；真正的闸在装配期（低于 N 的引擎整链拒载）。
 *   `--resources <目录名>` 额外带上旁挂资源树 `resources/mod/<目录名>/`（图/文本）与
 *   `sound/mod/<目录名>/`（音频），zip 内路径保持游戏根一致；两棵树存在时元数据追加
 *   `resources` 字段（在 `minApi` 之后），都不存在则包字节与不加参数逐字相同（详见 [PackModResources]）。
 *   退出码：`0` = 出包；`1` = 已拒绝；`2` = 用法或 IO 问题（详见 PackMod）。
 * - `modgen <output.manifest>`：生成最小样例 manifest。
 *
 * 分派到 object 内的 @JvmStatic main，而不是同包再加一个顶层 main —— 后者会与
 * ManifestProdChainTest 里裸调的 main(...) 争解析（BuiltinManifestBuild 的 KDoc 已踩过）。
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "new-mod") {
        exitProcess(NewModScaffold.runCli(args.drop(1).toTypedArray()))
    }
    // 子命令分派：`modgen check-mod <mod目录>` 是 mod 作者的自检入口（ModCheck，含 API/数据版本一致性）。
    if (args.firstOrNull() == "check-mod") {
        exitProcess(ModCheck.runCli(args.drop(1).toTypedArray()))
    }
    // `pack-mod` 复用 check-mod 判定后再打确定性 zip（PackMod）。
    if (args.firstOrNull() == "pack-mod") {
        exitProcess(PackMod.runCli(args.drop(1).toTypedArray()))
    }
    require(args.size == 1) { "Usage: modgen <output.manifest> (minimal sample)" }
    val source = ManifestSource(
        packageId = "mod.sample",
        version = "1",
        components = listOf(ComponentDecl("Counter", listOf(FieldDecl("value")))),
        csvValues = listOf(CsvValueDecl("CFLAG", "NO:1", "初期值", CsvOp.REPLACE, "1")),
    )
    ManifestBuild.write(source, Path.of(args[0]))
}
