package com.eratw.port.modfamily.generated

import com.eratw.port.modapi.EntityApi
import com.eratw.port.modapi.EntityId

class FamilyAccessors(private val entities: EntityApi) {
    fun age(e: EntityId): Long = entities.getInt(e, "age")
    fun setAge(e: EntityId, v: Long): Boolean = entities.setInt(e, "age", v)
    fun title(e: EntityId): String = entities.getStr(e, "title")
    fun setTitle(e: EntityId, v: String): Boolean = entities.setStr(e, "title", v)
    fun father(e: EntityId): EntityId = entities.getRef(e, "father")
    fun setFather(e: EntityId, v: EntityId): Boolean = entities.setRef(e, "father", v)
}
