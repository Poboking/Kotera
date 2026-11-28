<div align="center">

# Kotera

**简体中文** | [English](README_EN.md) | [日本語](README_JA.md)

[![License: MIT](https://img.shields.io/badge/license-MIT-263146?style=flat-square)](LICENSE)
[![CI](https://github.com/Poboking/Kotera/actions/workflows/ci.yml/badge.svg)](https://github.com/Poboking/Kotera/actions/workflows/ci.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?style=flat-square)
![JDK](https://img.shields.io/badge/JDK-17-263146?style=flat-square)
![Platform](https://img.shields.io/badge/platform-JVM-6a737d?style=flat-square)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)

**用 Kotlin 编写、运行在 Java 虚拟机上的 ERA 游戏引擎。**

Kotera 负责执行 ERB 游戏脚本、管理角色与变量、处理存档，再把画面交给 Android
或桌面前端。可以把它理解为游戏的「规则层」：它决定脚本怎么运行，不负责做好看的界面。

如果你只想在 Android 上玩 EraTW，请使用 [eratw-pocket](https://github.com/Poboking/eratw-pocket)；
如果你在制作或移植 era 游戏、想接入自己的前端，可以从本仓库开始。Kotera 不附带任何
游戏数据，也不需要 Android 环境就能独立构建与测试。

</div>

---

## 它是什么

- **语义层，不是前端。** Kotera 负责游戏规则本身：ERB 字节码执行、变量与角色
  系统、存档、内建函数。画面以显示列表输出——也就是一份描述文字、按钮和图片的
  绘制清单——交由各前端自行呈现。
- **兼容性看齐 Emuera。** 引擎以 Emuera 的实际表现为基准：同一份输入分别运行两套
  实现、比较结果，称为「对拍」。文档没写清的边界行为会再查 Emuera 源码，并用版本与
  `文件:行号` 记录出处。源码参考与翻译改编的边界见 [NOTICE.md](NOTICE.md)。
- **现代 ERA 运行时能力。** 除基础 VM 外，还包含可链接补丁 / Mod 装载、实体与
  虚拟角色、SQL 会话、更新清单签名、侧车存档与崩溃恢复、内存分级等运行时能力。
- **不依赖 Android。** 纯 JVM 实现，在无图形环境下照常测试，这也是它能在 CI
  上持续对拍的前提。
- **自足。** 不需要任何游戏数据即可构建、跑测试、复现问题。

`specs/` 是本项目的语义权威，覆盖 ISA、内建函数、变量、装载、存档格式、RNG、
补丁容器与错误文案。各文件之间的关系见 [specs/README.md](specs/README.md)。

## 最小跑起来

只需要 JDK 17+；不需要 Android SDK，也不需要准备游戏数据。命令从仓库根目录执行
（Windows 上把 `./gradlew` 换成 `.\gradlew.bat`）。若 Gradle 提示找不到 Java，先把
`JAVA_HOME` 设为 JDK 17+ 的安装目录：

```bash
git clone https://github.com/Poboking/Kotera.git
cd Kotera

./gradlew :core-vm:test :render:test
./gradlew :tools:uipreview:test --tests '*CliSmokeTest'
```

第一条构建并运行引擎与渲染层的自足测试。第二条会临时写一份 mini ERB 脚本、编译它，
启动桌面预览服务并模拟点击与输入，最后自动退出；用来确认「脚本 → 引擎 → 预览」
整条链路能工作。真实游戏语料相关的用例只有设置环境变量后才会运行，见下方
「构建与测试」。

## 性能：现有数据与缺口

Kotera 采用预编译字节码和内存映射装载，目的是减少启动时的解析工作；这不等于已经在
所有设备上胜过模拟器。**目前公开资料没有「Kotera / eratw-pocket vs 模拟器」的同版本、
同场景对照数据，因此本文不再声称“在部分低端设备上更顺畅”。**

可核对的真机记录是一项 Android AOT profile 实验，而不是模拟器对比：

| 项目 | 实验条件 |
|---|---|
| 日期 / 设备 | 2026-10-27；vivo V2199A；Android 14 / API 34 / arm64-v8a |
| 对比对象 | 同一个 13.1 MB release APK，仅改变 AOT 状态；不是 Emuera 或安卓模拟器 |
| 场景 | `am start` → 标题页出现可点选项；3 次读数 |
| 结果 | 无真机 profile：15.98 s；加入真机采集的 882 条 profile：12.74 s |
| 冷启 `TotalTime` | 5 次中位数：336 ms → 293 ms |
| 限制 | 复用设备上已解包的 749 MB 数据（`--skipAssets`）；未记录 SoC、物理 RAM 和 APK 版本号；只覆盖标题页路径 |

原始记录见 [eratw-android T2-63](https://github.com/Poboking/eratw-android-port/blob/main/docs/agent/archive-20261128/CONTRACT-T2.md#L520-L535)。
它证明该产物在该机的标题页启动路径上有收益，**不能**用来证明比模拟器快，也不能把
该机称为低端机。后续补性能声明时，应同时公布机型/SoC/RAM、系统与两端版本、相同
游戏数据、冷/热启动条件、场景、采样次数，以及帧时间或加载时间原始记录。

## 仓库结构

| 路径 | 说明 |
|---|---|
| `core-vm` | ERA 语义虚拟机：ERB 字节码执行、变量与角色系统、存档读写、内建函数、SFMT 随机数、补丁 / Mod 运行时 |
| `render` | 纯 JVM 渲染层：字形缓存、文本网格、按钮命中区、显示列表、图形层 |
| `sqlite-adapter` | `SqlDriver` 端口的 AndroidX SQLite 适配器：随包 seed 库落成可写副本、原子刷新（SQLite 真实引擎的平台接线） |
| `mod-api` | RT2 Mod SDK 的 API 面：实体 / 事件 / 函数 / Hook 等声明与宿主上下文 |
| `mod-family` / `mod-memory` / `mod-livingworld` | 内置官方 Mod：家族谱系、记忆、生界（随包 manifest 的真源） |
| `mod-registry` | 载体注册表：构建期扫各 `mod-*` 模块上打了 `@ModCarrier` 的声明 object，生成唯一 `ModRegistry`（宿主不再逐个点名载体模块） |
| `mod-template` | mod 作者工具链：最小载体模板骨架（只供人抄，**不进**生产 `ModRegistry`） |
| `mod-testing` | mod 作者工具链：`:mod-api` 能力面的内存实现 + `runMod` 装配，让作者不开引擎就能验 mod 业务逻辑 |
| `tools/erbcc` | ERB / ERH → `erbc.v1` 字节码容器编译器；含语料编译、符号审计与 Rust 对拍接线 |
| `tools/erbcc-rs` | erbcc 的 Rust 侧实现（Cargo 工程），用于与 Kotlin 编译器做逐字节对拍 |
| `tools/modgen` | RT2 模块 / manifest 生成器（内置 mod 与 demo 模块的构建入口） |
| `tools/modgen-accessors` | modgen 声明 → 类型安全访问器的代码生成 |
| `tools/vmdbg` | 面向 agent 的无交互调试 CLI：call / trace / where / var / disasm / sweep / ledger |
| `tools/uipreview` | 桌面渲染预览：驱动真实的 core-vm + render，浏览器查看画面 |
| `tools/fontsubset` | 字体子集再生成脚本（Python），维护 `render/src/main/resources/fonts` |
| `specs/` | 语义规格与对拍证据（ISA、内建、变量、装载、存档、RNG、补丁、错误文案） |
| `fixtures/` | 自包含测试夹具：golden ERB、回放轨迹、Rust 对拍数据等 |
| `buildSrc/` | 构建期任务与纯函数（语料补丁、erbc 新鲜度、资产 staging 等） |
| `devtools/` | 语料内容补丁与 erbcc-rs 对拍驱动脚本 |
| `gradle/libs.versions.toml` | 全仓依赖坐标与 JVM / 工具链版本的唯一落点 |

## 构建与测试

需要 JDK 17+。命令均从仓库根目录执行。

```bash
./gradlew :core-vm:test             # 单模块测试
./gradlew :render:test
./gradlew :tools:erbcc:installDist  # 编译器 CLI
./gradlew :tools:vmdbg:installDist  # 调试 CLI
```

CI 等价命令（十五个模块一起跑）：

```bash
./gradlew --no-daemon \
  :core-vm:test :render:test :sqlite-adapter:test \
  :mod-api:test :mod-family:test :mod-memory:test :mod-livingworld:test \
  :mod-registry:test :mod-testing:test :mod-template:test \
  :tools:erbcc:test :tools:modgen:test :tools:modgen-accessors:test \
  :tools:vmdbg:test :tools:uipreview:test
```

少数测试依赖真实语料、真产物、真存档或 Android 编译面，是否运行由环境变量决定。
变量未设置时，部分用例通过 JUnit assumption **跳过**，部分动态用例不会生成；
其余用例自足。公开仓 CI 跑的是这一档，因此“测试全绿”不等于真实语料路径都已覆盖。

CI 还会把姐妹仓 [eratw-pocket](https://github.com/Poboking/eratw-pocket) 放在同一目录下
检出（读取对方 `main`）：`tools/modgen` 的打包检查会读取壳层实际源码，壳层随包资产也
必须与引擎声明逐字节一致；两项跨仓检查只有在这套互检启用时才运行。跨仓契约（哪个
仓库负责哪项源码检查、合并顺序、冻结夹具比较什么、权威语料根）见
[specs/cross-repo-contract.md](specs/cross-repo-contract.md)。

| 环境变量 / 属性 | 作用 |
|---|---|
| `ERATW_CORPUS` | 游戏 `ERB/` 目录；真实语料编译与语料审计用例 |
| `ERATW_GAMEDIR` | 游戏根目录（含 `CSV/`、`resources/`、`emuera.config`）；XML / 配置 / 图像实测用例 |
| `ERATW_ERBC_DIR` | 已编译的 `erbc.v1` 产物目录；装载 / 侧车 / mmap 用例 |
| `ERATW_SAVE_SAMPLES` / `-PsaveSamples` | 真实 `.sav` 样本目录；存档回环与变异写入用例 |
| `ERATW_ANDROID_JAR` | Android `android.jar`；未设置时自动探测 `ANDROID_HOME` / `ANDROID_SDK_ROOT`，仍找不到才跳过 `AndroidApiCompatTest` |
| `ERATW_ROOT` | 可选的共享检出根；仅少数内部重放用例使用，公开仓无默认值 |

`ERATW_CORPUS` 未设置时，`compileCorpus` 等需要语料的任务也会直接给出明确报错，
不会静默产出空包。

**权威语料根 = Kotera 根**（`eratw-画蛇添足版eratw4.980`，NOW_VERSION 20260326）。
语料相关的冻结读数（`ErbccCorpusProfile` 的标签数、`WebpDecoderTest` 的 `.webp` 清单、
`AliasLoaderTest` 的 `.als` 别名、`RandSfCorpusE2ETest` 的原文锚点等）全部按这棵根冻结，
两个公开仓共用同一口径 —— 换根跑会与冻结值对不上，那是**语料换代**不是回归。
`RealCorpus` 的缺省探测也指向这棵根（`GAME_DIR_NAME`）。

### erbcc-rs 对拍

Rust 侧需要 Cargo；先构建，再走 Gradle 接线：

```bash
cargo build --release --manifest-path tools/erbcc-rs/Cargo.toml
./gradlew :tools:erbcc:erbccClasspathFile
./gradlew :tools:erbcc:rsParity -Pcorpus=<ERB目录>
./gradlew :tools:erbcc:regenRsBaselines -Plane=all
```

细节见 [tools/erbcc-rs/README.md](tools/erbcc-rs/README.md)。

## 仓库关系

```
Kotera（本仓库，MIT）     eratw-pocket（GPL-3.0）
├── core-vm   ←────────────  ├── render-canvas
├── render    ←────────────  ├── platform
├── sqlite-adapter / mod-*   └── app（Compose 壳层）
└── tools/erbcc
```

eratw-pocket 对游戏语义一无所知——这是有意为之。脚本行为、内建函数、存档格式
的权威定义全部在本仓库的 `core-vm` 与 `specs/` 里；壳层的改动也不允许改变任何
游戏行为。因此，看起来像「游戏行为不对」的问题，请提到本仓库而不是
[eratw-pocket](https://github.com/Poboking/eratw-pocket)，即使现象发生在手机上。

## 架构与设计取舍

**行为对拍 + 源码辅助考古。** 「对拍」就是把同一份脚本或输入分别交给两套实现，
比较输出、变量、存档、错误与事件轨迹；它回答“行为是否一致”。查阅 Emuera 源码则
回答“为什么一致、某个边界条件从何而来”，因此行为对拍与源码参考并不矛盾。
Kotera 的部分算法、数据结构和控制流确有逐行或逐句翻译与适配，源码注释也明确记录了
这类出处；项目不声称采用 clean-room 隔离开发。参考版本包括 1824+v24、EM+EE 谱系
和 4.981 Skia fork，不能一概写成“原版 1.824”。

**规格先行的对拍开发。** `specs/` 下的规约都成文于实现之前，行为结论以对拍证据为准，
并尽可能附上所参考 Emuera 的版本与 `文件:行号`。许多只看文档发现不了的细节，正是靠
对拍抓出来的——比如引擎默认 RNG 并不是 `System.Random`，而是自实现的 SFMT19937
（周期 2^19937−1），我们照原样复刻了它。项目内部有一条简单的约定：规格与实现
不一致时以规格为准；实现若证明规格有误，先改规格、再改实现，顺序不能反。

**AOT 字节码 + mmap 零解析装载。** ERB/ERH 在构建期由 erbcc 编译成 `.erbc`
容器（小端、i64 数值、字符串驻留、指令定长 4×u32、段表寻址），运行时经 mmap
直接寻址，不做任何解析。这样做的目标是减少 Android 冷启动时的解析工作；实际收益
取决于设备、游戏和前端，应以对照测量为准。装载层另有
`FlatPoolBudget` 按堆预算预检、取指页缓存与热路径融合指令等优化，均在
`core-vm` 内有定向用例与反向证明。

**可链接补丁与 Mod。** 补丁容器内嵌完整合法的 `.erbc`，装载期由
`ModuleLinker` / `CallRelocation` / `ExternalImportTable` 完成模块链接、调用重定位
与外部导入解析；`PatchCompiler` 负责把补丁编译成可链接模块。格式权威见
`specs/patch-format.yaml`。

**编译确定性是受测性质，不是假设。** 产物逐字节可复现（`ErbcDeterminismTest`），
另设装载序审计：若重名定义文件之间的相对次序受文件系统枚举顺序影响，审计计数
非零即判失败。跨机器、跨平台的编译结果因此一致。

**纯 JVM 分层。** `core-vm` 禁用 Android API 与 `kotlin.random`，没有静态可变
全局（状态挂在 store 的 aux 槽），因此在桌面和 CI 上可以全量测试；`render`
同样是纯 JVM 的显示列表模型，任何前端（Compose、桌面预览）都只是它的消费者。

**版本对账在构建期完成。** erbcc 版本单源自 `core-vm` 的 `Version.kt`。下游
构建时会校验字节码产物的编译器版本与源码指纹，发现漂移直接构建失败——「旧产物
配新引擎」的错配进不了最终产物。

**三级状态哈希回归。** 测试侧的 L3 装置只通过 `VariableStore` 的公开读写面观察
整个 VM 状态（变量、XML、精灵池、驻留表），逐簇摘要成哈希链，再与 PC 基准的
全量执行逐字节比对。整个过程不改引擎一行：内部布局重构不影响口径，语义变化
则会如实反映到链值上。

## 兼容性矩阵与游戏验证

截至 2026-11-28。这是一张能力地图，不是兼容度百分比：**已实现**表示已有生产路径和
自足测试；**部分**表示核心能力存在，但仍有明确缺口或依赖前端；**未实现**表示没有
可用的生产路径。真实语料、真实存档与真机运行是另一层验证，不能由单测代替。

| Emuera 能力 | 状态 | 当前范围与已知缺口 | 证据入口 |
|---|---|---|---|
| ERB/ERH 编译与装载 | 已实现 | AOT `.erbc`、普通读取与 mmap 路径有一致性测试；真实语料需另行配置 | [编译测试](core-vm/src/test/kotlin/com/eratw/port/core/compile/GoldenCompileTest.kt)、[mmap 测试](core-vm/src/test/kotlin/com/eratw/port/core/loader/MmapLoaderTest.kt) |
| 表达式、控制流、用户函数与 ISA | 已实现（审计中） | 主路径已落地；语义对拍尚未收口，个别 fork 增量还未登记 | [ISA](specs/isa.yaml)、[内建函数](specs/builtin-functions.yaml)、[验证记录](specs/verify-notes.md) |
| 变量、角色与 CSV | 已实现 | 多维变量、默认值、数组、角色模板和名字表均有生产实现与夹具 | [变量测试](core-vm/src/test/kotlin/com/eratw/port/core/vars/VariablePoolTest.kt)、[角色 CSV 测试](core-vm/src/test/kotlin/com/eratw/port/core/data/csv/CharaCsvLoaderTest.kt) |
| 存档 | 部分 | 格式、升版与合成档往返已实现；4.980 真档逐字节回环有记录。4.981 真档写侧及 PC 回读仍未对拍 | [存档格式](specs/save-format.md)、[升版测试](core-vm/src/test/kotlin/com/eratw/port/core/save/SaveUpgradeSemanticsTest.kt)、[真档探针](core-vm/src/test/kotlin/com/eratw/port/core/save/RealSaveSamplesProbeTest.kt) |
| 随机数 | 部分 | 默认 SFMT19937 有 PC 黄金向量；`UseNewRandom=true` 的 .NET 8 `xoshiro256**` 路径未实现 | [RNG 规约](specs/rng.md)、[SFMT 测试](core-vm/src/test/kotlin/com/eratw/port/core/rand/Sfmt19937Test.kt) |
| 图像与精灵 | 部分 | PNG 解码和图形模型已实现；纯 JVM WebP 只解通部分无损样本，有损 WebP 明确不支持，JPEG/GIF 等由宿主解码 | [PNG 测试](core-vm/src/test/kotlin/com/eratw/port/core/image/PngDecoderTest.kt)、[WebP 缺口](core-vm/src/main/kotlin/com/eratw/port/core/image/WebpDecoder.kt) |
| 音频 | 部分 | 声道、循环、音量和查询模型有测试；默认是空实现，真实解码与播放必须由宿主注入 | [音频适配器](core-vm/src/main/kotlin/com/eratw/port/core/audio/AudioAdapters.kt)、[音频测试](core-vm/src/test/kotlin/com/eratw/port/core/audio/AudioAdaptersTest.kt) |
| 视频 | 未实现 | 规格目录与生产代码中均未发现视频播放实现 | [ISA 目录](specs/isa.yaml)、[内建函数目录](specs/builtin-functions.yaml) |
| UI、输入与原生宿主 | 部分（前端负责） | 文本网格、按钮命中区、WAIT/INPUT 与输入队列已实现；Kotera 不复制 Emuera 的 Windows/Skia 窗口本身，画面交给前端 | [输入会话测试](render/src/test/kotlin/com/eratw/port/render/input/InputSessionTest.kt)、[输入语句测试](core-vm/src/test/kotlin/com/eratw/port/core/sem/InputStatementsTest.kt) |
| SQL | 部分 | core 端口、fake 测试和真实 SQLite 的 JVM 测试存在；Android API 21 真机执行尚未证明 | [SQL 方法测试](core-vm/src/test/kotlin/com/eratw/port/core/sql/SqlMethodsTest.kt)、[真实驱动测试](sqlite-adapter/src/test/kotlin/com/eratw/port/sqlite/AndroidXSqliteDriverTest.kt) |
| 网络与 UPDATECHECK | 部分（宿主负责） | URL 判序、权限、结果码与提示文案已测；真实 HTTP 请求由 app 宿主完成 | [更新检查测试](core-vm/src/test/kotlin/com/eratw/port/core/update/UpdateCheckStatementTest.kt)、[遥测边界](core-vm/src/main/kotlin/com/eratw/port/core/update/UpdateTelemetry.kt) |
| 可链接补丁 | 已实现 | 落盘字节装载、完整性失败拒载与回退有 mini 端到端测试 | [补丁装载测试](core-vm/src/test/kotlin/com/eratw/port/core/loader/ModPatchLoaderTest.kt)、[补丁格式](specs/patch-format.yaml) |
| RT2 Mod 运行时（Kotera 扩展，不属于 Emuera 基线） | 部分 | 容器、API、生命周期与失败隔离已有测试；生产调度器和真机全链仍需宿主接线 | [Mod 宿主](core-vm/src/main/kotlin/com/eratw/port/core/mod/ModRuntimeHost.kt) |

### 实际验证过哪些 era 游戏

这里的“验证”按证据强度分别写明，**不把“拿真实语料跑过并发现问题”写成“游戏已完整
兼容”**。

| 游戏 / 版本 | 已有证据 | 当前结论 |
|---|---|---|
| eraTW 4.980 | 真实语料、L3 黄金链、真实 4.980 存档逐字节回环均有记录；公开仓默认不携带这些外部数据 | 历史回归基线，不等于覆盖所有玩法 |
| eraTW 4.981（当前 fork 目标；261111 语料） | 真实语料、选定 E2E/L3 黄金记录；Android 项目另有设备运行记录 | 主要开发目标；4.981 真档写侧与 PC 回读仍是待验项 |
| eratohoReverse（版本未记录） | 真实编译暴露行首预识别缺陷；真机又暴露稀疏 ARG 越界和 `COSPLAY` 调用栈超限，均已留合成回归 | 做过真实语料/真机缺陷验证；**没有完整流程成功记录** |
| eratohoYMAEM（版本未记录） | 真实语料的 `#LOCALSIZE 3000000` 曾使编译崩溃，现有大尺寸合成回归 | 做过真实语料缺陷验证；**没有完整流程成功记录** |
| eraFL（版本未记录） | 真实配置/语料暴露 `#DIM` 维度与 VARI/VARS 可见性缺陷，现有合成回归 | 做过真实语料缺陷验证；**没有完整流程成功记录** |

其他游戏证据见 [行首预识别测试](tools/erbcc/src/test/kotlin/com/eratw/port/erbcc/EngineProfileMiniCorpusTest.kt)、
[稀疏 ARG 测试](core-vm/src/test/kotlin/com/eratw/port/core/proc/SparseArgLengthTest.kt)、
[关键字优先级测试](core-vm/src/test/kotlin/com/eratw/port/core/expr/IndexKeywordBeatsBareFuncTest.kt)、
[LOCALSIZE 测试](tools/erbcc/src/test/kotlin/com/eratw/port/erbcc/compile/LocalSizeWideRangeTest.kt) 与
[VARI/VARS 测试](tools/erbcc/src/test/kotlin/com/eratw/port/erbcc/compile/VariWholeFunctionTest.kt)。
**截至本文日期，除 EraTW 外没有可确认完整启动并走完主要流程的 era 游戏。**

## 语料与版权

本仓库不分发任何 era 游戏数据（脚本、图片、音频、存档、配置）。
`fixtures/` 下的 `GOLDEN_*.ERB`、回放轨迹与 Rust 对拍数据都是本项目自写的测试
夹具，不含游戏文本。请自备合法获得的游戏数据，用环境变量指向它。

Emuera 的出处声明与修改说明见 [NOTICE.md](NOTICE.md)。

## 参与贡献

项目仍在活跃开发中，与 Emuera 的语义对拍尚未收口，已知问题见 issue tracker。
欢迎各种 issue 与 PR。涉及语义的改动有一个前置要求：先在 `specs/` 里写清楚依据。
行为结论应附可复现的对拍输入与结果；涉及源码解释时，再附 Emuera 的版本和
`文件:行号`。如果只是怀疑某个行为有差异、自己也拿不准，直接开 issue 讨论就好，
不必先写规格。翻译或改编上游代码时，还须在源码与 [NOTICE.md](NOTICE.md) 中注明出处，
不能以 MIT 许可覆盖上游条件。

## 致谢

这个项目能做起来，靠的是 era 社区多年积累的成果。

- **MinorShift** 与 **妊）|дﾟ)の中の人**——Emuera 的著作者。本引擎的一切
  兼容性判断都以他们的作品为参考。
- **まだ名前は無い人**——eraThe World（eraTW）在 `GameBase.csv` 中署名的
  修改／制作者；以及所有为 eraTW 撰写口上、事件、数据与文档的创作者。
- era 社区的各位脚本作者、内容作者和工具维护者。

上述致谢不改变任何第三方材料的著作权与许可条件，使用或再分发时请以随附文件为准。

## 许可

Kotera 原创且权利人有权授权的贡献以 [MIT](LICENSE) 发布。该许可不重新许可 Emuera
源码、译文、提取表或相关 fork 材料，也不取代它们的上游条件；参考方式、修改说明与
完整 Emuera 许可原文见 [NOTICE.md](NOTICE.md)。内置字体另有各自的开源许可，见同一文件。
