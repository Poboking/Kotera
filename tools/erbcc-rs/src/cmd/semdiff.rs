//! P0.5 语义夹具的 Rust 侧比对器（CONTRACT-RUSTFULL §6.2）。
//!
//! 与 `devtools/semantics/DumpTables.java` 成对：JVM 侧用**真实 JDK 行为**产出基线表，
//! 本工具用 **Rust 侧实现**产出同形表并逐行 diff，差异直接打印「键 / JVM 值 / Rust 值」。
//!
//! 四个子空间：
//! - `upper`：全 Unicode 标量空间的大写映射（K19，canonical 键的来源）
//! - `sjis`：MS932 单字节 + 双字节全空间的解码映射（K21）
//! - `float`：给定 f64 位型的 Java `Double.toString` 文案（K18）
//! - `cmp`：给定字符串对的 `String.compareTo` 符号（K20）
//!
//! 用法：
//!   semdiff upper --compare <java-upper.tsv>
//!   semdiff sjis  --compare <java-sjis.tsv>
//!   semdiff float --compare <java-float.tsv>
//!   semdiff cmp   --compare <java-cmp.tsv>
//! 任一子命令也可用 `--dump <path>` 只产 Rust 张表（排查用）。

use erbcc_rs::java_float::java_double_to_string;
use erbcc_rs::java_unicode_upper::uppercase_str;
use erbcc_rs::ms932;
use erbcc_rs::utf16_order::cmp_utf16;
use std::cmp::Ordering;
use std::collections::BTreeMap;
use std::fmt::Write as _;
use std::io::{BufRead, BufReader};
use std::process::ExitCode;

const MAX_REPORT: usize = 20;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() < 3 {
        eprintln!("usage: semdiff <upper|sjis|float|cmp> --compare <tsv> | --dump <tsv>");
        return ExitCode::from(2);
    }
    // 两种写法都接受：`semdiff upper --compare x` 与 `semdiff --compare upper x`
    let (kind, mode) = if args[0].starts_with("--") {
        (args[1].as_str(), args[0].as_str())
    } else {
        (args[0].as_str(), args[1].as_str())
    };
    let path = &args[2];

    let (total, mismatches) = match (mode, kind) {
        ("--dump", "upper") => return dump(&upper_table(), path),
        ("--dump", "sjis") => return dump(&sjis_table(), path),
        ("--compare", "upper") => compare(&upper_table(), path),
        ("--compare", "sjis") => compare(&sjis_table(), path),
        ("--compare", "float") => compare_float(path),
        ("--compare", "cmp") => compare_cmp(path),
        _ => {
            eprintln!("unknown mode/kind: {mode} {kind}");
            return ExitCode::from(2);
        }
    };

    println!("rows={total} mismatches={mismatches}");
    if mismatches == 0 {
        ExitCode::SUCCESS
    } else {
        ExitCode::FAILURE
    }
}

fn dump(table: &BTreeMap<String, String>, path: &str) -> ExitCode {
    let mut out = String::new();
    for (k, v) in table {
        let _ = writeln!(out, "{k}\t{v}");
    }
    match std::fs::write(path, out) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("write {path}: {e}");
            ExitCode::FAILURE
        }
    }
}

fn compare(table: &BTreeMap<String, String>, path: &str) -> (usize, usize) {
    let file = match std::fs::File::open(path) {
        Ok(f) => f,
        Err(e) => {
            eprintln!("open {path}: {e}");
            return (0, usize::MAX);
        }
    };
    let mut total = 0usize;
    let mut mismatches = 0usize;
    let mut reported = 0usize;
    for line in BufReader::new(file).lines() {
        let line = match line {
            Ok(l) => l,
            Err(_) => continue,
        };
        if line.is_empty() {
            continue;
        }
        let (key, jvm) = match line.split_once('\t') {
            Some(p) => p,
            None => continue,
        };
        total += 1;
        let rust = table.get(key).cloned().unwrap_or_default();
        if rust != jvm {
            mismatches += 1;
            if reported < MAX_REPORT {
                reported += 1;
                println!("DIFF {key}: jvm=[{jvm}] rust=[{rust}]");
            }
        }
    }
    (total, mismatches)
}

fn compare_float(path: &str) -> (usize, usize) {
    let file = match std::fs::File::open(path) {
        Ok(f) => f,
        Err(e) => {
            eprintln!("open {path}: {e}");
            return (0, usize::MAX);
        }
    };
    let mut total = 0usize;
    let mut mismatches = 0usize;
    let mut reported = 0usize;
    for line in BufReader::new(file).lines() {
        let line = match line {
            Ok(l) => l,
            Err(_) => continue,
        };
        if line.is_empty() {
            continue;
        }
        let (bits_s, jvm) = match line.split_once('\t') {
            Some(p) => p,
            None => continue,
        };
        let bits = match u64::from_str_radix(bits_s, 16) {
            Ok(b) => b,
            Err(_) => continue,
        };
        let v = f64::from_bits(bits);
        total += 1;
        let rust = java_double_to_string(v);
        if rust != jvm {
            mismatches += 1;
            if reported < MAX_REPORT {
                reported += 1;
                println!("DIFF {bits_s}: jvm=[{jvm}] rust=[{rust}]");
            }
        }
    }
    (total, mismatches)
}

fn compare_cmp(path: &str) -> (usize, usize) {
    let file = match std::fs::File::open(path) {
        Ok(f) => f,
        Err(e) => {
            eprintln!("open {path}: {e}");
            return (0, usize::MAX);
        }
    };
    let mut total = 0usize;
    let mut mismatches = 0usize;
    let mut reported = 0usize;
    for line in BufReader::new(file).lines() {
        let line = match line {
            Ok(l) => l,
            Err(_) => continue,
        };
        if line.is_empty() {
            continue;
        }
        let parts: Vec<&str> = line.split('\t').collect();
        if parts.len() < 3 {
            continue;
        }
        let a = from_hex(parts[0]);
        let b = from_hex(parts[1]);
        let jvm: i32 = match parts[2].parse() {
            Ok(v) => v,
            Err(_) => continue,
        };
        total += 1;
        let rust = match cmp_utf16(&a, &b) {
            Ordering::Less => -1,
            Ordering::Equal => 0,
            Ordering::Greater => 1,
        };
        if rust != jvm {
            mismatches += 1;
            if reported < MAX_REPORT {
                reported += 1;
                println!("DIFF [{a}] vs [{b}]: jvm={jvm} rust={rust}");
            }
        }
    }
    (total, mismatches)
}

fn from_hex(s: &str) -> String {
    if s.trim().is_empty() {
        return String::new();
    }
    let mut out = String::new();
    for tok in s.trim().split(' ') {
        if let Ok(cp) = u32::from_str_radix(tok, 16) {
            if let Some(c) = char::from_u32(cp) {
                out.push(c);
            }
        }
    }
    out
}

fn hex_of(s: &str) -> String {
    let mut out = String::new();
    for c in s.chars() {
        if !out.is_empty() {
            out.push(' ');
        }
        let _ = write!(out, "{:04X}", c as u32);
    }
    out
}

fn upper_table() -> BTreeMap<String, String> {
    let mut map = BTreeMap::new();
    let mut cp: u32 = 0;
    while cp <= 0x10FFFF {
        if let Some(c) = char::from_u32(cp) {
            let up = uppercase_str(&c.to_string());
            map.insert(format!("{cp:04X}"), hex_of(&up));
        }
        cp += 1;
    }
    map
}

fn sjis_table() -> BTreeMap<String, String> {
    let mut map = BTreeMap::new();
    for b in 0x00u8..=0xFF {
        map.insert(format!("S{b:02X}"), decode_sjis(&[b]));
    }
    for lead in (0x81u8..=0x9F).chain(0xE0..=0xEF) {
        for trail in 0x40u8..=0xFC {
            map.insert(format!("D{lead:02X}{trail:02X}"), decode_sjis(&[lead, trail]));
        }
    }
    map
}

fn decode_sjis(bytes: &[u8]) -> String {
    match ms932::decode(bytes) {
        Some(text) => hex_of(&text),
        None => String::new(),
    }
}
