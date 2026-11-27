//! 语句层分派（CONTRACT-RUSTFULL §6.10 P2-2a）—— Kotlin `LineParser.parseCodeLine` 的 Rust 腿。
//!
//! 本模块只做 P2-2a 的「结构面」：四步分派的判定顺序、语句名 → `FunctionCode`、PRINT 后缀形态、
//! 参数原文切分（`splitArgs`）、以及空实参/非法分隔符/赋值两段式的错误位置。
//! 参数**树**（37 语句 × 47 标签的构建规则）是 P2-2b 的面，本模块刻意不建参数对象：
//! 因此 dump 里 `SET`（赋值）行的 `Argument.shape` 一律写 `-`，不拿未知当已知。
//!
//! 真源纪律（AGENTS §5）：语句名映射、内建方法名单、内建变量表一律从 `erdbc dump-funcodes`
//! 导出的 TSV 读（[`FunctionTables`]），代码里不许手抄一张并行的表 —— 手抄表漏项时只在
//! 具体语料行上表现为「查不到」，不会报错。

use std::collections::{BTreeMap, BTreeSet};

use crate::java_unicode_upper::uppercase_str;
use crate::lexer::{flag, CharStream, ErbLexer, LexEnd, LineKind, OperatorCode, TokKind};

/// `funcodes.tsv` 的一行 `code` 段。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CodeRow {
    pub ordinal: i32,
    pub name: String,
    /// `ArgType` 枚举名（`SP_SET` / `STR_NULLABLE` …）。
    pub arg_type: String,
    pub flag: i32,
    pub kind: String,
}

/// `funcodes.tsv` 的一行 `var` 段（内建变量的解析期静态类型）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VarRow {
    pub key: String,
    pub name: String,
    pub ty: String,
    pub dimension: i32,
    pub char_data: bool,
    pub konst: bool,
}

/// 语句层用的三张表（均由 Kotlin 侧活实现导出）。
#[derive(Debug, Clone, Default)]
pub struct FunctionTables {
    rows: Vec<CodeRow>,
    by_name: BTreeMap<String, usize>,
    methods: BTreeSet<String>,
    vars: BTreeMap<String, VarRow>,
}

impl FunctionTables {
    pub fn from_tsv(text: &str) -> Result<Self, String> {
        let mut t = FunctionTables::default();
        for (lineno, raw) in text.lines().enumerate() {
            let line = raw.trim_end_matches('\r');
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let cols: Vec<&str> = line.split(' ').collect();
            let bad = |what: &str| format!("funcodes 第 {} 行{what}坏了: {}", lineno + 1, line);
            match cols[0] {
                "code" if cols.len() == 6 => {
                    t.rows.push(CodeRow {
                        ordinal: cols[1].parse().map_err(|_| bad(" ordinal "))?,
                        name: cols[2].to_string(),
                        arg_type: cols[3].to_string(),
                        flag: cols[4].parse().map_err(|_| bad(" flag "))?,
                        kind: cols[5].to_string(),
                    });
                }
                "code" => return Err(bad(" 列数 ")),
                "byname" if cols.len() == 3 => {
                    let idx = t
                        .rows
                        .iter()
                        .position(|r| r.name == cols[2])
                        .ok_or_else(|| bad(" 未定义的 code "))?;
                    t.by_name.insert(cols[1].to_string(), idx);
                }
                "byname" => return Err(bad(" 列数 ")),
                "method" if cols.len() == 2 => {
                    t.methods.insert(cols[1].to_string());
                }
                "method" => return Err(bad(" 列数 ")),
                "var" if cols.len() == 7 => {
                    t.vars.insert(
                        cols[1].to_string(),
                        VarRow {
                            key: cols[1].to_string(),
                            name: cols[2].to_string(),
                            ty: cols[3].to_string(),
                            dimension: cols[4].parse().map_err(|_| bad(" dimension "))?,
                            char_data: cols[5] == "1",
                            konst: cols[6] == "1",
                        },
                    );
                }
                "var" => return Err(bad(" 列数 ")),
                other => return Err(format!("funcodes 第 {} 行未知段: {}", lineno + 1, other)),
            }
        }
        Ok(t)
    }

    pub fn codes(&self) -> &[CodeRow] {
        &self.rows
    }

    pub fn code_of(&self, name: &str) -> Option<&CodeRow> {
        self.by_name.get(name).map(|&i| &self.rows[i])
    }

    pub fn has_method(&self, name: &str) -> bool {
        self.methods.contains(name)
    }

    pub fn var_of(&self, key: &str) -> Option<&VarRow> {
        self.vars.get(key)
    }
}

/// 一行语句的**分派面**观测值（P2-2a 对拍面；不含参数树）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StmtRecord {
    /// 语句类名（`AssignStmt` / `IfStmt` / `InstructionStmt` …）。
    pub class: String,
    /// `FunctionCode` 名（标签/`#` 行为 `__NULL__`）。
    pub code: String,
    /// 参数原文（`splitArgs` 结果；`VARI`/`VARS` 为名之后全部字符；自增减行空串）。
    pub arg_text: String,
    /// 分派层可见的特化字段（`op=` / `print=` / `label=` / `-`）。
    pub extra: String,
}

/// 语句解析失败（与 Kotlin `StmtParseException` 同口径）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StmtError {
    pub line: i32,
    pub col: i32,
    pub name: String,
    pub message: String,
}

impl StmtError {
    fn new(line: i32, col: i32, name: impl Into<String>, message: impl Into<String>) -> Self {
        Self { line, col, name: name.into(), message: message.into() }
    }
}

/// `PrintSpec.parse` 的可观测投影（Kotlin `PrintSpec`）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PrintSpec {
    pub code: String,
    pub arg_type: String,
    pub is_line_end: bool,
    pub is_single: bool,
    pub is_v: bool,
    pub is_s: bool,
    pub is_forms: bool,
    pub is_c: bool,
    pub is_lc: bool,
    pub is_k: bool,
    pub is_d: bool,
    pub is_n: bool,
    pub flag: i32,
}

impl PrintSpec {
    /// Kotlin `PrintSpec.parse` 逐行对齐：名字后缀逐段吃掉，任何残余字符都判失败（原版 ExeEE）。
    pub fn parse(code: &str) -> Option<PrintSpec> {
        if !code.starts_with("PRINT") {
            return None;
        }
        let mut st = CharStream::new(code);
        let mut flag = 8192;
        st.jump(5);
        let mut is_single = false;
        let mut is_v = false;
        let mut is_s = false;
        let mut is_forms = false;
        let arg_type;
        if st.current_equal_to("SINGLE") {
            flag |= 1026;
            is_single = true;
            st.jump(6);
        }
        if st.current_equal_to("V") {
            arg_type = "SP_PRINTV";
            is_v = true;
            st.jump(1);
        } else if st.current_equal_to("S") {
            arg_type = "STR_EXPRESSION";
            is_s = true;
            st.jump(1);
        } else if st.current_equal_to("FORMS") {
            arg_type = "STR_EXPRESSION";
            is_forms = true;
            st.jump(5);
        } else if st.current_equal_to("FORM") {
            arg_type = "FORM_STR_NULLABLE";
            st.jump(4);
        } else {
            arg_type = "STR_NULLABLE";
        }
        let mut is_lc = false;
        let mut is_c = false;
        if st.current_equal_to("LC") {
            flag |= 2;
            is_lc = true;
            st.jump(2);
        } else if st.current_equal_to("C") {
            if code == "PRINTFORMC" {
                flag |= 2;
            }
            is_c = true;
            st.jump(1);
        }
        let mut is_k = false;
        if st.current_equal_to("K") {
            flag |= 4098;
            is_k = true;
            st.jump(1);
        }
        let mut is_d = false;
        if st.current_equal_to("D") {
            flag |= 2050;
            is_d = true;
            st.jump(1);
        }
        let mut is_line_end = true;
        let mut is_n = false;
        if st.current_equal_to("N") {
            is_line_end = false;
            is_n = true;
            flag |= 512;
            st.jump(1);
        }
        if st.current_equal_to("L") {
            flag |= 256;
            flag |= 4;
            st.jump(1);
        } else if st.current_equal_to("W") {
            flag |= 768;
            st.jump(1);
        } else {
            flag |= 4;
        }
        if !st.eos() {
            return None;
        }
        Some(PrintSpec {
            code: code.to_string(),
            arg_type: arg_type.to_string(),
            is_line_end,
            is_single,
            is_v,
            is_s,
            is_forms,
            is_c,
            is_lc,
            is_k,
            is_d,
            is_n,
            flag,
        })
    }

    pub fn describe(&self) -> String {
        format!(
            "{}:{}:{}:{}:{}:{}:{}:{}:{}:{}:{}:{}",
            self.arg_type,
            if self.is_line_end { "LF" } else { "NO-LF" },
            if self.is_single { "SINGLE" } else { "-" },
            if self.is_v { "V" } else { "-" },
            if self.is_s { "S" } else { "-" },
            if self.is_forms { "FORMS" } else { "-" },
            if self.is_c { "C" } else { "-" },
            if self.is_lc { "LC" } else { "-" },
            if self.is_k { "K" } else { "-" },
            if self.is_d { "D" } else { "-" },
            if self.is_n { "N" } else { "-" },
            self.flag,
        )
    }
}

/// `LexOperatorCode` → 表达式层 `OperatorCode` 枚举名（Kotlin `toExprOperator` + `fromCSharpName`）。
pub fn expr_operator_name(op: OperatorCode) -> Option<&'static str> {
    Some(match op {
        OperatorCode::Plus => "PLUS",
        OperatorCode::Minus => "MINUS",
        OperatorCode::Mult => "MULT",
        OperatorCode::Div => "DIV",
        OperatorCode::Mod => "MOD",
        OperatorCode::Equal => "EQUAL",
        OperatorCode::Greater => "GREATER",
        OperatorCode::Less => "LESS",
        OperatorCode::GreaterEqual => "GREATER_EQUAL",
        OperatorCode::LessEqual => "LESS_EQUAL",
        OperatorCode::NotEqual => "NOT_EQUAL",
        OperatorCode::Increment => "INCREMENT",
        OperatorCode::Decrement => "DECREMENT",
        OperatorCode::And => "AND",
        OperatorCode::Or => "OR",
        OperatorCode::Xor => "XOR",
        OperatorCode::Nand => "NAND",
        OperatorCode::Nor => "NOR",
        OperatorCode::BitAnd => "BIT_AND",
        OperatorCode::BitOr => "BIT_OR",
        OperatorCode::BitXor => "BIT_XOR",
        OperatorCode::Not => "NOT",
        OperatorCode::BitNot => "BIT_NOT",
        OperatorCode::RightShift => "RIGHT_SHIFT",
        OperatorCode::LeftShift => "LEFT_SHIFT",
        OperatorCode::TernaryA => "TERNARY_A",
        OperatorCode::TernaryB => "TERNARY_B",
        OperatorCode::Assignment => "ASSIGNMENT",
        OperatorCode::AssignmentStr => "ASSIGNMENT_STR",
        OperatorCode::Null => return None,
    })
}

/// 分派器：只持有表与词法器（无符号层、无参数构建器 —— 那是 P2-2b 的面）。
pub struct StmtParser<'a> {
    tables: &'a FunctionTables,
    lexer: ErbLexer,
    use_scoped_variable_instruction: bool,
    system_allow_full_space: bool,
}

impl<'a> StmtParser<'a> {
    pub fn new(tables: &'a FunctionTables) -> Self {
        Self {
            tables,
            lexer: ErbLexer::new(crate::lexer::LexerConfig::default()),
            use_scoped_variable_instruction: false,
            system_allow_full_space: true,
        }
    }

    pub fn with_scoped_variables(mut self, enabled: bool) -> Self {
        self.use_scoped_variable_instruction = enabled;
        self
    }

    /// 逻辑行入口（Kotlin `parseLogicalLine`）：CODE 行走 [`Self::parse_code_line`]，
    /// 标签/`#` 行只做类名与语句码投影（形参/尺寸校验属语句层细节，P2-2b 再对齐）。
    pub fn parse_logical_line(
        &self,
        content: &str,
        kind: LineKind,
        line_no: i32,
    ) -> Result<StmtRecord, StmtError> {
        match kind {
            LineKind::Code => self.parse_code_line(content, line_no),
            LineKind::FunctionLabel | LineKind::GotoLabel => {
                let is_function = matches!(kind, LineKind::FunctionLabel);
                Ok(StmtRecord {
                    class: if is_function { "FuncLabelStmt" } else { "GotoLabelStmt" }.to_string(),
                    code: "__NULL__".to_string(),
                    arg_text: String::new(),
                    extra: "-".to_string(),
                })
            }
            LineKind::Sharp => Ok(StmtRecord {
                class: "SharpStmt".to_string(),
                code: "__NULL__".to_string(),
                arg_text: String::new(),
                extra: "-".to_string(),
            }),
        }
    }

    /// Kotlin `LineParser.parseCodeLine` 的四步分派。
    pub fn parse_code_line(&self, text: &str, line_no: i32) -> Result<StmtRecord, StmtError> {
        let content = text.trim_start_matches([' ', '\t']);
        if content.is_empty() {
            return Err(StmtError::new(line_no, 0, "", "空行"));
        }
        // 1. +/- 开头 —— 只允许 ++/--
        if content.starts_with('+') || content.starts_with('-') {
            return self.parse_increment_line(content, line_no);
        }
        // 2. 行首标识符
        let first = match self.lexer.read_first_identifier(&mut CharStream::new(content), line_no) {
            Ok(tok) => tok.text,
            Err(e) => return Err(StmtError::new(line_no, 0, "", e.message)),
        };
        let name_end = first.len();
        let upper = uppercase_str(&first);
        if let Some(code) = self.tables.code_of(&upper) {
            return self.parse_instruction_line(code.clone(), &first, content, name_end, line_no);
        }
        // 3. 内建方法名 → METHOD
        if self.tables.has_method(&upper) {
            let arg_text = self.split_args(content, name_end, line_no, &first)?;
            return Ok(StmtRecord {
                class: "MethodStmt".to_string(),
                code: "METHOD".to_string(),
                arg_text,
                extra: format!("label={}", first),
            });
        }
        // 4. 赋值两段式
        self.parse_assignment_line(content, line_no)
    }

    fn parse_increment_line(&self, content: &str, line_no: i32) -> Result<StmtRecord, StmtError> {
        let tokens = match self.lexer.analyse(
            &mut CharStream::new(content),
            LexEnd::EoL,
            flag::NONE,
            line_no,
        ) {
            Ok(t) => t,
            Err(e) => return Err(StmtError::new(line_no, e.column, "", e.message)),
        };
        // Kotlin 判的是首 token 是否 `Token.Operator` 且 code ∈ {Increment, Decrement}；
        // Rust 的 `Token::op` 把枚举名（`"Increment"` / `"Decrement"`）存进 `text`（见
        // `lexer.rs` 的 `Token::op`），⇒ 等价判据是首 token 的 text 等于枚举名。
        // 不要比 `"++"`：`read_operator` 已经把两个 `+` 吞成一个 `OperatorCode::Increment`。
        let first_op_text = tokens.first().filter(|t| t.kind == TokKind::Op).map(|t| t.text.as_str());
        let is_inc = first_op_text == Some("Increment");
        let is_dec = first_op_text == Some("Decrement");
        if !is_inc && !is_dec {
            return Err(StmtError::new(
                line_no,
                0,
                "",
                if content.starts_with('+') { "行首为'+'但不是自增" } else { "行首为'-'但不是自减" },
            ));
        }
        Ok(StmtRecord {
            class: "AssignStmt".to_string(),
            code: "SET".to_string(),
            arg_text: String::new(),
            extra: format!("op={}", if is_inc { "INCREMENT" } else { "DECREMENT" }),
        })
    }

    fn parse_instruction_line(
        &self,
        code: CodeRow,
        name: &str,
        content: &str,
        name_end: usize,
        line_no: i32,
    ) -> Result<StmtRecord, StmtError> {
        // VARI/VARS 门控先于参数切分（原版开关关闭时语句名解析即失败）。
        if !self.use_scoped_variable_instruction
            && (code.name == "VARI" || code.name == "VARS")
        {
            return Err(StmtError::new(
                line_no,
                0,
                name,
                "命令在当前设置下不可使用（UseScopedVariableInstruction 已禁用）",
            ));
        }
        // `VARI`/`VARS` 的参数原文是「名之后**全部**字符」（原版直接 Substring，不跳分隔符）。
        let is_var_decl = code.name == "VARI" || code.name == "VARS";
        let arg_text = if is_var_decl {
            content[name_end..].to_string()
        } else {
            self.split_args(content, name_end, line_no, name)?
        };
        let mut extra = "-".to_string();
        if code.arg_type == "PRINT" {
            let spec = PrintSpec::parse(&code.name)
                .ok_or_else(|| StmtError::new(line_no, 0, name, abnormal_print_text()))?;
            extra = format!("print={}", spec.describe());
        }
        let class = if code.arg_type == "PRINT" {
            "PrintStmt"
        } else if is_var_decl {
            "VarDeclStmt"
        } else {
            instruction_class(&code.name)
        };
        Ok(StmtRecord {
            class: class.to_string(),
            code: code.name,
            arg_text,
            extra,
        })
    }

    /// Kotlin `LineParser.splitArgs`：名后只看**一个**字符（`;` / 空格 / 制表符 / 允许时的全角空格）。
    fn split_args(
        &self,
        content: &str,
        name_end: usize,
        line_no: i32,
        name: &str,
    ) -> Result<String, StmtError> {
        let Some(c) = content[name_end..].chars().next() else {
            return Ok(String::new());
        };
        let is_sep = c == ';' || c == ' ' || c == '\t' || (c == '\u{3000}' && self.system_allow_full_space);
        if is_sep {
            return Ok(content[name_end + c.len_utf8()..].to_string());
        }
        Err(StmtError::new(
            line_no,
            name_end as i32,
            name,
            if c == '\u{3000}' {
                "指令后跟随全角空格，但 SystemAllowFullSpace 已关闭（原版提示要把该配置打开）".to_string()
            } else {
                format!("指令后跟随非法字符 '{c}'")
            },
        ))
    }

    fn parse_assignment_line(&self, content: &str, line_no: i32) -> Result<StmtRecord, StmtError> {
        let mut st = CharStream::new(content);
        if let Err(e) = self.lexer.analyse(&mut st, LexEnd::Operator, flag::NONE, line_no) {
            return Err(StmtError::new(line_no, e.column, "", e.message));
        }
        // analyse 已把指针停在运算符前（与 Kotlin 同一实现），继续读赋值运算符。
        let op = match self.lexer.read_assignment_operator(&mut st, line_no) {
            Ok(o) => o,
            Err(e) => {
                return Err(StmtError::new(
                    line_no,
                    e.column,
                    "",
                    format!("无法解释为赋值语句：{}", e.message),
                ))
            }
        };
        let effective = if op == OperatorCode::Equal {
            // 原版 level 0 提示「赋值请使用 '=' 而不是 '=='」（告警，不致命），照常按赋值执行。
            OperatorCode::Assignment
        } else {
            op
        };
        let name = expr_operator_name(effective).ok_or_else(|| {
            StmtError::new(line_no, 0, "", format!("不支持的赋值运算符 {effective:?}"))
        })?;
        let arg_text = slice_from_char(content, st.pointer());
        Ok(StmtRecord {
            class: "AssignStmt".to_string(),
            code: "SET".to_string(),
            arg_text,
            extra: format!("op={name}"),
        })
    }
}

/// 按**字符**下标切片（`CharStream` 的 pointer 是字符下标，不是字节下标）。
fn slice_from_char(s: &str, char_index: usize) -> String {
    s.char_indices()
        .nth(char_index)
        .map(|(byte, _)| s[byte..].to_string())
        .unwrap_or_default()
}

/// Kotlin `buildInstruction` 的类名映射（`code.name` → 语句类名）。
pub fn instruction_class(name: &str) -> &'static str {
    match name {
        "IF" | "SIF" => "IfStmt",
        "ELSEIF" => "ElseIfStmt",
        "ELSE" => "ElseStmt",
        "ENDIF" => "EndIfStmt",
        "SELECTCASE" => "SelectCaseStmt",
        "CASE" => "CaseStmt",
        "CASEELSE" => "CaseElseStmt",
        "ENDSELECT" => "EndSelectStmt",
        "FOR" | "REPEAT" => "ForStmt",
        "NEXT" | "REND" => "NextStmt",
        "WHILE" => "WhileStmt",
        "WEND" => "WendStmt",
        "DO" => "DoStmt",
        "LOOP" => "LoopStmt",
        "CATCH" => "CatchStmt",
        "ENDCATCH" => "EndCatchStmt",
        "DATALIST" | "ENDLIST" => "DataListStmt",
        "ENDDATA" | "ENDNOSKIP" | "ENDFUNC" => "EndBlockStmt",
        "DATA" | "DATAFORM" | "STRDATA" => "DataStmt",
        "PRINTDATA" | "PRINTDATAL" | "PRINTDATAW" | "PRINTDATAK" | "PRINTDATAKL"
        | "PRINTDATAKW" | "PRINTDATAD" | "PRINTDATADL" | "PRINTDATADW" => "PrintDataStmt",
        "JUMP" | "TRYJUMP" | "JUMPFORM" | "TRYJUMPFORM" | "TRYCJUMP" | "TRYCJUMPFORM"
        | "GOTO" | "TRYGOTO" | "GOTOFORM" | "TRYGOTOFORM" | "TRYCGOTO" | "TRYCGOTOFORM" => {
            "JumpStmt"
        }
        "CALL" | "TRYCALL" | "CALLFORM" | "TRYCALLFORM" | "TRYCCALL" | "TRYCCALLFORM"
        | "TRYCALLSTR" | "TRYCCALLSTR" => "CallStmt",
        "CALLF" | "TRYCALLF" | "CALLFORMF" | "TRYCALLFORMF" => "CallFStmt",
        "CALLSHARP" => "CallSharpStmt",
        "RETURN" => "ReturnStmt",
        "RETURNF" | "RETURNFORM" => "ReturnFStmt",
        "BREAK" | "CONTINUE" | "RESTART" | "QUIT" | "QUIT_AND_RESTART" | "FORCE_QUIT"
        | "FORCE_QUIT_AND_RESTART" | "STOPCALLTRAIN" => "FlowStmt",
        _ => "InstructionStmt",
    }
}

/// `LangKey.AbnormalPrint.render()` 的文本（PRINT 名后缀残留时）。
pub fn abnormal_print_text() -> &'static str {
    "PRINT异常"
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parser() -> StmtParser<'static> {
        let tables: &'static FunctionTables = Box::leak(Box::new(
            FunctionTables::from_tsv(include_str!("../tests/data/funcodes.tsv"))
                .expect("funcodes.tsv 可解析"),
        ));
        StmtParser::new(tables).with_scoped_variables(false)
    }

    /// 语料实样（`衣服/PANTS.ERB:244`）：`++V:(F(ARG))` 带下标也是自增。
    /// 判据锚在 `Token::op` 的 text = 枚举名（`Increment`），不是字面 `"++"` ——
    /// `read_operator` 已把两个 `+` 吞成一个 token，比字面串会永远不等。
    #[test]
    fn increment_with_indices_is_assign_increment() {
        let rec = parser()
            .parse_code_line("++PANTS_COUNT:(加权随机角色内裤(ARG))", 1)
            .expect("自增行应可解析");
        assert_eq!(rec.class, "AssignStmt");
        assert_eq!(rec.code, "SET");
        assert_eq!(rec.extra, "op=INCREMENT");
        assert_eq!(rec.arg_text, "");
    }

    #[test]
    fn decrement_line_is_assign_decrement() {
        let rec = parser().parse_code_line("--FLAG:700", 1).expect("自减行应可解析");
        assert_eq!(rec.extra, "op=DECREMENT");
    }

    /// 裸 `+` 不是自增（原版 `行首为'+'但不是自增`）。
    #[test]
    fn lone_plus_is_not_increment() {
        let err = parser().parse_code_line("+1", 1).expect_err("裸 + 应报错");
        assert_eq!(err.message, "行首为'+'但不是自增");
    }
}
