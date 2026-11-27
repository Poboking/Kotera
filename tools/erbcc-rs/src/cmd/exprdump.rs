//! `expr-dump` —— 表达式层对拍夹具的 Rust 侧（CONTRACT-RUSTFULL §6.12，P2-2b/c 前置面）。
//!
//! 与 Kotlin `erbcc dump-expr` 逐字节可比：同一份语料两条腿各跑一次，两份 TSV 直接比 sha。
//! 量的是「参数原文 → 表达式列表」的骨架（节点种类 / 结合性 / 下标补全 / 函数返回类型），
//! 参数构造规则（P2-2b）另版夹具再量。
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use erbcc_rs::expr::{ExprParser, LooseTables};
use erbcc_rs::lexer::{flag, CharStream, ErbLexer, LexEnd, LexerConfig, LineKind, TokKind};
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
                println!("usage: expr-dump <语料根> [--limit N] [--substr 过滤] [--out <tsv>] [--funcodes <tsv>]");
                return 0;
            }
            a if a.starts_with("--") => {
                eprintln!("错误: expr-dump 不认识的参数: {a}");
                return 2;
            }
            a => {
                root = Some(PathBuf::from(a));
                i += 1;
            }
        }
    }
    let Some(root) = root else {
        eprintln!("错误: expr-dump 需要一个语料目录");
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
    let loose = LooseTables::from_tables(&tables);
    let stmt_parser = StmtParser::new(&tables).with_scoped_variables(false);

    let mut files: Vec<PathBuf> = Vec::new();
    collect(&root, substr.as_deref(), &mut files);
    files.sort_by_key(|p| rel_of(&root, p));
    if let Some(n) = limit {
        files.truncate(n);
    }

    let mut text = String::with_capacity(1 << 20);
    text.push_str("#format erbcc-dump-expr 1\n");
    text.push_str("#parser loose exprConfig=systemNoTarget:false,compatiRAND:false\n");
    let (mut t_ok, mut t_err, mut t_skip) = (0usize, 0usize, 0usize);
    for f in &files {
        let rel = rel_of(&root, f);
        match dump_file(&stmt_parser, &loose, f) {
            Ok((lines, ok, err, skip, body)) => {
                t_ok += ok;
                t_err += err;
                t_skip += skip;
                text.push_str(&format!("file {} {} {} {} {}\n", rel, lines, ok, err, skip));
                text.push_str(&body);
            }
            Err(e) => {
                text.push_str(&format!("file {} 0 0 1 0\nE 0 0 \"{}\"\n", rel, e));
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
                "已导出 {} 个文件 / 成功 {} / 失败 {} / 跳过 {} → {}",
                files.len(),
                t_ok,
                t_err,
                t_skip,
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
    stmt_parser: &StmtParser<'_>,
    loose: &LooseTables,
    path: &Path,
) -> Result<(usize, usize, usize, usize, String), String> {
    let lexer = ErbLexer::new(LexerConfig::default());
    let bytes = fs::read(path).map_err(|e| format!("IOException: {e}"))?;
    let lines = read_all_lines(&bytes);
    let mut reader = LogicalLineReader::new(lines, "file");
    let (mut total, mut ok, mut err, mut skip) = (0usize, 0usize, 0usize, 0usize);
    let mut body = String::new();
    loop {
        let line = match reader.read_logical_line() {
            Ok(Some(l)) => l,
            Ok(None) => break,
            Err(e) => return Err(e.to_string()),
        };
        total += 1;
        if line.kind == LineKind::Code {
            let rec = match stmt_parser.parse_code_line(&line.content, line.line_no) {
                Ok(r) => r,
                Err(e) => {
                    // 分派层失败的行不进表达式面（Kotlin 侧 `probeCodeLine` 抛错同样计 skip）。
                    // 两侧都必须计 skip：漏计会让文件头的四元组对不上，全量 sha 永远差一截。
                    let _ = e;
                    skip += 1;
                    continue;
                }
            };
            let text = rec.arg_text;
            if text.is_empty() {
                skip += 1;
                continue;
            }
            let mut st = CharStream::new(&text);
            let tokens = match lexer.analyse(&mut st, LexEnd::EoL, flag::NONE, line.line_no) {
                Ok(t) => t,
                Err(e) => {
                    err += 1;
                    body.push_str(&format!(
                        "E {} {} {}\n",
                        line.line_no,
                        e.column,
                        quote(&e.message)
                    ));
                    continue;
                }
            };
            if tokens.iter().any(|t| t.kind == TokKind::Form) {
                skip += 1;
                continue;
            }
            let mut p = ExprParser::new(&tokens, loose);
            match p.reduce_arguments(erbcc_rs::expr::ArgsEnd::Eol) {
                Ok(list) => {
                    ok += 1;
                    let joined: Vec<String> = list
                        .into_iter()
                        .map(|e| match e {
                            Some(e) => e.describe(),
                            None => "_".to_string(),
                        })
                        .collect();
                    body.push_str(&format!("X {} {}\n", line.line_no, joined.join("|")));
                }
                Err(e) => {
                    err += 1;
                    body.push_str(&format!(
                        "E {} {} {}\n",
                        line.line_no,
                        e.col,
                        quote(&e.message)
                    ));
                }
            }
        }
    }
    reader.file_end();
    Ok((total, ok, err, skip, body))
}

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
