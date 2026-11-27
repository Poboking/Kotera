//! `rewrite` —— 把产物读成模型、按编码规则重写一份，并报告逐段/整份是否**逐字节相同**
//! （CONTRACT-RUSTFULL §6.4 P1.5「writer 先行」的验收面）。
//!
//! 用法：
//!   rewrite <program.erbc> [--out <path>]        # 容器单文件
//!   rewrite <产物目录>   [--out <目录>]          # 容器 + 侧车两件（stmt.idx / meta）
//!
//! 不给 `--out` 就只算不写（纯闸模式，381 MB 产物约 1 秒量级）。
//!
//! 退出码：0 = 全部逐字节相同且配对校验通过；1 = 有差异或某段不自洽（已打印首个不同偏移）；
//! 2 = 参数/读取/解析错误。
//!
//! 为什么先做「重写」而不是「从零生成」：整条链最难的部分是语义前端（lexer/parser/codegen），
//! 而容器编码是**可被单独证明**的一层。先让「读 → 模型 → 写」在真产物上拿到逐字节读数，
//! 后面每迁一段（表达式池、语句池）都能用同一把尺子量，不必等整条链跑通。

use erbcc_rs::container;
use erbcc_rs::rewrite::{self, SectionMode};
use erbcc_rs::sidecars::{self, IdxReport, MetaReport};
use erbcc_rs::types::{EXIT_FAIL, EXIT_OK, EXIT_USAGE};
use std::path::{Path, PathBuf};
use std::process::ExitCode;

/// 写 stdout 并忽略「管道被关」（`| head` 之类）：闸工具的结论不该因为下游截断而 panic。
macro_rules! say {
    ($($arg:tt)*) => {{
        use std::io::Write;
        let _ = writeln!(std::io::stdout(), $($arg)*);
    }};
}

const USAGE: &str = "usage: rewrite <program.erbc|dir> [--out <path|dir>]";

fn args() -> Result<(PathBuf, Option<PathBuf>), String> {
    let mut argv = std::env::args().skip(1);
    let mut input: Option<PathBuf> = None;
    let mut out: Option<PathBuf> = None;
    while let Some(a) = argv.next() {
        match a.as_str() {
            "--out" => {
                out = Some(PathBuf::from(
                    argv.next().ok_or("--out 后面要给路径".to_string())?,
                ));
            }
            "-h" | "--help" => return Err(USAGE.to_string()),
            other if other.starts_with("--") => return Err(format!("未知参数 {other}")),
            other => {
                if input.is_some() {
                    return Err("只接受一个输入".to_string());
                }
                input = Some(PathBuf::from(other));
            }
        }
    }
    let input = input.ok_or(USAGE.to_string())?;
    Ok((input, out))
}

fn main() -> ExitCode {
    let (input, out_path) = match args() {
        Ok(v) => v,
        Err(m) => {
            eprintln!("{m}");
            return ExitCode::from(EXIT_USAGE as u8);
        }
    };
    let result = if input.is_dir() {
        run_dir(&input, out_path.as_deref())
    } else {
        run_container(&input, out_path.as_deref())
    };
    match result {
        Ok(true) => ExitCode::from(EXIT_OK as u8),
        Ok(false) => ExitCode::from(EXIT_FAIL as u8),
        Err(e) => {
            eprintln!("{}：{e}", input.display());
            ExitCode::from(EXIT_USAGE as u8)
        }
    }
}

fn run_container(input: &Path, out_path: Option<&Path>) -> Result<bool, String> {
    let bytes = std::fs::read(input).map_err(|e| format!("读不了：{e}"))?;
    let (out, report) = rewrite::rewrite(&bytes)?;

    say!(
        "version={} flags=0x{:x} sections={}",
        report.version, report.flags, report.section_count
    );
    for s in &report.sections {
        let mode = match s.mode {
            SectionMode::Modeled => "重编码",
            SectionMode::RawCopy => "原样搬运",
        };
        let state = if !s.present {
            "缺段"
        } else if s.same {
            "相同"
        } else {
            "不同"
        };
        say!(
            "  段 {id} {name:<16} {mode} {state}  {len} 字节",
            id = s.id,
            name = s.name,
            len = s.body_len
        );
    }
    let (modeled, present) = report.modeled_counts();
    say!(
        "建模段 {modeled}/{present} 存在段；整份文件{}",
        if report.file_identical { "逐字节相同" } else { "不同" }
    );
    if let Some(off) = report.first_diff {
        say!("首个不同字节：{off}（{}）", describe(&bytes, off));
    }

    if let Some(p) = out_path {
        if p.is_dir() {
            let target = p.join(input.file_name().ok_or("输入没有文件名")?);
            std::fs::write(&target, &out).map_err(|e| format!("写不了 {}：{e}", target.display()))?;
        } else {
            std::fs::write(p, &out).map_err(|e| format!("写不了 {}：{e}", p.display()))?;
        }
    }

    Ok(report.file_identical && report.modeled_all_same())
}

fn run_dir(dir: &Path, out_dir: Option<&Path>) -> Result<bool, String> {
    let erbc = dir.join("program.erbc");
    let bytes = std::fs::read(&erbc).map_err(|e| format!("读不了 {}：{e}", erbc.display()))?;
    let (container_out, container_report) = rewrite::rewrite(&bytes)?;
    say!("[容器] {}", erbc.display());
    let (modeled, present) = container_report.modeled_counts();
    say!(
        "  建模段 {modeled}/{present} 存在段；整份{}",
        if container_report.file_identical { "逐字节相同" } else { "不同" }
    );

    let idx_path = dir.join("program.stmt.idx");
    let idx_bytes = std::fs::read(&idx_path).map_err(|e| format!("读不了 {}：{e}", idx_path.display()))?;
    let (idx_out, IdxReport { stmt_count, record_bytes, count_matches_program, crc_matches_program, same: idx_same }) =
        sidecars::rewrite_stmt_idx(&idx_bytes, &bytes)?;
    say!("[侧车] {}", idx_path.display());
    say!(
        "  语句 {stmt_count} 条 / 记录区 {record_bytes} 字节；配对={} CRC={} 重建={}",
        if count_matches_program { "条数一致" } else { "条数不符" },
        if crc_matches_program { "一致" } else { "不符" },
        if idx_same { "相同" } else { "不同" }
    );

    let meta_path = dir.join("program.meta");
    let meta_bytes = std::fs::read(&meta_path).map_err(|e| format!("读不了 {}：{e}", meta_path.display()))?;
    let (meta_out, MetaReport { lines, quoted_values, same: meta_same }) = sidecars::rewrite_meta(&meta_bytes)?;
    say!("[侧车] {}", meta_path.display());
    say!(
        "  {lines} 行 / 重建引号串 {quoted_values} 条；重建={}",
        if meta_same { "相同" } else { "不同" }
    );

    if let Some(d) = out_dir {
        std::fs::create_dir_all(d).map_err(|e| format!("建不了 {}：{e}", d.display()))?;
        for (name, data) in [
            ("program.erbc", &container_out),
            ("program.stmt.idx", &idx_out),
            ("program.meta", &meta_out),
        ] {
            let target = d.join(name);
            std::fs::write(&target, data).map_err(|e| format!("写不了 {}：{e}", target.display()))?;
        }
    }

    Ok(container_report.file_identical
        && container_report.modeled_all_same()
        && idx_same
        && count_matches_program
        && crc_matches_program
        && meta_same)
}

/// 首个不同偏移落在哪个段（用容器的段表口径，越界 = 文件尾之后）。
fn describe(bytes: &[u8], off: usize) -> String {
    let Ok(h) = container::parse(bytes) else {
        return "容器头已不可解析".to_string();
    };
    let ends = container::section_ends(&h, bytes.len());
    for (id, o) in h.offsets.iter().enumerate() {
        let Some(start) = o else { continue };
        let start = *start as usize;
        let end = ends.get(id).copied().unwrap_or(bytes.len());
        if off >= start && off < end {
            return format!("段 {}（{}）内 +{}", id, container::section_name(id), off - start);
        }
    }
    "段表之外（文件长度差异）".to_string()
}


