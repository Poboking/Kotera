//! `token-dump` —— 词法对拍夹具的 Rust 侧（CONTRACT-RUSTFULL §6.9）。
//!
//! 输出格式与 `erbcc dump-tokens`（Kotlin）逐字节可比：同一份语料分别跑两条腿，两份 TSV 直接
//! 比 sha 即判「逻辑行 / token / 错误位置」是否全等。因此这里**不追求可读性**，追求的是
//! 「一个字节都不许自己发明」：字段顺序、单空格分列、`quote()` 方言、SYMBOL 的 NUL 类型字符
//! 全部照 Kotlin 落。
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use erbcc_rs::lexer::{ErbLexer, flag, LexError, LexerConfig, LineKind};
use erbcc_rs::logical::{read_all_lines, LogicalLineReader};

pub fn run(args: &[String]) -> i32 {
    let mut root: Option<PathBuf> = None;
    let mut out: Option<PathBuf> = None;
    let mut limit: Option<usize> = None;
    let mut substr: Option<String> = None;
    let mut stats_only = false;
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
            "--stats" => {
                stats_only = true;
                i += 1;
            }
            a if a.starts_with("--") => {
                eprintln!("错误: dump-tokens 不认识的参数: {a}");
                return 2;
            }
            a => {
                root = Some(PathBuf::from(a));
                i += 1;
            }
        }
    }
    let Some(root) = root else {
        eprintln!("错误: dump-tokens 需要一个语料目录");
        return 2;
    };
    if !root.is_dir() {
        eprintln!("错误: dump-tokens 需要一个语料目录");
        return 2;
    }

    let t0 = std::time::Instant::now();
    let mut files: Vec<PathBuf> = Vec::new();
    collect(&root, substr.as_deref(), &mut files);

    files.sort_by_key(|p| rel_of(&root, p));

    if let Some(n) = limit {
        files.truncate(n);
    }


    let lexer = ErbLexer::new(LexerConfig::default());
    if stats_only {
        let mut lines = 0usize;
        let mut errs = 0usize;
        let (mut tok, mut lg) = (0usize, 0usize);
        for f in &files {
            let rel = rel_of(&root, f);
            match stats_file(&lexer, f, &rel) {
                Ok((l, t, e)) => {
                    lg += l;
                    tok += t;
                    errs += e;
                }
                Err(_) => errs += 1,
            }
            lines += 1;
        }
        eprintln!(
            "词法统计：{} 文件 / {} 逻辑行 / {} token / {} 处行级错误，用时 {:?}",
            lines, lg, tok, errs, t0.elapsed()
        );
        return 0;
    }

    let mut text = String::with_capacity(1 << 20);
    text.push_str("#format erbcc-dump-tokens 1\n");
    text.push_str("#lexer-config default flags=ALLOW_ASSIGNMENT|ALLOW_SINGLE_QUOTATION_STR\n");
    let mut tokens = 0usize;
    for f in &files {
        let rel = rel_of(&root, f);
        match dump_file(&lexer, f, &rel) {
            Ok((logical, body, n)) => {
                tokens += n;
                text.push_str(&format!("file {} {}\n", rel, logical));
                text.push_str(&body);
            }
            Err(e) => {
                text.push_str(&format!("file {} 0\nE 0 {} 0\n", rel, quote(&e)));
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
            eprintln!("已导出 {} 个文件 / {} 个 token → {}", files.len(), tokens, p.display());
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
        // 用 read_dir 自带的 file_type（Win32 FindFirstFile 的缓存字段），不要 `path.is_dir()`：
        // 后者每项各发一次 GetFileAttributes，实测 4,370 文件要 262 s（本机 D: 上单次 ~48 ms），
        // 换成缓存位后同一趟 <1 s。这个坑与 P0 读到的「CreateFile0 占 84%」是同一类。
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

/// `--stats` 用的极简通路：只词法、不拼文本。
fn stats_file(lexer: &ErbLexer, path: &Path, rel: &str) -> Result<(usize, usize, usize), String> {
    let bytes = fs::read(path).map_err(|e| format!("IOException: {e}"))?;
    let lines = read_all_lines(&bytes);
    let mut reader = LogicalLineReader::new(lines, rel);
    let (mut logical, mut tokens, mut errors) = (0usize, 0usize, 0usize);
    loop {
        let line = match reader.read_logical_line() {
            Ok(Some(l)) => l,
            Ok(None) => break,
            Err(_) => return Err("read".to_string()),
        };
        logical += 1;
        match tokens_of(lexer, &line.content, line.kind, line.line_no, rel) {
            Ok(ts) => tokens += ts.len(),
            Err(_) => errors += 1,
        }
    }
    reader.file_end();
    Ok((logical, tokens, errors))
}

fn rel_of(root: &Path, p: &Path) -> String {
    p.strip_prefix(root).unwrap_or(p).to_string_lossy().replace('\\', "/")
}

/// 一个文件的全部记录；返回（逻辑行数, 正文, token 数）。
fn dump_file(lexer: &ErbLexer, path: &Path, rel: &str) -> Result<(usize, String, usize), String> {
    let bytes = fs::read(path).map_err(|e| format!("IOException: {e}"))?;
    let lines = read_all_lines(&bytes);
    let mut reader = LogicalLineReader::new(lines, rel);
    let mut body = String::new();
    let mut logical = 0usize;
    let mut tokens = 0usize;
    loop {
        let line = match reader.read_logical_line() {
            Ok(Some(l)) => l,
            Ok(None) => break,
            Err(e) => return Err(e.to_string()),
        };
        logical += 1;
        body.push_str(&format!("L {} {} {}\n", line.line_no, line.kind.name(), quote(&line.content)));
        match tokens_of(lexer, &line.content, line.kind, line.line_no, rel) {
            Ok(ts) => {
                for (idx, t) in ts.iter().enumerate() {
                    body.push_str(&format!(
                        "T {} {} {} {} {} {}\n",
                        line.line_no,
                        idx,
                        t.type_char,
                        quote(&t.text),
                        t.line,
                        t.col
                    ));
                }
                tokens += ts.len();
            }
            Err(e) => {
                body.push_str(&format!("E {} {} {}\n", line.line_no, quote(&e.message), e.column));
            }
        }
    }
    reader.file_end();
    Ok((logical, body, tokens))
}

/// 与 Kotlin `DumpTokens.tokensOf` 同一口径：整行 `analyse` 用 `ALLOW_ASSIGNMENT|SINGLE_QUOTATION_STR`，
/// 标签行/`#` 行先把名字合成一个 Identifier token，再补其余 token。
fn tokens_of(
    lexer: &ErbLexer,
    content: &str,
    kind: LineKind,
    line_no: i32,
    filename: &str,
) -> Result<Vec<DumpToken>, LexError> {
    let flags = flag::ASSIGNMENT | flag::SINGLE_QUOTATION_STR;
    match kind {
        LineKind::Code => lexer
            .analyse(&mut erbcc_rs::lexer::CharStream::new(content), erbcc_rs::lexer::LexEnd::EoL, flags, line_no)
            .map(|ts| ts.iter().map(DumpToken::from_lexer).collect()),
        LineKind::FunctionLabel | LineKind::GotoLabel => {
            let (_, name, rest) = lexer.parse_label_line(content, line_no, filename)?;
            let mut out = vec![DumpToken { type_char: 'A', text: name, line: line_no, col: 1 }];
            out.extend(rest.iter().map(DumpToken::from_lexer));
            Ok(out)
        }
        LineKind::Sharp => {
            let (kw, rest) = lexer.parse_sharp_line(content, line_no, filename)?;
            let mut out = vec![DumpToken { type_char: 'A', text: kw, line: line_no, col: 1 }];
            out.extend(rest.iter().map(DumpToken::from_lexer));
            Ok(out)
        }
    }
}

struct DumpToken {
    type_char: char,
    text: String,
    line: i32,
    col: i32,
}

impl DumpToken {
    fn from_lexer(t: &erbcc_rs::lexer::Token) -> Self {
        Self { type_char: t.kind.type_char(), text: t.text.clone(), line: t.line, col: t.col }
    }
}

/// `DumpTokens.kt` 的 `quote()` —— 只转义 `"` `\` 与 C0 控制字符（`\uXXXX` 四位小写）。
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





