# .erbm 产物格式与 mods/rt2 盘面规约（erbm-format.md）

> **状态**：MODDX4-D1（2026-11-19）落 specs 权威面。内容从代码逐字段实测反推（真源纪律：
> 每个字段带代码锚，代码与散文冲突以代码为准）；上位叙事只做导航。
> **背景**：ADR-014（`eratw-架构决策记录.md:401`）自认「`.erbm` 至今无 `specs/` 规约文件
> （格式真源只在 KDoc）——文档债另立」，本文件即那次「另立」。
> **证据口径**：source 一律为「相对 eratw-android/ 的路径:行号」。

## 0. 三个同形名辨析（先读这段，别混）

| 名字 | 是什么 | magic | 盘面位置 | 权威规约 |
|---|---|---|---|---|
| legacy 窗口补丁 | R7 时代 `.patch`（v1/v2 文本头 + `---` + .erbc 字节），**只在进口存在**（进口即转码，M2-4） | —（文本头 `erbc-patch`） | 导入输入，不落盘 | specs/patch-format.yaml |
| 四池容器补丁 | M2-4 转码产线之外的 Module 容器（零基线四池 + imports + callTargets + relocations + exports），**免 manifest**（容器自带 ContainerDescriptor） | 前 4 字节 `ERBM` | `mods/` 根目录 `.patch` 且 probe 命中 | 无独立 specs（唯一规约面 = `core-vm/src/main/kotlin/com/eratw/port/core/patch/ModuleContainer.kt` 代码；本文件只记装配面事实） |
| **ERB 模块容器 `.erbm`** | **本文件的对象**：erbcc `compile-module` / 导入链产出的 Mod 代码包容器，直存 Program + FuncMeta + 签名指纹表 | 5 字节 ASCII `ERTM1` | `mods/rt2/<packageId>.erbm` | 本文件 §2-§4 |

magic 取 `ERTM1` 是刻意的：`ModuleContainerCodec.decode` 读前 4 字节判 role（`ERBM` → MODULE /
`ERBC` → LINKED_PROGRAM），`.erbm` 的 magic 前 4 字节 `ERTM` 刻意避开这两个前缀，防同名被误判成
legacy MODULE 容器后按四池格式解出垃圾；扩展名沿用 `.erbm` 与 magic 不同名，同为防解码歧义。
（`core-vm/src/main/kotlin/com/eratw/port/core/mod/ErbModuleContainer.kt:29-34`、`:46-47`）

## 1. packageId 与包身份

- **口上源包**：`kojo-` + sha256(排序后留档源 diskName 列表以 LF join) 前 8 位十六进制（4 字节）。
  同集合重导 = 同 packageId = 原地替换；磁盘命名空间身份与作者运行期身份（见 `.codeid`）结构性不同。
  （`app/src/main/kotlin/com/eratw/port/android/ModImportController.kt:1306-1318`；前缀常量单源
  `ModPackageInventory.PACKAGE_ID_PREFIX` = `app/src/main/kotlin/com/eratw/port/android/ModPackageInventory.kt:120`）
- **纯资源包载体（manifest-only，无 `.erbm`）**：`kojo-` + sha256(作者自述包名) 前 8 位十六进制，
  同构不同输入（`ModImportController.kt:1410-1428`）。
- 排序口径 `ModStore.compareNames` 与 `.sources` 名单、GC 判定三处同源
  （`ModPackageInventory.kt:253-258`）。

## 2. `.erbm` 容器（ERTM1 v1）字节布局

编解码唯一真源：`core-vm/src/main/kotlin/com/eratw/port/core/mod/ErbModuleContainer.kt`
（下称 EMC）。所有整数大端（java DataOutputStream）；读写均 fail-closed，坏档一律抛
`IllegalArgumentException` 整包拒，不返回半成品（EMC:36-42 KDoc）。

| 序 | 字段 | 类型 | 值 / 纪律 | source |
|---|---|---|---|---|
| 1 | magic | 5 字节 ASCII | `ERTM1` | EMC:47（写 :96 / 读 :136-139） |
| 2 | version | u16 | `1`，只认 1 | EMC:49（写 :97 / 读 :140-141） |
| 3 | flags | u16 | bit0 = `FLAG_ALLOW_SUPER_CALLS`（0x1）；**未知档位位一律拒** | EMC:52-54、:98、:142-145 |
| 4 | packageId | i32 长度 + UTF-8 | 非空；≤ 256 字节 | EMC:99、:67、:186-189 |
| 5 | program | i32 长度 + bytes | ≤ 256 MiB；内容 = `ErbcWriter.encode` 的既有 .erbc 字节（**不重造序列化**）；**必须零基线**（六基线全 0） | EMC:100-102、:63、:147-150；零基线判据 :214-216 |
| 6 | FuncMeta 条数 | i32 | ≤ 2 的 20 次幂；与 program 函数表**同长**、逐条 `index == i`（稠密 0 基） | EMC:103-104、:65、:152-163、:193-203 |
| 7 | FuncMeta 记录 × N | 见下 | 逐字段见 §2.1 | EMC:222-243 / :245-280 |
| 8 | 签名条数 | i32 | ≤ 2 的 20 次幂；键**大写归一**、非空、不重复；指纹非空 | EMC:105-109、:66、:165-175、:204-211 |
| 9 | 签名记录 × N | i32 长度 + UTF-8 键 + i32 长度 + UTF-8 指纹 | 键序 = encode 入参序（LinkedHashMap 往返，读侧归一化大写后查重） | EMC:106-109、:166-175 |
| 10 | 摘要 | 32 字节 | SHA-256(以上全部 body 字节)，尾部追加；**读侧先核摘要再解析**；记录区之后多余字节 = 拒 | EMC:113-114、:129-134、:176 |

容器级上界：整容器 ≤ 512 MiB、字符串 ≤ 1 MiB（EMC:62-64）；长度不足
（5+4+32=41 字节）即拒（EMC:123-125）。

### 2.1 FuncMeta 记录布局

逐字段（EMC `writeMeta` :222-243 / `readMeta` :245-280）：
`i32 index`（≥0）→ `str key`（非空）→ `str name` → `u16 位集` → `i32 argIntCount` →
`i32 argStrCount` → `i32 localSize` → `i32 localsSize` → `i32 floatLocalSize`（各 ≤ Int 上界）
→ `i32 defIndex` → `str position`。

位集 8 个布尔位（EMC:70-77，读侧只认 0x00..0xFF，含未知位即拒 :250-251）：
bit0 `isEvent`、bit1 `isMethod`、bit2 `isOnly`、bit3 `isPri`、bit4 `isLater`、bit5 `isSingle`、
bit6 `returnsStr`、bit7 `returnsFloat`。

### 2.2 装配闸对 `.erbm` 的额外要求（读入侧）

- `allowSuperCalls` 位**必须置位**：不是 Mod 包编译档（位为 0）= 整包拒——生产装配闸
  `ModChainAssembly` 只收 Mod 包形状（`app/src/main/kotlin/com/eratw/port/android/ModChainAssembly.kt:399-406`）。
- 签名指纹表语义：只有**覆盖集**（模块函数名 ∩ base 函数名，事件名除外）的键带补丁真实签名；
  其余补丁函数指纹为空串——非覆盖键不参与签名闸比较（`core-vm/src/main/kotlin/com/eratw/port/core/mod/PatchImportTranscoder.kt:67-70`）。
- 语义权威 TODO(verify)：`.erbm` 与 Mod 链装配是 RT2 自研（原版 Emuera 无 Mod 代码容器可比），
  无 C# 锚；锚定 = 蓝图 era-arch-v61u.md §16.1 / §10 / §12.1 与 `LinkableModule` 契约
  （EMC:41-42 KDoc 自认、`ModChainAssembly.kt:137-138` 同）。

## 3. 零基线转码形态（产物怎么来的）

导入链「进口即转码」：legacy 窗口补丁编译产物 → `PatchImportTranscoder.transcode` → 零基线
`ErbModule`（可直接 `ErbModuleContainer.encode` 落 `.erbm`）+ `ModuleDescriptor`（可直接
`ManifestCodec.encode` 落 manifest）。此后与二进制分发 Module 走**完全相同**的装配路，运行期
单管线；`kojo-imports.patch` 并集重编随窗口模型退役（`PatchImportTranscoder.kt:24-30`；
`ModImportController.kt:2059-2082`；UNION_PATCH_NAME 退役面 `ModImportController.kt:1381-1390`）。

链路 = `compileToModule`（② base 上下文 + ③ PatchCompiler 编译 + ④ 转码/编码，导入与重编
共用同一条实现：`ModImportController.kt:1326-1379`）→ `encodeArtifacts`（转码 → 版本/min-api
覆盖 → 容器与 manifest 编码：`:2094-2103`）。产物字节在落正式名前**必须解码自检**，编码器坏
字节不许进 `mods/rt2`（`:2130-2131`）。

零基化五变换（`PatchImportTranscoder.kt:40-63` KDoc，实现 :141-452）：

1. **池引用机械下移**：分类只问 `OpcodeRelocation`（与链接器平移面同一张表）；`EXPR_NULLABLE`
   的 NONE_REF 哨兵不平移（:375-392、:459-468）。
2. **`_CALL.a` 语义分野**：`a >= functionBase` → 模块内下标（`a - functionBase`）；
   `a < functionBase` → 导入哨兵 `-(extIdx+1)`，imports 表按名内注，名字查 base 函数目录，
   查不到 = 拒；事件名拒导入；super 哨兵出现即拒（:330-373）。
3. **合成事件入口截断**：入口恒在全部成员之后声明，截断 = 裁掉指令流尾部；「入口之后还有成员」
   = 上游编译序变化，拒；保留代码 CALL 被截断入口 = 拒（:242-286）。
4. **元数据 rebase**：FunctionInfo entryPc/labelPc、callArgs 键、FuncMeta index/defIndex 各按
   窗口基线下移；跳转是函数内相对偏移不动（:397-452、:470-490）。
5. **出口自检**：`checkPoolIndexBounds`（允许哨兵）+ `ErbcReader.requireJumpTargets`
   （:183-185）。

覆盖声明面：覆盖集现推「模块函数名 ∩ base 函数名（事件名除外）」；写进容器签名段的是**补丁
真实签名**（不是 base 签名，防 ABI 闸恒等通过）；显式收窄声明（pack.json `overrideFunctions`）
必须与实际推导集逐一相等，多列/漏列都拒（`PatchImportTranscoder.kt:132-136`、:517-571）。

## 4. manifest（`.manifest`，RT2MANIFEST 线格式）与配对关系

### 4.1 线格式要点（权威 = `core-vm/src/main/kotlin/com/eratw/port/core/patch/ManifestCodec.kt`）

- 首行版本 token：`RT2MANIFEST/3`（基础 18 段）→ `/4`（meta 段 5 值）→ `/5`（+`settings`）→
  `/6`（+`min-api`）→ `/7`（+`declaration-positions`）。**只有真声明了对应内容才升版**；空表
  按低版落盘，旧资产逐字节不变。`/1`、`/2` 解码首行即明示拒载（ManifestCodec.kt:96-124、
  :154-191、:1141-1148）。
- token 语法：每行一个 token；`S`=字符串（UTF-8 字节小写 hex）、`Z`=null、`I`=十进制整数、
  `F`=Double 原始位 16 位小写 hex、`B0`/`B1`=布尔、`E`=枚举名 hex（白名单精确匹配）、
  `@段名` 段开始、`/` 段结束（ManifestCodec.kt:20-34）。
- 18 基础段表（序写死，乱序/缺段/未知段/重复键全拒）：`meta, requires, load-after,
  external-calls, override-functions, global-dims, private-dims, csv-patches,
  csv-value-patches, entity-refs, component-schemas, entity-archetypes,
  relationship-schemas, hooks, interaction-hooks, phase-jobs, exported-macros,
  artifact-sha256`（ManifestCodec.kt:194-213）；`/5` 起末尾依次追加 `settings` / `min-api` /
  `declaration-positions`（:215-222）。
- 确定性：Set/Map 写侧按原文自然序排序、读侧校验严格升序无重复；同一 descriptor 两次 write
  逐字节相同，write→read→write 亦逐字节相同（:142-145、:460-475）。
- fail-closed 全清单：未知段 / 未知 token tag / 段乱序 / 段重复 / 缺段 / 截断 / 尾部多余字节 /
  非法枚举 / 非法判别 tag / 非法 hex / 非法 UTF-8 / CR 行尾 / 未以 LF 收口 / 键未排序 / 构造期
  不变量不符，全部抛 `ManifestFormatException`（带行号），不返回半成品（:14-18、:147-150）。
- 语义权威 TODO(verify)：manifest 是 RT2 自研文件形态，C# 原版无对应物；锚定 = 蓝图 §5 与
  §20.4 加 `DeclarationRegistry.kt` 的 DeclarationDomain 加 `ExtensionDeclarations.kt`
  构造期约束（ManifestCodec.kt:91-95 自认）。

### 4.2 导入端的两处赋值

manifest 的 `version` 与 `min-api` 由导入端赋值，不交给转码器（core 层保持零策略）：转码器
产出的 descriptor 版本恒 `"1"`、min-api 恒 0，导入端按包元数据（作者声明）覆盖后再编码；
`min-api` 只在非 0 时升 `/6` 并写段，0 时字节与老包逐字相同（`ModImportController.kt:2094-2103`；
重编回落口径 :1349-1363）。

### 4.3 配对 = 单向（RT2-ERBM 裁定①）

- **每 `.erbm` 必须找到同 packageId 的 manifest**，缺失整包拒（fail-closed，启动砖化的直接来源）；
- **反方向（manifest 无容器）是载体形态**（如 `eratw.family` 纯资源包），链接链跳过——其声明
  事务仍由 `ModRuntimeAssembly.attach` 全量加载，冲突检测不丢，只是不进五步链接链；
  （`ModChainAssembly.kt:111-116`、:297-306、:746-780）
- manifest 包名重复 = 身份冲突整包拒（`ModChainAssembly.kt:641-651`）；
- **引擎 API 版本闸**：manifest `min-api` > 当前 `ModApi.VERSION` → `LinkOrderException` 整链
  拒载（多包越闸按 manifest 文件名序同列）；已知边界：纯载体组合（无任何 `.erbm`）完全旁路时
  本闸不覆盖（`ModChainAssembly.kt:598-614`、:638-671）。

## 5. 链接输入的真实文件集（ModChainAssembly）

生产装配入口 `ModChainAssembly.assemble / assembleWithCatalog`
（`ModChainAssembly.kt:214-279`）读的文件集：

| 输入 | 位置 | 收集判据 | source |
|---|---|---|---|
| `.erbm` 容器 | `mods/rt2/` | 文件名以 `.erbm` 结尾、按文件名排序、**停用包摘除**（`<packageId>.off` 标记两侧同摘） | `ModChainAssembly.kt:142-143`、:813-823；停用标记 `ModEnablement.kt:21-22` |
| manifest | `mods/rt2/`（目录名单源 `RT2_MANIFEST_DIR_NAME = "rt2"`） | `.manifest` 结尾、按名排序、同一停用闸 | `ModRuntimeAssembly.kt:282/:285`；`ModChainAssembly.kt:858-865` |
| 四池容器补丁 | `mods/` 根目录 | `.patch` 扩展名且 `ModuleContainerFormat.probe` 命中（先探后读，只读前缀字节）；**免 manifest**；packageId 与 rt2 面/彼此撞名 = 拒 | `ModChainAssembly.kt:835-846`、:681-739 |
| base 签名目录 | `erbc.v1/program.meta` | 尾部**有界**读 8 MiB 的 `signatures` 段 → `BaseSignatureCatalog`；缺段/旧档 = 空表 fail-closed（覆盖声明不了、签名闸拒） | `ModChainAssembly.kt:174/:177`、:118-129、:194-211 |

装配链：开态先跑 `ModPackageInventory.gcOrphans`（半成品清在容器读取之前）→ 解码 `.erbm`
（坏档 / 非 Mod 编译档整包拒）→ 读 manifest（坏字节 / 重复包名 / min-api 越闸拒）→ 四池补丁
解码对账 → 单向配对成等数集 → `ModChainAssembler.assemble` 五步链（声明冲突 / 定序拒 / 符号
重名 / 其余 IAE 逐包 canonical 留痕后整包拒）（`ModChainAssembly.kt:262-361`）。

## 6. mods/rt2 盘面：包成员全集与写序

成员扩展名常量单源 = `ModPackageInventory`（`app/src/main/kotlin/com/eratw/port/android/ModPackageInventory.kt:117-172`）：

| 成员 | 扩展名/形状 | 作用 | 可选性 | source |
|---|---|---|---|---|
| provenance | `<pkg>.sources` | 逐行留档源名（排序 = packageIdFor 同序 + 末尾 LF）；只做来源记账，不是装载面文件 | 导入恒写（纯资源包恒空名单） | `ModPackageInventory.kt:128-129`、:253-258 |
| 内容指纹 | `<pkg>.sources.sha256` | 头 `RT2SOURCES/1` + 每行 `<64 位小写 hex>  <diskName>`（摘要 = SHA-256(diskName UTF-8 + NUL + 源字节)，按 compareNames 排序）；重编判「内容一致」，缺失退回 mtime | 可选件 | `ModPackageInventory.kt:131-136`；线格式 `ModSourceFingerprint.kt:16-34`、:44 |
| manifest | `<pkg>.manifest` | §4 的 RT2MANIFEST | 必需（与容器同 packageId 单向配对） | `ModPackageInventory.kt:125-126` |
| 容器 | `<pkg>.erbm` | §2 的 ERTM1；纯资源包不落 | 代码包必需（manifest-only 载体合法例外见 §7） | `ModPackageInventory.kt:122-123` |
| 覆盖侧车 | `<pkg>.assetmap` | 头三行固定（`ERATW-ASSETMAP/1` + 包名 + 条目数）+ tab 三列数据行（`SPRITE`/`BGM` → 官方资源名 → mod 资源名，稳定序）；坏档整份不采信 | 可选件（导入链无论有无 overrides 都写，无声明 = 空表） | `core-vm/src/main/kotlin/com/eratw/port/core/mod/ModDx3AssetMap.kt:13-25`、:44-47 |
| 代码身份 | `<pkg>.codeid` | 两行 `RT2CODEID/1` + 运行期包名（作者声明）；**只有带 `code.mod` 的包写**，无代码产物的包显式清掉 | 可选件 | `ModPackageInventory.kt:165-172`；线格式 `ModCodeIdentity.kt:44-48`、:68、:102 |

包成员还有**非文件成员**：目录 `<pkg>.assets/`（资源树）、`<pkg>.chara/`（随包角色模板目录）、
`<pkg>.media`（旁挂媒体清单，ADR-014 S5，不在成员面、由 `ModMediaReclaim` 独立判）；设置侧车
`<pkg>.settings` 由设置页独立写、**不在导入写序**；停用标记 `<pkg>.off`；`mods/rt2/code/` 是
dex 产物树，永不进包成员枚举（`ModPackageInventory.kt:28-38`、:56-66、:110-115、:174-184；
`ModSettingsStore.kt:60/:72`；`ModEnablement.kt:21-22`）。

### 6.1 导入链写序（⑤ 相位起；崩溃残留的 GC 判据依赖这个顺序）

1. `provenance` 先写（任一步崩溃时 GC 拿到的是「已声明源名单」，半成品判据不因缺 provenance 漏清）；
2. 指纹紧跟 provenance（两件都是源名单派生物，写在产物之前）；
3. `manifest` → `erbm`（容器最后写，缺容器即半成品）；
4. 四件套读回校验（`verifyWrittenPackage`）；
5. `⑤‴` 随包角色模板目录（先清旧目录再落）；
6. `⑤⁗` 覆盖侧车 `.assetmap`（附加面 best-effort：写失败只留 WARN、不回退导入成功）；
7. `⑤‴` 代码产物 + `.codeid`（fail-closed 整包拒导）；
8. `⑤″` 资源面 swap；
9. `⑤‴` 媒体资源面（清单驱动）；
10. `⑤′` 存量 legacy `.patch` 迁移（删除相位后移）。

落盘原子性：同目录 `.tmp-<nano>` → fsync → rename，失败清理临时文件，不留半截产物
（写序与逐条 source：`ModImportController.kt:631-729`；原子落盘 :1437-1448）。

### 6.2 孤儿 GC 与半成品判据（组长裁定 3，冻结）

- 判据**只看容器与 manifest**：`.erbm` / `.manifest` 任一缺失 = 半成品（导入两次写之间崩溃的
  残留），就地清掉——半成品会让单向配对整包 fail-closed ⇒ 启动砖化，必须在容器被读取之前清；
  **缺指纹侧车不算半成品**（老包迁移期必须留下）。
- **manifest-only 纯资源载体是合法形态**：manifest 在场 + `.erbm` 缺 + `.sources` 空名单 +
  `<pkg>.assets/` 在场 = 载体，不当半成品删；其余组合保守退回半成品判定。
- **「源失联」判据已整体撤销（2026-11-18 误删风险审计）**：`.sources` 只证明「作者留过哪些源」
  证明不了「玩家还要不要这个包」；能用的包绝不自动删，清理走显式卸载。
- 成员判定：只有七种文件成员后缀 + 两种目录成员后缀、且包名 `kojo-` 前缀合法才参与；内置
  `eratw.*` 与玩家手放件永不删。
  （判据全链：`ModPackageInventory.kt:99-115` KDoc、:446-474 判定、:476-488 载体例外、
  :502-520 后缀表；GC 执行面 :285-315）
- 载体链写序与四件套不同：纯资源包**不落 `.erbm`、不落 `.sources.sha256`**（载体无源可重编），
  只写空名单 provenance + manifest + 资源拷贝（`ModImportController.kt:867-877`、:945-946、
  :1004-1030）。

## 7. 与既有 specs 的边界（不越界、不重复）

- **specs/patch-format.yaml** = legacy 窗口补丁的 `patch.erbc` 文本头格式——是本文件 §3 转码的
  **输入侧**形态，本文件不覆盖它的字段表；两份规约无重叠字段（本波取舍：新建 `erbm-format.md`
  而非扩段 patch-format.yaml，因 `.erbm`/ERTM1 与 `patch.erbc` 是不同容器、不同生命周期——
  前者是落盘产物与装配输入，后者只在导入进口存在）。
- **specs/erbc-format.md** = `.erbc` 容器（`ERBC` magic）格式权威；`.erbm` 内嵌的 program 字节
  就是 `ErbcWriter.encode` 的既有 .erbc 字节（EMC:23-24），本文件不重复其字节表。
- **specs/mod-save.yaml** = `.rt2` 存档侧车容器 ABI（mod 存档面），与本文件（mod 代码产物面）
  互不覆盖。
- 四池容器补丁（magic `ERBM`）暂无独立 specs：本文件只记录它在装配面的收编事实（§5）；
  补格式规约属需组长接线的文档债（同 ADR-014 的另立先例）。

## 8. 未定项（如实登记，不假装已定）

- **T1（TODO(verify)）**：`.erbm` 与 Mod 链装配语义是 RT2 自研，无 C# 锚；语义权威 = 蓝图
  era-arch-v61u.md §16.1 / §10 / §12.1 与 `LinkableModule` / `../docs/archive/CONTRACT-RT2-P15R.md`
  （EMC:41-42、`ModChainAssembly.kt:137-138` 自认）。卡点 = 自研面无上游可比对源。
- **T2（TODO(verify)）**：manifest 语义权威 = 蓝图 §5/§20.4 加两个 core 类的构造期约束
  （ManifestCodec.kt:91-95）。卡点同 T1。
- **T3**：四池容器补丁（ModuleContainerCodec）字节格式未入 specs；触发条件 = 需要第三方手工
  构造该形态时先立规约波（当前生产只有 M2-4 转码产线消费）。
- **T4**：`mods/rt2/eratw.demo.erbm` 随包样件（app/src/main/assets/mods/rt2/）的版本与段面未
  逐字节核对进本文件；卡点 = 样件由产线重生成即漂移，冻结样件字节属出包链职责。
