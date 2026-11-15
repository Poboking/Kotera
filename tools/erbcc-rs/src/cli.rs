use crate::types::{
    current_dir, erbcc_version, find_repo_root, ErbccError, Outcome, EXIT_FAIL, EXIT_OK, EXIT_USAGE,
};

/// USAGE 文案 —— Kotlin `Main.kt:53-123` 的 `private const val USAGE` 的**声明式子集**。
///
/// 口径（MODERN-R9-A 起）：Rust CLI 是 Kotlin CLI 的**子集**，不是逐字副本。
/// 权威文案 = `tests/data/rust-scan/kotlin-usage.txt`（真 Kotlin 工具链 `--help` 的原始
/// stdout 字节；body 全 LF + 结尾一个 `\r\n`，即 JVM `println` 那道行终止符）。本常量的
/// **期望值** = 该基准主体逐行应用 `tests/data/rust-scan/usage-subset.tsv` 之后的结果 ——
/// 不许多一个字节、少一个字节，由
/// `tests/cli_contract.rs::usage_equals_kotlin_baseline_with_declared_subset_applied` 钉住；
/// 未在 tsv 里声明的漂移（Kotlin 又加开关、或本常量自己乱加乱删）仍然红。
/// Kotlin 侧新增开关时：先重出基准，再在 tsv 里 `drop`/`sub` 声明缺口，最后在下面的
/// 「逐条遗漏理由」补一条 —— 三处缺一处就红。
///
/// 内容含 `*.erb/*.erh`（即出现 `/*` 字符对），故必须放在**字符串字面量**里而非块注释
/// （AGENTS §2.5：块注释正文出现 `/*` 会开嵌套注释吞掉闭合符）。
/// 结尾随 Kotlin 一样带一个换行（`Main.kt:123` 的 `"""` 前的 `\n`），再经 `println!`
/// 追加换行，与 Kotlin `println(USAGE)` 的字节输出一致。
///
/// ## 逐条遗漏理由（声明在 tsv，理由只写在这里）
///
/// 1. `compile --mode` + `--allow-parse-failures`（R7-PATCH-MOD 的 compat 容错档）：
///    Rust `compile` 只走 strict「任何解析失败都拒落盘」这一条路（`cmd_compile`），
///    compat 的 THROW 空体桩、`degraded-slices` manifest 段与容错清单校验一行都没移植
///    ⇒ 用法行用 `sub` 回落成 `[--limit <N>]` 收尾，说明 8 行（`--mode` 起 5 行 +
///    `--allow-parse-failures` 3 行）整行 `drop`。
/// 2. `audit --reachability` / `--reach-out`（R6 的可达性/可移植性报告）：Rust `audit` 只做
///    「解析期使用点 × 运行期真实命名空间」的未绑定符号审计，不产可达集、不产
///    reachability.tsv ⇒ 2 行 `drop`。
/// 3. `audit --reach-expect` / `--reach-write-expect`（R8-B 的读数基线对拍）：两者都以 R6
///    的读数为输入，Rust 侧连带缺 ⇒ 2 行 `drop`。
/// 4. `audit` 的「退出码」行：Kotlin 版把「可达性读数与基线有漂移」并进 1 档、把「两个基线
///    开关缺 `--reachability` 或互斥同给」并进 2 档；Rust 没这两族开关，照抄就是广告不存在的
///    失败档 ⇒ 用 `sub` 回落成不带这两句的旧措辞。
///
/// 唯一**不许**动的措辞是 `--allow` 那行与含它的「退出码」行：`--allow` 白名单不是合法 UTF-8
/// ⇒ 退出码 2 拒绝那一档两腿都有（Kotlin `Main.kt:859-871` 的 `readAllowListText` 走
/// `CodingErrorAction.REPORT`，坏字节抛 `IllegalStateException`（`:870`，正文见 `Main.kt:879-881`
/// 的 `allowListCharsetRed`）→ `Main.kt:642-645` 的 `cmdAudit` 捕获 → `Main.kt:179-182` 的
/// `err()` 恒退 2；缺档提示见 `Main.kt:887-890`，打一行后返回 `AllowList.EMPTY`、不占退出码）。
/// **这两行刻意不增减行数**：`Main.kt` 的 `USAGE` 区间（`:53-123`）与 `:123` 之后的行号在全仓
/// 各处被点名引用，USAGE 里多一行会把它们整体错位。
pub const USAGE: &str = r#"erbcc - EraBasic 编译器 (eratw-android)

用法:
  erbcc scan --corpus <ERB目录> [--use-index <lazyloadingfiles.dat>] [--rename <_Rename.csv>] [--out <报告目录>]
  erbcc symbols --corpus <ERB目录> [--json <输出文件>]
  erbcc compile --corpus <ERB目录> --out <产物目录> [--limit <N>]
  erbcc check --corpus <ERB目录> [--out <产物目录>]
  erbcc audit --corpus <ERB目录> [--game-root <游戏根>] [--out <TSV文件>] [--limit <N>] [--strict] [--allow <白名单>]
  erbcc audit --corpus <ERB目录> [--game-root <游戏根>] --roundtrip [--artifact-dir <产物目录>] [--limit <N>]
  erbcc --help
  erbcc --version

symbols 选项（@标签符号表，规约见 specs/loader.md 附录 D）:
  --corpus          游戏数据根（ERB 语料所在目录）
  --json            追加写出机器可读的符号表摘要
  --collator-check  比较器自检：ja-JP Collator 与序数比较各跑一遍，对比重名胜者与事件执行序
  --ordinal         改用序数文件名排序（默认 ja-JP Collator，见 ADR-006）

compile 选项（全量语料 → .erbc 产物 + 确定性 manifest，规约见 specs/erbc-format.md）:
  --corpus      游戏数据根（ERB 语料所在目录）
  --out         产物输出目录（默认 tools/erbcc/erbc-out/；落成 <out>/erbc.v1/{program.erbc,program.meta,manifest.txt}）
  --limit       只编译前 N 个文件（调试用；manifest 会注明，正式产物必须全量）
  退出码        0 = 成功并落盘；1 = 编译/构建失败（stderr 逐条给 文件:行号，不落盘）；2 = 参数错误
  侧车          program.meta 是装载期元数据（函数元数据 / #DIM / 标签 / 数据节 / 宏表），
                与 program.erbc 成对分发；装载侧用 ProgramLoader + BootMetadata.read 一起读。

check 选项（产物新鲜度：清单登记的源文件摘要 × 磁盘现状，见 ArtifactFreshness）:
  --corpus      语料根（与编译时同一路径；清单里的相对路径相对它）
  --out         产物目录（`compile --out` 传的那个；缺省 tools/erbcc/erbc-out）
  退出码        0 = 容器/侧车/源文件全对齐；1 = 有新鲜度问题（逐条 stderr，含「改源未重编」）；2 = 参数错误

audit 选项（装载期符号链接审计：解析期使用点 × 运行期真实命名空间）:
  --corpus      语料根（通常 <gameRoot>/ERB）
  --game-root   游戏根（含 CSV/ 与 emuera.config；缺省 = corpus 的父目录）
  --out         未绑定符号 TSV（缺省 tools/erbcc/symbol-audit/unresolved-symbols.tsv）
  --limit       只切片前 N 个文件（调试用）
  --strict      有硬差异时退出码 1（未绑定变量/关键字/#DIM 注册失败等；动态关键字下标不计）
  --allow       白名单 TSV（每行 `<kind>\t<name>` 或 `<kind>\t<functionKey>\t<name>`；# 开头为注释）；不是合法 UTF-8 ⇒ 退出码 2 拒绝（不做有损解码，且在落任何产物之前挡下）；文件不存在不是错误 —— 打一行提示后按空白名单处理
  退出码        0 = 审计完成且（--strict 时）无硬差异；1 = 编译失败或 --strict 下有硬差异；2 = 参数错误（含 `--allow` 白名单不是合法 UTF-8 的拒绝档）

audit --roundtrip（CMP-79：落盘后再装载对账，审计口径改走 .erbc + program.meta 真装载链）:
  --roundtrip      编译 → 落盘 → ProgramLoader + BootMetadata.read 重建会话 → 与内存产物对账；
                   绿的判据 = 侧车模型逐字段 + runtimeGlobals/privateVars/systemVars 三读数 +
                   使用点名字集合哈希 + 容器语句/指令条数全部相等
  --artifact-dir   产物落盘目录（缺省临时目录：绿了自删、红了保留路径供复现）
  --out            对账明细 TSV（缺省 tools/erbcc/symbol-audit/roundtrip.tsv）
  退出码           0 = 对账全绿；1 = 任一项不等（或编译失败，不落盘）

scan 选项:
  --corpus      游戏数据根（ERB 语料所在目录，索引路径相对此目录）
  --use-index   lazyloadingfiles.dat 装载索引（提供 index/union 策略）
  --rename      _Rename.csv 置換名辞書（默认自动探测 <corpus>/../CSV/_Rename.csv）
  --out         报告输出目录（默认 tools/erbcc/scan-report/）

文件集策略（一次运行内全部执行并对比）:
  all    磁盘上全部 *.erb/*.erh
  index  仅 lazyloadingfiles.dat 索引内文件
  union  磁盘与索引的并集
"#;

/// CLI 分发：子命令 → 各路 `execute`。逐项复刻 Kotlin `Main.kt:125-142` 的 `main`。
///
/// - 空参 / `--help` → 打 USAGE 退 0（`Main.kt:127-128`）；
/// - `--version` → 打 `erbcc <引擎版本>` 退 0（`Main.kt:129`，版本串走
///   `types::erbcc_version`，禁止硬编码）；
/// - `scan` → `cmd_scan::execute`（`Main.kt:130`）；`compile`/`check` → 各自 execute
///   （`Main.kt:132-133`）；`audit` → `cmd_audit::execute`（`Main.kt:134`，RUST1-B rust-audit 车道）；
/// - `symbols` → `cmd_symbols::execute`（`Main.kt:131`，RUST1-B rust-symbols 车道，组长落的分派臂）；
/// - 无法识别的参数 → 照 `Main.kt:135-139` 文案退 2。
pub fn run(args: &[String]) -> i32 {
    if args.is_empty() {
        // Main.kt:127
        println!("{USAGE}");
        return EXIT_OK;
    }
    match args[0].as_str() {
        "--help" => {
            println!("{USAGE}");
            EXIT_OK
        }
        "--version" => {
            // Main.kt:129：`erbcc ${Version.VERSION}`。版本无源可读就报错，不硬编码。
            let root = find_repo_root(&current_dir());
            match root.as_ref().map(|r| erbcc_version(r)) {
                Some(Ok(v)) => {
                    println!("erbcc {v}");
                    EXIT_OK
                }
                Some(Err(e)) => {
                    eprintln!("错误: 读取引擎版本失败: {}", err_text(&e));
                    EXIT_FAIL
                }
                None => {
                    eprintln!("错误: 定位不到仓库根（找不到标记文件 {}），版本无源可读、拒绝硬编码",
                        crate::types::MARKER_REL);
                    EXIT_FAIL
                }
            }
        }
        "scan" => finish(crate::cmd_scan::execute(&args[1..])),
        "compile" => finish(crate::cmd_compile::execute(&args[1..])),
        "check" => finish(crate::cmd_check::execute(&args[1..])),
        // audit：RUST1-B / rust-audit 车道接线（`Main.kt:134` 的等价物）。
        // `--roundtrip`（CMP-79）子路由 rust-roundtrip 车道落地，分派在 `cmd_audit` 内部
        // （照 `Main.kt:616` 降级警告在先、`:631` 分派在后的顺序）。
        "audit" => finish(crate::cmd_audit::execute(&args[1..])),
        // symbols：RUST1-B / rust-symbols 车道接线（`Main.kt:131` 的等价物，组长落的分派臂）。
        // 装载序与比较器判定仍在 JVM kernel，本臂只做参数面、读数转发与 JSON 序列化。
        "symbols" => finish(crate::cmd_symbols::execute(&args[1..])),
        _ => {
            // Main.kt:135-139 文案原样
            eprintln!("错误: 无法识别的参数: {}", args.join(" "));
            eprintln!("使用 --help 查看用法。");
            EXIT_USAGE
        }
    }
}

/// 把 `Outcome<i32>` 落成退出码：Ok 原样；`Usage`→2、其余→1（Kotlin 里参数错误经 `err()`
/// 恒退 2、运行期异常退非 0）。
fn finish(result: Outcome<i32>) -> i32 {
    match result {
        Ok(code) => code,
        Err(ErbccError::Usage(msg)) => {
            eprintln!("错误: {msg}"); // Main.kt:164 的 err() 文案
            EXIT_USAGE
        }
        Err(e) => {
            eprintln!("错误: {}", err_text(&e));
            EXIT_FAIL
        }
    }
}

fn err_text(e: &ErbccError) -> String {
    match e {
        ErbccError::NotPorted(name) => format!("{name} 本波未移植"),
        ErbccError::Usage(m) | ErbccError::Io(m) | ErbccError::Parity(m) => m.clone(),
    }
}
