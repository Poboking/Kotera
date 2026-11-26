package com.eratw.port.modtesting

import com.eratw.port.modapi.GameStateApi

/**
 * 内存全局变量面：缺键 `getIntVar` → 0、`getStrVar` → ""（与生产 `GameStateApi` 哨兵一致）。
 *
 * 变量名大小写口径照内核：按大写归一寻址。
 *
 * 写侧（ModDX4-B1）：本类只提供**预置通道**（`presetIntVar` / `presetStrVar`，作者用例的
 * arrange 手段，不经授权闸）；被测的受控写 API（[GameStateApi.setIntVar] / [setStrVar]）
 * 由 [TestContext] 的 `GatedBenchGameState` 供给（授权闸镜像 + 经 [com.eratw.port.modapi.RuntimeScheduler]
 * 提交），不落在本类 —— 本类若直写接口方法就成了绕过授权闸的第二通道。
 */
internal class TestGameState : GameStateApi {

    private val intVars = HashMap<String, Long>()
    private val strVars = HashMap<String, String>()

    override fun getIntVar(name: String): Long = intVars[normalize(name)] ?: 0L

    override fun getStrVar(name: String): String = strVars[normalize(name)] ?: ""

    /** 存在性探测（DX-C）：预置过的键答 true，未预置答 false；归一口径与读侧同（大写归一）。 */
    override fun hasIntVar(name: String): Boolean = intVars.containsKey(normalize(name))

    /** 存在性探测（DX-C）：口径同 [hasIntVar]。 */
    override fun hasStrVar(name: String): Boolean = strVars.containsKey(normalize(name))

    /** 台架预置：给作者用例预置全局变量（arrange 手段，不经授权闸）。 */
    fun presetIntVar(name: String, value: Long) {
        intVars[normalize(name)] = value
    }

    /** 台架预置：给作者用例预置字符串全局变量（口径同 [presetIntVar]）。 */
    fun presetStrVar(name: String, value: String) {
        strVars[normalize(name)] = value
    }
}
