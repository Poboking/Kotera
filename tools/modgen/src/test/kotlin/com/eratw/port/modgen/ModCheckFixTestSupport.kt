package com.eratw.port.modgen

import com.eratw.port.modgen.accessors.AccessorEmit
import com.eratw.port.modgen.accessors.parseSchema
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** 一次 check-mod 进程内调用的观测结果（退出码 + stdout + stderr）。 */
internal data class FixCheckRun(val exit: Int, val out: String, val err: String)

/** 进程内跑 check-mod：与 [ModCheckJsonContractSupport.runContractCheck] 同形，但不依赖契约测试。 */
internal fun runCheckFix(args: List<String>): FixCheckRun {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val exit = PrintStream(out, true, "UTF-8").use { outStream ->
        PrintStream(err, true, "UTF-8").use { errStream ->
            ModCheck.runCli(args.toTypedArray(), outStream, errStream)
        }
    }
    return FixCheckRun(exit, out.toString("UTF-8"), err.toString("UTF-8"))
}

/** 生成物应有的唯一内容：把 schema 再喂一遍生成器得到的逐字节输出。 */
internal fun expectedGenerated(module: Path): String =
    AccessorEmit.emit(parseSchema(Files.readString(schemaFile(module))))

/** 把生成物改脏：尾部追加一行注释，制造「生成物过期」这一可修类 ERROR。 */
internal fun makeGeneratedStale(module: Path) {
    val file = generatedFile(module)
    Files.writeString(file, Files.readString(file) + "// stale\n")
}
