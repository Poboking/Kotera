//! 常量折叠的 Rust 侧**预验证**：用 Rust 自己的算法（f64 `powf` + 向零截断、UTF-16 码元计数、
//! RGB 拼装）去复算 Kotlin 折叠器在 `tests/data/fold.tsv` 里的读数。
//!
//! 目的不是「测夹具」，而是**在写 Rust 折叠器之前**确认口径能对上：如果这里就红了，
//! 说明 Rust 的数值/编码口径与 Kotlin 不同（例如 powi 与 powf 的边界差），得先解决
//! 再进 P1，而不是等产物 diff 才发现。

use std::collections::BTreeMap;

fn fold_rows() -> BTreeMap<(String, String), String> {
    let p = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/data/fold.tsv");
    let text = std::fs::read_to_string(&p).unwrap_or_else(|e| panic!("读不到 {}: {e}（先跑 erbcc dump-fold）", p.display()));
    text.lines()
        .map(|l| l.trim_end_matches('\r'))
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .map(|l| {
            let cols: Vec<&str> = l.split('\t').collect();
            assert_eq!(cols.len(), 4, "fold.tsv 必须四列：{l}");
            ((cols[0].to_string(), cols[2].to_string()), cols[3].to_string())
        })
        .collect()
}

fn folded(rows: &BTreeMap<(String, String), String>, fn_name: &str, args: &str) -> String {
    rows.get(&(fn_name.to_string(), args.to_string()))
        .unwrap_or_else(|| panic!("探针缺失：{fn_name}({args})"))
        .clone()
}

/// `POWER`：`Math.Pow` 等价 = f64 幂后向零截断（C# `(long)` 也是向零截断）。
fn rust_power(a: i64, b: i64) -> i64 {
    (a as f64).powf(b as f64) as i64
}

/// `STRLENSU`：UTF-16 码元数（Java `String.length()` 口径，代理对算 2）。
fn rust_strlensu(s: &str) -> i64 {
    s.encode_utf16().count() as i64
}

/// `COLOR_FROMNAME` 已知色：`(R<<16)+(G<<8)+B`。
fn rust_rgb(r: u32, g: u32, b: u32) -> i64 {
    ((r << 16) + (g << 8) + b) as i64
}

#[test]
fn power_rows_match_rust_formula() {
    let rows = fold_rows();
    for (args, a, b) in [("2,10", 2i64, 10i64), ("2,0", 2, 0), ("-2,3", -2, 3), ("10,-1", 10, -1)] {
        let expect = format!("folded:{}", rust_power(a, b));
        assert_eq!(folded(&rows, "POWER", args), expect, "POWER({args}) 口径不一致");
    }
}

#[test]
fn strlenu_rows_match_utf16_count() {
    let rows = fold_rows();
    for (args, s) in [("\"abc\"", "abc"), ("\"あいう\"", "あいう"), ("\"😀\"", "😀")] {
        let expect = format!("folded:{}", rust_strlensu(s));
        assert_eq!(folded(&rows, "STRLENSU", args), expect, "STRLENSU({args}) 口径不一致");
    }
}

#[test]
fn color_rows_match_rgb_packing() {
    let rows = fold_rows();
    assert_eq!(folded(&rows, "COLOR_FROMNAME", "\"red\""), format!("folded:{}", rust_rgb(255, 0, 0)));
    assert_eq!(folded(&rows, "COLOR_FROMNAME", "\"Red\""), format!("folded:{}", rust_rgb(255, 0, 0)));
    assert_eq!(
        folded(&rows, "COLOR_FROMNAME", "\"lightskyblue\""),
        format!("folded:{}", rust_rgb(135, 206, 250)),
        "light sky blue = #87CEFA"
    );
    // 两条边界必须原样：抛出 vs 折 -1，混了就是语义错
    assert_eq!(folded(&rows, "COLOR_FROMNAME", "\"transparent\""), "throw:CodeEE");
    assert_eq!(folded(&rows, "COLOR_FROMNAME", "\"nosuchcolor\""), "folded:-1");
}

#[test]
fn missing_table_means_no_fold_not_zero() {
    // 没给 varSizes / keywordTables 时，VARSIZE 与 GETNUM 必须「不折叠」。
    // Rust 侧如果为了省事返回 0，编译产物就会在这里静默分叉。
    let rows = fold_rows();
    assert_eq!(folded(&rows, "VARSIZE", "FLAG,0"), "null");
    assert_eq!(folded(&rows, "GETNUM", "TALENT,\"普通\""), "null");
}
