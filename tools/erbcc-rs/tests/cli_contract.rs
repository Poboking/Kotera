//! CLI 契约闸：逐项钉 `Main.kt:125-142` 的退出码与分发，以及 `Main.kt:53-123` 的 USAGE 文案。
//!
//! 期望值一律来自**真 Kotlin 工具链产物**（`tests/data/rust-scan/kotlin-*.txt`），不手抄。
//!
//! ## USAGE 口径（MODERN-R9-A 起）：Rust 是 Kotlin 的**声明式子集**
//!
//! 权威文案 = 真 Kotlin 工具链 `--help` 的原始 stdout（`tests/data/rust-scan/kotlin-usage.txt`）。
//! Rust `cli::USAGE` 的期望值 = 该基准主体（`strip_trailing_crlf` 之后）逐行应用
//! `tests/data/rust-scan/usage-subset.tsv` 之后的结果：
//!
//! - `drop<TAB><lhs>`：Rust 不打印基准里的这一整行；
//! - `sub<TAB><lhs><TAB><rhs>`：Rust 把这一整行改打印成 rhs（rhs 必须是 Rust USAGE 里的整行原文）。
//!
//! 匹配是**整行相等**（不做前缀/正则/模糊）。每条 lhs 在基准里必须恰好出现一次、每条 sub 的
//! rhs 在 USAGE 里必须恰好出现一次 —— 声明腐烂（零次/多次）就红，不靠容错活着。
//! 为什么允许子集：Kotlin 在 R6 / R7-PATCH-MOD / R8-B 加了 `audit --reach*` 与
//! `compile --mode`/`--allow-parse-failures`，Rust 侧确实没实现（`src/` 对
//! `reachability` / `allow-parse-failures` / `degraded` 零命中），于是「Rust help 不广告不存在的
//! 开关」是事实而不是懒；逐条遗漏理由只写在 `src/cli.rs` 的文件头 KDoc，不写进 tsv（免得把说明
//! 混进判据）。
//!
//! 三条判别力（除应用式对拍之外，专防「悄悄漂移」）：
//!
//! 1. [usage_advertises_only_flags_kotlin_has]：`flags(USAGE) ⊆ flags(基准)` —— 不广告幻觉参数；
//! 2. [kotlin_flags_absent_from_usage_are_exactly_the_declared_gap]：
//!    `flags(基准) \ flags(USAGE)` 恰等于被 drop/sub 掉的行所声明的参数名集合 ——
//!    Kotlin 新增开关而 Rust 既不支持也不声明 ⇒ 红；
//! 3. [unimplemented_switches_are_rejected_with_exit_2]：六个未实现开关逐个交给 `cli::run`
//!    ⇒ 退出码 2（参数错误档）——「help 里没有 = 真不接受」，也挡住「悄悄接受却不写进 help」。
//!
//! ## 已知且上报的 JVM 环境差
//!
//! - USAGE 文案的字节编码：本机 JVM 默认 stdout 字符集是 GBK/cp936，Rust 恒写 UTF-8，
//!   故基准一律带 `-Dsun.stdout.encoding=UTF-8` 重出（不加这个开关，`-` 之外的中文会按 cp936
//!   落盘，与 Rust 腿的 UTF-8 不可比；加了就是纯 UTF-8，不再需要「GBK→UTF-8 解码」那一步）；
//! - 行尾：Kotlin `println` 用 `System.lineSeparator()`（本机 = `\r\n`），Rust `println` 恒 `\n`，
//!   两者文本主体相同、仅终端换行符不同。因此对拍比较的是**去掉 JVM 那一层 `\r\n` 后的主体**。
//!
//! ## `kotlin-usage.txt` 的生成命令（**禁止手抄/手写**）
//!
//! USAGE 文案变了、或 Kotlin 加/删开关，就要按这两条重出基准（在 `eratw-android/` 下执行；
//! `ERBCC_CLASSPATH` 必须是 installDist 的 lib 通配或分号直串，**绝不能**指到
//! `erbcc-rs-classpath.txt` 那个 `.txt` 文件路径 —— 指到文件会让腿报「找不到主类」而整片假红，
//! 见 AGENTS.md §5 与派单简报的坑位）：
//!
//! ```text
//! ./gradlew --configure-on-demand :tools:erbcc:installDist
//! export ERBCC_CLASSPATH="tools/erbcc/build/install/erbcc/lib/*"
//! java -Dsun.stdout.encoding=UTF-8 -cp "$ERBCC_CLASSPATH" com.eratw.port.erbcc.MainKt --help \
//!   > tools/erbcc-rs/tests/data/rust-scan/kotlin-usage.txt
//! ```
//!
//! 落盘的是那条命令 stdout 的**原始字节**（实测：body 全 LF、只有结尾一个 `\r\n` ——
//! Kotlin 编译器把 raw string 里的 CRLF 归一成 LF，行终止符只出现在 `println` 追加的那一位），
//! 所以 `strip_trailing_crlf` 之后的主体与 `cli::USAGE` 可比。
//! MODERN-R9-A 重出读数：**6749 字节（含结尾 `\r\n`）/ 主体 6747 字节、70 行**，
//! sha256 `4084e1ad253142abada4acc68c40341762635f323a2170e4e33d4faaac93e96e`
//! （R6 之前停在 2026-11-01 的旧基准是 4511 字节 / 58 行主体）。
//!
//! ## 本文件还钉 `--allow` 的两格退出码口径（②，rust-cli-polish）
//!
//! 见 [load_allowlist_缺档不是错误_退出码由审计结果定] 与
//! [load_allowlist_非utf8走usage档且正文与kotlin单源同文]：Kotlin 对「缺档」与「坏字节」
//! 给的是**两个不同档**（前者不占退出码、后者 `err()` 恒退 2），Rust 侧必须同档同文。

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

use erbcc_rs::audit;
use erbcc_rs::cli;
use erbcc_rs::types::{absolutize, erbcc_version, find_repo_root, kotlin_path_text, ErbccError};

fn manifest_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

fn data(rel: &str) -> PathBuf {
    manifest_dir().join("tests/data/rust-scan").join(rel)
}

fn golden_corpus() -> PathBuf {
    manifest_dir()
        .join("../../fixtures/golden")
        .canonicalize()
        .unwrap_or_else(|_| manifest_dir().join("../../fixtures/golden"))
}

/// 去掉末尾一个 CRLF（Kotlin `println` 在本 Windows JVM 上的行终止符）。
fn strip_trailing_crlf(bytes: &[u8]) -> &[u8] {
    if bytes.ends_with(b"\r\n") {
        &bytes[..bytes.len() - 2]
    } else {
        bytes
    }
}

fn run(args: &[&str]) -> i32 {
    let owned: Vec<String> = args.iter().map(|s| s.to_string()).collect();
    cli::run(&owned)
}

#[test]
fn empty_args_prints_usage_and_exits_0() {
    // Main.kt:127
    assert_eq!(run(&[]), 0);
}

#[test]
fn help_exits_0() {
    // Main.kt:128
    assert_eq!(run(&["--help"]), 0);
}

#[test]
fn version_exits_0() {
    // Main.kt:129
    assert_eq!(run(&["--version"]), 0);
}

#[test]
fn version_string_matches_core_vm_source_not_hardcoded() {
    // 版本唯一来源 = core-vm 的 Version.kt（types::erbcc_version），baseline 由 Kotlin 产出。
    let repo = find_repo_root(&manifest_dir()).expect("定位仓库根");
    let v = erbcc_version(&repo).expect("从 Version.kt 读版本");
    let baseline = std::fs::read(data("kotlin-version.txt")).expect("读 Kotlin --version 基线");
    let content = String::from_utf8_lossy(strip_trailing_crlf(&baseline)).into_owned();
    assert_eq!(content, format!("erbcc {v}"), "版本串必须与 Kotlin 同、且来自 Version.kt");
    assert!(!v.is_empty());
}

// ---------------------------------------------------------------------------
// USAGE 应用式对拍（MODERN-R9-A）：Rust `cli::USAGE` 是 Kotlin 基准的声明式子集
// ---------------------------------------------------------------------------

/// `usage-subset.tsv` 里的一条声明：`drop` 整行省略，`sub` 整行替换。
#[derive(Debug)]
struct SubsetOp {
    /// `true` = `drop`（`rhs` 为 `None`），`false` = `sub`（`rhs` 为 `Some`）。
    is_drop: bool,
    lhs: String,
    rhs: Option<String>,
}

/// Kotlin 基准主体：真工具链 `--help` 的原始 stdout 去掉 `println` 追加的那一个 `\r\n`。
fn baseline_body() -> String {
    let raw = std::fs::read(data("kotlin-usage.txt")).expect("读 Kotlin --help 基线");
    String::from_utf8(strip_trailing_crlf(&raw).to_vec()).expect("基准主体必须是合法 UTF-8")
}

/// 解析 `usage-subset.tsv`：`#` 开头为注释、空行忽略；其余每行 2 或 3 个 TAB 字段。
fn subset_ops() -> Vec<SubsetOp> {
    let raw = std::fs::read(data("usage-subset.tsv")).expect("读子集声明表 usage-subset.tsv");
    let text = String::from_utf8(raw).expect("usage-subset.tsv 必须是 UTF-8 / LF / 无 BOM");
    let mut ops = Vec::new();
    for (i, line) in text.lines().enumerate() {
        if line.trim().is_empty() || line.starts_with('#') {
            continue;
        }
        let fields: Vec<&str> = line.split('\t').collect();
        let at = i + 1;
        match fields.as_slice() {
            ["drop", lhs] => ops.push(SubsetOp {
                is_drop: true,
                lhs: (*lhs).to_string(),
                rhs: None,
            }),
            ["sub", lhs, rhs] => ops.push(SubsetOp {
                is_drop: false,
                lhs: (*lhs).to_string(),
                rhs: Some((*rhs).to_string()),
            }),
            _ => panic!(
                "usage-subset.tsv:{at} 必须是 2 或 3 个 TAB 字段（drop<TAB>lhs / sub<TAB>lhs<TAB>rhs），\
                 实为 {fields:?}"
            ),
        }
    }
    assert!(!ops.is_empty(), "usage-subset.tsv 不能是空声明表（子集至少要有 compile 用法行那条 sub）");
    ops
}

/// 整行相等计数（不做前缀/正则/模糊匹配）。
fn count_exact_line(text: &str, needle: &str) -> usize {
    text.split_inclusive('\n')
        .filter(|l| l.strip_suffix('\n').unwrap_or(l) == needle)
        .count()
}

/// 把声明逐行应用到基准主体：命中即整行删除（drop）或整行替换（sub），其余行原样搬过去。
///
/// 每条声明必须**恰好命中一次**：零次 = 声明腐烂（基准里那行已变），多次 = 声明有歧义。
fn apply_subset(body: &str, ops: &[SubsetOp]) -> String {
    assert!(
        body.ends_with('\n'),
        "基准主体必须以换行结尾（`println` 追加的那一位在 strip_trailing_crlf 之后仍在）"
    );
    let mut hits = vec![0usize; ops.len()];
    let mut out = String::with_capacity(body.len());
    for line in body.split_inclusive('\n') {
        let text = line.strip_suffix('\n').unwrap_or(line);
        let matched: Vec<usize> = ops
            .iter()
            .enumerate()
            .filter(|(_, op)| op.lhs == text)
            .map(|(i, _)| i)
            .collect();
        assert!(
            matched.len() <= 1,
            "同一行被多条声明命中（声明重叠）：{text}"
        );
        match matched.first() {
            None => out.push_str(line),
            Some(&i) => {
                hits[i] += 1;
                if let Some(rhs) = &ops[i].rhs {
                    out.push_str(rhs);
                    out.push('\n');
                }
            }
        }
    }
    for (i, op) in ops.iter().enumerate() {
        assert_eq!(
            hits[i], 1,
            "声明必须恰好命中基准的一行（零次 = 声明腐烂，多次 = 基数变了）：{op:?}"
        );
    }
    out
}

/// 文本里出现的参数名：`--` 开头的连续 `[a-z-]` 词（`--reach-out` 这类连字符名整体算一个）。
fn flag_names(text: &str) -> BTreeSet<String> {
    let bytes = text.as_bytes();
    let mut out = BTreeSet::new();
    let mut i = 0;
    while i + 1 < bytes.len() {
        if bytes[i] == b'-' && bytes[i + 1] == b'-' {
            let mut j = i + 2;
            while j < bytes.len() && (bytes[j].is_ascii_lowercase() || bytes[j] == b'-') {
                j += 1;
            }
            if j > i + 2 {
                out.insert(text[i..j].to_string());
            }
            i = j;
        } else {
            i += 1;
        }
    }
    out
}

/// 应用式对拍（原「逐字节相等」用例的现行形态）：`cli::USAGE` 必须等于「Kotlin 基准主体逐行
/// 应用 `usage-subset.tsv`」的字节串，不许多一个字节、少一个字节。
///
/// 这条红有且只有四种原因：Kotlin 又改了 USAGE 而基准没重出 / 基准重出了而 USAGE 没跟上 /
/// 声明表没跟着改 / 有人手改了 USAGE。
#[test]
fn usage_equals_kotlin_baseline_with_declared_subset_applied() {
    let body = baseline_body();
    let ops = subset_ops();
    let applied = apply_subset(&body, &ops);
    assert_eq!(
        applied, cli::USAGE,
        "cli::USAGE 与「Kotlin --help 基准 + usage-subset.tsv」不一致（行尾 CRLF/LF 已剔除、\
         编码差见模块头注释；任何未声明的增删改都算红）"
    );
}

/// 声明本身不许腐烂：每条 lhs 在基准里恰好出现一次，每条 `sub` 的 rhs 在 USAGE 里恰好出现一次。
#[test]
fn usage_subset_declarations_match_exactly_one_line() {
    let body = baseline_body();
    let ops = subset_ops();
    for op in &ops {
        assert_eq!(
            count_exact_line(&body, &op.lhs),
            1,
            "drop/sub 的 lhs 必须是基准里**恰好一次**的整行原文，实为 {} 次：{:?}\n\
             （基准重出后行文变了就要同步改声明）",
            count_exact_line(&body, &op.lhs),
            op.lhs
        );
        if let Some(rhs) = &op.rhs {
            assert_eq!(
                count_exact_line(cli::USAGE, rhs),
                1,
                "sub 的 rhs 必须是 Rust USAGE 里**恰好一次**的整行原文，实为 {} 次：{:?}",
                count_exact_line(cli::USAGE, rhs),
                rhs
            );
            assert_ne!(op.lhs, *rhs, "sub 的 lhs 与 rhs 相同就没有声明任何东西：{rhs:?}");
            assert!(
                !op.is_drop,
                "sub 行的 is_drop 必须是 false（解析分支写错了才会走到这里）"
            );
        }
    }
}

/// 判别力 1：**不广告幻觉参数** —— `flags(cli::USAGE) ⊆ flags(Kotlin 基准)`。
///
/// 参数名 = 文本里 `--` 开头的连续 `[a-z-]` 词。Rust 只要在 help 里写出 Kotlin 没有的开关，
/// 这条就红（免得「help 里有、Kotlin 里没有」的私货悄悄长出来）。
#[test]
fn usage_advertises_only_flags_kotlin_has() {
    let body = baseline_body();
    let kotlin_flags = flag_names(&body);
    let usage_flags = flag_names(cli::USAGE);
    let hallucinated: Vec<&String> = usage_flags.difference(&kotlin_flags).collect();
    assert!(
        hallucinated.is_empty(),
        "USAGE 里出现了 Kotlin `--help` 没有的参数名（幻觉参数）：{hallucinated:?}\n\
         Rust 是 Kotlin 的子集，不许比 Kotlin 多广告任何开关"
    );
}

/// 判别力 2：**不漏声明的缺口** —— `flags(基准) \ flags(USAGE)` 恰等于
/// 「被 drop 的行 + 被 sub 换掉的内容所声明的参数名集合」。
///
/// 声明口径：drop 行按 lhs 全算；sub 行按 `lhs 有、rhs 没有` 算（rhs 里保留的 `--allow` 之类
/// 不是缺口）；再剔掉 USAGE 里仍然出现的名字（`--reach-out` 那行行文里顺手提到的 `--out`
/// 也还在，那不是缺口）。Kotlin 新加一个开关而 Rust 既不支持、tsv 又不声明 ⇒ 这条红。
#[test]
fn kotlin_flags_absent_from_usage_are_exactly_the_declared_gap() {
    let body = baseline_body();
    let ops = subset_ops();
    let usage_flags = flag_names(cli::USAGE);
    let kotlin_flags = flag_names(&body);

    let mut declared: BTreeSet<String> = BTreeSet::new();
    for op in &ops {
        let lhs_flags = flag_names(&op.lhs);
        let kept: BTreeSet<String> = op.rhs.as_deref().map(flag_names).unwrap_or_default();
        declared.extend(lhs_flags.difference(&kept).cloned());
    }
    declared.retain(|f| !usage_flags.contains(f));

    let gap: BTreeSet<String> = kotlin_flags.difference(&usage_flags).cloned().collect();
    assert_eq!(
        gap, declared,
        "Kotlin 有、Rust USAGE 没有的参数名，必须逐条声明在 usage-subset.tsv 的 drop/sub 行里：\n\
         Kotlin 有而 Rust 没有 = {gap:?}\n声明表声明的缺口 = {declared:?}\n\
         （差在左边 = 未声明的漂移；差在右边 = 声明了但 USAGE 里还在，两样都算红）"
    );
}

/// 判别力 3：**不广告不支持** —— 六个未实现开关逐个交给 `cli::run`，退出码必须是 2（参数错误档）。
///
/// 参数面在起 kernel 之前就判（`cmd_audit`/`cmd_compile` 的「无法识别的 … 参数」分支），
/// 所以这里不会拉起 JVM。两形态都钉：裸开关（`args[0]` 位）与子命令后的开关位 ——
/// 后者才是玩家真会敲的形状，也挡住「在子命令里悄悄接受却不写进 help」。
#[test]
fn unimplemented_switches_are_rejected_with_exit_2() {
    // Kotlin 有、Rust 没实现的那六个（`src/` 对 reachability/allow-parse-failures/degraded 零命中）。
    let audit_switches = [
        "--reachability",
        "--reach-out",
        "--reach-expect",
        "--reach-write-expect",
    ];
    let compile_switches = ["--mode", "--allow-parse-failures"];

    for sw in audit_switches.iter().chain(compile_switches.iter()) {
        assert_eq!(
            run(&[sw]),
            2,
            "{sw} 在 Rust 侧没实现 ⇒ 裸开关必须是参数错误档（Main.kt:135-139 的等价物）"
        );
    }

    let corpus = golden_corpus().to_string_lossy().into_owned();
    let out = manifest_dir().join("target/cli-contract-unimplemented-switches");
    let _ = std::fs::remove_dir_all(&out);
    for sw in audit_switches {
        assert_eq!(
            run(&["audit", "--corpus", &corpus, sw]),
            2,
            "audit {sw} 未实现 ⇒ 必须是参数错误档（help 里没有 = 真不接受），不许悄悄接受"
        );
    }
    for sw in compile_switches {
        assert_eq!(
            run(&["compile", "--corpus", &corpus, "--out", &out.to_string_lossy(), sw]),
            2,
            "compile {sw} 未实现 ⇒ 必须是参数错误档（help 里没有 = 真不接受），不许悄悄接受"
        );
    }
    assert!(!out.exists(), "参数错误档不许落任何产物：{out:?}");
}

/// ①（rust-cli-polish）：USAGE 必须**自己就说明**「`--allow` 白名单不是合法 UTF-8 ⇒ 退 2」。
///
/// 上面那条逐字节格子保证「两侧文案同源」，本条保证「同源的那份里确有这句话」——
/// 谁把 USAGE 改回不提这一档（并跟着重出基准），字节格子会绿、这条会红。
/// 判据来源：`Main.kt:859-871` 的 `readAllowListText`（`CodingErrorAction.REPORT` ⇒ 抛）
/// → `Main.kt:642-645` 的 `cmdAudit` 捕获 → `Main.kt:179-182` 的 `err()` 恒退 2；
/// 缺档不占退出码那半句对位 `Main.kt:860` + `Main.kt:888-891`。
#[test]
fn usage_states_allow_non_utf8_rejected_with_exit_2() {
    let allow_line = cli::USAGE
        .lines()
        .find(|l| l.starts_with("  --allow"))
        .expect("USAGE 里要有 --allow 那行");
    assert!(
        allow_line.contains("不是合法 UTF-8") && allow_line.contains("退出码 2 拒绝"),
        "--allow 行要写明坏字节退 2，实为：{allow_line}"
    );
    assert!(
        allow_line.contains("文件不存在不是错误"),
        "--allow 行要同时写明「缺档不是错误」（否则使用者会把提示行当成失败），实为：{allow_line}"
    );
    let exit_line = cli::USAGE
        .lines()
        .filter(|l| l.starts_with("  退出码"))
        .find(|l| l.contains("--allow"))
        .expect("audit 的「退出码」行要点名 --allow 的 2 档");
    assert!(
        exit_line.contains("2 = 参数错误"),
        "退出码行要给 2 档定义，实为：{exit_line}"
    );
}

/// ②（rust-cli-polish）：Kotlin 对「`--allow` 指向的档**不存在/不是普通文件**」的结论是
/// **不当错误**——`Main.kt:860` 返回 null → `Main.kt:888-891` 打一行提示后 `AllowList.EMPTY`，
/// 进程退出码完全由审计结果自己定（实测两腿同档同 rc=1，且那 1 来自 `--strict` 下 8 处硬差异：
/// `tools/erbcc-rs/build/cli-polish/a3k.err` / `a3r.err`）。
/// 所以 Rust 侧这格**不许**是 `Io`（退 1 的「错误」档）也不许是 `Usage`（退 2）：
/// 只能 `Ok(空)`，退出码归编排层判。简报里「缺文件走 ErbccError::Io ⇒ 退 1」这句在本仓
/// 现存代码里**读不到**（见交付报告：该档一直是 `Ok(Vec::new())`），真实的双档混用是下一条。
#[test]
fn load_allowlist_缺档不是错误_退出码由审计结果定() {
    let missing = data("nope-definitely-not-there.tsv");
    assert!(!missing.exists(), "这格要的是一个确实不存在的路径：{missing:?}");
    let got = audit::load_allowlist(&missing).expect("缺档必须 Ok（Kotlin 只打提示不报错）");
    assert!(got.is_empty(), "缺档 ⇒ 空白名单，实为 {got:?}");

    // 「存在但不是普通文件」的目录档同口径（Kotlin 的判据就是 isRegularFile，Main.kt:860）。
    let dir = manifest_dir().join("tests/data/rust-scan");
    assert!(dir.is_dir());
    audit::load_allowlist(&dir).expect("目录档也必须 Ok（不是 Io）");
}

/// ②（rust-cli-polish）的**真分歧面**：同一个「非合法 UTF-8」条件，Rust 原先在两处给两个档——
/// 编排层的 `cmd_audit::preflight_allowlist` 走 `Usage`（⇒ 退 2，与 Kotlin 同），
/// 而 [audit::load_allowlist] 里的第二道严格解码走 `Io`（⇒ 退 1）。
/// Kotlin 只有**一个**档：`Main.kt:642-645` 捕获 `IllegalStateException` 后转 `err()` ⇒ **恒 2**
/// （`Main.kt:179-182`）。修向＝Rust 对齐 Kotlin（不是反向），正文也收进同一枚单源
/// `cmd_audit::allow_charset_red` ⇒ 两道闸同档同文。
#[test]
fn load_allowlist_非utf8走usage档且正文与kotlin单源同文() {
    let bad = manifest_dir()
        .join("tests/data/rust-cli-surface/allow-nonutf8.tsv");
    assert!(bad.is_file(), "坏字节白名单夹具应存在：{bad:?}");
    let err = audit::load_allowlist(&bad).expect_err("非 UTF-8 白名单必须报红，不许糊成空白名单");
    let msg = match &err {
        ErbccError::Usage(m) => m.clone(),
        other => panic!("必须走 Usage（退出码 2，Kotlin 的 err() 那一把尺），实为 {other:?}"),
    };
    // 正文单源：与 cmd_audit 的第一道闸逐字相同（两枚闸同文 ⇒ 使用者面没有第二种说法）。
    assert_eq!(
        msg,
        erbcc_rs::cmd_audit::allow_charset_red(&kotlin_path_text(&bad.to_string_lossy())),
        "第二道闸的报红正文必须复用同一枚单源"
    );
    assert!(
        msg.starts_with("--allow 白名单不是合法 UTF-8 文本，已拒绝："),
        "正文前缀必须照 Kotlin `allowListCharsetRed`（Main.kt:879-881），实为：{msg}"
    );
}

/// ②的 CLI 面：坏字节白名单在**起 kernel 之前**就退 2（`cli::finish` 把 `Usage` 映射成 2）。
/// 这条只点 in-process 的 [cli::run]：`preflight_allowlist` 在 kernel 之前拦，所以不会起 JVM。
#[test]
fn audit_non_utf8_allow_exits_2_from_cli_dispatch() {
    let erb = manifest_dir()
        .join("tests/data/rust-audit/game/ERB")
        .to_string_lossy()
        .into_owned();
    let allow = manifest_dir()
        .join("tests/data/rust-cli-surface/allow-nonutf8.tsv")
        .to_string_lossy()
        .into_owned();
    let out = manifest_dir().join("target/cli-contract-nonutf8/u.tsv");
    let _ = std::fs::remove_file(&out);
    assert_eq!(
        run(&["audit", "--corpus", &erb, "--allow", &allow, "--out", &out.to_string_lossy()]),
        2,
        "坏字节白名单 ⇒ 参数错误档（Kotlin 同档，见 cli_surface_diff 的 F2 格全文对拍）"
    );
    assert!(!out.exists(), "退 2 的一格不许落 TSV（`Main.kt:642-645` 在任何产物之前）");
}

/// ③（rust-cli-polish）：缺档提示行的**路径形态单源**（本车道不能改 `cmd_audit.rs`，
/// 故把归一后的正文放在这里，等组长接线；分歧本身的实测读数与在册差分见
/// `tests/cli_surface_diff.rs` 的 `c4_audit_allow_相对路径缺档_分歧钉在册`）。
///
/// 期望值取**真跑 Kotlin 腿**的原文（`tools/erbcc-rs/build/cli-polish/a3k.err` 第 6 行）：
/// Kotlin 打在 `Path(原文)` 上 ⇒ 相对原样 + 分隔符归一（`WindowsPath.toString()`），**不绝对化**。
#[test]
fn allow_missing_warning_text_是kotlin的相对形态() {
    let raw = "tools/erbcc-rs/tests/data/rust-cli-surface/nope-missing.tsv";
    let want_path = if cfg!(windows) {
        "tools\\erbcc-rs\\tests\\data\\rust-cli-surface\\nope-missing.tsv"
    } else {
        raw
    };
    let want = format!("白名单文件不存在（按空白名单处理）: {want_path}");
    assert_eq!(audit::allow_missing_warning_text(raw), want);
    assert!(audit::is_allow_missing_warning_line(&want));
    assert!(audit::is_allow_missing_warning_line(
        "白名单文件不存在（按空白名单处理）: C:\\absolute\\form.tsv"
    ));
    assert!(
        !audit::is_allow_missing_warning_line("白名单行格式不对（跳过）: 1 列"),
        "滤 kernel 那行时不许顺手把别的白名单行也滤掉（`Main.kt:901` 的坏行告警要照常透出）"
    );
    // 与「绝不绝对化」这条口径绑死：kotlin_path_text 只折分隔符（absolutize 会把这格做坏）。
    let abs = absolutize(Path::new(raw));
    assert_ne!(
        audit::allow_missing_warning_text(&abs.to_string_lossy()),
        want,
        "把绝对路径喂进本单源就不是 Kotlin 那一行了 —— 接线时传的必须是**原文**"
    );
}

#[test]
fn symbols_and_audit_reject_bad_corpus_with_exit_2() {
    // 五路子命令已全部接线（RUST1-B 收口）。这里钉的是**移植后的参数面**：
    // corpus 不是目录 ⇒ 走 Kotlin `err()` 的等价物退 2（`Main.kt:179-182`），不是「未移植」占位。
    assert_eq!(run(&["symbols", "--corpus", "x"]), 2);
    assert_eq!(run(&["audit", "--corpus", "x"]), 2);
}

#[test]
fn unrecognized_args_exit_2() {
    // Main.kt:135-139
    assert_eq!(run(&["frobnicate", "foo"]), 2);
    assert_eq!(run(&["--nonsense"]), 2);
}

#[test]
fn scan_missing_corpus_exits_2() {
    // Main.kt:164 err → 2
    assert_eq!(run(&["scan"]), 2);
}

#[test]
fn scan_corpus_not_dir_exits_2() {
    // Main.kt:165
    let p = data("kotlin-version.txt"); // 存在的文件，非目录
    assert_eq!(run(&["scan", "--corpus", &p.to_string_lossy()]), 2);
}

#[test]
fn scan_use_index_not_file_exits_2() {
    // Main.kt:166
    let mut out = manifest_dir().join("target/scan-contract-out");
    std::fs::create_dir_all(&mut out).ok();
    assert_eq!(
        run(&[
            "scan",
            "--corpus",
            &golden_corpus().to_string_lossy(),
            "--use-index",
            &data("does-not-exist.dat").to_string_lossy(),
            "--out",
            &out.to_string_lossy(),
        ]),
        2
    );
}

#[test]
fn scan_unrecognized_flag_exits_2() {
    // Main.kt:160
    assert_eq!(
        run(&[
            "scan",
            "--corpus",
            &golden_corpus().to_string_lossy(),
            "--bogus-flag"
        ]),
        2
    );
}

#[test]
fn scan_flag_without_value_exits_2() {
    // Main.kt:156-159 `--corpus 需要参数` 等
    assert_eq!(run(&["scan", "--corpus"]), 2);
    assert_eq!(run(&["scan", "--rename"]), 2);
    assert_eq!(run(&["scan", "--out"]), 2);
}

#[test]
fn scan_valid_exits_0() {
    let out = manifest_dir().join("target/scan-contract-valid");
    let _ = std::fs::remove_dir_all(&out);
    assert_eq!(
        run(&[
            "scan",
            "--corpus",
            &golden_corpus().to_string_lossy(),
            "--out",
            &out.to_string_lossy()
        ]),
        0
    );
    assert!(out.join("report.json").is_file());
    assert!(out.join("files.tsv").is_file());
    assert!(out.join("summary.txt").is_file());
}

/// 契约铁律 0.6 的现行形态：子命令**即使已移植**，也不许对坏输入退 0 冒充成功。
/// （RUST1 第一波时这两条还是「未移植退 2」的占位护栏；audit/symbols 接线后判据不变、理由变了。）
#[test]
fn not_ported_subcommands_never_return_zero() {
    for sub in ["symbols", "audit"] {
        assert_ne!(
            run(&[sub, "--corpus", "whatever"]),
            0,
            "{sub} 对坏 corpus 必须非 0（禁止假成功）"
        );
    }
}

#[allow(dead_code)]
fn _unused(_: &Path) {}
