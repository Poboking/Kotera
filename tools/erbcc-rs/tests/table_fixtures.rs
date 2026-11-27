//! 跨语言语义表夹具的**真数据**校验（CONTRACT-RUSTFULL §6.3 第 2 项）。
//!
//! 夹具 `tests/data/{builtins,opcodes}.tsv` 由 Kotlin 腿导出（见 `src/tables.rs` 头部命令），
//! 入库快照的用途：Rust 腿的语义面（内建函数边界、ISA 编号）在**没有 JVM** 的机器上也
//! 能跑形状闸。注册面变了就重新导出并更新快照 —— 这一步漏了，本文件的读数会先红，
//! 而不是等 Rust 编译器在语料上跑出错语句。
//!
//! 重新导出（在仓库根，一次一条）：
//! `java -cp <erbcc classes>:<core-vm classes>:<kotlin-stdlib>:<coroutines> \
//!   com.eratw.port.erbcc.MainKt dump-builtins --out tools/erbcc-rs/tests/data/builtins.tsv`

use std::path::PathBuf;

fn data(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/data").join(name)
}

fn read(name: &str) -> String {
    let p = data(name);
    std::fs::read_to_string(&p).unwrap_or_else(|e| panic!("读不到夹具 {}: {e}（先按 src/tables.rs 头部命令导出）", p.display()))
}

#[test]
fn builtins_fixture_matches_registry_shape() {
    let t = erbcc_rs::tables::BuiltinTable::from_tsv(&read("builtins.tsv")).expect("内建表能解析");
    assert!(t.len() > 300, "内建函数应有 300+ 条（4.981 注册面），实际 {}", t.len());
    // eraTW 高频函数抽查：类型与参数边界
    let abs = t.get("ABS").expect("ABS 在册");
    assert_eq!(abs.ret_type, "INT");
    assert_eq!((abs.min_args, abs.max_args), (1, 1));
    // 需要未求值实参的函数必须被标出来，否则 Rust 侧会先求值再调用（语义就错了）。
    // 注意：`IIF`/`SELECTFUNC` 这一族**不在**内建表里（它们在语句/表达式层），别按名字硬点。
    assert!(
        t.get("ARRAYMSORT").map(|r| r.needs_expr_args).unwrap_or(false),
        "ARRAYMSORT 必须 needsExprArgs=1"
    );
    // 占位现状：**0 条**（2026-11-27 快照）—— 4.981 能力面已全部落地，注册面里没有
    // 「尚未实现」的桩。这条钉的是事实而非立场：哪天有人加了占位，Rust 腿必须同步实现
    // 那条 `CodeEE("函数 X 尚未实现")` 的路径，所以宁可在快照闸先红一次。
    assert_eq!(
        t.rows().iter().filter(|r| r.placeholder).count(),
        0,
        "占位应为 0；若新增占位，须同步更新快照并告知 Rust 侧补未实现路径"
    );
    // 成对变参组存在且四段自洽
    for r in t.rows() {
        if let Some(v) = r.variadic {
            assert!(v.group_size > 0 && v.min_group_args >= v.fixed_prefix);
        }
    }
}

#[test]
fn opcodes_fixture_is_dense_and_aliases_resolve() {
    let t = erbcc_rs::tables::OpcodeTable::from_tsv(&read("opcodes.tsv")).expect("指令表能解析");
    assert_eq!(t.internal_count, 29, "VM 内部指令 0..28（OpcodeTable.kt:148）");
    assert_eq!(t.max_statement_opcode, 197, "语句段上界（OpcodeTable.kt:809）");
    assert_eq!(t.name_of(0), Some("_NOP"));
    assert_eq!(t.name_of(t.internal_count), Some("BEGIN"), "29 号是首个语句（flow 段起点）");
    // 别名 → 操作码 → 名字 的三级回查（ERB 书写面就靠这条链）
    let pf = t.alias("PRINTFORM").expect("PRINTFORM 是语句别名（OpcodeTable.kt:1083）");
    assert_eq!(pf.canonical, "PRINT");
    assert_eq!(pf.variant, 9, "PRINTFORM 的 variant 是 9（换行样式位）");
    assert_eq!(t.name_of(pf.opcode), Some("PRINT"));
    assert!(t.aliases().len() > 300, "别名应有 300+ 条，实际 {}", t.aliases().len());
    // kind 段首尾相接（29..197 无缝），否则 kindOf 会漏判
    let mut kinds: Vec<_> = t.kinds().to_vec();
    kinds.sort_by_key(|k| k.1);
    assert_eq!(kinds.first().map(|k| k.1), Some(t.internal_count));
    assert_eq!(kinds.last().map(|k| k.2), Some(t.max_statement_opcode));
    for w in kinds.windows(2) {
        assert_eq!(w[0].2 + 1, w[1].1, "kind 段 {} 与 {} 之间有缝", w[0].0, w[1].0);
    }
}
