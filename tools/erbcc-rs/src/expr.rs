//! 表达式层（P2-2b/c 前置）：`expr/Parser.kt` + `Expr.kt` 的 Rust 移植。
//!
//! 口径与 Kotlin 侧**逐行对齐**（CONTRACT-RUSTFULL §6.12）：
//! - `reduce_term` 的 TermStack 状态机（前置/后置一元、二元优先级归约、三目 `? #`）；
//! - `reduce_identifier` 的回落顺序（变量 → `@` 私钥 → 下标关键字 → 零参函数引用）；
//! - `build_variable_term` 的下标省略补全（角色变量补 TARGET、一般 1 维补 0、RAND 两条拒绝）；
//! - 归约工厂的类型闸与报错文案（`Parser.kt:858-968`）。
//!
//! 未移植（本版明确跳过，夹具落 `K` 行不计入判据）：
//! - FORM_STRING（`FormStringTerm.kt` 34 KB，属 P2-2c 后续版）；
//! - `constantKeywordLookup`（解析期常量下标折叠，生产符号层注入；宽松档为 null）；
//! - `resolveIndexKeyword` 的三态（宽松档一律按「无关键字表」放行 → `StrLiteral`）。
use crate::lexer::{flag, CharStream, ErbLexer, LexEnd, LexerConfig, OperatorCode, TokKind, Token};

use crate::stmt::FunctionTables;

// ---------------------------------------------------------------------------
// 类型 / 节点
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum OperandType {
    Int,
    Str,
    Float,
}

impl OperandType {
    pub fn from_name(s: &str) -> OperandType {
        match s {
            "STR" => OperandType::Str,
            "FLOAT" => OperandType::Float,
            _ => OperandType::Int,
        }
    }

    pub fn tag(self) -> char {
        match self {
            OperandType::Int => 'I',
            OperandType::Str => 'S',
            OperandType::Float => 'F',
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct VarInfo {
    pub name: String,
    pub ty: OperandType,
    pub dimension: i32,
    pub char_data: bool,
    pub konst: bool,
}

/// 解析期标识符解析（`IdentifierResolver`，宽松档见 [`LooseTables`]）。
pub trait Resolver {
    fn resolve_variable(&self, name: &str, sub_key: Option<&str>) -> Option<VarInfo>;
    fn resolve_function(&self, name: &str) -> Option<OperandType>;
    fn can_return_float(&self, _name: &str) -> bool {
        false
    }
    fn can_be_referenced_bare(&self, _name: &str) -> bool {
        true
    }
}

#[derive(Clone, Debug)]
pub enum Expr {
    IntLiteral(i64),
    StrLiteral(String),
    FloatLiteral(f64),
    NullTerm(bool),
    VarRef {
        name: String,
        indices: Vec<Expr>,
        is_no_arg: bool,
        ty: OperandType,
        is_const: bool,
        sub_key: Option<String>,
    },
    KeywordIndexArg {
        expr: Box<Expr>,
        var_name: String,
        dim: i32,
    },
    FuncCall {
        name: String,
        args: Vec<Option<Expr>>,
        ty: OperandType,
    },
    Unary {
        op: OperatorCode,
        operand: Box<Expr>,
    },
    UnaryAfter {
        op: OperatorCode,
        operand: Box<Expr>,
    },
    Binary {
        op: OperatorCode,
        left: Box<Expr>,
        right: Box<Expr>,
        ty: OperandType,
    },
    Ternary {
        cond: Box<Expr>,
        if_true: Box<Expr>,
        if_false: Box<Expr>,
        ty: OperandType,
    },
}

impl Expr {
    pub fn operand_type(&self) -> OperandType {
        match self {
            Expr::IntLiteral(_) => OperandType::Int,
            Expr::StrLiteral(_) => OperandType::Str,
            Expr::FloatLiteral(_) => OperandType::Float,
            Expr::NullTerm(is_int) => {
                if *is_int {
                    OperandType::Int
                } else {
                    OperandType::Str
                }
            }
            Expr::VarRef { ty, .. } => *ty,
            Expr::KeywordIndexArg { .. } => OperandType::Int,
            Expr::FuncCall { ty, .. } => *ty,
            Expr::Unary { operand, .. } => operand.operand_type(),
            Expr::UnaryAfter { .. } => OperandType::Int,
            Expr::Binary { ty, .. } => *ty,
            Expr::Ternary { ty, .. } => *ty,
        }
    }

    /// 规范序列化 —— 与 Kotlin `DumpExpr.describe` 是函数对偶，任一侧改动必须同步。
    pub fn describe(&self) -> String {
        match self {
            Expr::IntLiteral(v) => format!("L{v}"),
            Expr::StrLiteral(s) => format!("S{}", quote(s)),
            Expr::FloatLiteral(v) => format!("F{:x}", v.to_bits()),
            Expr::NullTerm(true) => "NI".to_string(),
            Expr::NullTerm(false) => "NS".to_string(),
            Expr::VarRef { name, indices, is_no_arg, ty, is_const, sub_key } => {
                let idx: Vec<String> = indices.iter().map(|e| e.describe()).collect();
                format!(
                    "V{name}/{}{}/{}/{}/{}",
                    ty.tag(),
                    if *is_const { '1' } else { '0' },
                    if *is_no_arg { '1' } else { '0' },
                    sub_key.as_deref().unwrap_or("-"),
                    idx.join(",")
                )
            }
            Expr::KeywordIndexArg { expr, var_name, dim } => {
                format!("K{var_name}#{dim}:{}", expr.describe())
            }
            Expr::FuncCall { name, args, ty } => {
                let a: Vec<String> = args
                    .iter()
                    .map(|e| match e {
                        Some(e) => e.describe(),
                        None => "_".to_string(),
                    })
                    .collect();
                format!("C{name}/{}({})", ty.tag(), a.join(","))
            }
            Expr::Unary { op, operand } => format!("U{}({})", op_enum_name(*op), operand.describe()),
            Expr::UnaryAfter { op, operand } => format!("A{}({})", op_enum_name(*op), operand.describe()),
            Expr::Binary { op, left, right, ty } => format!(
                "B{}/{}({},{})",
                op_enum_name(*op),
                ty.tag(),
                left.describe(),
                right.describe()
            ),
            Expr::Ternary { cond, if_true, if_false, ty } => format!(
                "T{}({},{},{})",
                ty.tag(),
                cond.describe(),
                if_true.describe(),
                if_false.describe()
            ),
        }
    }
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

// ---------------------------------------------------------------------------
// 错误
// ---------------------------------------------------------------------------

/// 解析期错误。`line`/`col` 为 -1 表示「归约工厂抛错」（Kotlin `ExprEvalException` 无位置）。
#[derive(Clone, Debug)]
pub struct ExprError {
    pub line: i32,
    pub col: i32,
    pub message: String,
}

impl ExprError {
    fn parse(line: i32, col: i32, message: impl Into<String>) -> Self {
        ExprError { line, col, message: message.into() }
    }

    fn eval(message: impl Into<String>) -> Self {
        ExprError { line: -1, col: -1, message: message.into() }
    }
}

// ---------------------------------------------------------------------------
// 运算符属性（`Operator.kt:15-55` 的位编码）
// ---------------------------------------------------------------------------

fn op_value(op: OperatorCode) -> u32 {
    match op {
        OperatorCode::Null => 0x00000,
        OperatorCode::Plus => 0x30180,
        OperatorCode::Minus => 0x30280,
        OperatorCode::Mult => 0x20390,
        OperatorCode::Div => 0x20490,
        OperatorCode::Mod => 0x20590,
        OperatorCode::Equal => 0x20660,
        OperatorCode::Greater => 0x20765,
        OperatorCode::Less => 0x20865,
        OperatorCode::GreaterEqual => 0x20965,
        OperatorCode::LessEqual => 0x20A65,
        OperatorCode::NotEqual => 0x20B60,
        OperatorCode::Increment => 0x92000,
        OperatorCode::Decrement => 0x92100,
        OperatorCode::And => 0x20C40,
        OperatorCode::Or => 0x20D40,
        OperatorCode::Xor => 0x21540,
        OperatorCode::Nand => 0x21640,
        OperatorCode::Nor => 0x21740,
        OperatorCode::BitAnd => 0x20E50,
        OperatorCode::BitOr => 0x20F50,
        OperatorCode::BitXor => 0x21050,
        OperatorCode::Not => 0x11100,
        OperatorCode::BitNot => 0x11200,
        OperatorCode::RightShift => 0x21370,
        OperatorCode::LeftShift => 0x21470,
        OperatorCode::TernaryA => 0x41805,
        OperatorCode::TernaryB => 0x41910,
        OperatorCode::Assignment => 0x1FE,
        OperatorCode::AssignmentStr => 0x2FE,
    }
}

pub fn op_is_unary(op: OperatorCode) -> bool {
    op_value(op) & 0x10000 == 0x10000
}
pub fn op_is_unary_after(op: OperatorCode) -> bool {
    op_value(op) & 0x80000 == 0x80000
}
pub fn op_is_binary(op: OperatorCode) -> bool {
    op_value(op) & 0x20000 == 0x20000
}
pub fn op_is_ternary(op: OperatorCode) -> bool {
    op_value(op) & 0x40000 == 0x40000
}
pub fn op_priority(op: OperatorCode) -> i32 {
    (op_value(op) & 0xFF) as i32
}

fn op_from_name(name: &str) -> Option<OperatorCode> {
    Some(match name {
        "NULL" => OperatorCode::Null,
        "Plus" => OperatorCode::Plus,
        "Minus" => OperatorCode::Minus,
        "Mult" => OperatorCode::Mult,
        "Div" => OperatorCode::Div,
        "Mod" => OperatorCode::Mod,
        "Equal" => OperatorCode::Equal,
        "Greater" => OperatorCode::Greater,
        "Less" => OperatorCode::Less,
        "GreaterEqual" => OperatorCode::GreaterEqual,
        "LessEqual" => OperatorCode::LessEqual,
        "NotEqual" => OperatorCode::NotEqual,
        "Increment" => OperatorCode::Increment,
        "Decrement" => OperatorCode::Decrement,
        "And" => OperatorCode::And,
        "Or" => OperatorCode::Or,
        "Xor" => OperatorCode::Xor,
        "Nand" => OperatorCode::Nand,
        "Nor" => OperatorCode::Nor,
        "BitAnd" => OperatorCode::BitAnd,
        "BitOr" => OperatorCode::BitOr,
        "BitXor" => OperatorCode::BitXor,
        "Not" => OperatorCode::Not,
        "BitNot" => OperatorCode::BitNot,
        "RightShift" => OperatorCode::RightShift,
        "LeftShift" => OperatorCode::LeftShift,
        "Ternary_a" => OperatorCode::TernaryA,
        "Ternary_b" => OperatorCode::TernaryB,
        "Assignment" => OperatorCode::Assignment,
        "AssignmentStr" => OperatorCode::AssignmentStr,
        _ => return None,
    })
}

/// Kotlin `OperatorCode` 的枚举名（`LESS_EQUAL` / `NOT_EQUAL` / `TERNARY_A` …）。
///
/// 串行化必须用这个名字：Kotlin 夹具落的是 `e.op.name`，而本仓 `OperatorCode::name()`
/// 给的是 C# 驼峰名（`LessEqual`）。两者混用会让每一行带比较/位运算的表达式都差一个串。
pub fn op_enum_name(op: OperatorCode) -> &'static str {
    match op {
        OperatorCode::Null => "NULL",
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
    }
}

/// 源码符号文本（`OperatorCode.toOperatorString`），报错文案里用。
pub fn op_text(op: OperatorCode) -> &'static str {
    match op {
        OperatorCode::Plus => "+",
        OperatorCode::Minus => "-",
        OperatorCode::Mult => "*",
        OperatorCode::Div => "/",
        OperatorCode::Mod => "%",
        OperatorCode::Equal => "==",
        OperatorCode::Greater => ">",
        OperatorCode::Less => "<",
        OperatorCode::GreaterEqual => ">=",
        OperatorCode::LessEqual => "<=",
        OperatorCode::NotEqual => "!=",
        OperatorCode::And => "&&",
        OperatorCode::Or => "||",
        OperatorCode::Xor => "^^",
        OperatorCode::Nand => "!&",
        OperatorCode::Nor => "!|",
        OperatorCode::BitAnd => "&",
        OperatorCode::BitOr => "|",
        OperatorCode::BitXor => "^",
        OperatorCode::Not => "!",
        OperatorCode::BitNot => "~",
        OperatorCode::RightShift => ">>",
        OperatorCode::LeftShift => "<<",
        OperatorCode::TernaryA => "?",
        OperatorCode::TernaryB => "#",
        OperatorCode::Increment => "++",
        OperatorCode::Decrement => "--",
        OperatorCode::Assignment => "=",
        OperatorCode::AssignmentStr => "'=",
        OperatorCode::Null => "",
    }
}

// ---------------------------------------------------------------------------
// 词法 Token → 表达式 Token
// ---------------------------------------------------------------------------

#[derive(Clone, Debug)]
enum ETok {
    Int(i64, i32, i32),
    Float(f64, i32, i32),
    Str(String, i32, i32),
    Form(i32, i32),
    Ident(String, i32, i32),
    Op(OperatorCode, i32, i32),
    Sym(char, i32, i32),
    Eol,
}

fn is_ident_char(c: char) -> bool {
    c == '_' || c.is_alphanumeric() || !c.is_ascii()
}

fn to_expr_tokens(tokens: &[Token]) -> Vec<ETok> {
    let mut out = Vec::with_capacity(tokens.len() + 1);
    for t in tokens {
        let (line, col) = (t.line, t.col);
        out.push(match t.kind {
            TokKind::Ident => ETok::Ident(t.text.clone(), line, col),
            TokKind::Int => ETok::Int(t.text.parse::<i64>().unwrap_or(0), line, col),
            TokKind::Float => ETok::Float(t.text.parse::<f64>().unwrap_or(0.0), line, col),
            // `Token::string` 的 text 是「引号 + 内容 + 引号」（内容已解转义），去掉首尾即 value。
            TokKind::Str => {
                let raw = t.text.as_str();
                let inner = raw
                    .strip_prefix('"')
                    .and_then(|s| s.strip_suffix('"'))
                    .unwrap_or(raw);
                ETok::Str(inner.to_string(), line, col)
            }
            TokKind::Form => ETok::Form(line, col),
            TokKind::Op => match op_from_name(&t.text) {
                Some(op) => ETok::Op(op, line, col),
                None => ETok::Sym('\0', line, col),
            },
            TokKind::Sym => {
                let c = t.text.chars().next().unwrap_or('\0');
                if is_ident_char(c) && !matches!(c, '(' | ')' | '[' | ']' | ',' | ':' | '@' | '.') {
                    ETok::Sym(c, line, col)
                } else {
                    ETok::Sym(c, line, col)
                }
            }
        });
    }
    out.push(ETok::Eol);
    out
}

// ---------------------------------------------------------------------------
// 宽松档符号器（`DumpStmt.kt` / `LooseResolver.kt` 的对偶）
// ---------------------------------------------------------------------------

pub struct LooseTables {
    vars: std::collections::BTreeMap<String, (String, OperandType, i32, bool, bool)>,
    funcs: std::collections::BTreeMap<String, (OperandType, bool)>,
    methods: std::collections::BTreeSet<String>,
}

impl LooseTables {
    pub fn from_tables(t: &FunctionTables) -> Self {
        let mut vars = std::collections::BTreeMap::new();
        for (k, v) in t.var_rows().iter() {
            vars.insert(
                k.clone(),
                (v.name.clone(), OperandType::from_name(&v.ty), v.dimension, v.char_data, v.konst),
            );
        }
        let mut funcs = std::collections::BTreeMap::new();
        for (k, (ty, cref)) in t.func_rows().iter() {
            funcs.insert(k.clone(), (OperandType::from_name(ty), *cref));
        }
        LooseTables { vars, funcs, methods: t.method_names().clone() }
    }

    fn norm(name: &str) -> String {
        name.to_uppercase()
    }

    /// 内建变量名 → 元信息（`BuiltinSymbols.variable` 的等价：按大写键查）。
    fn builtin_var(&self, name: &str) -> Option<VarInfo> {
        let key = Self::norm(name);
        // VarInfo 的 name 是 &'static str，宽松档只需要「元信息」，名字由调用方保留原文；
        // 这里返回 dimension/type 等，name 用泄露字符串缓存（表是进程级只读，量级 ~1000）。
        self.vars.get(&key).map(|(canon, ty, dim, ch, k)| VarInfo {
            name: canon.clone(),
            ty: *ty,
            dimension: *dim,
            char_data: *ch,
            konst: *k,
        })
    }

    fn builtin_func(&self, name: &str) -> Option<OperandType> {
        self.funcs.get(&Self::norm(name)).map(|(ty, _)| *ty)
    }

    // 注：本方法只在 `canReturnFloat` 判据里使用「表里没有」这一事实。
    fn func_present(&self, name: &str) -> bool {
        self.funcs.contains_key(&Self::norm(name))
    }

    fn func_can_return_float(&self, name: &str) -> bool {
        self.funcs.get(&Self::norm(name)).map(|(_, c)| *c).unwrap_or(false)
    }
}

impl Resolver for LooseTables {
    fn resolve_variable(&self, name: &str, _sub_key: Option<&str>) -> Option<VarInfo> {
        if let Some(v) = self.builtin_var(name) {
            return Some(v);
        }
        // 未知变量折 1 维 INT（方案 2）。
        Some(VarInfo {
            name: name.to_string(),
            ty: OperandType::Int,
            dimension: 1,
            char_data: false,
            konst: false,
        })
    }

    fn resolve_function(&self, name: &str) -> Option<OperandType> {
        Some(self.builtin_func(name).unwrap_or(OperandType::Int))
    }

    fn can_return_float(&self, name: &str) -> bool {
        // Kotlin：`BuiltinSymbols.function(name) == null && BuiltinSymbols.canReturnFloat(name)`
        !self.func_present(name) && self.func_can_return_float(name)
    }

    fn can_be_referenced_bare(&self, name: &str) -> bool {
        self.func_present(name) || !self.methods.contains(&Self::norm(name))
    }
}

// ---------------------------------------------------------------------------
// 解析器
// ---------------------------------------------------------------------------

pub const TERM_EOL: i32 = 1;
pub const TERM_COMMA: i32 = 2;
pub const TERM_RPAREN: i32 = 4;
pub const TERM_ASSIGNMENT: i32 = 16;
pub const TERM_KEYWORD_PX: i32 = 32;

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum ArgsEnd {
    Eol,
    RightParenthesis,
}

enum Item {
    Term(Expr),
    Op(OperatorCode),
}

struct TermStack {
    /// 最近一次 `add_*` / `reduce_all` 调用时的游标位置（Kotlin `parseErr(cur.line, cur.col)`）。
    pos: (i32, i32),
    state: u8,
    has_before: bool,
    has_after: bool,
    wait_after: bool,
    stack: Vec<Item>,
}

impl TermStack {
    fn new() -> Self {
        TermStack {
            pos: (-1, -1),
            state: 0,
            has_before: false,
            has_after: false,
            wait_after: false,
            stack: Vec::new(),
        }
    }

    fn last_priority(&self) -> i32 {
        if self.stack.len() < 3 {
            return -1;
        }
        match self.stack.get(self.stack.len() - 2) {
            Some(Item::Op(op)) => op_priority(*op),
            _ => -1,
        }
    }

    fn bad(&self, message: impl Into<String>) -> ExprError {
        ExprError::parse(self.pos.0, self.pos.1, message)
    }

    fn pop_term(&mut self) -> Result<Expr, ExprError> {
        match self.stack.pop() {
            Some(Item::Term(e)) => Ok(e),
            _ => Err(self.bad("无法识别的语法")),
        }
    }

    fn pop_op(&mut self) -> Result<OperatorCode, ExprError> {
        match self.stack.pop() {
            Some(Item::Op(o)) => Ok(o),
            _ => Err(self.bad("无法识别的语法")),
        }
    }

    fn reduce_unary(&mut self) -> Result<(), ExprError> {
        let o = self.pop_term()?;
        let op = self.pop_op()?;
        let e = reduce_unary_term(op, o)?;
        self.stack.push(Item::Term(e));
        Ok(())
    }

    fn reduce_unary_after(&mut self) -> Result<(), ExprError> {
        let op = self.pop_op()?;
        let o = self.pop_term()?;
        let e = reduce_unary_after_term(op, o)?;
        self.stack.push(Item::Term(e));
        Ok(())
    }

    fn reduce_last_three(&mut self) -> Result<(), ExprError> {
        let right = self.pop_term()?;
        let op = self.pop_op()?;
        let left = self.pop_term()?;
        if op_is_ternary(op) {
            if self.stack.len() <= 1 {
                return Err(self.bad("表达式不足"));
            }
            self.stack.pop(); // 丢弃 '#'
            let cond = self.pop_term()?;
            let e = reduce_ternary_term(cond, left, right)?;
            self.stack.push(Item::Term(e));
        } else {
            let e = reduce_binary_term(op, left, right)?;
            self.stack.push(Item::Term(e));
        }
        Ok(())
    }

    fn add_op(&mut self, op: OperatorCode) -> Result<(), ExprError> {
        if self.state == 2 || self.state == 3 {
            return Err(self.bad("无法识别的语法"));
        }
        if self.state == 0 {
            if !op_is_unary(op) {
                return Err(self.bad("无法识别的语法"));
            }
            self.stack.push(Item::Op(op));
            self.state = if matches!(op, OperatorCode::Plus | OperatorCode::Minus | OperatorCode::BitNot)
            {
                2
            } else {
                3
            };
            return Ok(());
        }
        if op_is_unary_after(op) {
            if self.has_after {
                return Err(self.bad("连续出现多个后置一元运算符"));
            }
            if self.has_before {
                return Err(self.bad("不能同时使用前置与后置 ++/--"));
            }
            self.stack.push(Item::Op(op));
            self.reduce_unary_after()?;
            if self.wait_after {
                self.reduce_unary()?;
            }
            self.has_before = false;
            self.has_after = true;
            self.wait_after = false;
        } else {
            if !op_is_binary(op) && !op_is_ternary(op) {
                return Err(self.bad("无法识别的语法"));
            }
            if self.wait_after {
                self.reduce_unary()?;
            }
            let priority = op_priority(op);
            while self.last_priority() >= priority {
                self.reduce_last_three()?;
            }
            self.stack.push(Item::Op(op));
            self.state = 0;
            self.wait_after = false;
            self.has_before = false;
            self.has_after = false;
        }
        Ok(())
    }

    fn add_term(&mut self, e: Expr) -> Result<(), ExprError> {
        self.stack.push(Item::Term(e));
        if self.state == 1 {
            return Err(self.bad("无法识别的语法"));
        }
        if self.state == 2 {
            self.wait_after = true;
        }
        if self.state == 3 {
            self.reduce_unary()?;
            self.has_before = true;
        }
        self.state = 1;
        Ok(())
    }

    fn reduce_all(&mut self) -> Result<Option<Expr>, ExprError> {
        if self.stack.is_empty() {
            return Ok(None);
        }
        if self.state != 1 {
            return Err(self.bad("无法识别的语法"));
        }
        if self.wait_after {
            self.reduce_unary()?;
        }
        self.wait_after = false;
        self.has_before = false;
        self.has_after = false;
        while self.stack.len() > 1 {
            self.reduce_last_three()?;
        }
        match self.stack.pop() {
            Some(Item::Term(e)) => Ok(Some(e)),
            _ => Err(self.bad("无法识别的语法")),
        }
    }
}

pub struct ExprParser<'a> {
    toks: Vec<ETok>,
    pos: usize,
    resolver: &'a dyn Resolver,
    /// `ParserConfig.systemNoTarget` / `compatiRAND`（宽松档两者皆 false）。
    system_no_target: bool,
    compati_rand: bool,
}

impl<'a> ExprParser<'a> {
    pub fn new(tokens: &[Token], resolver: &'a dyn Resolver) -> Self {
        ExprParser {
            toks: to_expr_tokens(tokens),
            pos: 0,
            resolver,
            system_no_target: false,
            compati_rand: false,
        }
    }

    fn cur(&self) -> &ETok {
        &self.toks[self.pos.min(self.toks.len() - 1)]
    }

    fn peek(&self, off: usize) -> &ETok {
        &self.toks[(self.pos + off).min(self.toks.len() - 1)]
    }

    fn cur_pos(&self) -> (i32, i32) {
        match self.cur() {
            ETok::Int(_, l, c)
            | ETok::Float(_, l, c)
            | ETok::Str(_, l, c)
            | ETok::Form(l, c)
            | ETok::Ident(_, l, c)
            | ETok::Op(_, l, c)
            | ETok::Sym(_, l, c) => (*l, *c),
            ETok::Eol => (-1, -1),
        }
    }

    fn shift_next(&mut self) {
        if !matches!(self.cur(), ETok::Eol) {
            self.pos += 1;
        }
    }

    fn err(&self, msg: impl Into<String>) -> ExprError {
        let (l, c) = self.cur_pos();
        ExprError::parse(l, c, msg)
    }

    /// `reduceArguments`（`Parser.kt:262-318` 的非 define 分支）。
    pub fn reduce_arguments(&mut self, end_with: ArgsEnd) -> Result<Vec<Option<Expr>>, ExprError> {
        let term_end_with = match end_with {
            ArgsEnd::Eol => TERM_COMMA,
            ArgsEnd::RightParenthesis => TERM_RPAREN | TERM_COMMA,
        };
        let mut terms: Vec<Option<Expr>> = Vec::new();
        loop {
            match self.cur().clone() {
                ETok::Eol => {
                    if end_with == ArgsEnd::RightParenthesis {
                        return Err(self.err("未闭合的小括号"));
                    }
                    return Ok(terms);
                }
                ETok::Sym(')', _, _) => {
                    if end_with == ArgsEnd::RightParenthesis {
                        self.shift_next();
                        return Ok(terms);
                    }
                    return Err(self.err("不该出现的小括号"));
                }
                ETok::Sym(']', _, _) => {
                    return Err(self.err("不该出现的中括号"));
                }
                _ => {
                    terms.push(self.reduce_term(false, term_end_with, false, None)?);
                    if matches!(self.cur(), ETok::Sym(',', _, _)) {
                        self.shift_next();
                    }
                }
            }
        }
    }

    pub fn reduce_expression_term(&mut self) -> Result<Option<Expr>, ExprError> {
        self.reduce_term(false, TERM_EOL, false, None)
    }

    /// `reduceTerm`（`Parser.kt:339-438`）。
    pub fn reduce_term(
        &mut self,
        allow_keyword_to: bool,
        end_with: i32,
        single_pass: bool,
        index_owner: Option<&VarInfo>,
    ) -> Result<Option<Expr>, ExprError> {
        let _ = index_owner;
        let mut stack = TermStack::new();
        let mut num = 0i32;
        let mut last_op = OperatorCode::Null;
        loop {
            let t = self.cur().clone();
            match t {
                ETok::Eol => {
                    stack.pos = (-1, -1);
                    return end(stack, num);
                }
                ETok::Str(s, _, _) => {
                    stack.pos = self.cur_pos();
                    stack.add_term(Expr::StrLiteral(s))?
                }
                ETok::Int(v, _, _) => {
                    stack.pos = self.cur_pos();
                    stack.add_term(Expr::IntLiteral(v))?
                }
                ETok::Float(v, _, _) => {
                    stack.pos = self.cur_pos();
                    stack.add_term(Expr::FloatLiteral(v))?
                }
                ETok::Form(..) => return Err(self.err("FORM_STRING 缺少词法器 StrForm")),
                ETok::Ident(code, _, _) => {
                    if eq_ic(&code, "TO") {
                        if allow_keyword_to {
                            return end(stack, num);
                        }
                        return Err(self.err("不合法的 TO"));
                    }
                    if eq_ic(&code, "IS") {
                        return Err(self.err("不合法的 IS"));
                    }
                    if (end_with & TERM_KEYWORD_PX) != 0
                        && eq_ic(&code, "px")
                        && matches!(self.peek(1), ETok::Sym(',', _, _) | ETok::Eol)
                    {
                        return end(stack, num);
                    }
                    let node = self.reduce_identifier(&code, single_pass, index_owner)?;
                    stack.pos = self.cur_pos();
                    stack.add_term(node)?;
                    if single_pass {
                        return end(stack, num);
                    }
                    // 与 Kotlin 一致：identifier 分支自己越过标识符/下标链后直接 continue，
                    // 不再走循环底部的 shiftNext（多跳一次会吞掉下一个运算符）。
                    continue;
                }
                ETok::Op(op, _, _) => {
                    if single_pass {
                        return Err(self.err("变量参数中不该出现运算符"));
                    }
                    if op == OperatorCode::Assignment {
                        if (end_with & TERM_ASSIGNMENT) != 0 {
                            return end(stack, num);
                        }
                        return Err(self.err("表达式中不允许 '='"));
                    }
                    if is_comparison(last_op) && is_comparison(op) {
                        // Kotlin 侧只记 warning（夹具不导出告警），此处同样不落判据。
                    }
                    stack.pos = self.cur_pos();
                    stack.add_op(op)?;
                    last_op = op;
                    match op {
                        OperatorCode::TernaryA => num += 1,
                        OperatorCode::TernaryB => {
                            if num > 0 {
                                num -= 1;
                            } else {
                                return Err(self.err("'#' 之前缺少 '?'"));
                            }
                        }
                        _ => {}
                    }
                }
                ETok::Sym('(', _, _) => {
                    self.shift_next();
                    let inner = self.reduce_term(false, TERM_RPAREN, false, None)?;
                    let inner = match inner {
                        Some(e) => e,
                        None => return Err(self.err("括号内缺少表达式")),
                    };
                    stack.pos = self.cur_pos();
                    stack.add_term(inner)?;
                    if !matches!(self.cur(), ETok::Sym(')', _, _)) {
                        return Err(self.err("未闭合的小括号"));
                    }
                    self.shift_next();
                    if single_pass {
                        return end(stack, num);
                    }
                    continue;
                }
                ETok::Sym(')', _, _) => {
                    if (end_with & TERM_RPAREN) != 0 {
                        return end(stack, num);
                    }
                    return Err(self.err("不该出现的符号 ')'"));
                }
                ETok::Sym(']', _, _) => {
                    return Err(self.err("不该出现的符号 ']'"));
                }
                ETok::Sym(',', _, _) => {
                    if (end_with & TERM_COMMA) != 0 {
                        return end(stack, num);
                    }
                    return Err(self.err("不该出现的符号 ','"));
                }
                _ => return Err(self.err("不该出现的符号")),
            }
            self.shift_next();
            if single_pass {
                return end(stack, num);
            }
        }
    }

    fn reduce_identifier(
        &mut self,
        id_str: &str,
        in_variable_arg: bool,
        // 本标识符处在变量 index_owner 的 `:` 下标位置时非空。宽松档没有关键字三态
        // 查询（`resolveIndexKeyword` 一律按「无表」放行），此处只保留形参以便 P2-2c 严格档接线。
        _index_owner: Option<&VarInfo>,
    ) -> Result<Expr, ExprError> {
        let ident = self.cur_pos();
        self.shift_next();
        match self.cur().clone() {
            ETok::Sym('.', _, _) => return Err(self.err("成员访问 '.' 未实现")),
            ETok::Sym('(', _, _) => {
                self.shift_next();
                let args = self.reduce_arguments(ArgsEnd::RightParenthesis)?;
                return self.function_call(id_str, args, ident.0, ident.1);
            }
            ETok::Sym('[', _, _) => return Err(self.err("不支持中括号形式的函数调用")),
            _ => {}
        }
        let mut sub_key: Option<String> = None;
        let mut at_pos = (-1, -1);
        if matches!(self.cur(), ETok::Sym('@', _, _)) {
            at_pos = self.cur_pos();
            self.shift_next();
            match self.cur().clone() {
                ETok::Ident(s, _, _) => {
                    sub_key = Some(s);
                    self.shift_next();
                }
                _ => return Err(self.err("'@' 后缺少标识符")),
            }
        }
        if let Some(info) = self.resolver.resolve_variable(id_str, sub_key.as_deref()) {
            if in_variable_arg {
                return self.build_variable_term(&info, None, None, None, sub_key);
            }
            return self.reduce_variable(&info, sub_key);
        }
        if sub_key.is_some() {
            return Err(ExprError::parse(at_pos.0, at_pos.1, "@符号的非法用法"));
        }
        // 宽松档 `resolveIndexKeyword` 三态一律按 null 处理 → 关键字串回落后统一 StrLiteral。
        if in_variable_arg {
            return Ok(Expr::StrLiteral(id_str.to_string()));
        }
        if let Some(ty) = self.resolver.resolve_function(id_str) {
            if !self.resolver.can_be_referenced_bare(id_str) {
                return Err(self.err(format!("用变量的方式使用函数名\"{id_str}\"")));
            }
            return Ok(Expr::FuncCall { name: id_str.to_string(), args: Vec::new(), ty });
        }
        Err(self.err(format!("未定义的标识符 '{id_str}'")))
    }

    fn function_call(
        &mut self,
        name: &str,
        args: Vec<Option<Expr>>,
        line: i32,
        col: i32,
    ) -> Result<Expr, ExprError> {
        let ty = match self.resolver.resolve_function(name) {
            Some(t) => t,
            None => {
                if self.resolver.resolve_variable(name, None).is_some() {
                    return Err(ExprError::parse(
                        line,
                        col,
                        format!("用函数的方式使用变量名\"{name}\""),
                    ));
                }
                return Err(ExprError::parse(line, col, format!("未定义的标识符 '{name}'")));
            }
        };
        let ty = if ty == OperandType::Int
            && self.resolver.can_return_float(name)
            && args.iter().any(|a| a.as_ref().map(|e| e.operand_type()) == Some(OperandType::Float))
        {
            OperandType::Float
        } else {
            ty
        };
        Ok(Expr::FuncCall { name: name.to_string(), args, ty })
    }

    fn reduce_variable(&mut self, info: &VarInfo, sub_key: Option<String>) -> Result<Expr, ExprError> {
        let mut p1: Option<Expr> = None;
        let mut p2: Option<Expr> = None;
        let mut p3: Option<Expr> = None;
        let mut num = 0;
        while matches!(self.cur(), ETok::Sym(':', _, _)) {
            if num >= 3 {
                return Err(self.err(format!("变量 {} 的下标过多", info.name)));
            }
            self.shift_next();
            let e = self.reduce_variable_argument(info)?;
            match num {
                0 => p1 = Some(e),
                1 => p2 = Some(e),
                _ => p3 = Some(e),
            }
            num += 1;
        }
        self.build_variable_term(info, p1, p2, p3, sub_key)
    }

    fn reduce_variable_argument(&mut self, owner: &VarInfo) -> Result<Expr, ExprError> {
        self.reduce_term(false, TERM_EOL, true, Some(owner))?
            .ok_or_else(|| self.err("缺少 ':' 后的参数"))
    }

    fn target_ref(&self) -> Expr {
        Expr::VarRef {
            name: "TARGET".to_string(),
            indices: vec![Expr::IntLiteral(0)],
            is_no_arg: false,
            ty: OperandType::Int,
            is_const: false,
            sub_key: None,
        }
    }

    fn build_variable_term(
        &self,
        info: &VarInfo,
        p1: Option<Expr>,
        p2: Option<Expr>,
        p3: Option<Expr>,
        sub_key: Option<String>,
    ) -> Result<Expr, ExprError> {
        let name = info.name.to_string();
        let mut array: Vec<Expr> = Vec::new();
        let mut no_arg = false;
        if info.char_data {
            if info.dimension == 2 {
                if p1.is_none() && p2.is_none() && p3.is_none() {
                    no_arg = true;
                } else if p1.is_none() || p2.is_none() || p3.is_none() {
                    return Err(self.err(format!("变量 {name} 的下标不能省略")));
                } else {
                    array.push(p1.unwrap());
                    array.push(p2.unwrap());
                    array.push(p3.unwrap());
                }
            } else if info.dimension == 1 {
                if p3.is_some() {
                    return Err(self.err(format!("变量 {name} 的下标过多")));
                }
                if p1.is_none() && p2.is_none() && self.system_no_target {
                    no_arg = true;
                } else {
                    let mut a1 = p1;
                    let mut a2 = p2;
                    if a2.is_none() {
                        if self.system_no_target {
                            return Err(self.err(format!("变量 {name} 的下标不能省略")));
                        }
                        a2 = Some(a1.unwrap_or(Expr::IntLiteral(0)));
                        a1 = Some(self.target_ref());
                    }
                    array.push(a1.unwrap());
                    array.push(a2.unwrap());
                }
            } else {
                if p2.is_some() {
                    return Err(self.err(format!("变量 {name} 的下标过多")));
                }
                if p1.is_none() && p2.is_none() && p3.is_none() && self.system_no_target {
                    no_arg = true;
                } else {
                    let a1 = match p1 {
                        Some(e) => e,
                        None => {
                            if self.system_no_target {
                                return Err(self.err(format!("变量 {name} 的下标不能省略")));
                            }
                            self.target_ref()
                        }
                    };
                    array.push(a1);
                }
            }
        } else if info.dimension == 3 {
            if p1.is_none() && p2.is_none() && p3.is_none() {
                no_arg = true;
            } else if p1.is_none() || p2.is_none() || p3.is_none() {
                return Err(self.err(format!("变量 {name} 的下标不能省略")));
            } else {
                array.push(p1.unwrap());
                array.push(p2.unwrap());
                array.push(p3.unwrap());
            }
        } else if info.dimension == 2 {
            if p1.is_none() && p2.is_none() && p3.is_none() {
                no_arg = true;
            } else if p1.is_none() || p2.is_none() {
                return Err(self.err(format!("变量 {name} 的下标不能省略")));
            } else if p3.is_some() {
                return Err(self.err(format!("变量 {name} 的下标过多")));
            } else {
                array.push(p1.unwrap());
                array.push(p2.unwrap());
            }
        } else if info.dimension == 1 {
            if p2.is_some() {
                return Err(self.err(format!("变量 {name} 的下标过多")));
            }
            let a1 = match p1 {
                Some(e) => e,
                None => {
                    if !self.compati_rand && info.name == "RAND" {
                        return Err(self.err("RAND省略了一个参数"));
                    }
                    Expr::IntLiteral(0)
                }
            };
            if !self.compati_rand && info.name == "RAND" {
                if let Expr::IntLiteral(0) = a1 {
                    return Err(self.err("RAND有一个参数为0"));
                }
            }
            array.push(a1);
        } else {
            if p1.is_some() {
                return Err(self.err(format!("变量 {name} 不带下标")));
            }
        }
        if no_arg {
            return Ok(Expr::VarRef {
                name,
                indices: Vec::new(),
                is_no_arg: true,
                ty: info.ty,
                is_const: info.konst,
                sub_key,
            });
        }
        // 字符串下标包装：常量折叠关（`constantKeywordLookup = null`）⇒ 一律 KeywordIndexArg。
        let wrapped: Vec<Expr> = array
            .into_iter()
            .enumerate()
            .map(|(i, e)| {
                if e.operand_type() != OperandType::Str {
                    e
                } else {
                    Expr::KeywordIndexArg {
                        expr: Box::new(e),
                        var_name: name.clone(),
                        dim: i as i32,
                    }
                }
            })
            .collect();
        Ok(Expr::VarRef {
            name,
            indices: wrapped,
            is_no_arg: false,
            ty: info.ty,
            is_const: info.konst,
            sub_key,
        })
    }
}

fn end(stack: TermStack, ternary: i32) -> Result<Option<Expr>, ExprError> {
    if ternary > 0 {
        return Err(ExprError::eval("'?' 缺少对应的 '#'"));
    }
    let mut stack = stack;
    stack.reduce_all()
}

fn eq_ic(a: &str, b: &str) -> bool {
    a.eq_ignore_ascii_case(b)
}

fn is_comparison(op: OperatorCode) -> bool {
    matches!(
        op,
        OperatorCode::Equal
            | OperatorCode::Greater
            | OperatorCode::Less
            | OperatorCode::GreaterEqual
            | OperatorCode::LessEqual
            | OperatorCode::NotEqual
    )
}

fn type_name(t: OperandType) -> &'static str {
    match t {
        OperandType::Int => "数值型",
        OperandType::Str => "字符串型",
        OperandType::Float => "浮点型",
    }
}

fn can_not_applied_unary(type_name: &str, op: OperatorCode) -> String {
    format!("无法对{type_name}使用单相运算符\"{}\"", op_text(op))
}

fn can_not_applied_binary(l: OperandType, r: OperandType, op: OperatorCode) -> String {
    format!(
        "无法对{}和{}使用二相运算符\"{}\"进行运算",
        type_name(l),
        type_name(r),
        op_text(op)
    )
}

/// `reduceUnaryTerm`（`Parser.kt:858-877`）。
pub fn reduce_unary_term(op: OperatorCode, o1: Expr) -> Result<Expr, ExprError> {
    if matches!(op, OperatorCode::Increment | OperatorCode::Decrement) {
        match &o1 {
            Expr::VarRef { name, is_const, .. } => {
                if *is_const {
                    return Err(ExprError::eval(format!("不能对常量 {name} 使用 ++/--")));
                }
            }
            _ => return Err(ExprError::eval("++/-- 只能作用于变量")),
        }
    }
    let t = o1.operand_type();
    if t == OperandType::Int || t == OperandType::Float {
        if t == OperandType::Float && !matches!(op, OperatorCode::Minus | OperatorCode::Plus) {
            return Err(ExprError::eval(can_not_applied_unary(type_name(t), op)));
        }
        if op == OperatorCode::Plus {
            return Ok(o1);
        }
        return Ok(Expr::Unary { op, operand: Box::new(o1) });
    }
    Err(ExprError::eval(format!(
        "一元运算符 {} 不能作用于{}",
        op_text(op),
        type_name(t)
    )))
}

/// `reduceUnaryAfterTerm`（`Parser.kt:880-900`）。
pub fn reduce_unary_after_term(op: OperatorCode, o1: Expr) -> Result<Expr, ExprError> {
    if matches!(op, OperatorCode::Increment | OperatorCode::Decrement) {
        match &o1 {
            Expr::VarRef { name, is_const, .. } => {
                if *is_const {
                    return Err(ExprError::eval(format!("不能对常量 {name} 使用 ++/--")));
                }
            }
            _ => return Err(ExprError::eval("++/-- 只能作用于变量")),
        }
    }
    let t = o1.operand_type();
    if t == OperandType::Int && matches!(op, OperatorCode::Increment | OperatorCode::Decrement) {
        return Ok(Expr::UnaryAfter { op, operand: Box::new(o1) });
    }
    if t == OperandType::Float {
        return Err(ExprError::eval(can_not_applied_unary("未知类型", op)));
    }
    Err(ExprError::eval(format!(
        "运算符 {} 不能作用于{}",
        op_text(op),
        type_name(t)
    )))
}

/// `reduceBinaryTerm`（`Parser.kt:903-950`）。
pub fn reduce_binary_term(op: OperatorCode, left: Expr, right: Expr) -> Result<Expr, ExprError> {
    let lt = left.operand_type();
    let rt = right.operand_type();
    if lt == OperandType::Float || rt == OperandType::Float {
        if lt == OperandType::Str || rt == OperandType::Str {
            return Err(ExprError::eval(format!(
                "{} {} {}：不合法的组合",
                type_name(lt),
                op_text(op),
                type_name(rt)
            )));
        }
        let ty = match op {
            OperatorCode::Plus
            | OperatorCode::Minus
            | OperatorCode::Mult
            | OperatorCode::Div => OperandType::Float,
            OperatorCode::Equal
            | OperatorCode::NotEqual
            | OperatorCode::Greater
            | OperatorCode::Less
            | OperatorCode::GreaterEqual
            | OperatorCode::LessEqual => OperandType::Int,
            _ => return Err(ExprError::eval(can_not_applied_binary(lt, rt, op))),
        };
        return Ok(Expr::Binary {
            op,
            left: Box::new(left),
            right: Box::new(right),
            ty,
        });
    }
    let li = lt == OperandType::Int;
    let ri = rt == OperandType::Int;
    if li && ri {
        return Ok(Expr::Binary { op, left: Box::new(left), right: Box::new(right), ty: OperandType::Int });
    }
    if !li && !ri {
        match op {
            OperatorCode::Plus => {
                return Ok(Expr::Binary {
                    op,
                    left: Box::new(left),
                    right: Box::new(right),
                    ty: OperandType::Str,
                })
            }
            OperatorCode::Equal
            | OperatorCode::NotEqual
            | OperatorCode::Greater
            | OperatorCode::Less
            | OperatorCode::GreaterEqual
            | OperatorCode::LessEqual => {
                return Ok(Expr::Binary {
                    op,
                    left: Box::new(left),
                    right: Box::new(right),
                    ty: OperandType::Int,
                })
            }
            _ => {
                return Err(ExprError::eval(format!(
                    "字符串型 {} 字符串型：不合法的组合",
                    op_text(op)
                )))
            }
        }
    }
    if op == OperatorCode::Mult {
        return Ok(Expr::Binary { op, left: Box::new(left), right: Box::new(right), ty: OperandType::Str });
    }
    Err(ExprError::eval(format!(
        "{} {} {}：不合法的组合",
        type_name(lt),
        op_text(op),
        type_name(rt)
    )))
}

/// `reduceTernaryTerm`（`Parser.kt:953-968`）。
pub fn reduce_ternary_term(cond: Expr, if_true: Expr, if_false: Expr) -> Result<Expr, ExprError> {
    let ty = if cond.operand_type() == OperandType::Int
        && if_true.operand_type() == OperandType::Int
        && if_false.operand_type() == OperandType::Int
    {
        OperandType::Int
    } else if cond.operand_type() == OperandType::Int
        && if_true.operand_type() == OperandType::Str
        && if_false.operand_type() == OperandType::Str
    {
        OperandType::Str
    } else if cond.operand_type() == OperandType::Int
        && if_true.operand_type() != OperandType::Str
        && if_false.operand_type() != OperandType::Str
        && (if_true.operand_type() == OperandType::Float
            || if_false.operand_type() == OperandType::Float)
    {
        OperandType::Float
    } else {
        return Err(ExprError::eval("三目运算符的操作数类型不合法"));
    };
    Ok(Expr::Ternary {
        cond: Box::new(cond),
        if_true: Box::new(if_true),
        if_false: Box::new(if_false),
        ty,
    })
}

/// 便捷入口：参数原文 → 表达式列表（宽松档，`ArgsEndWith::EoL`）。
pub fn parse_arg_text(text: &str, line_no: i32, resolver: &dyn Resolver) -> Result<Vec<Option<Expr>>, ExprError> {
    let lexer = ErbLexer::new(LexerConfig::default());
    let mut st = CharStream::new(text);
    let tokens = lexer
        .analyse(&mut st, LexEnd::EoL, flag::NONE, line_no)
        .map_err(|e| ExprError::parse(line_no, e.column, e.message))?;
    let mut p = ExprParser::new(&tokens, resolver);
    p.reduce_arguments(ArgsEnd::Eol)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tables() -> LooseTables {
        let ft: &'static FunctionTables = Box::leak(Box::new(
            FunctionTables::from_tsv(include_str!("../tests/data/funcodes.tsv")).unwrap(),
        ));
        LooseTables::from_tables(ft)
    }


    #[test]
    fn binary_precedence() {
        let t = tables();
        let list = parse_arg_text("1+2*3", 1, &t).unwrap();
        assert_eq!(list[0].as_ref().unwrap().describe(), "BPLUS/I(L1,BMULT/I(L2,L3))");
    }

    #[test]
    fn comparison_chain() {
        let t = tables();
        let list = parse_arg_text("CHARANUM > 人物数量上限", 1, &t).unwrap();
        assert_eq!(list.len(), 1);
    }

    #[test]
    fn function_call_simple() {
        let t = tables();
        let list = parse_arg_text("MOB_COLOR_INIT(PALETTE)", 1, &t).unwrap();
        assert_eq!(list.len(), 1);
    }

    /// 全角引号 + `%` 行（语料里 4 处）的现状锚点：两侧都判「无法识别的语法」，
    /// 但 Kotlin 报下一个 token 的列、Rust 报当前 token 的列（差 1）。锁住现状，
    /// 后续谁改 `TermStack` 错误位置时必须同步更新这条断言。
    #[test]
    fn wide_quote_percent_line_errors_at_mod_op() {
        let t = tables();
        let err = parse_arg_text("『%petname%,我可以摸摸你吗？』", 1, &t).unwrap_err();
        assert_eq!(err.message, "无法识别的语法");
        assert_eq!(err.col, 9);
    }
}
