//! ERB 词法器 —— `core-vm/.../lexer/LexicalAnalyzer.kt` 的 Rust 移植（CONTRACT-RUSTFULL §6.4 P2-1）。
//!
//! 逐函数对齐 Kotlin 侧，**不重新设计**：`CharStream`、`Token`、运算符表、整数/浮点读入器、
//! 字符串与格式化字符串、`\@` 三项条件式、宏展开的上限口径，全部照抄。这样做的原因只有一个：
//! P2-1 的出口判据是「与 Kotlin 腿逐 token 相同（含错误位置）」，任何「更合理」的改写都会在
//! 11,469,764 个 token 上放大成差异，且差异无法归因。
//!
//! 已知与 Kotlin 的**等价性依赖**（对拍会直接暴露，不要靠注释掩盖）：
//! 1. `Char::isDigit` —— Kotlin 走 `Character.isDigit`（Unicode Nd）。Rust 没有稳定 API 的 Nd
//!    判定，这里用「ASCII 数字 + 全角数字（U+FF10..U+FF19）」近似；语料里出现别的 Nd 数字时
//!    会差在「是否把该字符当数字消费」。
//! 2. 警告（`LexWarning`）本层不收：dump 夹具不比较警告，只有错误。收警告属 P2-4 装载期的事。
//! 3. `String.trim*` 的空白集：Kotlin `Char.isWhitespace` 与 Rust `str::trim` 在 U+0085 与
//!    U+001C..U+001F 上不同；这些字符不可能出现在 `{}` 块分行标记行上（该行必须是纯 `{`/`}`）。
use std::collections::BTreeMap;
use std::fmt;

pub const EOS: char = '\u{0}';

/// 词法分析结束条件 —— spec: LexEndWith.cs / `Enums.kt:6-8`。
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum LexEnd {
    None,
    EoL,
    Operator,
    Question,
    Percent,
    RightCurlyBrace,
    Comma,
    GreaterThan,
}

/// 格式化字符串结束条件 —— `Enums.kt:30-32`。
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum FormEnd {
    None,
    EoL,
    DoubleQuotation,
    Sharp,
    YenAt,
    Comma,
    LeftParenBracketCommaSemicolon,
}

/// 字符串字面量结束条件 —— `Enums.kt:23-25`。
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum StrEnd {
    DoubleQuotation,
    SingleQuotation,
    Comma,
    LeftParenBracketCommaSemicolon,
}

pub mod flag {
    pub const NONE: u8 = 0;
    pub const PRINT_V: u8 = 1;
    pub const ASSIGNMENT: u8 = 2;
    pub const SINGLE_QUOTATION_STR: u8 = 4;
}

/// 逻辑行种类 —— `Enums.kt:54-63`。`name()` 与 Kotlin 枚举名逐字相同（dump 里直接落这个名字）。
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum LineKind {
    Code,
    FunctionLabel,
    GotoLabel,
    Sharp,
}

impl LineKind {
    pub fn name(&self) -> &'static str {
        match self {
            LineKind::Code => "CODE",
            LineKind::FunctionLabel => "FUNCTION_LABEL",
            LineKind::GotoLabel => "GOTO_LABEL",
            LineKind::Sharp => "SHARP",
        }
    }
}

/// Token 类型与 `typeChar` —— `Enums.kt:68-89`（`SYMBOL` 的 typeChar 是 U+0000，dump 里落成该字符）。
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum TokKind {
    Ident,
    Int,
    Float,
    Str,
    Op,
    Sym,
    Form,
}

impl TokKind {
    pub fn type_char(&self) -> char {
        match self {
            TokKind::Ident => 'A',
            TokKind::Int => '0',
            TokKind::Float => '1',
            TokKind::Str => '"',
            TokKind::Op => '=',
            TokKind::Sym => EOS,
            TokKind::Form => 'F',
        }
    }
}

#[derive(Clone, PartialEq, Eq, Debug)]
pub struct Token {
    pub kind: TokKind,
    pub text: String,
    pub line: i32,
    pub col: i32,
}

impl Token {
    fn ident(code: &str, line: i32, col: i32) -> Self {
        Self { kind: TokKind::Ident, text: code.to_string(), line, col }
    }
    fn int(v: i64, line: i32, col: i32) -> Self {
        Self { kind: TokKind::Int, text: v.to_string(), line, col }
    }
    fn float(raw: &str, line: i32, col: i32) -> Self {
        Self { kind: TokKind::Float, text: raw.to_string(), line, col }
    }
    /// `Token.StringLiteral.text = "\"$value\""`（`Token.kt:57`）—— 引号是文本的一部分。
    fn string(value: &str, line: i32, col: i32) -> Self {
        Self { kind: TokKind::Str, text: format!("\"{}\"", value), line, col }
    }
    fn op(code: OperatorCode, line: i32, col: i32) -> Self {
        Self { kind: TokKind::Op, text: code.name().to_string(), line, col }
    }
    fn sym(c: char, line: i32, col: i32) -> Self {
        Self { kind: TokKind::Sym, text: c.to_string(), line, col }
    }
    fn form(line: i32, col: i32) -> Self {
        Self { kind: TokKind::Form, text: "<FORM>".to_string(), line, col }
    }
}

/// 运算符全集 —— `Enums.kt:38-48`；`name()` 与 Kotlin 枚举名逐字相同（dump 里落这个名字）。
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum OperatorCode {
    Null,
    Plus,
    Minus,
    Mult,
    Div,
    Mod,
    Equal,
    Greater,
    Less,
    GreaterEqual,
    LessEqual,
    NotEqual,
    Increment,
    Decrement,
    And,
    Or,
    Xor,
    Nand,
    Nor,
    BitAnd,
    BitOr,
    BitXor,
    Not,
    BitNot,
    RightShift,
    LeftShift,
    TernaryA,
    TernaryB,
    Assignment,
    AssignmentStr,
}

impl OperatorCode {
    pub fn name(&self) -> &'static str {
        match self {
            OperatorCode::Null => "NULL",
            OperatorCode::Plus => "Plus",
            OperatorCode::Minus => "Minus",
            OperatorCode::Mult => "Mult",
            OperatorCode::Div => "Div",
            OperatorCode::Mod => "Mod",
            OperatorCode::Equal => "Equal",
            OperatorCode::Greater => "Greater",
            OperatorCode::Less => "Less",
            OperatorCode::GreaterEqual => "GreaterEqual",
            OperatorCode::LessEqual => "LessEqual",
            OperatorCode::NotEqual => "NotEqual",
            OperatorCode::Increment => "Increment",
            OperatorCode::Decrement => "Decrement",
            OperatorCode::And => "And",
            OperatorCode::Or => "Or",
            OperatorCode::Xor => "Xor",
            OperatorCode::Nand => "Nand",
            OperatorCode::Nor => "Nor",
            OperatorCode::BitAnd => "BitAnd",
            OperatorCode::BitOr => "BitOr",
            OperatorCode::BitXor => "BitXor",
            OperatorCode::Not => "Not",
            OperatorCode::BitNot => "BitNot",
            OperatorCode::RightShift => "RightShift",
            OperatorCode::LeftShift => "LeftShift",
            OperatorCode::TernaryA => "Ternary_a",
            OperatorCode::TernaryB => "Ternary_b",
            OperatorCode::Assignment => "Assignment",
            OperatorCode::AssignmentStr => "AssignmentStr",
        }
    }
}

/// 词法错误 —— `LexError.kt:21-50`。`column` 缺省 -1（与 Kotlin 一致：末尾的括号/方括号错没有列号）。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LexError {
    pub message: String,
    pub filename: Option<String>,
    pub line_no: i32,
    pub column: i32,
}

impl LexError {
    pub fn new(message: impl Into<String>, line_no: i32, column: i32) -> Self {
        Self { message: message.into(), filename: None, line_no, column }
    }
    pub fn at_line(message: impl Into<String>, line_no: i32) -> Self {
        Self::new(message, line_no, -1)
    }
    pub fn with_file(mut self, filename: &str) -> Self {
        self.filename = Some(filename.to_string());
        self
    }
}

impl fmt::Display for LexError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "LexError: {} (line={}, col={})", self.message, self.line_no, self.column)
    }
}

/// 单个逻辑行上的字符游标 —— `CharStream.kt`。末尾用 `\0` 表示（`CharStream.kt:75`）。
#[derive(Clone)]
pub struct CharStream {
    chars: Vec<char>,
    pointer: usize,
    /// `u16_off[i]` = 第 i 个字符之前的 UTF-16 code unit 数。
    ///
    /// 为什么要有它：Kotlin 的 `CharStream.currentPosition` 是 **String 下标**（UTF-16 code unit），
    /// 而 Rust 的 `Vec<char>` 下标是**码位**。语料里有 emoji（`💀` 这类代理对），两者在同一行内
    /// 会差 1，进而让 token 的 `col` 在 610 个 token 上对不上（2026-11-27 全量对拍实测）。
    u16_off: Vec<usize>,
}

impl CharStream {
    pub fn new(source: &str) -> Self {
        let chars: Vec<char> = source.chars().collect();
        let mut u16_off = Vec::with_capacity(chars.len() + 1);
        let mut acc = 0usize;
        for c in &chars {
            u16_off.push(acc);
            acc += c.len_utf16();
        }
        u16_off.push(acc);
        Self { chars, pointer: 0, u16_off }
    }
    /// 当前位置的列号 —— 与 Kotlin `st.currentPosition` 同口径（UTF-16 code unit）。
    pub fn col(&self) -> i32 {
        self.col_at(self.pointer)
    }
    pub fn col_at(&self, i: usize) -> i32 {
        self.u16_off[i.min(self.chars.len())] as i32
    }
    pub fn pointer(&self) -> usize {
        self.pointer
    }
    pub fn set_pointer(&mut self, p: usize) {
        self.pointer = p;
    }
    pub fn len(&self) -> usize {
        self.chars.len()
    }
    pub fn is_empty(&self) -> bool {
        self.chars.is_empty()
    }
    pub fn current(&self) -> char {
        self.chars.get(self.pointer).copied().unwrap_or(EOS)
    }
    pub fn next_char(&self) -> char {
        self.chars.get(self.pointer + 1).copied().unwrap_or(EOS)
    }
    pub fn eos(&self) -> bool {
        self.pointer >= self.chars.len()
    }
    pub fn shift(&mut self) {
        self.pointer += 1;
    }
    pub fn jump(&mut self, n: usize) {
        self.pointer += n;
    }
    pub fn seek_end(&mut self) {
        self.pointer = self.chars.len();
    }
    pub fn char_at(&self, i: usize) -> char {
        self.chars.get(i).copied().unwrap_or(EOS)
    }
    /// `CharStream.substring(start, length)` —— **注意第二参是长度**（`CharStream.kt:35-39`）。
    pub fn substring(&self, start: usize, length: usize) -> String {
        if start >= self.chars.len() || length == 0 {
            return String::new();
        }
        let end = start.saturating_add(length).min(self.chars.len());
        self.chars[start..end].iter().collect()
    }
    pub fn current_equal_to(&self, other: &str) -> bool {
        let mut i = self.pointer;
        for c in other.chars() {
            if self.chars.get(i).copied().unwrap_or(EOS) != c {
                return false;
            }
            i += 1;
        }
        true
    }
    pub fn triple_symbol(&self) -> bool {
        if self.pointer + 3 > self.chars.len() {
            return false;
        }
        self.chars[self.pointer] == self.chars[self.pointer + 1]
            && self.chars[self.pointer] == self.chars[self.pointer + 2]
    }
}

/// 词法阶段配置 —— `LexerConfig.kt:12-46`。缺省值与 Kotlin `LexerConfig()` 一致。
#[derive(Clone, Debug)]
pub struct LexerConfig {
    pub system_allow_full_space: bool,
    pub system_ignore_triple_symbol: bool,
    pub debug_mode: bool,
    pub replace_continuation_br: String,
    pub macros: BTreeMap<String, String>,
    pub macro_ignore_case: bool,
}

impl Default for LexerConfig {
    fn default() -> Self {
        Self {
            system_allow_full_space: true,
            system_ignore_triple_symbol: false,
            debug_mode: false,
            replace_continuation_br: "\" \"".to_string(),
            macros: BTreeMap::new(),
            macro_ignore_case: true,
        }
    }
}

/// 全角空格条目的显示名（`LexerConfig.kt:56`）—— 只在该条配置为 NO 时才进报错文案。
/// TODO(verify): `ConfigSpecTable` 的 jpLabel/enLabel 未导表，此处沿用 Kotlin 注释里写明的官方文案
/// （`ConfigData.cs:101`）；P2-4 接配置表时以导出的真值替换。
pub const FULL_SPACE_CONFIG_NAME: &str =
    "全角スペースをホワイトスペースに含める/Whitespace includes full-width space";

pub fn is_unicode_digit(c: char) -> bool {
    c.is_ascii_digit() || ('\u{FF10}'..='\u{FF19}').contains(&c)
}

fn is_hex_digit(c: char) -> bool {
    c.is_ascii_hexdigit()
}

/// 标识符终止字符全集 —— `LexicalAnalyzer.kt:39-40`（唯一声明处，Rust 侧同表）。
pub fn is_identifier_terminator(c: char) -> bool {
    matches!(
        c,
        ' ' | '\u{3000}'
            | '.'
            | '+'
            | '-'
            | '*'
            | '/'
            | '%'
            | '='
            | '!'
            | '<'
            | '>'
            | '|'
            | '&'
            | '^'
            | '~'
            | '?'
            | '#'
            | ')'
            | '}'
            | ']'
            | ','
            | ':'
            | '('
            | '{'
            | '['
            | '$'
            | '\\'
            | '\''
            | '"'
            | '@'
            | ';'
            | '\t'
    )
}

/// `(long)(double)` 的 x64 口径 —— `LexicalAnalyzer.kt:18-22`（NaN/越界一律给 `i64::MIN`）。
pub fn long_from_scaled_double(d: f64) -> i64 {
    if d.is_nan() || d >= 9.223372036854776e18 {
        i64::MIN
    } else {
        d as i64
    }
}

#[derive(Clone, Debug)]
pub struct StrForm {
    pub strs: Vec<String>,
    pub subs: Vec<SubWord>,
}

#[derive(Clone, Debug)]
pub enum SubWord {
    Percent(Vec<Token>),
    CurlyBrace(Vec<Token>),
    TripleSymbol(char),
    YenAt(Vec<Token>, Box<StrForm>, Option<Box<StrForm>>),
}

pub struct ErbLexer {
    config: LexerConfig,
}

impl ErbLexer {
    pub fn new(config: LexerConfig) -> Self {
        Self { config }
    }
    pub fn config(&self) -> &LexerConfig {
        &self.config
    }

    // ------------------------------------------------------------------
    // 标识符
    // ------------------------------------------------------------------

    fn read_single_identifier(&self, st: &mut CharStream) -> String {
        let start = st.pointer();
        let mut i = start;
        while i < st.len() {
            if is_identifier_terminator(st.char_at(i)) {
                break;
            }
            i += 1;
        }
        let text = st.substring(start, i - start);
        st.pointer = i;
        text
    }

    pub fn read_first_identifier(&self, st: &mut CharStream, line_no: i32) -> Result<Token, LexError> {
        let code = self.read_single_identifier(st);
        if code.is_empty() {
            return Err(LexError::new("不正な文字で行が始まっています", line_no, st.col()));
        }
        let col = st.col_at(st.pointer() - code.chars().count());
        Ok(Token::ident(&code, line_no, col))
    }

    // ------------------------------------------------------------------
    // 整数 / 浮点
    // ------------------------------------------------------------------

    pub fn read_int64(&self, st: &mut CharStream, ret_zero: bool, line_no: i32) -> Result<i64, LexError> {
        let start_pos = st.pointer();
        let mut from_base: u32 = 10;
        if st.current() == '0' {
            match st.next_char() {
                'X' | 'x' => {
                    from_base = 16;
                    st.shift();
                    st.shift();
                }
                'B' | 'b' => {
                    from_base = 2;
                    st.shift();
                    st.shift();
                }
                _ => {}
            }
        }
        if ret_zero && st.current() != '+' && st.current() != '-' && !is_unicode_digit(st.current()) {
            if from_base != 16 {
                return Ok(0);
            }
            if !is_hex_digit(st.current()) {
                return Ok(0);
            }
        }
        let value = self.read_digits(st, from_base, line_no)?;
        let exp_base: i32 = if st.current() == 'p' || st.current() == 'P' {
            2
        } else if st.current() == 'e' || st.current() == 'E' {
            10
        } else {
            0
        };
        let mut exp: i32 = 0;
        if exp_base != 0 {
            st.shift();
            exp = self.read_digits(st, from_base, line_no)? as i32;
        }
        let end_pos = st.pointer();
        if exp_base != 0 && exp != 0 {
            let d = value as f64 * (exp_base as f64).powi(exp);
            if d.is_nan() || d.is_infinite() || d > 9.223372036854776e18 || d < -9.223372036854776e18 {
                // 官方口径照抄：`LexicalAnalyzer.kt:154-157` 把**结束位置**当 substring 的长度传，
                // 再经越界钳制 ⇒ 报错文本是「数字起点到本行末尾」。
                let text = st.substring(start_pos, end_pos);
                return Err(LexError::new(
                    format!("\"{}\"は64ビット符号付き整数の範囲を超えています", text),
                    line_no,
                    st.col_at(start_pos),
                ));
            }
            return Ok(long_from_scaled_double(d));
        }
        Ok(value)
    }

    fn read_digits(&self, st: &mut CharStream, from_base: u32, line_no: i32) -> Result<i64, LexError> {
        let start_pos = st.pointer();
        if st.current() == '-' || st.current() == '+' {
            st.shift();
        }
        match from_base {
            10 => {
                while !st.eos() && is_unicode_digit(st.current()) {
                    st.shift();
                }
            }
            16 => {
                while !st.eos() && (is_unicode_digit(st.current()) || is_hex_digit(st.current())) {
                    st.shift();
                }
            }
            2 => {
                while !st.eos() {
                    if !is_unicode_digit(st.current()) {
                        break;
                    }
                    if st.current() != '0' && st.current() != '1' {
                        return Err(LexError::new(
                            "二進法表記の中で使用できない文字が使われています",
                            line_no,
                            st.col(),
                        ));
                    }
                    st.shift();
                }
            }
            _ => unreachable!("from_base 只可能是 2/10/16"),
        }
        let s = st.substring(start_pos, st.pointer() - start_pos);
        if from_base == 10 {
            // .NET long.Parse 只吃 ASCII 数字与符号；全角数字已被循环消费但解析不了。
            if s.is_empty()
                || s == "+"
                || s == "-"
                || s.chars().any(|c| c != '+' && c != '-' && !c.is_ascii_digit())
            {
                return Err(LexError::new(
                    format!("\"{}\"を整数値に変換できません", s),
                    line_no,
                    st.col_at(start_pos),
                ));
            }
            return parse_signed_decimal(&s).map_err(|_| {
                LexError::new(
                    format!("\"{}\"は64ビット符号付き整数の範囲を超えています", s),
                    line_no,
                    st.col_at(start_pos),
                )
            });
        }
        self.parse_non_decimal(&s, from_base, st.col_at(start_pos), line_no)
    }

    /// 基数 2/16 —— 逐条复刻 .NET `Convert.ToInt64(s, base)` 的四档行为（`LexicalAnalyzer.kt:205-246`）。
    fn parse_non_decimal(
        &self,
        s: &str,
        from_base: u32,
        start_col: i32,
        line_no: i32,
    ) -> Result<i64, LexError> {
        if s.is_empty() {
            return Err(LexError::new("数値として認識できません", line_no, start_col));
        }
        let body = s.strip_prefix('+').unwrap_or(s);
        if body.is_empty()
            || body.contains('-')
            || body.chars().any(|c| !c.is_ascii_digit() && !is_hex_digit(c))
        {
            return Err(LexError::new(
                format!("\"{}\"を整数値に変換できません", s),
                line_no,
                start_col,
            ));
        }
        parse_unsigned(body, from_base).map(|v| v as i64).map_err(|_| {
            LexError::new(
                format!("\"{}\"は64ビット符号付き整数の範囲を超えています", s),
                line_no,
                start_col,
            )
        })
    }

    fn read_number_token(&self, st: &mut CharStream, line_no: i32) -> Result<Token, LexError> {
        let start = st.pointer();
        let mut i = start;
        while i < st.len() && st.char_at(i).is_ascii_digit() {
            i += 1;
        }
        let is_float = i < st.len()
            && st.char_at(i) == '.'
            && i + 1 < st.len()
            && st.char_at(i + 1).is_ascii_digit();
        if !is_float {
            let v = self.read_int64(st, false, line_no)?;
            return Ok(Token::int(v, line_no, st.col_at(start)));
        }
        st.jump(i - start); // 整数部分
        st.shift(); // '.'
        while !st.eos() && st.current().is_ascii_digit() {
            st.shift();
        }
        if !st.eos() && (st.current() == 'e' || st.current() == 'E') {
            let after = st.next_char();
            let exp_ok = after.is_ascii_digit()
                || ((after == '+' || after == '-')
                    && st.pointer() + 2 < st.len()
                    && st.char_at(st.pointer() + 2).is_ascii_digit());
            if exp_ok {
                st.shift(); // 'e' / 'E'
                if st.current() == '+' || st.current() == '-' {
                    st.shift();
                }
                while !st.eos() && st.current().is_ascii_digit() {
                    st.shift();
                }
            }
        }
        let raw = st.substring(start, st.pointer() - start);
        let v: f64 = raw.parse().map_err(|_| {
            LexError::new(format!("\"{}\"を数値に変換できません", raw), line_no, st.col_at(start))
        })?;
        if v.is_infinite() || v.is_nan() {
            return Err(LexError::new(
                format!("\"{}\"を数値に変換できません", raw),
                line_no,
                st.col_at(start),
            ));
        }
        Ok(Token::float(&raw, line_no, st.col_at(start)))
    }

    // ------------------------------------------------------------------
    // 运算符
    // ------------------------------------------------------------------

    pub fn read_operator(
        &self,
        st: &mut CharStream,
        allow_assignment: bool,
        line_no: i32,
    ) -> Result<OperatorCode, LexError> {
        let c1 = st.current();
        st.shift();
        let c2 = st.current();
        let op = match c1 {
            '+' => {
                if c2 == '+' {
                    st.shift();
                    OperatorCode::Increment
                } else {
                    OperatorCode::Plus
                }
            }
            '-' => {
                if c2 == '-' {
                    st.shift();
                    OperatorCode::Decrement
                } else {
                    OperatorCode::Minus
                }
            }
            '*' => OperatorCode::Mult,
            '/' => OperatorCode::Div,
            '%' => OperatorCode::Mod,
            '=' => {
                if c2 == '=' {
                    st.shift();
                    OperatorCode::Equal
                } else if allow_assignment {
                    OperatorCode::Assignment
                } else {
                    return Err(LexError::new(
                        "予期しない代入演算子'='を発見しました(等価比較には'=='を使用してください)",
                        line_no,
                        st.col_at(st.pointer() - 1),
                    ));
                }
            }
            '!' => match c2 {
                '=' => {
                    st.shift();
                    OperatorCode::NotEqual
                }
                '&' => {
                    st.shift();
                    OperatorCode::Nand
                }
                '|' => {
                    st.shift();
                    OperatorCode::Nor
                }
                _ => OperatorCode::Not,
            },
            '<' => match c2 {
                '=' => {
                    st.shift();
                    OperatorCode::LessEqual
                }
                '<' => {
                    st.shift();
                    OperatorCode::LeftShift
                }
                _ => OperatorCode::Less,
            },
            '>' => match c2 {
                '=' => {
                    st.shift();
                    OperatorCode::GreaterEqual
                }
                '>' => {
                    st.shift();
                    OperatorCode::RightShift
                }
                _ => OperatorCode::Greater,
            },
            '|' => {
                if c2 == '|' {
                    st.shift();
                    OperatorCode::Or
                } else {
                    OperatorCode::BitOr
                }
            }
            '&' => {
                if c2 == '&' {
                    st.shift();
                    OperatorCode::And
                } else {
                    OperatorCode::BitAnd
                }
            }
            '^' => {
                if c2 == '^' {
                    st.shift();
                    OperatorCode::Xor
                } else {
                    OperatorCode::BitXor
                }
            }
            '~' => OperatorCode::BitNot,
            '?' => OperatorCode::TernaryA,
            '#' => OperatorCode::TernaryB,
            _ => {
                return Err(LexError::new(
                    format!("'{}'は演算子として認識できません", c1),
                    line_no,
                    st.col_at(st.pointer() - 1),
                ));
            }
        };
        Ok(op)
    }

    pub fn read_assignment_operator(
        &self,
        st: &mut CharStream,
        line_no: i32,
    ) -> Result<OperatorCode, LexError> {
        let c1 = st.current();
        st.shift();
        let c2 = st.current();
        let op = match c1 {
            '+' => match c2 {
                '+' => Some(OperatorCode::Increment),
                '=' => Some(OperatorCode::Plus),
                _ => None,
            },
            '-' => match c2 {
                '-' => Some(OperatorCode::Decrement),
                '=' => Some(OperatorCode::Minus),
                _ => None,
            },
            '*' => if c2 == '=' { Some(OperatorCode::Mult) } else { None },
            '/' => if c2 == '=' { Some(OperatorCode::Div) } else { None },
            '%' => if c2 == '=' { Some(OperatorCode::Mod) } else { None },
            '=' => {
                if c2 == '=' {
                    Some(OperatorCode::Equal)
                } else {
                    return Ok(OperatorCode::Assignment);
                }
            }
            '\'' => if c2 == '=' { Some(OperatorCode::AssignmentStr) } else { None },
            '<' => {
                if c2 == '<' {
                    st.shift();
                    if st.current() != '=' {
                        return Err(LexError::new(
                            "\"<\"は代入演算子として認識できません",
                            line_no,
                            st.col(),
                        ));
                    }
                    Some(OperatorCode::LeftShift)
                } else {
                    None
                }
            }
            '>' => {
                if c2 == '>' {
                    st.shift();
                    if st.current() != '=' {
                        return Err(LexError::new(
                            "\">\"は代入演算子として認識できません",
                            line_no,
                            st.col(),
                        ));
                    }
                    Some(OperatorCode::RightShift)
                } else {
                    None
                }
            }
            '|' => if c2 == '=' { Some(OperatorCode::BitOr) } else { None },
            '&' => if c2 == '=' { Some(OperatorCode::BitAnd) } else { None },
            '^' => if c2 == '=' { Some(OperatorCode::BitXor) } else { None },
            _ => None,
        };
        match op {
            Some(o) => {
                st.shift();
                Ok(o)
            }
            None => Err(LexError::new(
                format!("\"{}\"は代入演算子として認識できません", c1),
                line_no,
                st.col(),
            )),
        }
    }

    // ------------------------------------------------------------------
    // 空白
    // ------------------------------------------------------------------

    pub fn skip_all_space(&self, st: &mut CharStream) -> usize {
        let mut num = 0;
        while matches!(st.current(), '\t' | ' ' | '\u{3000}') {
            num += 1;
            st.shift();
        }
        num
    }

    pub fn is_white_space(&self, c: char) -> bool {
        matches!(c, ' ' | '\t' | '\u{3000}')
    }

    pub fn skip_white_space(&self, st: &mut CharStream) -> usize {
        let mut num = 0;
        loop {
            let c = st.current();
            if (c as u32) <= 32 {
                if c != '\t' && c != ' ' {
                    break;
                }
            } else if c == ';' {
                if self.config.debug_mode && st.current_equal_to(";#;") {
                    st.jump(3);
                    continue;
                }
                if st.current_equal_to(";!;") || st.current_equal_to(";^;") {
                    st.jump(3);
                    continue;
                }
                st.seek_end();
                return num;
            } else {
                if c != '\u{3000}' {
                    break;
                }
                if !self.config.system_allow_full_space {
                    return num;
                }
            }
            num += 1;
            st.shift();
        }
        num
    }

    pub fn skip_half_space(&self, st: &mut CharStream) -> usize {
        let mut num = 0;
        while st.current() == ' ' {
            num += 1;
            st.shift();
        }
        num
    }

    // ------------------------------------------------------------------
    // 字符串
    // ------------------------------------------------------------------

    pub fn read_string(
        &self,
        st: &mut CharStream,
        end_with: StrEnd,
        line_no: i32,
    ) -> Result<String, LexError> {
        let mut buffer = String::with_capacity(100);
        loop {
            let c = st.current();
            match c {
                EOS => return Ok(buffer),
                '"' => {
                    if end_with == StrEnd::DoubleQuotation {
                        return Ok(buffer);
                    }
                }
                '\'' => {
                    if end_with == StrEnd::SingleQuotation {
                        return Ok(buffer);
                    }
                }
                ',' => {
                    if matches!(end_with, StrEnd::Comma | StrEnd::LeftParenBracketCommaSemicolon) {
                        return Ok(buffer);
                    }
                }
                '(' | ';' | '[' => {
                    if end_with == StrEnd::LeftParenBracketCommaSemicolon {
                        return Ok(buffer);
                    }
                }
                '\\' => {
                    st.shift();
                    match st.current() {
                        EOS => {
                            return Err(LexError::new(
                                "エスケープ文字\\の後に文字がありません",
                                line_no,
                                st.col(),
                            ));
                        }
                        's' => buffer.push(' '),
                        'S' => buffer.push('\u{3000}'),
                        't' => buffer.push('\t'),
                        'n' => buffer.push('\n'),
                        '\n' => {}
                        e => buffer.push(e),
                    }
                    st.shift();
                    continue;
                }
                _ => {}
            }
            buffer.push(st.current());
            st.shift();
        }
    }

    // ------------------------------------------------------------------
    // 主分析循环
    // ------------------------------------------------------------------

    pub fn analyse(
        &self,
        st: &mut CharStream,
        end_with: LexEnd,
        flag: u8,
        line_no: i32,
    ) -> Result<Vec<Token>, LexError> {
        let mut ret: Vec<Token> = Vec::new();
        let mut nest_s: i32 = 0;
        let mut nest_l: i32 = 0;
        loop {
            let c = st.current();
            match c {
                EOS | '\n' => break,
                '\t' | ' ' => st.shift(),
                '\u{3000}' => {
                    if !self.config.system_allow_full_space {
                        return Err(LexError::new(
                            format!(
                                "予期しない全角スペースを発見しました(この警告はシステムオプション「{}」により無視できます)",
                                FULL_SPACE_CONFIG_NAME
                            ),
                            line_no,
                            st.col(),
                        ));
                    }
                    st.shift();
                }
                '0'..='9' => ret.push(self.read_number_token(st, line_no)?),
                '>' | '!' | '#' | '%' | '&' | '*' | '+' | '-' | '/' | '<' | '=' | '?' | '^' | '|'
                | '~' => {
                    if c == '>' && end_with == LexEnd::GreaterThan {
                        break;
                    }
                    if nest_s == 0
                        && nest_l == 0
                        && (end_with == LexEnd::Operator
                            || (end_with == LexEnd::Percent && c == '%')
                            || (end_with == LexEnd::Question && c == '?'))
                    {
                        break;
                    }
                    let col = st.col();
                    let op = self.read_operator(st, flag & flag::ASSIGNMENT != 0, line_no)?;
                    ret.push(Token::op(op, line_no, col));
                }
                ')' => {
                    ret.push(Token::sym(')', line_no, st.col()));
                    nest_s -= 1;
                    st.shift();
                }
                ']' => {
                    ret.push(Token::sym(']', line_no, st.col()));
                    nest_l -= 1;
                    st.shift();
                }
                '(' => {
                    ret.push(Token::sym('(', line_no, st.col()));
                    nest_s += 1;
                    st.shift();
                }
                '[' => {
                    if st.next_char() == '[' {
                        return Err(LexError::new(
                            "字句解析中に予期しない文字'[['を発見しました",
                            line_no,
                            st.col(),
                        ));
                    }
                    ret.push(Token::sym('[', line_no, st.col()));
                    nest_l += 1;
                    st.shift();
                }
                ':' => {
                    ret.push(Token::sym(':', line_no, st.col()));
                    st.shift();
                }
                ',' => {
                    if end_with == LexEnd::Comma && nest_s == 0 {
                        break;
                    }
                    ret.push(Token::sym(',', line_no, st.col()));
                    st.shift();
                }
                '\'' => {
                    if flag & flag::SINGLE_QUOTATION_STR != 0 {
                        st.shift();
                        let col = st.col();
                        let s = self.read_string(st, StrEnd::SingleQuotation, line_no)?;
                        if st.current() != '\'' {
                            return Err(LexError::new(
                                "\"'\"が閉じられていません",
                                line_no,
                                st.col(),
                            ));
                        }
                        st.shift();
                        ret.push(Token::string(&s, line_no, col));
                    } else if flag & flag::PRINT_V == 0 {
                        if end_with == LexEnd::Operator && nest_s == 0 && nest_l == 0 && st.next_char() == '=' {
                            break;
                        }
                        return Err(LexError::new(
                            format!("字句解析中に予期しない文字'{}'を発見しました", c),
                            line_no,
                            st.col(),
                        ));
                    } else {
                        st.shift();
                        let col = st.col();
                        let s = self.read_string(st, StrEnd::Comma, line_no)?;
                        ret.push(Token::string(&s, line_no, col));
                        if st.current() != ',' {
                            break;
                        }
                        if end_with == LexEnd::Comma && nest_s == 0 {
                            break;
                        }
                        ret.push(Token::sym(',', line_no, st.col()));
                        st.shift();
                    }
                }
                '}' => {
                    if end_with == LexEnd::RightCurlyBrace {
                        break;
                    }
                    return Err(LexError::new(
                        format!("字句解析中に予期しない文字'{}'を発見しました", c),
                        line_no,
                        st.col(),
                    ));
                }
                '"' => {
                    st.shift();
                    let col = st.col();
                    let s = self.read_string(st, StrEnd::DoubleQuotation, line_no)?;
                    if st.current() != '"' {
                        return Err(LexError::new(
                            "\"\"\"が閉じられていません",
                            line_no,
                            st.col(),
                        ));
                    }
                    st.shift();
                    ret.push(Token::string(&s, line_no, col));
                }
                '@' => {
                    if st.next_char() != '"' {
                        ret.push(Token::sym('@', line_no, st.col()));
                        st.shift();
                    } else {
                        let col = st.col();
                        st.shift();
                        st.shift();
                        let _form =
                            self.analyse_formatted_string(st, FormEnd::DoubleQuotation, false, line_no)?;
                        if st.current() != '"' {
                            return Err(LexError::new(
                                "\"\"\"が閉じられていません",
                                line_no,
                                st.col(),
                            ));
                        }
                        st.shift();
                        ret.push(Token::form(line_no, col));
                    }
                }
                '.' => {
                    ret.push(Token::sym('.', line_no, st.col()));
                    st.shift();
                }
                '\\' => {
                    if st.next_char() != '@' {
                        return Err(LexError::new(
                            format!("字句解析中に予期しない文字'{}'を発見しました", c),
                            line_no,
                            st.col(),
                        ));
                    }
                    let col = st.col();
                    st.jump(2);
                    let _yen = self.analyse_yen_at(st, line_no)?;
                    ret.push(Token::form(line_no, col));
                }
                '$' | '{' => {
                    return Err(LexError::new(
                        format!("字句解析中に予期しない文字'{}'を発見しました", c),
                        line_no,
                        st.col(),
                    ));
                }
                ';' => {
                    if st.current_equal_to(";#;") && self.config.debug_mode {
                        st.jump(3);
                    } else if st.current_equal_to(";!;") {
                        st.jump(3);
                    } else {
                        st.seek_end();
                        break;
                    }
                }
                _ => {
                    let col = st.col();
                    let code = self.read_single_identifier(st);
                    ret.push(Token::ident(&code, line_no, col));
                }
            }
        }
        if nest_s != 0 || nest_l != 0 {
            if nest_s < 0 {
                return Err(LexError::at_line("'('に対応する')'が見つかりません", line_no));
            }
            if nest_s > 0 {
                return Err(LexError::at_line("構文解析中に予期しない')'を発見しました", line_no));
            }
            return Err(LexError::at_line("'['に対応する']'が見つかりません", line_no));
        }
        self.expand_macros(ret, line_no)
    }

    /// `#DEFINE` 宏展开 —— `LexicalAnalyzer.kt:724-765`：只替换标识符 token，展开体按声明期
    /// 口径（`ALLOW_ASSIGNMENT`）重词法化，递归上限 100 次。
    fn expand_macros(&self, tokens: Vec<Token>, line_no: i32) -> Result<Vec<Token>, LexError> {
        if self.config.macros.is_empty() {
            return Ok(tokens);
        }
        let lookup: BTreeMap<String, String> = if self.config.macro_ignore_case {
            self.config
                .macros
                .iter()
                .map(|(k, v)| (k.to_uppercase(), v.clone()))
                .collect()
        } else {
            self.config.macros.clone()
        };
        let mut current = tokens;
        let mut expanded = 0usize;
        loop {
            let mut changed = false;
            let mut out: Vec<Token> = Vec::with_capacity(current.len());
            for t in current.iter() {
                let key = if t.kind == TokKind::Ident {
                    let k = if self.config.macro_ignore_case { t.text.to_uppercase() } else { t.text.clone() };
                    lookup.get(&k).cloned()
                } else {
                    None
                };
                match key {
                    None => out.push(t.clone()),
                    Some(body) => {
                        expanded += 1;
                        if expanded > 100 {
                            return Err(LexError::at_line(
                                "マクロの展開数が1文あたりの上限値100を超えました(自己参照・循環参照のおそれ)",
                                line_no,
                            ));
                        }
                        let mut cfg = self.config.clone();
                        cfg.macros.clear();
                        let sub = ErbLexer::new(cfg).analyse(
                            &mut CharStream::new(&body),
                            LexEnd::EoL,
                            flag::ASSIGNMENT,
                            line_no,
                        )?;
                        out.extend(sub);
                        changed = true;
                    }
                }
            }
            if !changed {
                return Ok(out);
            }
            current = out;
        }
    }

    /// 格式化字符串 —— `LexicalAnalyzer.kt:771-861`。`@"..."`、`DATAFORM` 等。
    pub fn analyse_formatted_string(
        &self,
        st: &mut CharStream,
        end_with: FormEnd,
        trim: bool,
        line_no: i32,
    ) -> Result<StrForm, LexError> {
        let mut strs: Vec<String> = Vec::new();
        let mut subs: Vec<SubWord> = Vec::new();
        let mut sb = String::with_capacity(100);
        loop {
            let c = st.current();
            let mut do_break = false;
            let mut skip_shift = false;
            match c {
                '"' => {
                    if end_with == FormEnd::DoubleQuotation {
                        do_break = true;
                    } else {
                        sb.push(c);
                    }
                }
                '#' => {
                    if end_with == FormEnd::Sharp {
                        do_break = true;
                    } else {
                        sb.push(c);
                    }
                }
                ',' => {
                    if matches!(end_with, FormEnd::Comma | FormEnd::LeftParenBracketCommaSemicolon) {
                        do_break = true;
                    } else {
                        sb.push(c);
                    }
                }
                '(' | ';' | '[' => {
                    if end_with == FormEnd::LeftParenBracketCommaSemicolon {
                        do_break = true;
                    } else {
                        sb.push(c);
                    }
                }
                '%' => {
                    strs.push(std::mem::take(&mut sb));
                    st.shift();
                    let words = self.analyse(st, LexEnd::Percent, flag::NONE, line_no)?;
                    if st.current() != '%' {
                        return Err(LexError::new(
                            "'%'が使われましたが対応する'%'が見つかりません",
                            line_no,
                            st.col(),
                        ));
                    }
                    subs.push(SubWord::Percent(words));
                }
                '{' => {
                    strs.push(std::mem::take(&mut sb));
                    st.shift();
                    let words = self.analyse(st, LexEnd::RightCurlyBrace, flag::NONE, line_no)?;
                    if st.current() != '}' {
                        return Err(LexError::new(
                            "'{'が使われましたが対応する'}'が見つかりません",
                            line_no,
                            st.col(),
                        ));
                    }
                    subs.push(SubWord::CurlyBrace(words));
                }
                '$' | '*' | '+' | '/' | '=' => {
                    if !self.config.system_ignore_triple_symbol && st.triple_symbol() {
                        strs.push(std::mem::take(&mut sb));
                        st.jump(3);
                        subs.push(SubWord::TripleSymbol(c));
                        skip_shift = true;
                    } else {
                        sb.push(c);
                    }
                }
                '\\' => {
                    st.shift();
                    let e = st.current();
                    if e == EOS {
                        return Err(LexError::new(
                            "エスケープ文字\\の後に文字がありません",
                            line_no,
                            st.col(),
                        ));
                    } else if e == '\n' {
                        // 不追加
                    } else if (e as u32) <= 64 {
                        if e == '@' {
                            if !matches!(end_with, FormEnd::YenAt | FormEnd::Sharp) {
                                strs.push(std::mem::take(&mut sb));
                                st.shift();
                                let yen = self.analyse_yen_at(st, line_no)?;
                                subs.push(_yen_to_sub(yen));
                                skip_shift = true;
                            } else {
                                do_break = true; // \@ 是结束符
                            }
                        } else {
                            sb.push(e);
                        }
                    } else if e == 'S' {
                        sb.push('\u{3000}');
                    } else if e == 'n' {
                        sb.push('\n');
                    } else if e == 's' {
                        sb.push(' ');
                    } else if e == 't' {
                        sb.push('\t');
                    } else {
                        sb.push(e);
                    }
                }
                EOS | '\n' => do_break = true,
                _ => sb.push(c),
            }
            if do_break {
                break;
            }
            if !skip_shift {
                st.shift();
            }
        }
        strs.push(sb);
        if trim && !strs.is_empty() {
            let first = strs[0].trim_start_matches([' ', '\t']).to_string();
            let last_idx = strs.len() - 1;
            let last = strs[last_idx].trim_end_matches([' ', '\t']).to_string();
            strs[0] = first;
            strs[last_idx] = last;
        }
        Ok(StrForm { strs, subs })
    }

    /// `\@条件?左#右\@` 三项条件式 —— `LexicalAnalyzer.kt:867-889`。
    pub fn analyse_yen_at(&self, st: &mut CharStream, line_no: i32) -> Result<YenAt, LexError> {
        let condition = self.analyse(st, LexEnd::Question, flag::NONE, line_no)?;
        if st.current() != '?' {
            return Err(LexError::new(
                "'\\@'が使われましたが対応する'?'が見つかりません",
                line_no,
                st.col(),
            ));
        }
        st.shift();
        let left = self.analyse_formatted_string(st, FormEnd::Sharp, true, line_no)?;
        if st.current() != '#' {
            if st.current() != '@' {
                return Err(LexError::new(
                    "'\\@'が使われましたが対応する'#'が見つかりません",
                    line_no,
                    st.col(),
                ));
            }
            st.shift();
            // 警告（`'\\@'…'#'が見つかりません`）不收：dump 夹具只比错误，见文件头第 2 条。
            return Ok(YenAt { condition, left, right: None });
        }
        st.shift();
        let right = self.analyse_formatted_string(st, FormEnd::YenAt, true, line_no)?;
        if st.current() != '@' {
            return Err(LexError::new(
                "'\\@','?','#'が使われましたが対応する'\\@'が見つかりません",
                line_no,
                st.col(),
            ));
        }
        st.shift();
        Ok(YenAt { condition, left, right: Some(right) })
    }

    /// 整行分析（调试/夹具用）—— `LexicalAnalyzer.kt:901-904`。
    pub fn tokenize_line(&self, content: &str, line_no: i32) -> Result<Vec<Token>, LexError> {
        self.analyse(&mut CharStream::new(content), LexEnd::EoL, flag::ASSIGNMENT, line_no)
    }

    /// `@` / `$` 标签行 —— `LexicalAnalyzer.kt:910-924`。返回 (是否函数行, 标签名, 其余 token)。
    pub fn parse_label_line(
        &self,
        content: &str,
        line_no: i32,
        filename: &str,
    ) -> Result<(bool, String, Vec<Token>), LexError> {
        let is_function = content.starts_with('@');
        let mut st = CharStream::new(content);
        st.shift();
        let tokens = self
            .analyse(&mut st, LexEnd::EoL, flag::ASSIGNMENT, line_no)
            .map_err(|e| e.with_file(filename))?;
        let name = match tokens.first() {
            Some(t) if t.kind == TokKind::Ident => t.text.clone(),
            _ => {
                return Err(LexError::new("関数名が不正であるか存在しません", line_no, -1)
                    .with_file(filename))
            }
        };
        // `$` 标签带参数只发警告（`LexicalAnalyzer.kt:919-922`），不影响 token 流。
        Ok((is_function, name, tokens[1..].to_vec()))
    }

    /// `#` 行 —— `LexicalAnalyzer.kt:935-952`。返回 (关键字, 其余 token)。
    pub fn parse_sharp_line(
        &self,
        content: &str,
        line_no: i32,
        filename: &str,
    ) -> Result<(String, Vec<Token>), LexError> {
        let mut st = CharStream::new(content);
        st.shift();
        let keyword = self.read_single_identifier(&mut st);
        if keyword.is_empty() {
            return Err(LexError::new("解釈できない#行です", line_no, -1).with_file(filename));
        }
        let upper = keyword.to_uppercase();
        let tokens = if matches!(upper.as_str(), "LOCALSIZE" | "LOCALSSIZE" | "LOCALFSIZE" | "DIM" | "DIMS")
        {
            self.analyse(&mut st, LexEnd::EoL, flag::ASSIGNMENT, line_no)?
        } else {
            Vec::new()
        };
        Ok((keyword, tokens))
    }
}

#[derive(Clone, Debug)]
pub struct YenAt {
    pub condition: Vec<Token>,
    pub left: StrForm,
    pub right: Option<StrForm>,
}

fn _yen_to_sub(y: YenAt) -> SubWord {
    SubWord::YenAt(y.condition, Box::new(y.left), y.right.map(Box::new))
}

fn parse_signed_decimal(s: &str) -> Result<i64, ()> {
    let (neg, body) = match s.strip_prefix('-') {
        Some(rest) => (true, rest),
        None => (false, s.strip_prefix('+').unwrap_or(s)),
    };
    if body.is_empty() || !body.chars().all(|c| c.is_ascii_digit()) {
        return Err(());
    }
    let mut acc: u64 = 0;
    for c in body.chars() {
        let d = c.to_digit(10).ok_or(())? as u64;
        acc = acc.checked_mul(10).ok_or(())?;
        acc = acc.checked_add(d).ok_or(())?;
    }
    if neg {
        if acc > (i64::MAX as u64) + 1 {
            return Err(());
        }
        Ok((acc as i64).wrapping_neg())
    } else if acc > i64::MAX as u64 {
        Err(())
    } else {
        Ok(acc as i64)
    }
}

fn parse_unsigned(body: &str, base: u32) -> Result<u64, ()> {
    let trimmed = body.trim_start_matches('0');
    let digits = if trimmed.is_empty() { "0" } else { trimmed };
    let mut acc: u64 = 0;
    for c in digits.chars() {
        let d = c.to_digit(36).filter(|d| *d < base).ok_or(())? as u64;
        acc = acc.checked_mul(base as u64).ok_or(())?;
        acc = acc.checked_add(d).ok_or(())?;
    }
    Ok(acc)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lex_line(s: &str) -> Vec<String> {
        let l = ErbLexer::new(LexerConfig::default());
        match l.tokenize_line(s, 0) {
            Ok(ts) => ts.into_iter().map(|t| t.text).collect(),
            Err(e) => vec![format!("ERR:{}", e.message)],
        }
    }

    #[test]
    fn integer_and_operator_shapes() {
        assert_eq!(lex_line("X = 12 + 0x1F"), vec!["X", "Assignment", "12", "Plus", "31"]);
        // 单引号字面量在 ASSIGNMENT（无 SINGLE_QUOTATION_STR）下必须当场报错，与 Kotlin 同文案。
        let bad = lex_line("X'= 3");
        assert_eq!(bad.len(), 1);
        assert!(bad[0].starts_with("ERR:字句解析中に予期しない文字'''を発見しました")
            || bad[0].starts_with("ERR:字句解析中に予期しない文字"),
            "实际: {}",
            bad[0]);
    }

    #[test]
    fn float_literal_is_kept_raw() {
        let toks = lex_line("A = 0.7");
        assert_eq!(toks[2], "0.7");
        // `1.修改` 不是浮点：`.` 后不是数字 ⇒ 整数 token 之后是 Symbol('.')
        let toks = lex_line("1.修改");
        assert_eq!(toks[0], "1");
    }

    #[test]
    fn unsigned_hex_reinterprets_to_negative() {
        let l = ErbLexer::new(LexerConfig::default());
        let mut st = CharStream::new("0xFFFFFFFFFFFFFFFF");
        assert_eq!(l.read_int64(&mut st, false, 0).unwrap(), -1);
        let src = "0b";
        let mut st = CharStream::new(src);
        assert!(l.read_int64(&mut st, false, 0).is_err());
    }

    #[test]
    fn string_escapes_follow_official_table() {
        let toks = lex_line(r#"PRINTFORM "a\tb\nc\s""#);
        assert_eq!(toks[1], "\"a\tb\nc \"");
    }

    #[test]
    fn formatted_string_and_yenat_are_single_tokens() {
        let toks = lex_line(r#"S = @"x{1+2}y""#);
        assert_eq!(toks[2], "<FORM>");
        let toks = lex_line(r#"T = \@1?A#B\@"#);
        assert_eq!(toks[2], "<FORM>");
    }

    #[test]
    fn bracket_nesting_errors_carry_kotlin_messages() {
        let l = ErbLexer::new(LexerConfig::default());
        let e = l.analyse(&mut CharStream::new("A = (1"), LexEnd::EoL, flag::ASSIGNMENT, 0).unwrap_err();
        // Kotlin 的这一支文案与分支是错位照抄的（`LexicalAnalyzer.kt:683-688`）：S>0 报
        // 「構文解析中に予期しない']'…」。这里断言的是**Kotlin 的真实行为**，不是直觉的语义。
        assert_eq!(e.message, "構文解析中に予期しない')'を発見しました");
        assert_eq!(e.column, -1, "无列号的错误必须与 Kotlin 一样落 -1");
    }

    #[test]
    fn comment_semicolon_ends_line() {
        assert_eq!(lex_line("A = 1 ; note"), vec!["A", "Assignment", "1"]);
        assert_eq!(lex_line("A = 1 ;!; B"), vec!["A", "Assignment", "1", "B"]);
    }
}

