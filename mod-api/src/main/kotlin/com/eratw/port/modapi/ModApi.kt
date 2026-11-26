package com.eratw.port.modapi

/**
 * Kotlin mod SDK 的**作者面契约版本**（RT2 波 Lane E：API/ABI 版本协商）。
 *
 * ## 是什么 / 不是什么
 *
 * 这是「内核向 mod 作者承诺的 API 面」的版本号，与 [ModManifest.version]（mod 自己的语义版本，
 * 形如 `1.4.2`，用于展示/更新提示）是**两件独立的事**，互不推导：
 *
 * - [VERSION] 由**本体内核**发布：内核升级到某个 SDK 版本，这个常量跟着递增；
 * - mod 侧 [EraMod.apiVersion] / [ModCarrierDeclaration.apiVersion] 的缺省值就是作者**编译时**
 *   所对的那版 [VERSION]（作者不改代码就自动跟随）。
 *
 * 两者都只存在于 **Kotlin 代码声明 + `.rt2` 侧车**里，**不写进 `ModManifest` 的线格式**
 * （manifest 列集由 manifest 线格式维护，版本协商不占它的列 —— 避免与 manifest 格式升级相撞；
 * 作者不要试图把 apiVersion/dataVersion 塞进 manifest 字段，宿主不从那读）。
 *
 * ## 装载期规则（单向兼容）
 *
 * 宿主装载时比较 `mod.apiVersion > ModApi.VERSION`：
 *
 * - `>` ⇒ 这个 mod 需要更新的契约：**只拒这一个 mod**（`active=false`，原因经
 *   `mod.install pkg=… active=false failure=…` 日志通道上屏），其余 mod 与本体照常装载；
 * - `<=` ⇒ 正常装载（老 mod 不声明时取编译期缺省值，等价于「与编译它的内核同版」）。
 *
 * ## 递增规则（发布纪律）
 *
 * 1. 只增不减；发布过的值不复用、不回退。
 * 2. 任何**作者可见 API 面**的新增或变更（[ModContext] 的新属性、[EraMod] 的新回调、既有签名/语义
 *    的破坏性调整、SDK 行为约定变化）都要 +1 —— 老内核不认识这些面，必须能按上面的规则拒绝新 mod。
 * 3. 纯内部实现、日志文案、性能优化、不改变作者面契约的 bug 修复**不** +1。
 * 4. 与 [ModManifest.version] / [EraMod.dataVersion] 的递增完全独立，不由它们推出。
 *
 * ## 当前取值
 *
 * `2` = 在 v1 之上追加两处作者面：per-mod 设置面（[ModContext.settings]，RT2 波 Lane D）与
 * 跨 mod Kotlin 服务注册面（[ModContext.services]，Lane S）。两者都是**新增**：v1 编译的 mod
 * 不读它们、照常装载；反过来按 v2 编译的 mod 用到这两个属性时，v1 内核不认识 ⇒ 装载期按
 * 上面的单向兼容规则只拒这一个 mod。
 *
 * `1` = 首个冻结的作者面契约：全部 [ModContext] 属性（logger/runtime/entities/relationships/roster/
 * events/hooks/phases/jobs/interactions/actions/query/functions/storage/gameState/rng/timing/ui）
 * 加 [EraMod] 的六个生命周期回调，即 RT2 波 A–G 收口后的形态。
 *
 * `3` = 新增作者面属性 [ModContext.resources]（RT2 mod 资源注入第一刀：按包只读
 * `mods/rt2/<packageId>.assets/` 的字节存储面，语义与边界见 [ModResourceApi]）；
 * 其余 [ModContext] 属性与 [EraMod] 回调签名不变。老 mod（apiVersion 缺省 1）照常装载。
 *
 * `4` = [EventApi.trigger] 新增「mod 主动触发事件」作者面（v4 时点只允许两次 VM run 之间调用；
 * 线程/重入/装配边界见该方法 KDoc）。既有方法签名与语义不变；按 v3 及更早编译的 mod
 * 不调它即完全不受影响。
 *
 * `5` = [HookOptions.continueOnHandlerFailure] 新增「处理器失败后撤销该槽」作者面（缺省 `true`
 * = 与历史逐位一致）。旧 mod 不声明即维持既有行为；按 v5 编译且显式设 `false` 的 mod 要求内核
 * 认识这个语义，故按第 2 条递增。
 *
 * `6` = [EventApi.trigger] 的调用位置语义放宽：VM run 进行中调用不再是「拒绝」，而是**延迟到该 run
 * 收尾后按 FIFO 派发**（v6 时点：两次 run 之间仍是当刻立即派发；非 VM 线程与事件派发中仍拒）。
 * 这是作者面语义变更（v5 内核会抛 ISE 的位置 v6 受理），故 +1；按 v5 编译的 mod 只要不在 run 内
 * 依赖「抛异常」这一行为即不受影响。
 *
 * `7` = 新增浮层图片元素作者面 [ModUiElement] / [modUiImage]（结构化元素 + 资源名引用；
 * 只为**新增**面，既有 `UiApi.registerInteraction` 文本项语义不变）。渲染降级由宿主承担，
 * 接线见 app 侧 `ModUiSurface`；按 v6 及更早编译的 mod 不声明元素即不受影响。
 *
 * `8` = [EventApi.trigger] 的拒绝面再收窄：**事件派发过程中**（事件桶处理器 / hook 处理器里）调用
 * 不再抛 ISE，而是并入同一条延迟队列，**在本轮事件派发收尾后按 FIFO 补派**；连环触发（A 触发 B、
 * B 再触发 A）受每轮 64 条的链上限硬闸约束，到顶停止入队并落一条 WARN。至此只剩「非 VM 线程」与
 * 「装配缺失」两种拒绝。按 v7 及更早编译、依赖「派发中抛异常」这一行为的 mod 需留意（其余不受影响）。
 * `9` = `UiApi` 新增 [UiApi.registerImage] 声明面：作者把浮层图片元素**注册**进宿主浮层（v7 只给了
 * [ModUiElement] 元素模型，但当时没有任何作者可用的注册入口，元素只能靠测试注入）。声明期校验与
 * v7 元素模型同口径；未接线/未装配时渲染侧仍按 v7 的降级纪律处理。按 v8 及更早编译的 mod 不调用
 * 即不受影响。
 *
 * `10` = [StringsApi] 新增「mod 自带三语文案」作者面：[ModContext.strings] 的声明/解析入口
 * （`declare`/`of`/`has`/`language`），浮层标签先问作者文案、未声明回落既有技术标识。宿主侧语言码
 * 由壳层注入（`ModRuntimeHost.language`）。按 v9 及更早编译的 mod 不声明文案即不受影响（缺省
 * getter fail-closed 抛 UOE，不为老装配静默兜底）。
 *
 * `11` = [AudioApi] 新增「mod 自带音频」作者面：[ModContext.audio] 的 `play`/`stop`，播放汇由
 * 壳层注入（`ModRuntimeHost.audioSink`）。与引擎 `SoundChannel` 完全隔离：不抢既有 10 声道、
 * 不受 `STOPSOUND`/`BGMCONTROL` 影响，`stop()` 只停本包自己放的声音。按 v10 及更早编译的 mod
 * 不放声音即不受影响（缺省 getter fail-closed 抛 UOE，不为老装配静默兜底）。
 *
 * `12` = [EraMod] 新增依赖声明作者面 [EraMod.requires]：packageId 列表，装载期按它做稳定拓扑排序，
 * 缺依赖 / 成环 / 依赖未成功启用一律 fail-closed 拒载（原因经既有
 * `mod.install pkg=… active=false failure=…` 上屏）。缺省空列表 = 装载顺序与 v11 逐位一致；
 * 按 v11 及更早编译的 mod 不声明即不受影响，按 v12 编译且声明依赖的 mod 由装载期单向兼容规则
 * 挡在老内核外（老内核的 `EraMod` 没有这个属性）。
 *
 * `13` = 耗时归因面从「只读」扩到**读写**：[TimingApi.record] 与扩展
 * [com.eratw.port.modapi.measure] 让作者把自己的代码段记进与引擎四个测量点**同一张表**（同
 * `(packageId, scope)` 聚合、同 `count/total/max/average/last` 口径），因此作者 scope 也会出现在
 * `samples()` 的降序表与宿主 `mod.timing …` 日志里。`record` 接口方法带缺省实现且 fail-closed 抛
 * UOE（未接账本的老装配不静默丢弃作者计时）。按 v12 及更早编译的 mod 不调用即完全不受影响。
 *
 * `14` = [EventApi] 新增事件名**只读查询**面：[EventApi.exists] 与 [EventApi.knownEventKeys]，
 * 让作者在 `register`/`trigger` 之前就能自查「引擎认不认这个事件名」。真源仍是装载期注入的已知事件集
 * （与既有 known 闸同源同归一规则 `trim().uppercase`），查询面只是它的只读投影，不新增第二份真相。
 * 两个方法都带缺省实现且 fail-closed 抛 UOE（未接线的老装配不静默答「没有这个事件」——
 * 那会让作者把「引擎没这个事件」和「宿主没接线」混为一谈）。按 v13 及更早编译的 mod 不调用即不受影响。
 *
 * `15` = ModDX 创作体感系列三面（各自带缺省实现/缺省参数，互不强制）：
 * - [EraMod.onSettingsChanged]：玩家改本包设置并持久化成功后的热通知回调（缺省空实现；
 *   宿主派发前经内部重注缝刷新 `context.settings`，回调里读到的已是新值）；
 * - [GameStateApi.hasIntVar] / [GameStateApi.hasStrVar]：全局变量存在性探测（缺省 false =
 *   台架/未接线口径；既有 getIntVar/getStrVar 哨兵语义不变，专治拼错变量名静默 0）；
 * - [UiApi.registerInteraction] 增 `visible` 谓词形参（缺省 null = 恒可见）：交互项动态显隐，
 *   谓词在宿主查询/命中时现求值，不可见项不出快照、click 不命中。
 * 按 v14 及更早编译的 mod 不使用新面即完全不受影响。
 *
 * `16` = [HookApi] 新增函数钩子**只读查询**面：[HookApi.registrations] 与 [HookRegistration]
 * （ModDX2-G3），让作者在运行前后都能自查「谁在钩同一函数、什么顺序、priority 撞没撞」。
 * 投影序 = functionKey 字典序 → priority → 声明序，与钩子派发的槽确定性序（HookPipeline 同源）
 * 一致；真源仍是链接期槽表，查询面只是它的只读投影，不新增第二份真相。`registrations` 带缺省实现
 * 且 fail-closed 抛 UOE（未接线的老装配不静默答「没有钩子」——口径与 v14 的 [EventApi.exists]
 * 同形）。按 v15 及更早编译的 mod 不调用即完全不受影响。
 *
 * `17` = [EraMod] 新增**存档升级回调** [EraMod.onPackageUpgrade]（ModDX2-A）：装载既有存档且本包
 * 记账版本 ≠ 当前 manifest 版本时、在 onSaveLoaded 之后派发，供作者迁移 per-package storage
 * 旧数据（对标 Factorio migrations.lua；首次见到本包只记账不回调，记账键 `__eratw.pkgVersion`
 * 为宿主保留）。缺省空实现；按 v16 及更早编译的 mod 不覆写即完全不受影响。
 *
 * `18` = [modDeclarations] 新增 `minApi: Int = 0` 形参（缺省 0 = 不设闸 ⇒ 既有调用点源码零改动、
 * 产物字节逐字不变）：Kotlin 载体终于能像口上包（`pack-mod --min-api`）一样声明「最低引擎 API」，
 * 装配期由内核侧的 `ModuleDescriptor.minApi` 走既有整链拒载闸。manifest 只在 `minApi > 0` 时升
 * `RT2MANIFEST/6` 并写 `min-api` 段；`minApi = 0` 仍是原来那个版本号、原来那些字节。
 *
 * `19` = [ModManifest] 新增 `declarationPositions: Map<String, String> = emptyMap()` 作者面
 * （ModDX-3 DX3-C）：声明原文 → `文件:行号` 的可选位置表。写侧由 `modgen` 透传进 manifest 的
 * `RT2MANIFEST/7 declaration-positions` 段，读侧由内核 `DeclarationRegistry` 在声明冲突消息里
 * 回显定位（无位置则沿用旧文案，逐字不变）。空表不升 manifest 版本、产物字节与 `/3`–`/6`
 * 逐字相同；引擎不解析、不猜、不从反射造行号，值完全由作者填写，仅用于诊断。按 v18 及更早
 * 编译的 mod 不声明该字段即完全不受影响；老内核不认识该字段，故按第 2 条递增。
 *
 * `20` = [GameStateApi] 新增**受控写**作者面（ModDX4-B1）：[GameStateApi.setIntVar] /
 * [GameStateApi.setStrVar]，配套 [ModManifest.writeGrants] 声明域（`RT2MANIFEST/8` 的
 * `write-grants` 可选段 + [WriteGrantDecl]）与声明 DSL `writeGrant(key, type)`。写经
 * RuntimeScheduler 排队到转移边界（API 层禁止即时写）；授权 fail-closed：本包 global-dims
 * 变量免授权，原版键须 writeGrant 声明且类型一致，未声明键写当场抛 IllegalArgumentException
 * （文案对齐 SettingsApi 未声明键口径）；两个写方法都带缺省实现且 fail-closed 抛 UOE
 * （未接线/旧装配不静默吞写）。空 writeGrants 不升 manifest 版本、产物字节与 /3–/7 逐字相同。
 * 按 v19 及更早编译的 mod 不调用新方法即不受影响。
 *
 * `21` = [HookApi] 新增 hook 可挂函数键**只读查询**面（ModDX4-B3）：[HookApi.knownFunctionKeys]，
 * 作者在声明/绑定 hook 之前就能自查「能钩什么」——与 v14 的 [EventApi.knownEventKeys] 完全同构：
 * 真源 = 链接期 `SymbolCatalog.bindings` 的非事件胜者键集，经宿主 `ModRuntimeHost.knownFunctionKeys`
 * 灌入；查询面只是它的只读投影，不新增第二份真相。配套 [HookApi.register] 拼错闸：宿主灌入函数
 * 目录后，绑定到目录外目标键当场抛 IllegalArgumentException 并附最近 3 候选（编辑距离 → 名字序，
 * 口径与 events 未知名拒绝一致）；未灌源（台架/旧装配）= 空集，查询面答空集、register 不校验，
 * 与加闸前逐位一致。knownFunctionKeys 带缺省实现且 fail-closed 抛 UOE（未接线的老装配不静默
 * 返回空集）。按 v20 及更早编译的 mod 不调用即不受影响。
 */
object ModApi {
    /** 本内核向 mod 作者承诺的 API 面版本（递增规则见本对象 KDoc）。 */
    const val VERSION: Int = 21
}
