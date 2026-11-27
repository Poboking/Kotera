//! `arg-dump` —— 参数构建层的 Rust 侧（P2-2b / B1 批，CONTRACT-RUSTFULL §6.13）。
//!
//! 与 Kotlin `erbcc dump-args` 逐字节可比。量的是「参数条数 / 类型闸 / 单值形态 /
//! 常量身份（`isConst`/`constStr`/`constInt`）」，错误面只比分类（`F`），不落错误文案。
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use erbcc_rs::args::{self, ArgBuild};
use erbcc_rs::expr::LooseTables;
use erbcc_rs::lexer::{LineKind, TokKind};
use erbcc_rs::logical::{read_all_lines, LogicalLineReader};
use erbcc_rs::stmt::{FunctionTables, StmtParser};

pub fn run(argv: &[String]) -> i32 {
    let mut root: Option<PathBuf> = None;
    let mut out: Option<PathBuf> = None;
    let mut limit: Option<usize> = None;
    let mut substr: Option<String> = None;
    let mut tables_path: Option<PathBuf> = None;
    let mut i = 0;
    while i < argv.len() {
        match argv[i].as_str() {
            "--out" => {
                out = argv.get(i + 1).map(PathBuf::from);
                i += 2;
            }
            "--limit" => {
                limit = argv.get(i + 1).and_then(|v| v.parse::<usize>().ok());
                i += 2;
            }
            "--substr" => {
                substr = argv.get(i + 1).cloned();
                i += 2;
            }
            "--funcodes" => {
                tables_path = argv.get(i + 1).map(PathBuf::from);
                i += 2;
            }
            "--help" | "-h" => {
                println!("usage: arg-dump <语料根> [--limit N] [--substr 过滤] [--out <tsv>] [--funcodes <tsv>]");
                return 0;
            }
            a if a.starts_with("--") => {
                eprintln!("错误: arg-dump 不认识的参数: {a}");
                return 2;
            }
            a => {
                root = Some(PathBuf::from(a));
                i += 1;
            }
        }
    }
    let Some(root) = root else {
        eprintln!("错误: arg-dump 需要一个语料目录");
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
    text.push_str("#format erbcc-dump-args 1\n");
    text.push_str("#parser loose args=B1\n");
    let (mut t_ok, mut t_fail, mut t_skip) = (0usize, 0usize, 0usize);
    for f in &files {
        match dump_file(&stmt_parser, &tables, &loose, f) {
            Ok((lines, ok, fail, skip, body)) => {
                t_ok += ok;
                t_fail += fail;
                t_skip += skip;
                text.push_str(&format!("file {} {} {} {} {}\n", rel_of(&root, f), lines, ok, fail, skip));
                text.push_str(&body);
            }
            Err(e) => {
                text.push_str(&format!("file {} 0 0 1 0\nE 0 0 \"{}\"\n", rel_of(&root, f), e));
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
                t_fail,
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
        let Ok(ft) = entry.file_type() else { continue };
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
    tables: &FunctionTables,
    loose: &LooseTables,
    path: &Path,
) -> Result<(usize, usize, usize, usize, String), String> {
    let _ = TokKind::Form;
    let bytes = fs::read(path).map_err(|e| format!("IOException: {e}"))?;
    let lines = read_all_lines(&bytes);
    let mut reader = LogicalLineReader::new(lines, "file");
    let (mut total, mut ok, mut fail, mut skip) = (0usize, 0usize, 0usize, 0usize);
    let mut body = String::new();
    loop {
        let line = match reader.read_logical_line() {
            Ok(Some(l)) => l,
            Ok(None) => break,
            Err(e) => return Err(e.to_string()),
        };
        total += 1;
        if line.kind != LineKind::Code {
            continue;
        }
        let rec = match stmt_parser.parse_code_line(&line.content, line.line_no) {
            Ok(r) => r,
            Err(_) => {
                skip += 1;
                continue;
            }
        };
        let Some(arg_type) = args::arg_type_of(tables, &rec.code) else {
            skip += 1;
            continue;
        };
        if !args::in_b1(&arg_type) && !args::in_b2(&arg_type, &rec.code) {
            skip += 1;
            continue;
        }
        // REPEAT 的 INT_EXPRESSION 会被改写成 SpForNextArgument（B4 批），本版整行跳过。
        if rec.code.eq_ignore_ascii_case("REPEAT") {
            skip += 1;
            continue;
        }
        match args::build(&rec.code, &arg_type, &rec.arg_text, line.line_no, loose) {
            ArgBuild::Ok(a) => {
                ok += 1;
                body.push_str(&args::describe_line(line.line_no, &a));
            }
            ArgBuild::Fail => {
                fail += 1;
                body.push_str(&format!("F {}\n", line.line_no));
            }
            ArgBuild::Skip => skip += 1,
        }
    }
    reader.file_end();
    Ok((total, ok, fail, skip, body))
}

fn main() {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    std::process::exit(run(&argv));
}
