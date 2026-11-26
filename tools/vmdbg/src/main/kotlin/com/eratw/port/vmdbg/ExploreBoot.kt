package com.eratw.port.vmdbg

import java.nio.file.Files
import java.nio.file.Path

/**
 * 引导链步骤（CONTRACT-DBG6 §4.E1.4）：一条「输入 ;; 期望子串」的顺序表。
 *
 * 执行口径（驱动器侧，见 [ExploreDriver.bootNextActionLocked]）：第 k 步在**第 k 个等待点**
 * 先查屏尾是否含期望子串（空串 = 不查），再喂入该步输入；喂入后引擎推进到第 k+1 个等待点，
 * 以此类推。不中即引导失败（现场落盘 boot-fail.json + boot-fail-console.txt，不静默跳步）。
 * 任意键等待点不经食谱（自动 PassAnyKey 放行，对应 FLOW.md §2 里的 `(Enter×N)` 段）。
 *
 * ## 与 FLOW.md 的对照
 *
 * 步骤内容照抄 `$ERATW_ROOT/.tmp/emu-play/FLOW.md` §2 的 START 开局实测链（2026-10-25
 * 物理驱动取证）；FLOW.md §0 的四条机制是链能跑通的先决：
 * 1. ASK_YN 族 [0]=是、[1]=否（答反静默走否分支）——第 3/7/15 步喂 0 的依据；
 * 2. TINPUT 超时自动推进（标题菜单 20s 不答 = 进 BADAPPLE DEMO）——本驱动立即应答，
 *    不吃真实时限，但**超时是隐藏分支**（FLOW.md §5.2），策略层枚举时须计入；
 * 3. PRINTFORMW 链大量纯推进等待点——anykey 自动放行覆盖；
 * 4. anykey 后数字题的输入框残留——真实键盘才有，引擎级注入无此现象。
 * 地图第 9 步选 [3] 经典 = FLOW.md §0.2 的避坑指令（绕开动画地图的 5s 超时重绘循环）。
 *
 * 期望子串取自 FLOW.md §2 逐屏引注的原文；FLOW.md 未引屏文的步骤留空（不检查）——
 * 钉子只钉有出处的屏文，不脑补文案。
 *
 * ## 261111 语料换代重取证（2026-11-18 桌面取证，本节为准）
 *
 * 语料换代 261017→261111 后开局流程变了，旧 18 步链在第 2 步期望「开始游戏」即 MISS
 * （标题面重绘后尾窗 30 行不再含该串）且后续逐面错位。用 `explore --boot` 盲走 +
 * boot-steps.jsonl 逐步取证（种子 20260226，erbc = 当前编译器 × 261111 + CB-001~019）量出
 * 新链 15 步（anchor 终态 Shop_CallShowShop 不变）。换代变化面：
 * - 「游戏前的选择设定」补丁开局设定页（[61]魔法DLC / [66]实用设置补丁 / [999]完成）
 *   **前置到角色生成之前**（NEWGAME.ERB 主链）；
 * - 口上选择 `QOL_KOJO_SELECTION` 新面（[0] 返回），在角色定稿之后、地图种类之前；
 * - 地图种类随后选 [1] 颜色地图 + 动画（v4 实测通过；FLOW.md §0.2 的「选 3 避动画地图坑」
 *   仍可用，换代后未复测——保留旧避坑知识，不绑死新链）；
 * - 主地图住所改 `QOL_SET_MAINHOME`（**STR 等待**，数字按串交付）[1999] 决定；居所细选改
 *   `QOL_SET_HIKKOSHI_ROOM`（STR）[0] → 「将仓库设定为了你的私室」；
 * - 「素質技能の変更」**不再出现在开局段**（其选项自述「会在版本変更的时候再登場」，
 *   261111 实测開局链 [100] 完成自定义后直接进 UPDATE 链）；
 * - UPDATE 链新增 `K151_ROUTE_SELECT` 剧本面（幽幻魔眼路线，[1]）与 `MODIMG_SET` STR 面
 *   （「更新完力！」）；立绘 ASK_YN 计数 60→160（钉串不带计数）。
 * FLOW.md §2 本体（外部文档 `$ERATW_ROOT/.tmp/emu-play/FLOW.md`）尚未换代改写，TODO(verify)；
 * 本仓唯一权威 = 本节 + boot-steps.jsonl 取证（2026-11-18 explore-v4 运行）。
 *
 * ## 第 15 步的口径更正（obs-bootrecipe，2026-11-01 桌面取证）
 *
 * FLOW.md §2 那三行括注（「保留/全部覆盖/不变更」「用旧的/用新的/托人情」「检测到60个角色…」）
 * 是**人工转述**，不是屏上原文。2026-11-01 用 boot-steps.jsonl 逐步量到第 15 面的原文后逐条对上：
 * - 第 15 面确实存在（屏上走到那一面了），但屏上三个选项是 `[0]-保留 / [1]-変更 / [2]-不変更`，
 *   面头一行 `-----素質技能の変更-----`；**「全部覆盖」四字在整份控制台转写里零出现**，
 *   也在整个语料树里零命中（`ERB/` 全量 grep 只有一句「积雪将周围全部覆盖…」的无关正文）
 *   ⇒ 判为甲类：期望串口径错，不是流程回归、不是产物过期。出处
 *   `口上・メッセージ関連/個人口上/031 Sanae [早苗]/早苗/M_KOJO_K31_UPDATE.ERB:129`。
 * - 第 16、17 步的转述串（「用新的」「托人情」「可选立绘」里的前两个）同样零命中；第 17 面的出处
 *   存在（`fromEN/NEW_UPDATE/UPDATE_MAIN.ERB:988` 打「检测到N个角色有新的可选立绘…」）但按
 *   `Img_Detected` 是否为正才开火，headless 沙箱未必满足 ⇒ 两步一律留空（只推进不判定），
 *   拿到本次运行的真实屏文后再钉回（TODO(verify)）。
 * - 另一处装置侧根因（期望门抢在任意键宽容之前开火）记在
 *   [ExploreDriver.bootNextActionLocked] 的 KDoc：FLOW.md §2 明写第 15 步之前有
 *   「(Enter×N) ← 开局链：经验/能力换算、初始处理、各角色口上UPDATE（几十个等待点）」，
 *   配方不可能逐个列出这几个 `N`，只有「任意键面不经食谱」这条既定口径生效，配方才走得过去。
 */
data class BootStep(val input: String, val expect: String)

/**
 * 期望子串的命中判式（纯函数，[ExploreDriver] 的引导门与本仓单测共用同一个口）：
 * - 期望留空 = 该步不判定，只推进（[乙] 类分支面的容错口径：本局不出现这一面的步就留空）；
 * - 否则屏尾**任一行**含该子串才算命中——逐行 `contains`，不跨行拼串：
 *   原版一行一语句，跨行的文案在控制台里是两条行对象，拼串会把「按钮标签被换行打断」
 *   这类排版差异算成命中（假绿）；
 * - 比对前不做 trim / 全半角归一：期望串取的是屏上原文，归一了就等于「任何屏都命中」，
 *   判式失去可判性。
 */
fun bootExpectHit(expect: String, consoleTail: List<String>): Boolean =
    expect.isEmpty() || consoleTail.any { it.contains(expect) }

/**
 * 「推进步」判式（纯函数；[ExploreDriver] 的 bootActionFor 分支与引导门的 anykey 宽容共用这一个口）：
 * 输入列留空、或写 `Enter`（大小写随意）= 「按任意键往下走」，不带取值语义。
 * 之所以要抽出来：任意键等待点该不该消费这一步，取决于这一步是不是推进步——
 * 推进步消费（配方作者点名要在这里按一下），取值步不消费（见 [ExploreDriver.bootNextActionLocked]）。
 */
fun isEnterInput(input: String): Boolean =
    input.isBlank() || input.trim().equals("Enter", ignoreCase = true)

/** 引导链配方：步骤表 + 终点判定子串（终点到达即打种子快照）。 */
class BootRecipe(val steps: List<BootStep>, val terminal: String)

object ExploreBoot {

    /**
     * 链终点判定子串（CONTRACT-DBG6 §4.E1.4 钉死）：第 1 日主菜单的日期行。
     * 终点到达即打种子快照——开局链几十个等待点重走一次约数分钟（FLOW.md §5.4），
     * 必须快照播种，不许每集重走。
     */
    const val TERMINAL_DAY1 = "第1日"

    /**
     * 内置 START 开局链（到第 1 日主菜单为止）。
     * 现行 = **261111 语料重取证版**（2026-11-18，见类 KDoc「261111 语料换代重取证」；
     * 种子 20260226 实测全通落 seed.dbg，anchor=Shop_CallShowShop）。
     * 期望列只钉本轮 boot-steps.jsonl 取证过的屏上原文；重绘面（newLines 只有缩尺线）
     * 与屏文未取证的等待面留空（只推进不判定）。
     */
    val BUILTIN_START: BootRecipe = BootRecipe(
        listOf(
            // 标题菜单（SYSTEM_TITLE INT）：headless 沙箱恒无存档，TITLE.ERB 的 CHKDATA 分支
            // 直接 RESETDATA+BEGIN FIRST；空 Enter 即缺省 [0] 开始游戏（2026-11-18 实测第 1 步
            // Enter 落在 SYSTEM_TITLE:8667 的 INT 面）。标题面是图片+菜单，无稳定屏文可钉。
            BootStep("Enter", ""),
            // ★★请选择游戏模式★★ [0]START（取证屏文「★★请选择游戏模式★★」，钉去星稳定段）
            BootStep("0", "请选择游戏模式"),
            // 「游戏前的选择设定」补丁开局设定页（[61]魔法DLC / [66]实用设置补丁 / [999]完成）
            // ——261111 换代后前置到角色生成之前；[999] 完成。
            BootStep("999", "正式开始游戏前"),
            // 角色生成面板 [9999] 设定完毕（CUSTOM_TERMINAL；重绘面，新增屏行只有缩尺线，
            // 「设定完毕」等菜单项文本在台账里取不出 ⇒ 留空不钉）
            BootStep("9999", ""),
            // 「你这样真的可以吗？」ASK_YN [0]是
            BootStep("0", "这样真的可以吗"),
            // 口上选择 QOL_KOJO_SELECTION [0] 返回（换代新增面；重绘面，屏文未取证留空）
            BootStep("0", ""),
            // 「请选择地图的种类」[1] 颜色地图 + 动画（旧链选 [3] 经典避动画地图坑，
            // FLOW.md §0.2；261111 实测 [1] 全通，保留实测值）
            BootStep("1", "请选择地图的种类"),
            // 主地图住所 QOL_SET_MAINHOME（STR 等待，数字按串交付）[1999] 决定（缺省博丽神社；
            // 该面 60 行重绘，面头文案滑出尾窗 30 行 ⇒ 留空不钉）
            BootStep("1999", ""),
            // 居所细选 QOL_SET_HIKKOSHI_ROOM（STR）[0] → 仓库设为私室（49 行重绘，留空不钉）
            BootStep("0", ""),
            // 「将仓库设定为了你的私室」PRINTL 后的确认 INT（默认引っ越し）——推进步
            BootStep("Enter", "将仓库设定为了你的私室"),
            // 最终自定义菜单 [100] 完成自定义阶段并开始游戏（面头「在游戏开始前你可以变更
            // 游戏各种的设定。」取证在案；菜单项 [100] 文本在台账里取不出 ⇒ 钉面头不钉菜单项）
            BootStep("100", "变更游戏各种的设定"),
            // 口上版本选择 ASK_M → [1]（屏文未取证，沿用 2026-11-01 口径只推进不判定）
            BootStep("1", ""),
            // K151_ROUTE_SELECT 剧本面「当你第一次遇到幽幻魔眼，你感觉…」[1]（换代后 UPDATE
            // 链新增；语料/种子钉死 ⇒ 本面必现，屏文取证在案）
            BootStep("1", "当你第一次遇到幽幻魔眼"),
            // MODIMG_SET STR 面「更新完力！」→ [0]（换代后 UPDATE 链新增）
            BootStep("0", "更新完力"),
            // 「检测到N个角色有新的可选立绘…是否缄默进行此次立绘更新」ASK_YN [0] 是（跳过更新）。
            // 钉串沿用 2026-11-01 取证口径「可选立绘」：计数 60→160 随语料漂，不钉计数。
            BootStep("0", "可选立绘"),
            // 余下 (Enter×N) 链（经验/能力换算、初始处理、各角色口上 UPDATE……）由
            // anykey 自动放行，直至「第1日」现身

        ),
        TERMINAL_DAY1,
    )

    /**
     * `--boot <recipe文件>` 读取：每行 `输入 ;; 期望子串`（`;;` 缺省 = 该步不查期望）；
     * `#` 开头为注释行，空白行跳过。终点判定子串恒为 [TERMINAL_DAY1]（合同钉死，不在文件里配）。
     */
    fun parseFile(path: Path): BootRecipe {
        if (!Files.isRegularFile(path)) throw CliError("--boot 配方文件不存在: $path")
        return parseLines(Files.readAllLines(path, Charsets.UTF_8), path.toString())
    }

    /** 配方文本解析（[parseFile] 与单测共用；[source] 仅用于报错定位）。 */
    fun parseLines(lines: List<String>, source: String = "recipe"): BootRecipe {
        val steps = ArrayList<BootStep>()
        for ((idx, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val sep = line.indexOf(";;")
            val input: String
            val expect: String
            if (sep < 0) {
                input = line
                expect = ""
            } else {
                input = line.substring(0, sep).trim()
                expect = line.substring(sep + 2).trim()
            }
            if (input.isEmpty()) throw CliError("$source 第 ${idx + 1} 行输入为空: $raw")
            steps.add(BootStep(input, expect))
        }
        if (steps.isEmpty()) throw CliError("$source 没有任何配方步骤（每行形如「输入 ;; 期望子串」，# 注释）")
        return BootRecipe(steps, TERMINAL_DAY1)
    }
}
