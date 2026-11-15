package com.eratw.port.modgen

import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.CsvOp
import com.eratw.port.modapi.CsvValueDecl
import com.eratw.port.modapi.FieldDecl
import java.nio.file.Files
import java.nio.file.Path

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

fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: modgen <output.manifest> (minimal sample)" }
    val source = ManifestSource(
        packageId = "mod.sample",
        version = "1",
        components = listOf(ComponentDecl("Counter", listOf(FieldDecl("value")))),
        csvValues = listOf(CsvValueDecl("CFLAG", "NO:1", "初期值", CsvOp.REPLACE, "1")),
    )
    ManifestBuild.write(source, Path.of(args[0]))
}
