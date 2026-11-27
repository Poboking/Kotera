//! `firstdiff` —— 两个 `erbc.v1` 产物目录的首个差异报告（CONTRACT-RUSTFULL §6.3 第 3 项）。
//!
//! 为什么要有它：Rust 腿与 Kotlin 腿对拍目前只有 sha256 相等/不等两种结局。不等时
//! 「从头再来」是最贵的反应，所以这里把不等折成**可执行信息**：
//! 「哪个产物、第几字节、哪个段、哪个函数、哪条 pc、哪一行源码、两侧指令四字原值」。
//!
//! 用法：
//!   firstdiff <dirA> <dirB>
//!   firstdiff <dirA>/program.erbc <dirB>/program.erbc
//!
//! 退出码：0 = 全部逐字节相同；1 = 有差异（已打印定位）；2 = 参数/解析错误。

use erbcc_rs::container;
use erbcc_rs::locate::{self, Place};
use erbcc_rs::meta_files::FileMap;
use erbcc_rs::types::{EXIT_FAIL, EXIT_OK, EXIT_USAGE};
use std::path::{Path, PathBuf};
use std::process::ExitCode;

/// 参与比较的产物（与 `ErbcArtifacts.write` 的落盘集合一致；manifest 是文本，单列）。
const BINARY_ARTIFACTS: [&str; 3] = ["program.erbc", "program.meta", "program.stmt.idx"];
const TEXT_ARTIFACTS: [&str; 1] = ["manifest.txt"];

fn arg_paths() -> Result<(PathBuf, PathBuf), String> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() != 2 {
        return Err("usage: firstdiff <dirA> <dirB> | <a.erbc> <b.erbc>".to_string());
    }
    // 目录模式下「主文件」是 program.erbc（其余姊妹件由目录模式另比），
    // 但返回的是**调用者给的原始路径**：后面按目录/单文件分流时还要用它。
    let a = PathBuf::from(&args[0]);
    let b = PathBuf::from(&args[1]);
    let a_is_dir = a.is_dir();
    let b_is_dir = b.is_dir();
    if a_is_dir != b_is_dir {
        return Err("两侧必须同为产物目录或同为 .erbc 文件".to_string());
    }
    Ok((a, b))
}

fn fmt_place(p: &Place) -> String {
    let mut s = format!("段={}（+{}）", p.section, p.intra_offset);
    if let Some(pc) = p.pc {
        s.push_str(&format!(" pc={pc}"));
    }
    if let Some(f) = &p.function {
        s.push_str(&format!(" 函数={f}"));
    }
    if let Some(l) = p.line {
        s.push_str(&format!(" 行={l}"));
    }
    s
}

fn fmt_words(w: Option<[u32; 4]>) -> String {
    match w {
        Some(x) => format!("[{}, {}, {}, {}]", x[0], x[1], x[2], x[3]),
        None => "<段外/越界>".to_string(),
    }
}

/// 比一对 `.erbc`：首个差异 + 两侧定位 + 两侧指令四字 + （有侧车时）两侧源文件。
///
/// 侧车（58 MB）**只在真的出现差异时才读**：自比/全等是最常见的调用（毫秒级），
/// 不该为「可能用不上」的源文件表付 1.5 s。
fn diff_erbc(
    a: &Path,
    b: &Path,
    label: &str,
    a_dir: Option<&Path>,
    b_dir: Option<&Path>,
) -> Result<bool, String> {
    let off = locate::first_diff_offset(a, b).map_err(|e| format!("{label}: {e}"))?;
    let Some(off) = off else {
        println!("{label}: 相同");
        return Ok(false);
    };
    let ca = container::open(a).map_err(|e| e)?;
    let cb = container::open(b).map_err(|e| e)?;
    println!("{label}: 首个差异 @ 字节 {off}（A={} B={}）", size_of(a), size_of(b));
    let pa = locate::attribute(&ca, off).map_err(|e| format!("A 侧定位失败: {}", container::message_of(&e)))?;
    let pb = locate::attribute(&cb, off).map_err(|e| format!("B 侧定位失败: {}", container::message_of(&e)))?;
    println!("  A {}", fmt_place(&pa));
    println!("  B {}", fmt_place(&pb));
    println!("  A 指令字 {}", fmt_words(pa.words));
    println!("  B 指令字 {}", fmt_words(pb.words));
    // 源文件来自侧车的 `files` 段（`BootMetadata.kt:832`）；缺表就只少这一行，不影响定位。
    let fa = a_dir.and_then(load_file_maps);
    let fb = b_dir.and_then(load_file_maps);
    print_source("A", pa.function.as_deref(), fa.as_ref());
    print_source("B", pb.function.as_deref(), fb.as_ref());
    Ok(true)
}

fn print_source(side: &str, function: Option<&str>, map: Option<&FileMap>) {
    let (Some(f), Some(m)) = (function, map) else { return };
    if let Some(file) = m.file_of(f) {
        println!("  {side} 源文件 {file}");
    }
}

/// 目录模式下读各自的 `program.meta` 建函数→文件表；失败只警告，不阻断定位。
fn load_file_maps(dir: &Path) -> Option<FileMap> {
    let meta = dir.join("program.meta");
    if !meta.is_file() {
        return None;
    }
    match FileMap::load(&meta) {
        Ok(m) => Some(m),
        Err(e) => {
            eprintln!("提示: 未加载 {} 的函数→文件表: {e}", meta.display());
            None
        }
    }
}

fn size_of(p: &Path) -> String {
    std::fs::metadata(p)
        .map(|m| m.len().to_string())
        .unwrap_or_else(|_| "<缺>".to_string())
}

/// 文本产物（manifest）：首个不同行 + 行号 + 两侧原文（截断 160 字）。
fn diff_text(a: &Path, b: &Path, label: &str) -> Result<bool, String> {
    let ta = std::fs::read_to_string(a).map_err(|e| format!("读 A {label} 失败: {e}"))?;
    let tb = std::fs::read_to_string(b).map_err(|e| format!("读 B {label} 失败: {e}"))?;
    if ta == tb {
        println!("{label}: 相同（{} 行）", ta.lines().count());
        return Ok(false);
    }
    let la: Vec<&str> = ta.lines().collect();
    let lb: Vec<&str> = tb.lines().collect();
    let n = la.len().max(lb.len());
    for i in 0..n {
        let x = la.get(i).copied();
        let y = lb.get(i).copied();
        if x != y {
            println!("{label}: 首个差异 @ 第 {} 行", i + 1);
            println!("  A {}", clip(x));
            println!("  B {}", clip(y));
            return Ok(true);
        }
    }
    Ok(false)
}

fn clip(s: Option<&str>) -> String {
    match s {
        Some(v) if v.chars().count() > 160 => {
            let t: String = v.chars().take(160).collect();
            format!("{t}…")
        }
        Some(v) => v.to_string(),
        None => "<该行不存在>".to_string(),
    }
}

fn main() -> ExitCode {
    let Ok((a, b)) = arg_paths() else {
        eprintln!("usage: firstdiff <dirA> <dirB> | <a.erbc> <b.erbc>");
        return ExitCode::from(EXIT_USAGE as u8);
    };
    let a_dir = Path::new(&a).to_path_buf();
    let b_dir = Path::new(&b).to_path_buf();

    // 单文件模式：只比 .erbc（没有侧车目录，定位只到函数/行，不报源文件）
    if !a_dir.is_dir() {
        return match diff_erbc(&a_dir, &b_dir, "program.erbc", None, None) {
            Ok(true) => ExitCode::from(EXIT_FAIL as u8),
            Ok(false) => ExitCode::from(EXIT_OK as u8),
            Err(e) => {
                eprintln!("{e}");
                ExitCode::from(EXIT_USAGE as u8)
            }
        };
    }

    let mut differ = false;
    let mut errored = false;
    for name in BINARY_ARTIFACTS {
        let pa = a_dir.join(name);
        let pb = b_dir.join(name);
        if !pa.exists() || !pb.exists() {
            println!("{name}: 缺文件（A={} B={}）", pa.exists(), pb.exists());
            errored = true;
            continue;
        }
        let r = if name == "program.erbc" {
            diff_erbc(&pa, &pb, name, Some(&a_dir), Some(&b_dir))
        } else {
            match locate::first_diff_offset(&pa, &pb) {
                Ok(None) => {
                    println!("{name}: 相同");
                    Ok(false)
                }
                Ok(Some(off)) => {
                    println!("{name}: 首个差异 @ 字节 {off}（A={} B={}）", size_of(&pa), size_of(&pb));
                    Ok(true)
                }
                Err(e) => Err(format!("{name}: {e}")),
            }
        };
        match r {
            Ok(true) => differ = true,
            Ok(false) => {}
            Err(e) => {
                eprintln!("{e}");
                errored = true;
            }
        }
    }
    for name in TEXT_ARTIFACTS {
        let pa = a_dir.join(name);
        let pb = b_dir.join(name);
        if !pa.exists() || !pb.exists() {
            println!("{name}: 缺文件");
            errored = true;
            continue;
        }
        match diff_text(&pa, &pb, name) {
            Ok(true) => differ = true,
            Ok(false) => {}
            Err(e) => {
                eprintln!("{e}");
                errored = true;
            }
        }
    }

    if errored {
        ExitCode::from(EXIT_USAGE as u8)
    } else if differ {
        ExitCode::from(EXIT_FAIL as u8)
    } else {
        ExitCode::from(EXIT_OK as u8)
    }
}
