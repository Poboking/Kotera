# 跨仓契约 — Kotera（引擎侧）与 eratw-pocket（壳层侧）

> 本文件是**两个公开仓之间**的契约正文：谁拥有什么、依赖朝哪边、源码闸归谁、
> CI 怎么互检、冻结夹具按什么口径比、合并次序为什么是这个顺序。
> 私有单体仓（`eratw-android-port`）是上游，公开仓是从它同步出来的两半。

## 1. 拓扑与依赖方向

```
        私有单体仓 eratw-android-port（eratw-android/ 一棵树）
                        │ 同步波（按目录切分 + 三路合并）
            ┌───────────┴───────────┐
    Kotera（引擎侧，MIT）        eratw-pocket（壳层侧，GPL-3.0）
    纯 JVM，零 Android 依赖        Android 壳：Compose + 平台层
            ▲                              │
            └──── composite build ─────────┘
              includeBuild("../Kotera")
```

- 依赖**单向**：壳层 → 引擎。引擎侧**不得**依赖壳层侧的任何模块或源码。
- 壳层引用引擎模块一律用**发布坐标**（`com.eratw.port:<module>`）并在
  `settings.gradle.kts` 里配 `dependencySubstitution`；**不要**写 `project(":...")` ——
  composite build 里那是壳层自己的工程根，解析不到引擎模块。
  例：宿主依赖载体注册表写 `implementation("com.eratw.port:mod-registry")`，
  配 `substitute(module("com.eratw.port:mod-registry")).using(project(":mod-registry"))`。
- 两个仓必须**同级检出**（`<同父目录>/Kotera` 与 `<同父目录>/eratw-pocket`）。

## 2. 源码闸归谁：按「判据主语」分

| 判据主语 | 闸住在哪 | 理由 |
|---|---|---|
| 壳层源码 | 壳层仓 `app/src/test/kotlin/com/eratw/port/sentinel/` | 真源本地可得，不用跨仓取源 |
| 引擎源码 | 引擎仓本仓 | 同上 |
| 打包端 ↔ 导入端的对偶契约 | 打包侧住引擎仓 `tools/modgen`，导入侧住壳层仓 | 一份合同的两半，各守自己那半 |

判据主语是壳层的闸**不要**寄身在引擎仓：公开分仓后引擎仓 CI 只检出自己，
那道闸会退化成「恒跳过的空闸」—— 看着绿，其实什么都没查。

## 3. 需要读对方真源时怎么定位

跨仓取源的闸走**同级姊妹仓路径**：

```kotlin
val sibling = repoRoot().resolveSibling("eratw-pocket")   // 或 Kotera
```

- 姊妹仓**不在场** ⇒ 用 `org.opentest4j.TestAbortedException` 跳过（数据缺失不是回归）。
- 姊妹仓**在场但真源不对** ⇒ 硬红（不许退化成跳过）。
- 相对路径按**各自仓根**算，报错里仍写仓根相对形态，跨机可比。

引擎仓里读壳层真源的 pack 侧闸（`tools/modgen` 的三道）用的是 modgen 的
`internal PackMod` / `internal PackLimits` —— 出不了 modgen 模块，只能留在引擎仓；
代价是引擎仓 CI 必须把壳层仓同级检出，这道闸才会真跑。

## 4. CI 互检与合并次序

两仓 CI 各自**同级检出对方 `main`**（各读对方 main，不触发对方 CI）：

- 引擎仓 CI 检出壳层仓 ⇒ 引擎仓的「入库资产与声明真源一致」等跨仓守卫能真跑。
- 壳层仓 CI 检出引擎仓 ⇒ 壳层仓的「语料专名门禁」能扫到引擎主源。

由此产生一道**互为因果**的次序约束：

| 跨仓守卫 | 在哪仓 | 要求对方先合 |
|---|---|---|
| 入库资产（`app/src/main/assets/mods/rt2/*.manifest`）与引擎声明真源逐字节一致 | 引擎仓 | 壳层仓先合（壳层仓带着新资产） |
| 引擎主源不得含语料专名（`source gates`） | 壳层仓 | 引擎仓先合（引擎主源已清理干净） |

⇒ **合并次序：先合引擎仓（Kotera），再合壳层仓（eratw-pocket）。**
壳层仓合入后，引擎仓 CI 自动转绿（重新触发即可）。反过来合，引擎仓会红在资产守卫上。

## 5. 冻结夹具：比「包内容」，不比 zip 原始字节

`pack-mod` 的真产物夹具（冻结成 Base64 或 sha256）**不能**按 zip 原始字节比：

- zip 的**压缩流**随 JDK 自带的 zlib 版本而变 —— temurin 17.0.21 与 17.0.20.1
  写出的字节就不同；
- 内层 `code.mod` 本身是 zip，且其**文件名是内容寻址**（= 内层字节 sha256 前 16 位）⇒
  内层 sha 漂 ⇒ 文件名漂 ⇒ 外层包内容漂 ⇒ 冻结夹具在别的 JDK 上判红。

定解：

1. 冻结断言一律比**包内容** —— 条目名 + 未压缩字节的摘要（`zipContentDigest`），
   不比原始字节、不比整包 sha256。
2. 内层 `code.mod` 用**手写确定性 zip**（STORED 条目、固定 DOS 时间 1980-01-01、
   无 extra 字段、无 data descriptor、UTF-8 文件名标志），不走 `ZipOutputStream`
   —— 连 STORED 都不够：`ZipOutputStream` 的 extra 字段与 version-made-by 在
   JDK 版本之间也会变。
3. 打包侧与导入侧夹具的 Base64 **逐字节相同**，改动必须三仓同改
   （私源 + 引擎仓 + 壳层仓）；只改一侧，那一侧的守卫必红。

## 6. 权威语料根

**Kotera 根**（`eratw-画蛇添足版eratw4.980`，NOW_VERSION 20260326）——
即引擎仓 `RealCorpus` 的缺省探测目标。

两仓所有语料相关的冻结读数（`ErbccCorpusProfile` 的标签数、`WebpDecoderTest` 的
`.webp` 清单、`AliasLoaderTest` 的 `.als` 别名、`RandSfCorpusE2ETest` 的原文锚点、
壳层仓配置往返闸的 `emuera.config` 逐字节 sha256）全部按这棵根冻结。

本机另外还有两棵 4.981 语料根，它们的期望值与上面这棵**互相冲突**：没有单一
`ERATW_CORPUS` 能让全部语料闸同时绿。换根跑会对不上冻结值 —— 那是**语料换代**，
不是回归。公开仓 CI 不导出语料变量，所以 CI 口径是「全跳过」。

## 7. 同步波怎么落

1. 基线 sha = 上一波末 commit。
2. 按目录切分：`git diff --relative <基线>..HEAD -- <paths>` 生成补丁。
   **必须带 `--relative`**，否则路径带单体仓前缀，`git apply --3way` 找不到文件。
3. 在目标仓 `git apply --3way` 三路合并；上游已删的文件用 `git update-index
   --force-remove` 落地，删除走回收站工具（别直接 `rm`）。
4. 落进公开仓后按第 2、3 节适配跨仓闸；按第 4 节给 CI 补同级检出。
5. 上游每波常带出**它自己没跟上的红**（改了公开面/调用面但没刷新闸）——
   同步时会被闸出来，属正常流程，修了回灌上游。

## 8. 隐私口径

- 仓内引用本机路径一律用占位符：`<internal-workdir>`（原 `.workbuddy/`）、
  `<work-root>`（共享工作根）、`<sample-backup>`（存档样本备份目录）。
- `.gitattributes` 标 `binary` 的路径（`tools/erbcc-rs/tests/data/**` 逐字节对拍凭据）
  **不得**改字节。
