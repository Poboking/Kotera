package com.eratw.port.modgen.accessors

import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(runCli(args))
}

fun runCli(args: Array<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int {
    return try {
        val check = args.firstOrNull() == "--check"
        val paths = if (check) args.drop(1) else args.toList()
        require(paths.size == 2 && paths.none { it.startsWith("--") }) {
            "Usage: modgen-accessors [--check] <schema.tsv> <output.kt>"
        }
        val input = Path.of(paths[0]).toAbsolutePath().normalize()
        val output = Path.of(paths[1]).toAbsolutePath().normalize()
        require(input != output && (!Files.exists(output) || !Files.isSameFile(input, output))) {
            "Schema input and generated output must be different files"
        }
        val expected = AccessorEmit.emit(parseSchema(Files.readString(input))).toByteArray(Charsets.UTF_8)
        if (check) {
            if (!Files.exists(output)) {
                err.println("MISSING: $output; first diff at line 1 (existing: <EOF>)")
                1
            } else {
                val existing = Files.readAllBytes(output)
                val first = (0 until minOf(existing.size, expected.size)).firstOrNull { existing[it] != expected[it] }
                    ?: minOf(existing.size, expected.size).takeIf { existing.size != expected.size }
                if (first == null) {
                    out.println("OK: $output is up to date")
                    0
                } else {
                    val line = 1 + expected.take(first).count { it == '\n'.code.toByte() }
                    err.println("STALE: $output; first diff at line $line")
                    1
                }
            }
        } else {
            Files.createDirectories(output.parent)
            Files.write(output, expected)
            out.println("WROTE: $output")
            0
        }
    } catch (error: IllegalArgumentException) {
        err.println("ERROR: ${error.message}")
        2
    } catch (error: IOException) {
        err.println("ERROR: ${error.message}")
        2
    }
}
