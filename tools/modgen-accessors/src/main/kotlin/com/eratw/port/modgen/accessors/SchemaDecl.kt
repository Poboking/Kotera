package com.eratw.port.modgen.accessors

import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.FieldType

data class SchemaDecl(
    val packageName: String,
    val accessorName: String,
    val component: ComponentDecl,
)

/** UTF-8 TSV: schema<TAB>package<TAB>class<TAB>component; then field<TAB>name<TAB>INT|STR<TAB>true|false. */
fun parseSchema(text: String): SchemaDecl {
    val lines = text.removeSuffix("\n").removeSuffix("\r").split('\n').map { it.removeSuffix("\r") }
    val header = lines.first().split('\t')
    require(header.size == 4 && header[0] == "schema") { "line 1: expected schema, package, class, component (TSV)" }
    val fields = lines.drop(1).mapIndexed { index, line ->
        val columns = line.split('\t')
        val number = index + 2
        require(columns.size == 4 && columns[0] == "field") { "line $number: expected field, name, type, entityRef (TSV)" }
        val type = FieldType.entries.singleOrNull { it.name == columns[2] }
        require(type != null) { "line $number: type must be INT or STR" }
        val entityRef = columns[3].toBooleanStrictOrNull()
        require(entityRef != null) { "line $number: entityRef must be true or false" }
        require(columns[1].isNotBlank()) { "line $number: field name must not be blank" }
        FieldDecl(columns[1], type, entityRef)
    }
    return SchemaDecl(header[1], header[2], ComponentDecl(header[3], fields))
}
