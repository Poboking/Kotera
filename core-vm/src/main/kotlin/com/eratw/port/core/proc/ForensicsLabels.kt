package com.eratw.port.core.proc

/**
 * 现场诊断报告的**展示标签**（领域无关注入面）。
 *
 * [DatingForensics.render] 要把读数拼成一行人可读文本，但「怎么称呼每个字段」是宿主的展示语言，
 * 不是引擎知识。eraTW 中文标签放在其宿主侧 `EratwForensicsLayout.ERATW_LABELS`；core 只保留
 * [DEFAULT]（中性英文标签，无 eraTW 专有词），保证 core 主源集零业务文案。
 *
 * @see ForensicsLayout 读数坐标注入面
 */
data class ForensicsLabels(
    val title: String,
    val mainMap: String,
    val gmfBit: String,
    val gmfHomeMap: String,
    val homeMap: String,
    val mismatch: String,
    val homeMismatch: String,
    val detailPrefix: String,
    val homeDetailPrefix: String,
    val dateTravel: String,
    val masterDate: String,
    val masterPos: String,
    val masterTogether: String,
    val affairTarget: String,
    val targetDate: String,
    val targetPos: String,
    val targetTogether: String,
    val randomChara: String,
    val mobTalent: String,
    val forgetBit: String,
) {
    companion object {
        /** 中性标签：core 默认展示，无 eraTW 专有词；其他 era 宿主可直接用或替换。 */
        @JvmField
        val DEFAULT = ForensicsLabels(
            title = "field:",
            mainMap = "mainMap",
            gmfBit = "gmfBit",
            gmfHomeMap = "gmfHomeMap",
            homeMap = "homeMap",
            mismatch = "dating!=main",
            homeMismatch = "dating!=home",
            detailPrefix = "detail",
            homeDetailPrefix = "homeDetail",
            dateTravel = "dateTravel",
            masterDate = "masterDating",
            masterPos = "masterPos",
            masterTogether = "masterTogether",
            affairTarget = "affairTarget",
            targetDate = "targetDating",
            targetPos = "targetPos",
            targetTogether = "targetTogether",
            randomChara = "mobNo",
            mobTalent = "mobTalent",
            forgetBit = "forgetBit",
        )
    }
}
