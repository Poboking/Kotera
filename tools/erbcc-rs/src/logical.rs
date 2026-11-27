//! 逻辑行读取 —— `core-vm/.../lexer/LogicalLineReader.kt` 的 Rust 移植（CONTRACT-RUSTFULL P2-1）。
//!
//! 负责「字节 → 物理行 → 逻辑行」：BOM 探测顺序、CR/LF/CRLF 拆行、`{}` 行连续块合并、
//! `[SKIPSTART/IF/…]` 预处理状态机、行分类。所有口径与 Kotlin 侧逐条对齐：
//! - BOM 顺序照 .NET `StreamReader.DetectEncoding`（`LogicalLineReader.kt:297-337`）。
//! - 无 BOM 时先严格 UTF-8，失败回退 windows-31j（WHATWG 的 `shift_jis` 即 CP932，
//!   含 NEC/IBM 扩展区，见 `LogicalLineReader.kt:339-353` 的注释）。
//! - 预处理是**有状态**的：`[IF 宏]` 只看宏名是否在表里，本夹具的宏表为空 ⇒ 一律 disabled。
//!
//! 警告（`LexWarning`）本层不收：dump 夹具只比「逻辑行 + token + 错误位置」，收警告属 P2-4。
use crate::lexer::{CharStream, ErbLexer, LexError, LexerConfig, LineKind};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LogicalLine {
    pub kind: LineKind,
    pub filename: String,
    pub line_no: i32,
    pub content: String,
}

/// 文件字节 → 物理行 —— `LogicalLineReader.readAllLines`（`LogicalLineReader.kt:312-337`）。
pub fn read_all_lines(bytes: &[u8]) -> Vec<String> {
    let text = if bytes.len() >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF {
        decode_utf8_or_cp932(&bytes[3..])
    } else if bytes.len() >= 4
        && bytes[0] == 0xFF
        && bytes[1] == 0xFE
        && bytes[2] == 0x00
        && bytes[3] == 0x00
    {
        decode_utf32(&bytes[4..], true)
    } else if bytes.len() >= 2 && bytes[0] == 0xFF && bytes[1] == 0xFE {
        decode_utf16(&bytes[2..], true)
    } else if bytes.len() >= 2 && bytes[0] == 0xFE && bytes[1] == 0xFF {
        decode_utf16(&bytes[2..], false)
    } else if bytes.len() >= 4
        && bytes[0] == 0x00
        && bytes[1] == 0x00
        && bytes[2] == 0xFE
        && bytes[3] == 0xFF
    {
        decode_utf32(&bytes[4..], false)
    } else {
        decode_utf8_or_cp932(bytes)
    };
    split_lines(&text)
}

fn decode_utf8_or_cp932(body: &[u8]) -> String {
    match encoding_rs::UTF_8.decode_without_bom_handling_and_without_replacement(body) {
        Some(s) => s.into_owned(),
        // spec: EncodingHandler.cs:37-40 —— 非 UTF-8 回退 CP932（windows-31j）。
        None => encoding_rs::SHIFT_JIS.decode_without_bom_handling(body).0.into_owned(),
    }
}

fn decode_utf16(body: &[u8], le: bool) -> String {
    let mut src = body.to_vec();
    if src.len() % 2 == 1 {
        src.push(0);
    }
    let (text, _) = if le {
        encoding_rs::UTF_16LE.decode_without_bom_handling(&src)
    } else {
        encoding_rs::UTF_16BE.decode_without_bom_handling(&src)
    };
    text.into_owned()
}

/// UTF-32 —— encoding_rs 没有这条；逐码位解，非标量落 U+FFFD（对齐 Java 的 REPLACE）。
fn decode_utf32(body: &[u8], le: bool) -> String {
    let mut out = String::new();
    let mut i = 0;
    while i + 4 <= body.len() {
        let cp = if le {
            u32::from_le_bytes([body[i], body[i + 1], body[i + 2], body[i + 3]])
        } else {
            u32::from_be_bytes([body[i], body[i + 1], body[i + 2], body[i + 3]])
        };
        out.push(char::from_u32(cp).unwrap_or('\u{FFFD}'));
        i += 4;
    }
    out
}

/// 按 CR / LF / CRLF 拆行 —— `LogicalLineReader.splitLines`（`:359-381`）。
pub fn split_lines(text: &str) -> Vec<String> {
    let chars: Vec<char> = text.chars().collect();
    let mut result = Vec::new();
    let mut start = 0usize;
    let mut i = 0usize;
    while i < chars.len() {
        match chars[i] {
            '\r' => {
                result.push(chars[start..i].iter().collect());
                i += if i + 1 < chars.len() && chars[i + 1] == '\n' { 2 } else { 1 };
                start = i;
            }
            '\n' => {
                result.push(chars[start..i].iter().collect());
                i += 1;
                start = i;
            }
            _ => i += 1,
        }
    }
    if start < chars.len() {
        result.push(chars[start..].iter().collect());
    }
    result
}

/// 预处理状态 —— `PreprocessorState`（`LogicalLineReader.kt:28-140`）。
struct Preprocessor {
    macro_keys: Vec<String>,
    skip: bool,
    done: bool,
    disabled: bool,
    disabled_stack: Vec<bool>,
    done_stack: Vec<bool>,
    pp_match: Vec<String>,
}

impl Preprocessor {
    fn new(macro_names: &[String]) -> Self {
        Self {
            macro_keys: macro_names.iter().map(|s| s.to_uppercase()).collect(),
            skip: false,
            done: false,
            disabled: false,
            disabled_stack: Vec::new(),
            done_stack: Vec::new(),
            pp_match: Vec::new(),
        }
    }

    fn is_macro_defined(&self, token: &str) -> bool {
        self.macro_keys.contains(&token.to_uppercase())
    }

    /// `ErbLoader.cs:39-167 AddKeyWord`。警告路径只跳过、不影响 `disabled`（原版 warn 之后 return）。
    fn add_keyword(&mut self, token: &str, token2: &str) {
        match token {
            "SKIPSTART" => {
                if !token2.is_empty() || self.skip {
                    return;
                }
                self.pp_match.push("SKIPEND".to_string());
                self.disabled_stack.push(self.disabled);
                self.done_stack.push(self.done);
                self.skip = true;
                self.disabled = true;
                self.done = false;
            }
            "IF_DEBUG" => {
                if !token2.is_empty() {
                    return;
                }
                self.pp_match.push("ELSEIF".to_string());
                self.disabled_stack.push(self.disabled);
                self.done_stack.push(self.done);
                // debugMode 恒 false（`LexerConfig.kt:22`）⇒ disabled = true。
                self.disabled = true;
                self.done = !self.disabled;
            }
            "IF_NDEBUG" => {
                if !token2.is_empty() {
                    return;
                }
                self.pp_match.push("ELSEIF".to_string());
                self.disabled_stack.push(self.disabled);
                self.done_stack.push(self.done);
                self.disabled = false;
                self.done = !self.disabled;
            }
            "IF" => {
                if token2.is_empty() {
                    return;
                }
                self.pp_match.push("ELSEIF".to_string());
                self.disabled_stack.push(self.disabled);
                self.done_stack.push(self.done);
                self.disabled = !self.is_macro_defined(token2);
                self.done = !self.disabled;
            }
            "ELSEIF" => {
                if token2.is_empty() {
                    return;
                }
                if self.pp_match.pop().as_deref() != Some("ELSEIF") {
                    return;
                }
                self.pp_match.push("ELSEIF".to_string());
                self.disabled = self.done || !self.is_macro_defined(token2);
                self.done = self.done || !self.disabled;
            }
            "ELSE" => {
                if !token2.is_empty() {
                    return;
                }
                if self.pp_match.pop().as_deref() != Some("ELSEIF") {
                    return;
                }
                self.pp_match.push("ENDIF".to_string());
                self.disabled = self.done;
                self.done = true;
            }
            "SKIPEND" => {
                if !token2.is_empty() {
                    return;
                }
                let last = self.pp_match.pop().unwrap_or_default();
                if last != "SKIPEND" {
                    return;
                }
                self.skip = false;
                self.disabled = self.disabled_stack.pop().unwrap_or(false);
                self.done = self.done_stack.pop().unwrap_or(false);
            }
            "ENDIF" => {
                if !token2.is_empty() {
                    return;
                }
                let last = self.pp_match.pop().unwrap_or_default();
                if last != "ENDIF" && last != "ELSEIF" {
                    return;
                }
                self.disabled = self.disabled_stack.pop().unwrap_or(false);
                self.done = self.done_stack.pop().unwrap_or(false);
            }
            _ => return,
        }
        if self.skip {
            self.disabled = true;
        }
    }

    fn file_end(&mut self) {
        // 只发警告（`[$text]がありません`），不改变任何状态。
        if let Some(last) = self.pp_match.pop() {
            let _ = if last == "ELSEIF" { "ENDIF".to_string() } else { last };
        }
    }
}

/// 逻辑行读取器 —— `LogicalLineReader`（`LogicalLineReader.kt:153-291`）。
pub struct LogicalLineReader {
    lines: Vec<String>,
    filename: String,
    config: LexerConfig,
    lexer: ErbLexer,
    cur_no: i32,
    next_no: usize,
    pp: Preprocessor,
}

impl LogicalLineReader {
    pub fn new(physical_lines: Vec<String>, filename: &str) -> Self {
        Self::with_config(physical_lines, filename, LexerConfig::default(), Vec::new())
    }

    pub fn with_config(
        physical_lines: Vec<String>,
        filename: &str,
        config: LexerConfig,
        macro_names: Vec<String>,
    ) -> Self {
        // 首物理行只剥一个前导 BOM（`LogicalLineReader.kt:169`）。
        let lines: Vec<String> = physical_lines
            .into_iter()
            .enumerate()
            .map(|(i, s)| {
                if i == 0 {
                    s.strip_prefix('\u{FEFF}').map(|x| x.to_string()).unwrap_or(s)
                } else {
                    s
                }
            })
            .collect();
        let pp = Preprocessor::new(&macro_names);
        Self {
            lines,
            filename: filename.to_string(),
            lexer: ErbLexer::new(config.clone()),
            config,
            cur_no: 0,
            next_no: 0,
            pp,
        }
    }

    pub fn line_no(&self) -> i32 {
        self.cur_no
    }

    fn read_line(&mut self) -> Option<String> {
        self.cur_no = self.next_no as i32;
        let line = self.lines.get(self.next_no).cloned();
        if line.is_some() {
            self.next_no += 1;
        }
        line
    }

    /// 读取下一条逻辑行；文件结束返回 `Ok(None)`。
    pub fn read_logical_line(&mut self) -> Result<Option<LogicalLine>, LexError> {
        loop {
            let text = match self.read_line() {
                Some(t) => t,
                None => return Ok(None),
            };
            if text.is_empty() {
                continue;
            }
            let mut st = CharStream::new(&text);
            self.lexer.skip_white_space(&mut st);
            if st.eos() {
                continue;
            }

            // `{}` 行连续块 —— 只在 !disabled 时生效（`LogicalLineReader.kt:218-253`）。
            if !self.pp.disabled {
                if st.current() == '}' {
                    return Err(LexError::at_line(
                        "予期しない行連結終端記号'}'が見つかりました",
                        self.cur_no,
                    )
                    .with_file(&self.filename));
                }
                if st.current() == '{' {
                    if text.trim() != "{" {
                        return Err(LexError::at_line(
                            "行連結始端記号'{'の行に'{'以外の文字を含めることはできません",
                            self.cur_no,
                        )
                        .with_file(&self.filename));
                    }
                    let mut sb = String::new();
                    loop {
                        let t = match self.read_line() {
                            Some(t) => t,
                            None => {
                                return Err(LexError::at_line(
                                    "行連結始端記号'{'が使われましたが終端記号'}'が見つかりません",
                                    self.cur_no,
                                )
                                .with_file(&self.filename))
                            }
                        };
                        let span = t.trim_start();
                        if !span.is_empty() {
                            if span.starts_with('}') {
                                if span.trim() == "}" {
                                    break;
                                }
                                return Err(LexError::at_line(
                                    "行連結終端記号'}'の行に'}'以外の文字を含めることはできません",
                                    self.cur_no,
                                )
                                .with_file(&self.filename));
                            }
                            if span == "{" {
                                return Err(LexError::at_line(
                                    "予期しない行連結始端記号'{'が見つかりました",
                                    self.cur_no,
                                )
                                .with_file(&self.filename));
                            }
                        }
                        sb.push_str(&t);
                        // spec: EraStreamReader.cs:179 —— ReplaceContinuationBR 去掉 '"'（"\" \"" ⇒ 空格）
                        sb.push_str(&self.config.replace_continuation_br.replace('"', ""));
                    }
                    st = CharStream::new(&sb);
                    self.lexer.skip_white_space(&mut st);
                    // 块内全空白：原版照样把 EOS 流交回，ParseLine 的 EOS 分支 return null
                    // ⇒ 不产逻辑行（`LogicalLineReader.kt:248-251`）。
                    if st.eos() {
                        continue;
                    }
                }
            }

            // 预处理行 `[...]`
            if st.current() == '[' && st.next_char() != '[' {
                st.shift();
                let keyword = self.read_single_identifier(&mut st);
                self.lexer.skip_white_space(&mut st);
                let token2 = self.read_single_identifier(&mut st);
                let _bad = keyword.is_empty() || st.current() != ']';
                self.pp.add_keyword(&keyword, &token2);
                st.shift();
                let _trailing = !st.eos();
                continue;
            }

            if self.pp.disabled {
                continue;
            }
            return Ok(Some(self.classify(&st)));
        }
    }

    fn read_single_identifier(&self, st: &mut CharStream) -> String {
        let start = st.pointer();
        let mut i = start;
        while i < st.len() {
            if crate::lexer::is_identifier_terminator(st.char_at(i)) {
                break;
            }
            i += 1;
        }
        let text = st.substring(start, i - start);
        st.set_pointer(i);
        text
    }

    fn classify(&self, st: &CharStream) -> LogicalLine {
        let content = if st.pointer() >= st.len() { String::new() } else { st.substring(st.pointer(), st.len() - st.pointer()) };
        let kind = match st.current() {
            '#' => LineKind::Sharp,
            '@' => LineKind::FunctionLabel,
            '$' => LineKind::GotoLabel,
            _ => LineKind::Code,
        };
        LogicalLine { kind, filename: self.filename.clone(), line_no: self.cur_no, content }
    }

    pub fn file_end(&mut self) {
        self.pp.file_end();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn split_lines_matches_dotnet_readalllines() {
        assert_eq!(split_lines("a\n"), vec!["a"]);
        assert_eq!(split_lines("\n"), vec![""]);
        assert_eq!(split_lines(""), Vec::<String>::new());
        assert_eq!(split_lines("a\r\nb\rc"), vec!["a", "b", "c"]);
    }

    #[test]
    fn bom_is_stripped_once_and_cp932_fallback_works() {
        let utf8 = b"\xEF\xBB\xBFA=1\n";
        assert_eq!(read_all_lines(utf8), vec!["A=1"]);
        // CP932 'あ' = 0x82 0x60：严格 UTF-8 解不了，必须回退
        let cp932 = [0x82u8, 0xA0];
        assert_eq!(read_all_lines(&cp932), vec!["あ"]);
    }

    #[test]
    fn brace_block_merges_with_space() {
        // 块起始行的首个非空白字符必须是 `{`（`LogicalLineReader.kt:222-226`）
        let lines = vec!["{".to_string(), "A".to_string(), "}".to_string()];
        let mut r = LogicalLineReader::new(lines, "t.erb");
        let l = r.read_logical_line().unwrap().unwrap();
        assert_eq!(l.kind, LineKind::Code);
        assert_eq!(l.content, "A ", "续行分隔符 = 去掉引号后的一个空格（尾随空格保留，与 Kotlin 同形）");
    }

    #[test]
    fn empty_if_block_is_disabled_without_macro_table() {
        let lines = vec![
            "[IF TEST]".to_string(),
            "A = 1".to_string(),
            "[ENDIF]".to_string(),
            "B = 2".to_string(),
        ];
        let mut r = LogicalLineReader::new(lines, "t.erb");
        let l = r.read_logical_line().unwrap().unwrap();
        assert_eq!(l.content, "B = 2", "宏表为空 ⇒ IF 分支整体跳过");
        assert!(r.read_logical_line().unwrap().is_none());
    }
}


