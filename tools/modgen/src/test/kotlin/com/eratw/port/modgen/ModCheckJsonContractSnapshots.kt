package com.eratw.port.modgen

/**
 * `check-mod --json` 的逐字节冻结快照（机器可读契约的一部分）。
 *
 * 这些常量由固定夹具的真实输出采集而来，任何人改 ModCheckJson 的字段、顺序、
 * 缩进、转义或排序键都会让本文件的对比测试变红 —— 这是故意的：JSON 一旦发布给 CI/
 * 编辑器，就必须显式升 schema 并同步刷新快照，而不是悄悄变形。
 *
 * 刷新方式：改夹具后运行契约测试，把失败信息里给出的实际输出按下面的夹具重新采集，
 * 逐字节追加进去（保留末尾换行）。WARN/ERROR 快照里的行号来自 new-mod 脚手架模板，
 * 模板行数变动时须同步刷新。
 */

/**
 * 无 finding：scaffold 出模块并生成访问器产物后直接检查。
 */
internal const val CLEAN_JSON_CONTRACT_SNAPSHOT = """{
  "schema": "1",
  "ok": true,
  "errors": 0,
  "warnings": 0,
  "findings": []
}
"""

/**
 * 仅 WARN：在载体类里加 onMigrate 但未声明 dataVersion。
 *
 * 行号 54 对齐脚手架模板现状（fin-c3 wave 89d4ecda4 给骨架补了分发指引小节，
 * MyMod.kt 类体下移 4 行；快照随模板演进刷新，WARN 语义与正文不变）。
 */
internal const val WARN_ONLY_JSON_CONTRACT_SNAPSHOT = """{
  "schema": "1",
  "ok": true,
  "errors": 0,
  "warnings": 1,
  "findings": [
    {"severity": "WARN", "file": "src/main/kotlin/eratw/mymod/MyMod.kt", "line": 54, "message": "发现 override fun onMigrate 但 dataVersion 仍是缺省 0：迁移永远不会被触发（请在载体声明 dataVersion）"}
  ]
}
"""

/**
 * 有 ERROR：删掉生成的访问器产物，对应 finding 的 line 为 null。
 */
internal const val ERROR_JSON_CONTRACT_SNAPSHOT = """{
  "schema": "1",
  "ok": false,
  "errors": 1,
  "warnings": 0,
  "findings": [
    {"severity": "ERROR", "file": "schemas/My.schema.tsv", "line": null, "message": "生成物缺失：src/generated 下没有 object MyKeys；重跑 :tools:modgen-accessors 生成器产出它，或核对 schema 的 component 基名"}
  ]
}
"""

/**
 * 排序：ModCheckJson.render 对乱序 findings 的稳定排序结果。
 */
internal const val SCRAMBLED_SORT_JSON_CONTRACT_SNAPSHOT = """{
  "schema": "1",
  "ok": false,
  "errors": 4,
  "warnings": 3,
  "findings": [
    {"severity": "ERROR", "file": "a.kt", "line": null, "message": "no line"},
    {"severity": "ERROR", "file": "a.kt", "line": 5, "message": "y"},
    {"severity": "WARN", "file": "a.kt", "line": 10, "message": "x"},
    {"severity": "ERROR", "file": "b.kt", "line": 2, "message": "aa"},
    {"severity": "ERROR", "file": "b.kt", "line": 2, "message": "bb"},
    {"severity": "WARN", "file": "b.kt", "line": 2, "message": "aa"},
    {"severity": "WARN", "file": "b.kt", "line": 2, "message": "zz"}
  ]
}
"""

