//! `stmt-dump` —— 语句分派对拍夹具的 Rust 侧（CONTRACT-RUSTFULL §6.10 P2-2a）。
//!
//! 与 Kotlin `erbcc dump-stmt` 逐字节可比：同一份语料两条腿各跑一次，两份 TSV 直接比 sha。
//! 本夹具只量**结构面**（语句类名 / 语句码 / 参数形态 / 分派特化 / 原文）；参数树留 P2-2b。
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use erbcc_rs::logical::{read_all_lines, LogicalLineReader};
use erbcc_rs::stmt::{FunctionTables, StmtParser};

pub fn run(args: &[String]) -> i32 {
    let mut root: Option<PathBuf> = None;
    let mut out: Option<PathBuf> = None;
    let mut limit: Option<usize> = None;
    let mut substr: Option<String> = None;
    let mut tables_path: Option<PathBuf> = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--out" => {
                out = args.get(i + 1).map(PathBuf::from);
                i += 2;
            }
            "--limit" => {
                limit = args.get(i + 1).and_then(|v| v.parse::<usize>().ok());
                i += 2;
            }
            "--substr" => {
                substr = args.get(i + 1).cloned();
                i += 2;
            }
            "--funcodes" => {
                tables_path = args.get(i + 1).map(PathBuf::from);
                i += 2;
            }
            "--help" | "-h" => {
                println!("usage: stmt-dump <语料根> [--limit N] [--substr 过滤] [--out <tsv>] [--funcodes <tsv>]");
                return 0;
            }
            a if a.starts_with("--") => {
                eprintln!("错误: stmt-dump 不认识的参数: {a}");
                return 2;
            }
            a => {
                root = Some(PathBuf::from(a));
                i += 1;
            }
        }
    }
    let Some(root) = root else {
        eprintln!("错误: stmt-dump 需要一个语料目录");
        return 2;
    };
    let tables_path = tables_path.unwrap_or_else(|| {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/data/funcodes.tsv")
    });
    let tables_text = match fs::read_to_string(&tables_path) {
        Ok(t) => t,
        Err(e) => {
            eprintln!("错误: 读 {} 失败: {e}", tables_path.display());
            return 2;
        }
    };
    let tables = match FunctionTables::from_tsv(&tables_text) {
        Ok(t) => t,
        Err(e) => {
            eprintln!("错误: {e}");
            return 2;
        }
    };

    let mut files: Vec<PathBuf> = Vec::new();
    collect(&root, substr.as_deref(), &mut files);
    files.sort_by_key(|p| rel_of(&root, p));
    if let Some(n) = limit {
        files.truncate(n);
    }

    let parser = StmtParser::new(&tables);
    let mut text = String::with_capacity(1 << 20);
    text.push_str("#format erbcc-dump-stmt 1\n");
    text.push_str("#parser loose stmtConfig=useScopedVariableInstruction:false\n");
    let (mut total_stmts, mut total_errs) = (0usize, 0usize);
    for f in &files {
        let rel = rel_of(&root, f);
        match dump_file(&parser, f, &rel) {
            Ok((logical, stmts, errs, body)) => {
                total_stmts += stmts;
                total_errs += errs;
                text.push_str(&format!("file {} {} {} {}\n", rel, logical, stmts, errs));
                text.push_str(&body);
            }
            Err(e) => {
                text.push_str(&format!("file {} 0 0 1\nE 0 0 \"\" \"{}\"\n", rel, e));
            }
        }
    }

    match out {
        None => {
            let stdout = std::io::stdout();
            let mut lock = stdout.lock();
            let _ = lock.write_all(text.as_bytes());
        }
        Some(p) => {
            if let Some(parent) = p.parent() {
                let _ = fs::create_dir_all(parent);
            }
            if let Err(e) = fs::write(&p, text.as_bytes()) {
                eprintln!("错误: 写 {} 失败: {e}", p.display());
                return 2;
            }
            eprintln!(
                "已导出 {} 个文件 / {} 条语句 / {} 处错误 → {}",
                files.len(),
                total_stmts,
                total_errs,
                p.display()
            );
        }
    }
    0
}

fn collect(dir: &Path, substr: Option<&str>, out: &mut Vec<PathBuf>) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let Ok(ft) = entry.file_type() else {
            continue;
        };
        if ft.is_dir() {
            collect(&path, substr, out);
            continue;
        }
        if !ft.is_file() {
            continue;
        }
        let ext_ok = path
            .extension()
            .and_then(|e| e.to_str())
            .map(|e| e.eq_ignore_ascii_case("erb") || e.eq_ignore_ascii_case("erh"))
            .unwrap_or(false);
        if !ext_ok {
            continue;
        }
        if let Some(s) = substr {
            if !path.to_string_lossy().replace('\\', "/").contains(s) {
                continue;
            }
        }
        out.push(path);
    }
}

fn rel_of(root: &Path, p: &Path) -> String {
    p.strip_prefix(root).unwrap_or(p).to_string_lossy().replace('\\', "/")
}

fn dump_file(
    parser: &StmtParser<'_>,
    path: &Path,
    rel: &str,
) -> Result<(usize, usize, usize, String), String> {
    let bytes = fs::read(path).map_err(|e| format!("IOException: {e}"))?;
    let lines = read_all_lines(&bytes);
    let mut reader = LogicalLineReader::new(lines, rel);
    let (mut logical, mut stmts, mut errs) = (0usize, 0usize, 0usize);
    let mut body = String::new();
    loop {
        let line = match reader.read_logical_line() {
            Ok(Some(l)) => l,
            Ok(None) => break,
            Err(e) => return Err(e.to_string()),
        };
        logical += 1;
        match parser.parse_logical_line(&line.content, line.kind, line.line_no) {
            Ok(rec) => {
                stmts += 1;
                // 标签行/`#` 行的原文不做 trimStart（Kotlin 对这两类行原样落 raw）；
                // CODE 行落 trimStart 后的内容（Kotlin `parseCodeLine` 的 content）。
                let raw = if line.kind == erbcc_rs::lexer::LineKind::Code {
                    line.content.trim_start_matches([' ', '\t'])
                } else {
                    line.content.as_str()
                };
                body.push_str(&format!(
                    "S {} {} {} {} {} {}\n",
                    line.line_no,
                    rec.class,
                    rec.code,
                    quote(&rec.arg_text),
                    rec.extra,
                    quote(raw)
                ));
            }
            Err(e) => {
                errs += 1;
                body.push_str(&format!(
                    "E {} {} {} {}\n",
                    e.line,
                    e.col,
                    quote(&e.name),
                    quote(&e.message)
                ));
            }
        }
    }
    reader.file_end();
    Ok((logical, stmts, errs, body))
}

/// `DumpStmt.kt` 的 `quote()` —— 与 `dump-tokens` 同一方言。
fn quote(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    std::process::exit(run(&args));
}
