# ERB 存档格式规约（save-format.md）

> 依据 emuera-cs 源码提取（Emuera.dll 1.824.0.0，.NET 8）。所有结论均附 `文件:行号` 证据，源码根为 `reference/emuera-cs/`。

## 1. 存档文件结构

### 1.1 两种格式并存：文本格式（默认）与二进制格式（可选）

- 是否二进制由配置项 `SystemSaveInBinary` 决定，默认 `false`（文本格式）：
  - `MinorShift.Emuera.Runtime.Config/ConfigData.cs:107`（默认 `t: false`）
  - `MinorShift.Emuera.Runtime.Config/Config.cs:371`
- 二进制格式下可选 GZip 压缩（`ZipSaveData`，默认 `false`）：
  - `ConfigData.cs:128`、`Config.cs:388`
  - 压缩仅当 `SystemSaveInBinary && ZipSaveData` 同时为真才生效：`EraBinaryDataWriter.cs:22-32`
- **eratw 实际采用哪种取决于用户配置；文本格式是保守默认，二进制格式是必须兼容的主路径。**

### 1.2 二进制格式头部（魔数 + 版本字段）

`EraBinaryDataWriter.WriteHeader()`（EraBinaryDataWriter.cs:35-51）写入顺序：

| 偏移 | 长度 | 内容 | 证据 |
|---|---|---|---|
| 0 | 8 字节 (UInt64 LE) | 魔数：未压缩 `727905341820519817`（0x0A18_4206_5F4A_8B49? 十进制原样）；GZip 压缩 `743174590507926921` | EraBinaryDataWriter.cs:37-44 |
| 8 | 4 字节 (UInt32) | 版本号 `1808` | EraBinaryDataWriter.cs:45 |
| 12 | 4 字节 (UInt32) | 校验表数量，**恒写 0** | EraBinaryDataWriter.cs:46 |
| 16 | 0 条 | 校验表本体（历史遗留，循环上限为 0） | EraBinaryDataWriter.cs:47-50 |

- 读取侧对称校验两个魔数：`EraBinaryDataReader.CreateReader()`（EraBinaryDataReader.cs:786-830）；第 2 个魔数命中时将后续字节经 `GZipStream` 解压后再读（EraBinaryDataReader.cs:807-815）。
- 版本号只认 `1808`，其他值返回 null（按文本格式回退处理）：EraBinaryDataReader.cs:820-824。
- **无加密；无有效校验和**（校验表恒空）。文件末尾以 `EOF=0xFF` 字节分界：EraBinaryDataWriter.cs:78-81、VariableEvaluator.cs:2604-2606。

### 1.3 二进制数据编码（正文字节序/编码）

- 容器：`BinaryWriter`/`BinaryReader` 均以 **Encoding.Unicode（UTF-16LE）** 创建字符串编码：
  - EraBinaryDataWriter.cs:25/30、EraBinaryDataReader.cs:794/814/818
- 所有数值 little-endian（.NET BinaryWriter 默认）。
- 变长整数压缩编码 `m_WriteInt`（EraBinaryDataWriter.cs:175-196）：
  - `0..207` → 单字节原值
  - `-32768..32767` → `0xD0` + Int16
  - int 范围 → `0xD1` + Int32
  - 其余 → `0xD2` + Int64
- 标记字节（EraBinaryDataWriter.cs:68-81）：
  - `0xFD` Separator、`0xFE` EOC（角色数据块结束）、`0xFF` EOF
- 类型字节（WriteWithKey，EraBinaryDataWriter.cs:83-173；枚举定义 EraSaveDataType.cs:3-19）：
  - `0` Int、`1..3` Int1D/2D/3D、`16` Str、`17..19` Str1D/2D/3D、`32` Map、`33` Xml、`34` DataTable
- 数组采用 0 游程压缩（RLE）：`0xF0`=连续 n 个 0（1D，EraBinaryDataWriter.cs:203-223）；2D/3D 另有 `0xE0`（整行/面零）、`0xF1`、`0xF2`（EraBinaryDataWriter.cs:225-332）；字符串数组以 `0xD8`+字符串表示非空项、空/null 计入 0 游程（EraBinaryDataWriter.cs:346-478）。读取侧严格对称：EraBinaryDataReader.cs:102-764。
- 字符串数组中 **null 与 "" 在写入时等同**（都按空项跳过，读回后统一为 null/空，needInit 时 Int 填 0、Str 填 null）：EraBinaryDataWriter.cs:352、EraBinaryDataReader.cs:481-497。

### 1.4 文本格式（SystemSaveInBinary=false）

- 逐行文本，`StreamWriter` 编码为 `Config.SaveEncode`，默认 **UTF-8 with BOM**：EraDataWriter.cs:21-25、Config.cs:19。
- 读侧用 `EncodingHandler.DetectEncoding(file)` 自动探测：EraDataReader.cs:36。
- 数值即十进制行；数组以最后非零/非空元素截断后逐行输出，以 `__FINISHED` 行收尾：EraDataWriter.cs:52-109。
- 扩展段标记：`__EMUERA_1808_STRAT__`（注意源码拼写 STRAT）开启 1808 扩展区，`__EMU_SEPARATOR__` 分段，`key:value` / `key` + 行列表格式：EraDataWriter.cs:11-15、111-127、129-219；读取版本探测 `__EMUERA_STRAT__`(1700)/1708/1729/1803/1808：EraDataReader.cs:14-26、133-175。

## 2. 哪些数据进存档

### 2.1 文件清单

| 文件 | 路径 | 证据 |
|---|---|---|
| 普通存档 | `save{index:00}.sav` | VariableEvaluator.cs:2023-2032 |
| 全局存档 | `global.sav` | VariableEvaluator.cs:2018-2021 |
| 变量存档 | `var_{index:00}.dat` | VariableEvaluator.cs:2039-2042 |
| 角色存档 | `chara_{index:00}.dat` | VariableEvaluator.cs:2044-2047 |

### 2.2 普通存档（saveXX.sav）整体布局

- 写入链：`Process.saveGameWaitInputOverwrite` → `VariableEvaluator.SaveTo` → `SaveToStream`/`SaveToStreamBinary`（Process.cs:2857-2889、VariableEvaluator.cs:2644-2685）。
- `SAVEDATA_TEXT` 在保存前由 `DateTime.Now.ToString("yyyy/MM/dd HH:mm:ss") + " "` 填入并允许 `@SAVEINFO` 修改：Process.cs:2872。
- 二进制布局（VariableEvaluator.SaveToStreamBinary，VariableEvaluator.cs:2591-2607）：
  1. Header（魔数+1808+0）
  2. FileType=0（Normal，EraSaveFileType.cs:3-9）
  3. Int64 `gamebase.ScriptUniqueCode`
  4. Int64 `gamebase.ScriptVersion`
  5. String `saveDataText`
  6. Int64 角色数；逐个角色 `CharacterData.SaveToStreamBinary`（CharacterData.cs:513-558，以 EOC=0xFE 结尾）
  7. `VariableData.SaveToStreamBinary`：**遍历 varTokenDic，凡 `IsSavedata && !IsCharacterData && !IsGlobal` 的变量按字典序逐个 WriteWithKey**（VariableData.cs:3980-3997），后接用户自定义静态变量
  8. EOF(0xFF)；EM 段（SAVECHARA/SAVEGLOBAL 之外的 Map/Xml/DT 扩展，VariableData.cs:3936-3959）；EOF(0xFF)
- 文本布局（VariableEvaluator.SaveToStream，VariableEvaluator.cs:2409-2426）：
  1. UniqueCode 行、ScriptVersion 行、saveDataText 行
  2. 角色数行；每角色：2 个 String + 2 个 Int + 17 个 Int1D 数组（CharacterData.cs:351-369）
  3. VariableData：60 个 Int1D + 1 个 String1D（VariableData.cs:3516-3534）
  4. `__EMUERA_1808_STRAT__` 后接扩展区（ExtSaveList 变量按 VariableCode 枚举顺序 + 用户自定义变量，8 段各以 `__EMU_SEPARATOR__` 分隔）：VariableData.cs:3556-3626、CharacterData.cs:391-423
- **RANDDATA（625 元素全局数组）属于 global.sav，不进 saveXX.sav**（`IsGlobal` 变量被 SaveToStreamBinary 排除：VariableData.cs:3985；RANDDATA 定义 VariableEvaluator.cs:117、VariableCode.cs:89）。
- SAVESTR 即 `DataIntegerArray` 同级的 String1D 存档变量，文本格式随 VariableData 主体第 1 个 String1D 段写入（VariableData.cs:3530-3533，SAVESTR 注册 VariableData.cs:3068）。

### 2.3 全局存档（global.sav）

- 写入链：`SAVEGLOBAL` 指令 → `VariableEvaluator.SaveGlobal`（FunctionIdentifier.cs:1801-1812、VariableEvaluator.cs:2474-2511）。
- 二进制：Header → FileType=1(Global) → UniqueCode → ScriptVersion → 空串 → `SaveGlobalToStreamBinary`（**凡 `IsSavedata && !IsCharacterData && IsGlobal` 的变量**，VariableData.cs:3961-3978）→ EOF → GlobalEM 段 → EOF（VariableEvaluator.cs:2483-2493）。
- 文本：UniqueCode、ScriptVersion 行后写 `dataIntegerArray[63]`（GLOBAL）与 `dataStringArray[5]`（GLOBALS）两个数组（VariableData.cs:3804-3814），1808 扩展区再写用户自定义全局变量（VariableData.cs:3816-3846）。

## 3. 版本迁移（LASTLOAD_VERSION 与兼容检查）

- **不存在自动触发 VERSION_UP.ERB 的机制**：全仓 grep `VERSION_UP` 无任何引擎侧代码命中（仅 era 脚本层可自行实现）。加载失败直接抛 FileEE。
- 存档中保存的 ScriptVersion 在加载时写回 `varData.LastLoadVersion`，供 ERB 通过伪变量 `LASTLOAD_VERSION` 读取：
  - 赋值点：VariableEvaluator.cs:2442（文本）、2627（二进制）
  - 伪变量：VariableData.cs:904-913、3158；`LastLoadVersion` 默认 -1（VariableData.cs:2898）
  - 同族：`LASTLOAD_NO`（VariableEvaluator.cs:2706）、`LASTLOAD_TEXT`
- 校验逻辑 `GameBase.CheckVersion`（GameBase.cs:69-80）：
  - 未定义 ScriptVersion 且存档版本 !=1000 → 通过（旧档宽容）
  - `ScriptCompatibleMinVersion <= 存档版本` → 通过
  - 否则要求版本完全相等，失败抛 `DifferentVersion`（VariableEvaluator.cs:2435-2438）
- UniqueCode 校验：存档为 0 时豁免，否则必须相等（GameBase.cs:60-67）。
- 扩展区版本迁移：文本格式按 `__EMUERA_*_STRAT__` 标记判定 emu_version，`<1803` 走 `LoadFromStreamExtended_Old1802` 兼容路径（VariableEvaluator.cs:2457-2471、CharacterData.cs:477）。

## 4. 读写两侧类与方法清单

### 写侧

| 类/方法 | 位置 |
|---|---|
| `EraBinaryDataWriter..ctor` | EraBinaryDataWriter.cs:20 |
| `EraBinaryDataWriter.WriteHeader` | EraBinaryDataWriter.cs:35 |
| `EraBinaryDataWriter.WriteFileType/WriteInt64/WriteString` | EraBinaryDataWriter.cs:53/58/63 |
| `EraBinaryDataWriter.WriteWithKey` | EraBinaryDataWriter.cs:83 |
| `EraBinaryDataWriter.m_WriteInt / writeData(...)` | EraBinaryDataWriter.cs:175/198-478 |
| `EraDataWriter（文本）` | EraDataWriter.cs:9 |
| `VariableEvaluator.SaveTo / SaveToStream / SaveToStreamBinary` | VariableEvaluator.cs:2644/2409/2591 |
| `VariableEvaluator.SaveGlobal / SaveGlobalToStreamBinary` | VariableEvaluator.cs:2474、VariableData.cs:3961 |
| `VariableData.SaveToStream / SaveToStreamExtended / SaveToStreamBinary` | VariableData.cs:3516/3556/3980 |
| `CharacterData.SaveToStream / SaveToStreamExtended / SaveToStreamBinary` | CharacterData.cs:351/391/513 |

### 读侧

| 类/方法 | 位置 |
|---|---|
| `EraBinaryDataReader.CreateReader`（魔数/版本/解压入口） | EraBinaryDataReader.cs:786 |
| `EraBinaryDataReader1808`（唯一实现，ReaderVersion=1808） | EraBinaryDataReader.cs:14-16 |
| `ReadFileType/ReadInt/ReadString/ReadVariableCode/ReadIntArray*` | EraBinaryDataReader.cs:23/92/97/82/102-459 |
| `ReadStrArray*` | EraBinaryDataReader.cs:461-764 |
| `EraDataReader（文本，含编码探测与版本探测）` | EraDataReader.cs:32、36、133 |
| `VariableEvaluator.LoadFrom / LoadFromStream / LoadFromStreamBinary` | VariableEvaluator.cs:2687/2428/2609 |
| `VariableEvaluator.LoadGlobal / LoadGlobalFromStreamBinary` | VariableEvaluator.cs:2513 |
| `VariableData.LoadFromStream / LoadFromStreamBinary / LoadVariableBinary` | VariableData.cs:3536/3999/4081 |
| `CharacterData.LoadFromStream / LoadFromStreamBinary` | CharacterData.cs:371/560 |
| `SAVEGAME/LOADGAME 指令入口` | FunctionIdentifier.cs:2956-2981、3951-3952 |
| `SAVEGLOBAL 指令` | FunctionIdentifier.cs:1801 |

## 5. 兼容性结论：Kotlin 逐字节兼容最脆弱的 3 个点

1. **字符串编码（UTF-16LE + BinaryWriter 7-bit 变长长度前缀）**。二进制格式所有字符串是 UTF-16LE 字节序列，且长度前缀为 .NET `BinaryWriter.Write(string)` 的 **LEB128（7-bit 变长，每字节低 7 位，最高位为续位标志）** 编码——Kotlin 侧必须手写该前缀，DataOutputStream 的 UTF 格式不兼容。文本格式则需复刻 UTF-8 BOM 写入与 `EncodingHandler.DetectEncoding` 的 BOM 探测（EraDataReader.cs:36）。
2. **二进制头 + GZip 分支的精确字节**。两个 8 字节魔数、UInt32 版本 1808、恒 0 的校验表数量（EraBinaryDataWriter.cs:37-50）；`ZipSaveData=true` 时 GZip 必须用与 .NET `GZipStream` 相同的头部/尾部（header 的 MTIME 等字段 .NET 写 0），否则 Windows 端 .NET 读不了 Android 端产出。**MTIME 字段一致性未在源码中找到显式保证，需运行验证。**
3. **变量写入顺序的确定性**。二进制格式按 `varTokenDic`（`Dictionary<string, VariableToken>`）枚举顺序写出（VariableData.cs:3963/3982/3989）。.NET Dictionary 枚举顺序在无删除时为**插入序**，而 Kotlin `LinkedHashMap`/`HashMap` 遍历序不同——任何顺序偏差都会产出不同字节但语义相同的存档。逐字节兼容必须用**注册序（即 varTokenDic.Add 的调用顺序，VariableData.cs:3068-3160）**复刻，用户自定义变量亦然。文本格式的角色数组计数（2 String/2 Int/17 Int1D，CharacterData.cs:353-368）与 60 Int1D + 1 Str1D（VariableData.cs:3526-3533）为硬编码，须原样保留。

> 次要风险：`DateTime.Now.ToString("yyyy/MM/dd HH:mm:ss")` 受 locale 影响需固定 InvariantCulture（Process.cs:2872）；`Environment.TickCount` 仅影响 RNG 种子，不影响存档字节。

### 5.3 读侧未知键与「类型/维度不符」：读完即丢（引用锚点）

> **勘误（2026-11-02，T10 路）**：计划与代码注释里引用的「`specs/save-format.md` §5.3 未知键丢弃」
> 指本节。此前 §5 只有上面三条结论、并无 5.3 小节，属引用漂移；本节按源码证据补写，不改动上面三条。

- **原版口径**：`VariableData.LoadVariableBinary`（`reference/emuera-cs/MinorShift.Emuera.GameData.Variable/VariableData.cs:4081`）：
  先从键名查变量 token（`:4084` `variableToken = null`；`:4085-4088` 查表，`:4089-4092` 命中
  `IsCharacterData|IsConst|IsPrivate|IsLocal|IsCalc` 之一则一律置 null）；随后每个类型分支都先判
  `variableToken == null || !IsInteger/IsString || Dimension != n`，**不匹配就走「读掉值再丢弃」**
  （Int 分支 `:4127` 起 `reader.ReadInt()`；数组走 `ReadIntArray(null, needInit:true)` 一族）；
  非法类型码落 `:4207-4208` `throw new FileEE(Lang.Error.AbnormalData.Text)`。
  ⇒ **键不认识 / 类型不符 / 维度不符都**不报错、也不写变量池；只有类型字节非法才抛。
- **移植侧对应**：`SaveData.applyEntry`（`core-vm/.../data/save/SaveData.kt:268-289`）对未知键与
  类型/维度不符一律返回 false（跳过）；[SaveFormat] 的 `keepKey` 谓词只决定**是否建数组**，
  键名本身照读（`core-vm/.../data/save/SaveFormat.kt:582-592`；跳过路径 `skipValue` 仍校验标签，
  坏档不因「键不认识」被放过，见 `SaveUnknownKeySkipTest`）。
- **迁移应用**：4.981 相对 4.980 删除的 3 个 SAVEDATA 声明（`MP_GRADE`/`P_AdjList`/`P_AdjListDone`，
  verify-notes §24.2）在旧档读入时按本节丢弃；4.981 新增的自然变量在旧游戏下读入同样丢弃。
- **写侧不写回（A 案现状，2026-11-14 裁定）**：未知键读掉后不进变量池，下一次存档只遍历声明表
  （写侧 `VariableData.cs:3980-3997`，移植侧 `SaveCapture.emitVariables` 同口径）
  ⇒ **这些键下次存档后会从存档中移除**。原版与移植侧在这条读写不对称上同构，
  不保留、不半保留、不写回；现状锁在 `SaveUnclaimedNoWriteBackTest`（core）。
  若要改成透传回写（B 案），属 ADR-005「官方 bug 原样复刻」的例外，须先登记裁定、单独立波。

## 6. 实测勘误（a2-savechara，2026-10-19 实测）

> 本节为**实机对拍**（6 个 PC 原版样本，`<internal-workdir>/<sample-backup>/sav-20261018-110513/`）得到的修正，
> 与上文源码推断冲突处以本节为准（合同：真相优先，附证据）。固化于 `core-vm/.../SaveFormatTest`。

### 6.1 容器头逐字节实测

| 偏移 | 长度 | 实测值 | 结论 |
|---|---|---|---|
| 0 | 8 (UInt64 LE) | `743174590507926921` | 6 个样本**全部命中 GZip 分支魔数**（即 eratw 实机为 `SystemSaveInBinary=1 && ZipSaveData=1`） |
| 8 | 4 (UInt32) | `1808` | 与 §1.2 一致 |
| 12 | 4 (UInt32) | `0` | 校验表恒空，与 §1.2 一致 |
| 16 | 10 | `1f 8b 08 00 00 00 00 00 00 0a` | .NET 8 `GZipStream` 固定的 OS 字节 `0x0A`（Windows 侧亦为 0x0A，非通常的 0x00/0x03） |

- GZip 成员之后的固定尾含 **CRC32 + ISIZE**（.NET `GZipStream` 与小端 `Deflater` 尾部布局一致），故整文件末 4 字节是 ISIZE 高位，不是 `EOF=0xFF`；判 `EOF` 要去 GZip 外壳看 payload 末字节。

### 6.2 六个真实样本头字段实测表

| 样本 | FileType | UniqueCode | ScriptVersion | 角色数 | 备注 |
|---|---|---|---|---|---|
| `save00.sav` | 0 Normal | 7153 | 0 | 168 | 描述 `2026/05/18 14:22:54 第  1日(春之月1日) …CH20260326 `（尾含空格） |
| `save01.sav` | 0 Normal | 7153 | 0 | 168 | 同上结构 |
| `save02.sav` | 0 Normal | 7153 | 0 | 168 | 同上结构 |
| `save03.sav` | 0 Normal | 7153 | 0 | 168 | 同上结构 |
| `save1000.sav` | 0 Normal | 7153 | 0 | 168 | 描述尾 `*自动存档*` |
| `global.sav` | 1 Global | 7153 | 0 | 0 | 无角色块；描述为空串 |

- 该批样本 `ScriptVersion=0`（即游戏侧未定义 `ScriptVersion`），`UniqueCode=7153` 全批一致；
  据此 `CheckVersion` 只能走「未定义 + 存档版本 != 1000 → 通过」这一支（GameBase.cs:69-80）。

### 6.3 往返幂等实测：整文件 6/6 + payload 6/6

- `write(read(f)) == f`（含 GZip 外壳）**6/6 逐字节相等**；`encodePayload` 与实机 payload 亦 **6/6 相等**。
- 但**不可据此认为 Kotlin 压缩器与 .NET 逐字节同构**：实测同一 payload 用 `java.util.zip.Deflater`
  level 6 输出 508 字节，.NET `DeflateStream` 为 510 字节。故实现侧 `SaveFormat.write` 对**未改动**的存档
  直接回写原字节（`SaveData.sourceFile`），仅对改动过的存档重新压缩（.NET 侧可读但字节不同）。这是 6/6 幂等的真实机制，
  §5.2 关于「GZip 需与 .NET 完全一致否则不可读」的**担忧不成立**：.NET `GZipStream` 只要能解压即可，不要求压缩字节相同。

### 6.4 勘误：RANDDATA 出现在 `saveXX.sav`（修正 §2.2 第 85 行）

- §2.2 称「RANDDATA（625 元素全局数组）属于 global.sav，不进 saveXX.sav」。**实测为假**：
  `save00.sav` 的全局块里 `RANDDATA` 确实出现（payload 偏移 ~564xxx，625 元素）。
- 归因：`RANDDATA` 定义处 `IsGlobal` 标记与「是否写入普通存档」的筛选口径不一致——
  `SaveToStreamBinary` 排除的是 `IsGlobal`，但 RANDDATA 的 `IsGlobal` 实为假（详见 TODO(verify)）。
  **结论：普通存档的全局块遍历筛选必须覆盖 RANDDATA，否则 saveXX.sav 幂等必破。**

### 6.5 勘误：角色存档（CHARVAR）尾部多一个 EOF 字节（补 §2.2 / §1.2）

- 角色文件 `chara_<name>.dat` 的 payload 布局，在 §1.3/§2.2 之外**末尾还有一个 `0xFF`**：
  `… 角色数 | 每角色条目…EOC | EOF(0xFF)`。
- 证据：`VariableEvaluator.SaveChara` 在写完角色块后调用 `WriteEOF()`（VariableEvaluator.cs:2270）；
  而 `LoadChara` 只读 `num` 个角色、**不消费**这个字节（VariableEvaluator.cs:2308-2314），故读侧宽容、
  写侧必须补，否则 `write(read(f)) == f` 在末字节差 1 字节。已在 `SaveFormat.encodePayload` 对 CHARVAR 补齐。

### 6.6 勘误（实现侧拦路石）：SAVECHARA 语句参数序被颠倒

- 原版参数序为 **`termList[0]=文件名(String), termList[1]=描述(String), termList[2..]=角色号(long…)`**：
  `SAVECHARA_Instruction.DoInstruction`（FunctionIdentifier.cs:1874-1895，`strValue=term[0]`、`strValue2=term[1]`、
  `array[i]=term[i+2]`）+ `SP_SAVECHARA_ArgumentBuilder`（ArgumentParser.cs:2231-2242，类型 `String,String,Long`）。
- 当前 Kotlin 实现把顺序反了（`sem/StatementExecutor.kt:741-748`：`nos=terms.dropLast(2)`、`filename=terms[size-2]`、
  `text=terms[size-1]`）。后果：`SAVECHARA "GOLDEN_CHARA.dat","golden chara",0` 被解析成
  `saveChara(nos=[0(文件名的 getIntValue→0)], filename="golden chara"(描述), text="0")`，
  实际落盘文件名 = `chara_golden chara.dat`（E2E 临时 dump 实证）。
- 影响的判据：GOLDEN_IO 的 IO-CHARAFILE 节（`FIND_CHARADATA=0` / `CHKCHARADATA=1` / `AFTER-LOAD` 无角色）。
  该文件非本 agent 所有权，按合同列为「需组长接线」。修法见交付报告。

## 7. L15 例外登记（ADR-005）：顶层未认领键的原始切片回写（2026-11-14）

> 本节是 §5.3「写侧不写回（A 案现状）」的**受控例外**，不修改 §5.3 的读面语义；
> 裁定与判据 = `CONTRACT-RT2-UNCLAIMED-B1`（组长已登记 ADR-005 例外）。

- **范围**：只做顶层 `normal` / `global` 域，只回写 `def == null` 的条目；`char` 域（角色块内）
  与 EM 段**不在本波**，仍按 §5.3 原样丢弃。
- **读侧**：`SaveFormat.read(bytes, keepKey)` 对 `keepKey` 判 false 的顶层条目，把
  「类型字节 + 键名 + 值」的**原始编码切片**按原序收进 `SaveData.unclaimedVariables`；
  不解析、不重编码，所以 §5.3 的「不建数组」根修（`SaveUnknownKeySkipTest`，306.8 MiB 声明量）
  不回退。
- **写侧**：`SaveCapture.emitNormal` / `emitGlobal` 与 `SaveFormat.writePayload` 在已知变量之后、
  `EOF` 之前原样直写切片（`SaveWriter.raw`）；已知条目的字节/顺序不动。无切片 ⇒ 输出与改动前逐字节相同。
- **跨档生命周期**：切片只随「读档 → 存档」存活（`store.aux` 槽 `save.unclaimed`）；
  normal 域以 `lastLoadNo`（`VariableEvaluator.cs:2706`；`ResetData` 经 `SetDefaultValue`
  `VariableData.cs:3511-3512` 复位）作失效闸，回标题/新局不会把旧档未知键带进新档。
- **写回闸**：装载后又被声明（mod / ERH 补装）的键会被跳过，避免同名条目出现两次。
- **失败面**：捕获/回写步骤异常 ⇒ 按原行为丢弃并留痕（`SaveDiagnostics.note`），不写半截档；
  `OutOfMemoryError` 仍走既有求援重试链。文本格式（非 eratw 口径）不回写。
- **原版对照**：读侧丢弃 `VariableData.cs:4081-4160`；写侧只遍历声明表
  `VariableData.cs:3980-3997`（无回写设施）。本例外是移植侧增强，按 ADR-005 登记。

## 8. L17 例外登记（ADR-005）：角色块未认领键的原始切片回写（2026-11-14）

> 本节是 §5.3「写侧不写回（A 案现状）」的第二笔受控例外（第一笔见 §7），
> 裁定与判据 = `CONTRACT-RT2-UNCLAIMED-B2`；不修改 §5.3 的读面语义与 §7 的顶层口径。

- **范围**：普通存档（`FileType=NORMAL`）的**角色块内**未认领条目；`chara_*.dat`（CHARVAR）
  与 EM 段**不在本波**（CHARVAR 是「追加角色」语义，按 `NO` 回写会串进别人的角色档）。
- **身份绑定**：分桶键 = **该块内 `NO` 条目的数值**（`SystemLayout.charaInt0D` 第 2 项，
  原版角色块首项整数条目），**不按块位置（pos）** —— 角色顺序/数量变化时位置绑定会把切片
  挂到别人身上。块内 `NO` 缺失 / 重复 / 非整数标量，或两个以上块解出同一个 `NO`
  （桶键撞车）⇒ **整块切片丢弃** + `SaveDiagnostics.note` 留痕（fail-closed）。
- **读侧**：`SaveFormat.read(bytes, keepKey)` 在角色块里截「类型字节 + 键名 + 值」的原始编码，
  按 `NO` 分桶收进 `SaveData.unclaimedCharacterVariables`；`droppedCharacterVariables`
  的键名清单语义不变（报告只作内部读数，2026-11-15 起无玩家面上屏）。
- **写侧**：`SaveCapture.emitNormal` 与 `SaveFormat.writePayload` 在**同一 `NO` 的角色块**
  已知条目之后、`EOC` 之前原样直写（`SaveWriter.raw`）；当前 store 无该 `NO`
  （角色被删/被替换）⇒ 该桶丢弃 + 留痕；不新建角色块、不改已知条目字节/顺序。
- **跨档生命周期**：角色桶与 §7 的顶层 normal 桶共用 `store.aux` 槽（`save.unclaimed`）与
  `lastLoadNo` 失效闸；`ResetData` 之后旧档的角色块切片不进入新局。
- **失败面**：捕获/回写任一步异常 ⇒ 按原行为丢弃 + 留痕，不写半截档；
  `OutOfMemoryError` 原样上抛给 `FileSystemOps.saveData` 的求援重试链。文本格式（非 eratw 口径）不回写。
- **原版对照**：读侧 `VariableData.cs:4081-4160`；写侧 `CharacterData.cs:513-558`
  （只遍历声明表，无回写设施）。本例外是移植侧增强，按 ADR-005 登记。

### 8.1 相位口径（L23，`CONTRACT-RT2-UNCLAIMED-B2-PHASE`）

> 角色块有**两段相位**：系统项（`Separator` 之前）与用户 `#DIM CHARADATA` 项（`Separator` 之后）。
> §8 的「落 `EOC` 之前」只说了插入点，未说相位；L23 补上相位，不改 §8 的身份绑定与 fail-closed 规则。

- **原版锚**：写侧 `CharacterData.SaveToStreamBinary` 先写系统项（`CharacterData.cs:601-663`），
  只有 `UserDefCVarDataList.Count != 0` 才 `writer.WriteSeparator()`（`:666-668`）再写用户项，
  最后 `writer.WriteEOC()`（`:686`）；读侧 `LoadFromStreamBinary` 用 `bool userDefineData`
  （`:692`）记相位，`case EraSaveDataType.Separator:` 置位（`:718-720`）之后只认
  `UserDefinedCharaVariableToken`（`:702-707`）。
- **读侧**：`SaveFormat.readCharacterBlock` 以块内 `Separator` 为界，给每条未认领切片记
  `SYSTEM` / `USER`（`UnclaimedCharaEntry.phase`）；相位错 = 同名变量将来被重新声明时落到
  错误的查找分支而被丢（文件里有键、运行期读不到）。
- **写侧**：`SaveCapture.emitNormal`（经 `CharaFileIO.emitCharacter` 的相位参数）与
  `SaveFormat.writePayload` 把系统相位切片放在 `Separator` **之前**、用户相位切片放在
  **之后**；当前块没有 `Separator` 但存在用户相位切片 ⇒ **补一个 `Separator`** 再写
  （否则读侧按系统相位认它们）。不新建角色块、不改已知条目字节/顺序。
- **零变化**：无未认领键的档逐字节不变；canonical 两相位夹具 `write(read(f)) == f`
  逐字节不变（切片落回原相位）。物化入口（`SaveCapture.normal` / `global`）与流式入口逐字节一致。
- **失败面**：相位判定/回写异常仍按 §8 口径丢弃切片 + `SaveDiagnostics.note`，不写坏档；
  `OutOfMemoryError` 原样上抛。`chara_*.dat`（CHARVAR）仍不捕获。

## 9. L28 例外登记（ADR-005）：EM 段未认领容器名的原始切片回写（2026-11-14）

> 本节是 §5.3「写侧不写回」的第三笔受控例外（前两笔见 §7 顶层变量 / §8 角色块），
> 裁定与判据 = `CONTRACT-RT2-UNCLAIMED-B3-EM`（上游 L19 RECON §1/§8 的 P0 裁定）；
> 不修改 §5.3 的读面语义，也不动 §7/§8 的既有口径。

- **范围**：`FileType=NORMAL` / `GLOBAL` 的 **EM 段**（`Map` / `Xml` / `DataTable` 三种条目）；
  `chara_*.dat`（CHARVAR）与文本格式路**不在本波**。MAP/XML/DT 的函数语义（`MAP_*` / `XML_*` /
  `DT_*`）零改动，`EmDataPools` 的池操作零改动。
- **读侧**：`SaveFormat.read(bytes, keepKey)` **不变**（不筛 EM 段，未知容器读完即丢，§5.3 原口径）。
  新增三参重载 `SaveFormat.read(bytes, keepKey, emKeepKey)`：`emKeepKey` 判 false 的容器名
  **只走标签、不建值对象**，把「类型字节 + 容器名 + 值」的原始编码切片按原序收进
  `SaveData.unclaimedEmData`；不解析、不重编码（未知 DT/Xml 的坏 XML 内容照样往返）。
  被拒名字**不进** `droppedVariables`（EM 名不是变量，报告只收变量；报告自 2026-11-15 起
  无玩家面上屏，见 §10 末段）。
  为什么是三参重载而不是给旧函数加缺省参数：Kotlin 尾随 lambda 永远绑定最后一个形参，
  加参会把全仓 `read(bytes) { 变量谓词 }` 的谓词悄悄变成 EM 谓词（实测 6 例立刻红）。
- **写侧**：`SaveCapture.emitNormal` / `emitGlobal` 与 `SaveFormat.writePayload` 在
  **已知 EM 条目之后、EM 段的 `EOF` 之前**逐条原样直写（`SaveWriter.raw`）；已知条目的
  字节/顺序不动。无未知 EM 条目 ⇒ 输出与改动前逐字节相同（`sourceFile` 幂等短路仍命中）。
- **写回闸 = 本档自己的声明表**：`emitNormal` 用 `SAVE_*`、`emitGlobal` 用 `GLOBAL_*`，即
  `EmDataPools.emitSnapshot` 刚遍历的那张表。名字此刻在表内 ⇒ 活池已按活值写过一条，
  切片跳过（避免同名两条）；名字只在**另一域**表里（读面接受集是 `Save* ∪ Global*` 并集，
  `VariableData.cs:4099/4109/4119`）⇒ 本档不会写它，切片保留（按并集过滤会直接丢数据）。
- **域与生命周期**：normal / global **分槽**（`SaveUnclaimedPassthrough` 的 em 两域）；
  normal 域与顶层变量共用 `lastLoadNo` 闸（`ResetData` 后旧档 EM 切片不进新局），
  global 域跨局（与 `GLOBAL` 变量同源）。`LOADDATA` / `LOADGLOBAL` 开头与既有的
  `clearNormal` / `clearGlobal` 一起清 em 槽。
- **失败面**：捕获/回写异常 ⇒ 按原行为丢弃 + `SaveDiagnostics.note`，不写半截档；
  `OutOfMemoryError` 原样上抛给 `FileSystemOps.saveData` 的求援重试链。
  文本格式（非 eratw 口径）不写 EM 段，`SaveTextFormat` 零改动。
- **物化入口**：`SaveCapture.normal` / `global` → `materialize` 读回时 EM 接受集 = 本次发射用的
  `EmDataNames`，于是 `SaveData.emData` 恰是已知条目、`SaveData.unclaimedEmData` 恰是切片，
  与流式入口产物逐字节一致。
- **原版对照**：读侧 `VariableData.LoadVariableBinary` 的 Map/Xml/DT 三支只接受
  `Save* ∪ Global*`（`:4095-4124`，不在并集的名字读完值即丢）；写侧
  `SaveEMDataToStreamBinary` / `SaveGlobalEMDataToStreamBinary` 只遍历声明名
  （`:3936-3959` / `:3911-3934`），无回写设施。本例外是移植侧增强，按 ADR-005 登记。
  触发面：4.981 的 `CSV/VarExtGameData.csv` 比 4.980 多一条 `SAVE_DTS, QOL_FISH_INSTANCE`
  （钓鱼实例表），4.980 名字表读 4.981 档时它就是未知名。

## 10 未认领键回写范围矩阵（B1 / B2 / B3-EM / 余量，2026-11-14）

> 本节把「哪一域在**本仓**被保住、哪一域仍按原版丢弃、生命周期由谁清」集中到一页。
> 各域的实现口径与失败面仍以 §7（顶层）、§8 + §8.1（角色块两相位）、§9（EM 段）为准，
> 本节只做汇总与索引，不改那三节的语义。原版对照总口径见 §5.3（读掉即丢）与 §7 末
> 「写侧只遍历声明表」。
>
> 口径：「未认领」= 当前游戏**没声明**的条目（读侧 `keepKey` / `emKeepKey` 判 false），
> 不是坏档；「切片」= 类型字节 + 键名（EM 段为容器名）+ 值的原始编码，不解析、不重编码。
> 「已声明」的条目始终按活值写一条，切片只补未声明面（写回闸 = `SaveUnclaimedPassthrough`
> 的 `normalSlices` / `globalSlices` / `charaSlices` / `emSlices`，EM 域另由发射点按本档
> `SAVE_*` / `GLOBAL_*` 名表过滤）。表内 `TODO(verify)` = 有代码/源码依据但缺实机取证，卡点随行。

| 域 | 读侧行为 | 写侧行为 | 例外登记 | 残余 | owner lane |
|---|---|---|---|---|---|
| **顶层 normal 变量**（`saveNN.sav` 变量段） | `SaveFormat.read(bytes, keepKey)` 对 `keepKey=false` 的顶层条目截「类型字节 + 键名 + 值」原序收进 `SaveData.unclaimedVariables`；不解析、不建数组（`SaveUnknownKeySkipTest` 的 306.8 MiB 根修不回退） | `SaveCapture.emitNormal`（`emitUnclaimedSlices`）与 `SaveFormat.writePayload`：已知变量之后、`EOF` 之前 `SaveWriter.raw` 直写；写回闸跳过此刻已声明的键 | ADR-005 例外 ①：§7；`CONTRACT-RT2-UNCLAIMED-B1`（C# 读侧 `VariableData.LoadVariableBinary` `VariableData.cs:4081-4160`；写侧 `VariableData.SaveToStreamBinary` `:3980-3997`） | 切片只随「读档 → 存档」存活，`lastLoadNo` 闸见 §10.1；`LOADGAME` / `SAVEGAME` 宿主快照不携带 aux 槽（失败方向 = 不回写）；PC 实机对段尾追加条目的接受度 `TODO(verify)`（卡点：无 PC 往返环境） | L15 `lane-unclaimed` |
| **顶层 global 变量**（`global.sav` 顶层；`var_*.dat` 同类域） | 同上（`SaveFormat.read` 对非 `CHARVAR` 类别都走同一顶层捕获），条目进 `SaveData.unclaimedVariables` | `SaveCapture.emitGlobal`（`emitUnclaimedSlices`）与 `SaveFormat.writePayload` 同一插入点；`globalSlices` **无**装载号闸（global 域跨局） | ADR-005 例外 ①：§7；`CONTRACT-RT2-UNCLAIMED-B1`（C# 写侧 `VariableData.SaveGlobalToStreamBinary` `:3961-3978`） | `RESETGLOBAL` 清 global 槽 = **已落 L32**（`CONTRACT-RT2-RESETGLOBAL-EM` + `ResetGlobalUnclaimedSlotTest`；复位后 `SAVEGLOBAL` 不再写回重置前的未知键）；`var_*.dat` 的宿主入口不在 eratw 常规路径，`TODO(verify)` 是否有玩家可见面 | L15 `lane-unclaimed`；槽清理 L32 `lane-resetglobal`（已落） |
| **普通存档角色块 · 系统相位**（`Separator` 之前） | `SaveFormat.readCharacterBlock` 以块内 `Separator` 分相位；未认领切片记 `CharaPhase.SYSTEM`，按块内 `NO`（`SystemLayout.charaInt0D` 第 2 项）分桶，**不按块位置** | `SaveCapture.emitNormal` 的 `charaSlicePlan` → `CharaFileIO.emitCharacter`：同一 `NO` 的块内、最后一个系统项之后 `Separator` 之前 `raw` 直写 | ADR-005 例外 ②：§8 + §8.1；`CONTRACT-RT2-UNCLAIMED-B2` / `-B2-PHASE`（C# 写侧 `CharacterData.SaveToStreamBinary` `CharacterData.cs:513-558`；4.981 树相位 `:601-687`、`Separator` `:666-668`、`EOC` `:686`） | 块内 `NO` 缺失/重复/非整数、或两块解出同一 `NO` ⇒ 整块/整桶 fail-closed 丢弃 + `SaveDiagnostics.note`（§8）；`ADDVOIDCHARA` 伪角色 `NO=0` 与真角色同档的组合是否出现 `TODO(verify)`（卡点：缺含多伪角色的玩家档）；回写档在块尾的 PC 接受度 `TODO(verify)` | L17 `lane-unclaimed` + L23（相位） |
| **普通存档角色块 · 用户相位**（`Separator` 之后，`#DIM CHARADATA`） | 同上，切片记 `CharaPhase.USER`（读侧 `bool userDefineData` 置位后只认 `UserDefinedCharaVariableToken`） | 同上；用户项之后 `raw`。当前块没有 `Separator` 但存在 USER 切片 ⇒ **补一个 `Separator`** 再写（否则读侧按系统相位认它们） | ADR-005 例外 ②：§8.1；`CONTRACT-RT2-UNCLAIMED-B2-PHASE`（4.981 树 `CharacterData.cs:692` / `:702-720`） | 相位错 = 「文件里有键、运行期读不到」；未知条目在块内的绝对位置不保留（只保证相位 + 桶内原序）；`NO` 撞桶规则同系统相位行 | L17 `lane-unclaimed` + L23（相位） |
| **EM 段**（`Map` / `Xml` / `DataTable` 容器名） | 三参 `SaveFormat.read(bytes, keepKey, emKeepKey)`：`emKeepKey=false` 的容器名只走标签、不建值对象，「类型字节 + 容器名 + 值」原序收进 `SaveData.unclaimedEmData`；被拒名字**不进** `droppedVariables`（EM 名不是变量） | `SaveCapture.emitNormal` / `emitGlobal`（`emitUnclaimedEm`）与 `SaveFormat.writePayload`：已知 EM 条目之后、EM 段 `EOF` 之前 `raw`；写回闸 = **本档自己的** `SAVE_*` / `GLOBAL_*` 名字表（与 `EmDataPools.emitSnapshot` 同源） | ADR-005 例外 ③：§9；`CONTRACT-RT2-UNCLAIMED-B3-EM`（C# 读侧接受集 `VariableData.cs:4095-4124`；写侧 `SaveEMDataToStreamBinary` / `SaveGlobalEMDataToStreamBinary` `:3911-3959`） | normal / global **分槽**；normal 共用 `lastLoadNo` 闸；global 槽的 `RESETGLOBAL` 清理 = **已落 L32**；真实触发面 = 4.980/4.981 `CSV/VarExtGameData.csv` 的 `QOL_FISH_INSTANCE` 差；PC 实机接受度与未知 EM 值尺寸分布 `TODO(verify)` | L28 `lane-unclaimed`；槽清理 L32 `lane-resetglobal`（已落） |
| **`chara_*.dat`（CHARVAR）** | **CHARVAR 自身未知键 = 仍不回写（L38 D）**：`CharaFileIO.load` 走二参 `SaveFormat.read(bytes) { 变量谓词 }`，不传 `emKeepKey`，也不捕获切片（`SaveFormat.decode` 的 `capture = fileType == NORMAL && keepKey != null`）；未认领键读完即丢（L38 D 案：保持原版「读完即丢」） | **`SAVECHARA` 导出面 = 消费普通存档 char 切片（L41，`NO` 唯一才写 / 未选中不写 / 相位回写）**：`SAVECHARA` 的 `CharaFileIO.emitCapture`（`capture` / `encode` 共用）按本次选中角色的**当前 `NO`** 取 `SaveUnclaimedPassthrough.charaSlicesOf` 的桶，`emitCharacter` 按相位写回（SYSTEM 在 `Separator` 前、USER 在后，缺 `Separator` 时补一个）；当前表里该 `NO` 不唯一 ⇒ 该角色不写 + `SaveDiagnostics.note`（fail-closed），未选中角色的桶不写（子集 = 快照）。CHARVAR 自身的未知键仍不捕获、不回写 | CHARVAR 自身：无（L38 D：保持原版丢弃，**不立** ADR-005 例外）；导出面消费普通存档 char 切片：ADR-005 例外 ② 的消费扩展（L41 `CONTRACT-RT2-SAVECHARA-CHARSLICES`） | CHARVAR 自身重启前置 = per-row generation + `COPYCHARA` 切片转移（L38 `RECON-RT2-CHARVAR` §0.1 / §3.6：`NO` 无法区分同号新实例；无 generation 会错挂、加 generation 又丢 wife 目标行）；`LOADDATA` 之后同一会话内才生效（`charaSlicesOf` 的 `lastLoadNo` 闸，§10.1）；CHARVAR 仍是**追加**语义（`LoadChara` 不清空角色表，§6.5 同文件）；`NO` 不唯一 / 角色被删或替换 ⇒ 宁可丢也不挂错人；第三方 mod 往 chara 档塞未知键的真机样本 `TODO(verify)` | L19 `lane-unclaimed`（CHARVAR 暂缓）+ L41 `lane-savechara-slices`（导出面消费） |
| **文本格式路**（`SystemSaveInBinary=false`） | **未回写**：`SaveCodec.read` 的文本分支忽略 `SaveReadFilters`，`SaveTextFormat.read` 不筛键（未知条目作为普通条目进 `SaveData.variables`，`SaveData.droppedVariables` 恒空） | **未回写**：`SaveTextFormat.write` 无切片直写点；二进制 → 文本、文本 → 二进制都丢 EM 段与切片 | 无（L19 裁定「只登记不落码」，**不立**实现波） | 原版文本存档不写 EM 段（4.981 树 `VariableEvaluator.SaveToStream` `:2788-2805`；`SaveGlobal` 文本支 `:2866-2874` 不调 `SaveEMDataToStreamBinary`）；文本路同样不携带角色用户变量（`SaveTextFormat` 类 KDoc 差异 2）；eratw 实机为二进制 + GZip（§6.1），文本路是保守兼容面 | L19 `lane-unclaimed`（不立实现波） |
| **EM STATIC 集合**（`STATIC_MAPS` / `STATIC_XMLS` / `STATIC_DTS`） | **不进档**：`EmSaveSets.staticSets` 认这三张名单，但不并入 `loadAccept`（读档接受集 = `Save* ∪ Global*`；`EmSaveSets` 类 KDoc） | **不回写**：`SaveEMDataToStreamBinary` / `SaveGlobalEMDataToStreamBinary` 只遍历 `Save*` / `GlobalSave*`，无 static 支 | 无（原版同口径，非 ADR-005 例外） | `RESETGLOBAL` 负责清：C# `VariableData.RemoveEMStaticData`（`VariableData.cs:4056-4079`；4.981 树 `:1344-1367`），移植侧 `EmDataPools.clear(store, em.staticSets)`（`StatementExecutor` 的 `RESETGLOBAL` 分支）；基语料 / DLC 全树 `RESETGLOBAL` 0 命中、第三方包是否调用 `TODO(verify)` | M4-N（静态池复位）+ L28（EM 域口径） |

**玩家面上屏：无**（2026-11-15 退役）。原版 `LOADDATA` / `LOADGLOBAL` 对未声明键全程静默
（`VariableData.LoadVariableBinary`：Int `VariableData.cs:1418-1421`、Str `:1424-1426`、
数组/浮点 `:1430-1500`；`vToken == null` ⇒ `reader.ReadInt()` / `ReadIntArray(null, true)`，
无 `ParserMediator.Warn`）。移植侧 M1-2 曾把三域清单经 `SaveRestoreCopy` 上屏；2026-11-15
玩家事故（261017 旧档含 5 个已退役的 `#DIM SAVEDATA`，每次读档被喊「未载入」）后，
`SaveRestoreCopy` / `SaveLoadHandoff.restoreRenderer` / `StatementExecutor.printRestoreNotice`
整体退役，`RestoreReport` 只作内部读数（不再有壳层渲染器注入位）。
`LoadStatementSilenceTest` 用语句层零输出 + 源码哨兵钉住这条（不许再长出第二套上屏通道）。

### 10.1 生命周期闸（切片什么时候失效）

| 闸 / 入口 | 触发点 | 失效面 | C# 锚 | 状态 |
|---|---|---|---|---|
| `lastLoadNo` | 每次 `LOADDATA` 成功置槽号（`FileSystemOps.loadData` 的 `store.lastLoadNo = no`） | normal 顶层（`normalSlices`）、角色桶（`charaSlices`）、EM normal（`emSlices(global=false)`）三域共用：装载号不等 ⇒ 一律不回写 | `VariableEvaluator.cs:2706`；初值 -1 见 4.981 树 `VariableData.cs:64` | 已落（L15 / L17 / L28） |
| `LOADDATA` 开头 `clearNormal()` | 读到一半失败、或下一份档不认领任何键时，旧切片不得带进下一次存档 | normal + chara + em-normal 三槽整体清 | 原版 `LoadFromStreamBinary` 先复位再解析（§7 口径） | 已落（L15） |
| `LOADGLOBAL` 开头 `clearGlobal()` | 文件存在、确认要重读时（文件不存在 ⇒ 原版不改状态，槽也不动） | global + em-global 两槽整体清 | `RemoveEMGlobalData()` 在打开文件之前（§9） | 已落（L15 / L28） |
| `RESETDATA` | `FunctionCode.RESETDATA`（脚本 / 回标题可达） | `resetData()` → `resetValues()` 把 `lastLoadNo` 复位成 -1 ⇒ normal/char/em-normal 自动失效；EM `SAVE_*` 池同步清（`EmDataPools.clear(store, em.save)`） | 4.981 树 `Instraction.Child.cs:1955-1968` → `VariableEvaluator.ResetData`（`:1727-1744`）；`SetDefaultValue` 复位 `LastLoadNo`（`VariableData.cs:745`） | 已落 |
| `RESETGLOBAL` | `FunctionCode.RESETGLOBAL` | `resetGlobalValues()` + `EmDataPools.clear(global)` + `EmDataPools.clear(staticSets)`；**未认领 global 槽（顶层 + EM）的清理 = 已落 L32** | 4.981 树 `Instraction.Child.cs:1970-1982` → `VariableEvaluator.ResetGlobalData`（`:1746-1755`）；`RemoveEMGlobalData` / `RemoveEMStaticData`（`VariableData.cs:4031-4079`） | 已落（L32：变量/EM 池 + 槽清理） |
| 回标题 / 新开一局 | 语料 `TITLE.ERB:115` 的 `RESETDATA` 可达（与「新局」同一复位面） | 同 `RESETDATA`：旧档 normal/char/em-normal 切片不进入新局 | 同上 `ResetData`（`StatementExecutor` 的 `RESETDATA` 分支注释锚） | 已落（靠 `lastLoadNo` 闸，无需额外接线） |
| 宿主快照 / 会话重建 | `SessionVault` 接回同一活 store | aux 槽**不进** VM / 宿主快照；重建后按「没有切片」处理（安全方向 = 不回写） | 无 C# 锚（移植侧宿主面） | `TODO(verify)`：是否把槽并入快照段（L15 交付已登记，卡点：需宿主快照格式裁定） |

## 11. mod 扩展数据不进 `.sav` 本体：`.rt2` 侧车交叉引用（2026-11-19，MODDX4-D1）

> 本节是导航与边界声明，**不修改 §1-§10 的任何 `.sav` 语义**。mod 存档容器 ABI 的权威 =
> `specs/mod-save.yaml`；mod 代码产物与 `mods/rt2` 盘面 = `specs/erbm-format.md`；
> era-arch-v61u.md §8 只做叙事导航。

- **mod 扩展数据不进 `.sav` 本体**：`saveNN.sav` / `global.sav` 保持 PC Emuera 兼容的原版结构
  （§1-§3 与 §7-§10 的全部 `.sav` 语义不动）。mod 的每包存储、Mod CSV 注入值、实体图、注册表
  快照、虚拟 NO 映射、决策/任务态全部走本体文件名后接 `.rt2` 的伴生容器（`saveNN.sav.rt2` /
  `global.sav.rt2`），与本体组成一个逻辑存档单元、走同一配对事务。
  锚：`core-vm/src/main/kotlin/com/eratw/port/core/save/FileSystemOps.kt:385-386`（伴生路径）、
  `:476-498`（两成员事务）；`core-vm/src/main/kotlin/com/eratw/port/core/save/SidecarSaveBundle.kt:12`。
- **PC 互通诚实口径（规约面）**：`.sav` 本体可被 PC Emuera 读写；随行的 `.rt2` 扩展数据 PC
  不识别——**完整扩展存档（带 `.rt2`）在 PC Emuera 上不保证可继续游戏**。PC 玩过同一槽再传回
  安卓 = 检测 `.rt2` 绑定失配/缺失 → 上警告（扩展状态可能滞后或丢失），不是静默应用；不存在
  「100% 互通」（era-arch-v61u.md:223-231 兼容性矩阵，v6.2-c 起该表述作废）。
  锚：`SidecarSaveBundle.kt:49-51`、`SaveService.kt:28-30`、`FileSystemOps.kt:514-552`。
  玩家可见的三语文案归 D3 波，本节只落规约口径。
- **实体数据严禁寄生 `.sav` 的 emData/MapValue**（审计 19.19 + sidecar 裁定）：§9 的 EM 段
  未认领回写例外（B3-EM）只针对原版 EM 容器语义，与 `.rt2` 实体面无交集。
  锚：`SidecarSaveBundle.kt:49`。
