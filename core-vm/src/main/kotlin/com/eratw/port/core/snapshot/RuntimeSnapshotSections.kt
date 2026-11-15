package com.eratw.port.core.snapshot

import com.eratw.port.core.fn.EmDataNames
import com.eratw.port.core.host.CBG_SLOT
import com.eratw.port.core.host.CbgPool
import com.eratw.port.core.host.GRAPHICS_SLOT
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.host.SPRITE_SLOT
import com.eratw.port.core.host.SpritePool
import com.eratw.port.core.save.SaveCapture
import com.eratw.port.core.save.SaveCodec
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SaveWiring
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.snapshot.RuntimeSnapshot.Section
import com.eratw.port.core.snapshot.RuntimeSnapshot.Value

/**
 * v1 快照的**各节采集器**（契约 S1）。
 *
 * 每节只从**现有只读面**取数，绝不为快照改 VM / 渲染 / 变量的语义：
 *
 * | 节 | 数据源 | 说明 |
 * |---|---|---|
 * | [VM] | `VariableStore.frameSnapshotLines` + `callDepth` | 帧链与各帧局部（8 元素/变量，只读导出） |
 * | [VARS] | `SaveCapture` + `SaveCodec` | **复用引擎存档字节**，不发明第二种变量序列化 |
 * | [RNG] | `VariableStore.randProvider.state()` | SFMT 19937 的 625 元素（末元素 = 游标） |
 * | [DISPLAY] | `console.tailText` / `buttons` / 计数 | 保留行文本、按钮区间、LINECOUNT 与 revision |
 * | [RESOURCES] | `GraphicsPool` / `SpritePool` / `CbgPool` | 只有计数、名字与占用字节，**不含像素** |
 * | [EVENTS] | 调用方注入 | 结构化事件尾（app 侧来自 EventJournal.tail） |
 *
 * ## 拿不到就缺席，不猜
 *
 * - `pc` 在 `Vm.kt` 里是 `run` 的局部变量，核心层没有读口 ⇒ 本节写 `pc=unavailable` 并
 *   在交付报告记 `TODO(verify)`（契约 §4 明确不许为此改 VM 语义）；
 * - 池槽没装配（纯变量单测 / 未跑图形的会话）⇒ 该节只写可得字段，缺失字段不编造；
 * - 变量节抓取抛错（堆贴顶时的真实形态）⇒ 该节只留 `error=`，**不中断**其余节的采集。
 */
object RuntimeSnapshotSections {

    /** 调用栈 / 帧局部。 */
    const val VM: String = "vm"

    /** 变量面（存档字节）。 */
    const val VARS: String = "vars"

    /** 随机数状态。 */
    const val RNG: String = "rng"

    /** 显示面（行 / 按钮 / 尾文本）。 */
    const val DISPLAY: String = "display"

    /** 资源池（计数与名字，不含像素）。 */
    const val RESOURCES: String = "resources"

    /** 结构化事件尾（调用方注入）。 */
    const val EVENTS: String = "events"

    /** v1 认识的节名（顺序 = [collect] 的产出序；分析器据此报「缺席」）。 */
    val KNOWN_SECTIONS: List<String> = listOf(VM, VARS, RNG, DISPLAY, RESOURCES, EVENTS)

    /** 资源节里精灵名最多列多少个（防止一屏几千条名字把快照撑大）。 */
    const val RESOURCE_NAME_LIMIT: Int = 64

    /** 显示节尾文本行数（与 vmdbg sidecar 的 200 行同口径）。 */
    const val DISPLAY_TAIL_LINES: Int = 200

    /**
     * 采全部节（顺序 = [KNOWN_SECTIONS]）。
     *
     * @param saveConfig 会话存档配置（决定变量节字节是二进制还是文本、是否压缩）
     * @param snapshotText 写进存档字节的描述文本（`SAVEDATA_TEXT` 同口径；只作人读标记）
     * @param events 调用方注入的事件尾（旧在前）
     */
    fun collect(
        store: VariableStore,
        console: LineBufferConsoleSink,
        saveConfig: SaveConfig = SaveConfig.DEFAULT,
        events: List<String> = emptyList(),
        snapshotText: String = "eratw runtime snapshot",
        tailLines: Int = DISPLAY_TAIL_LINES,
    ): List<Section> = listOf(
        vm(store),
        vars(store, saveConfig, snapshotText),
        rng(store),
        display(console, tailLines),
        resources(store),
        events(events),
    )

    /** 调用栈节：帧链（`#i FUNC LOCAL[0..n]=…` 口径原文）+ 深度。 */
    fun vm(store: VariableStore): Section {
        val depth = runCatching { store.callDepth() }.getOrDefault(-1)
        val frames = runCatching { store.frameSnapshotLines(VM_FRAME_ELEMS) }.getOrDefault(emptyList())
        return Section(
            VM,
            linkedMapOf(
                "callDepth" to Value.Int64(depth.toLong()),
                "frames" to Value.TextList(frames),
                // `TODO(verify)`：`Vm.kt` 的 pc 在 run() 里是局部变量，核心层没有读口；
                // 拿不到就如实写 unavailable，不为快照给 VM 加字段（契约 §1「不改」）。
                "pc" to Value.Text("unavailable"),
                "framesSource" to Value.Text("VariableStore.frameSnapshotLines(maxElems=$VM_FRAME_ELEMS)"),
            ),
        )
    }

    /**
     * 变量节：把当前变量池按**引擎存档编码**抓成字节（`SAVEDATA` 的同一条链）。
     *
     * 非 SAVEDATA 变量不进档 —— 这是 `SaveCapture` 的既有筛选口径（`VarDef.isSavedata` 位），
     * 快照如实继承：`scope` 字段把这条口径写进文件，免得分析器把「缺席」误读成「丢数据」。
     */
    fun vars(
        store: VariableStore,
        saveConfig: SaveConfig = SaveConfig.DEFAULT,
        snapshotText: String = "eratw runtime snapshot",
    ): Section = runCatching {
        val emData = SaveWiring.of(store)?.emSets?.save ?: EmDataNames.EMPTY
        val compressed = saveConfig.systemSaveInBinary && saveConfig.zipSaveData
        val data = SaveCapture.normal(store, snapshotText, compressed, emData)
        val bytes = SaveCodec.write(data, store.registry, saveConfig)
        Section(
            VARS,
            linkedMapOf(
                "bytes" to Value.Blob(bytes),
                "encoding" to Value.Text(if (saveConfig.systemSaveInBinary) "binary" else "text"),
                "compressed" to Value.Bool(compressed),
                "characters" to Value.Int64(store.characters.count.toLong()),
                "savedUserVars" to Value.Int64(store.savedUserVarList().size.toLong()),
                "scope" to Value.Text("SAVEDATA 位过滤（SaveCapture 口径）：非 SAVEDATA 变量不入档"),
            ),
        )
    }.getOrElse { e ->
        Section(VARS, linkedMapOf("error" to Value.Text(e.javaClass.simpleName + ": " + (e.message ?: ""))))
    }

    /**
     * 随机数节：SFMT 的 625 元素状态原文（分析器据此 diff「只差一次抽号」）。
     *
     * 非 SFMT 提供者 / 未注入（[VariableStore.randProvider] 为 null）⇒ 空表 + `provider=absent`；
     * `state()` 抛错（旧接口的非 SFMT 实现）同样按缺席记，不让快照失败。
     */
    fun rng(store: VariableStore): Section {
        val state = runCatching { store.randProvider?.state() ?: LongArray(0) }.getOrElse { LongArray(0) }
        val fields = LinkedHashMap<String, Value>(4)
        if (state.isEmpty()) {
            fields["provider"] = Value.Text("absent")
            fields["state"] = Value.IntList(emptyList())
        } else {
            fields["provider"] = Value.Text("sfmt19937")
            fields["state"] = Value.IntList(state.toList())
            fields["cursor"] = Value.Int64(state[state.size - 1])
            fields["stateLen"] = Value.Int64(state.size.toLong())
        }
        return Section(RNG, fields)
    }

    /**
     * 显示节：保留行的**行文本尾** + 按钮区间 + 三个计数。
     *
     * 只取文本，不取 [LineBufferConsoleSink.DisplayLine] 的结构对象：快照要跨进程、跨版本读，
     * 段样式/div 块这类渲染面细节会随渲染层演进漂移，而「屏幕上最后那句话 + 有哪些可点区间」
     * 才是诊断最贵的那两样。像素与位图同理不进来（契约 §4「不存像素」）。
     */
    fun display(console: LineBufferConsoleSink, tailLines: Int = DISPLAY_TAIL_LINES): Section {
        val tail = runCatching { console.tailText(tailLines) }.getOrDefault(emptyList())
        val buttons = runCatching { console.buttons() }
            .getOrDefault(emptyList())
            .map { "${it.lineIndex}:${it.startCol}-${it.endCol}=${it.value}" }
        val fields = linkedMapOf<String, Value>(
            "tail" to Value.TextList(tail),
            "buttons" to Value.TextList(buttons),
            "lineCount" to Value.Int64(console.lineCount),
            "displayLines" to Value.Int64(console.displayLineCount.toLong()),
            "revision" to Value.Int64(console.revision),
            "tailLimit" to Value.Int64(tailLines.toLong()),
        )
        return Section(DISPLAY, fields)
    }

    /**
     * 资源节：三个池的计数 / 名字 / 占用字节。
     *
     * `store.aux` 的槽没装配时对应字段整段缺席（不是 0 —— 0 会被读成「池是空的」）。
     * 精灵名只列前 [RESOURCE_NAME_LIMIT] 个（按字典序，保证同等状态产出同等字节，
     * 便于 diff）；像素本体、PNG 载荷、位图路径一律不进快照。
     */
    fun resources(store: VariableStore): Section {
        val fields = LinkedHashMap<String, Value>(10)
        val gfx = store.aux.peek<GraphicsPool>(GRAPHICS_SLOT)
        if (gfx != null) {
            val stats = runCatching { gfx.stats() }.getOrNull()
            if (stats != null) {
                fields["gfx.entries"] = Value.Int64(stats.entries.toLong())
                fields["gfx.created"] = Value.Int64(stats.created.toLong())
                fields["gfx.pixelBytes"] = Value.Int64(stats.pixelBytes)
                fields["gfx.demoted"] = Value.Int64(stats.demoted.toLong())
                fields["gfx.sourceMisses"] = Value.Int64(stats.sourceMisses)
            }
        }
        val sprites = store.aux.peek<SpritePool>(SPRITE_SLOT)
        if (sprites != null) {
            runCatching { sprites.size() }.getOrNull()?.let { fields["sprite.count"] = Value.Int64(it.toLong()) }
            runCatching { sprites.resourceSize() }.getOrNull()
                ?.let { fields["sprite.resourceCount"] = Value.Int64(it.toLong()) }
            runCatching { sprites.names() }.getOrNull()?.let { names ->
                val listed = names.sorted().take(RESOURCE_NAME_LIMIT)
                fields["sprite.names"] = Value.TextList(listed)
                fields["sprite.namesTotal"] = Value.Int64(names.size.toLong())
            }
        }
        val cbg = store.aux.peek<CbgPool>(CBG_SLOT)
        if (cbg != null) {
            runCatching { cbg.snapshot(gfx, sprites) }.getOrNull()
                ?.let { fields["cbg.layers"] = Value.Int64(it.size.toLong()) }
        }
        return Section(RESOURCES, fields)
    }

    /** 事件节：调用方注入的事件行（app 侧 = `EventJournal.tail(n)` 的编码行，旧在前）。 */
    fun events(lines: List<String>): Section = Section(
        EVENTS,
        linkedMapOf(
            "lines" to Value.TextList(lines),
            "count" to Value.Int64(lines.size.toLong()),
        ),
    )

    /** 帧局部每变量导出元素数（与 `store.frameSnapshotLines` 的缺省 8 同值）。 */
    private const val VM_FRAME_ELEMS = 8
}
