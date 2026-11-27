//! 参数构建层（P2-2b / B1 批）：`ArgBuilders` 的单表达式与常量族。
//!
//! 本版只移植 `ArgType` ∈ {VOID, EXPRESSION(_NULLABLE), INT_EXPRESSION(_NULLABLE),
//! STR(_NULLABLE), KEYWORD}（全语料约占 62% 的语句），其余批次（PRINT / CASE / INT_ANY /
//! SP_*）见 CONTRACT-RUSTFULL §6.13 的 B2..B6。
//!
//! 错误面**只比分类、不比文案**：官方的错误文本来自 `LangKey` 资源表，需要单独建表才能逐字可比，
//! 属语义面（方案 1）。夹具因此只落 `F`（该参数构造成立/不成立）而不落错误文本。
use crate::expr::{self, Expr, LooseTables, OperandType};
use crate::lexer::{flag, CharStream, ErbLexer, LexEnd, LexerConfig, TokKind};
use crate::stmt::FunctionTables;

/// 一次参数构造的结果。
pub enum ArgBuild {
    /// 构造成功。
    Ok(BuiltArg),
    /// 构造失败（对应 Kotlin `StmtParseException`）。
    Fail,
    /// 本版不量（参数原文含 FORM_STRING 词法 token）。
    Skip,
}

/// 构造成功的参数（只覆盖 B1 批会产生的两个 `Argument` 子类）。
pub struct BuiltArg {
    /// `VoidArgument` / `ExpressionArgument`（Kotlin 夹具落 `::class.simpleName`）。
    pub class: &'static str,
    /// `Argument.shape`（Kotlin 侧返回的是 `ArgType`：`VOID` 或 `EXPRESSION`）。
    pub shape: &'static str,
    pub is_const: bool,
    /// Kotlin `Argument.constStr` 是非空字段，缺省空串。
    pub const_str: String,
    /// Kotlin `Argument.constInt` 是非空字段，缺省 0。
    pub const_int: i64,
    /// `ExpressionArgument.term`（`None` 对应原版的可空形态，夹具落 `-`）。
    pub term: Option<Expr>,
    /// 多值参数（`SpPrintVArgument` 一类没有单 term 的参数）的夹具正文，优先于 `term`。
    pub multi: Option<String>,
}

enum PopTerms {
    Terms(Vec<Option<Expr>>),
    /// 出现 FORM_STRING 词法 token（本版不量）。
    FormSkip,
    /// 解析失败（对应 Kotlin `popTerms` 抛异常 → `ArgBuilders.create` 折成 `StmtParseException`）。
    Fail,
}

/// 参数原文 → `popTerms` 结果（`ParseSupport.kt:298` 的 `reduceArguments(EoL)`）。
fn pop_terms(text: &str, line: i32, loose: &LooseTables) -> PopTerms {
    let lexer = ErbLexer::new(LexerConfig::default());
    let mut st = CharStream::new(text);
    let tokens = match lexer.analyse(&mut st, LexEnd::EoL, flag::NONE, line) {
        Ok(t) => t,
        Err(_) => return PopTerms::Fail,
    };
    if tokens.iter().any(|t| t.kind == TokKind::Form) {
        return PopTerms::FormSkip;
    }
    let mut p = expr::ExprParser::new(&tokens, loose);
    match p.reduce_arguments(expr::ArgsEnd::Eol) {
        Ok(v) => PopTerms::Terms(v),
        Err(_) => PopTerms::Fail,
    }
}

fn const_int_of(term: &Option<Expr>) -> Option<i64> {
    match term {
        Some(Expr::IntLiteral(v)) => Some(*v),
        _ => None,
    }
}

/// 按 `ArgType` 构造参数。`arg_type` 取 `funcodes.tsv` 的 `code` 段（`FunctionCode.argType`）；
/// `code_name` 只给 PRINT 家族用（它的参数形态编在**语句名后缀**里，不走 `argType` 直分）。
pub fn build(code_name: &str, arg_type: &str, arg_text: &str, line: i32, loose: &LooseTables) -> ArgBuild {
    // PRINT 家族（全语料 128 万条 = 38%）：参数形态由后缀决定（`PrintSpec.kt:44-125`）。
    if arg_type == "PRINT" {
        return match print_kind(code_name) {
            None => ArgBuild::Fail,
            // FORM 串要 `FormStringTerm`（P2-2c 未移植面），本批记 skip。
            Some(PrintKind::FormStrNullable) => ArgBuild::Skip,
            Some(PrintKind::PrintV) => print_v(arg_text, line, loose),
            Some(PrintKind::StrExpr) => str_expr(arg_text, line, loose, false),
            Some(PrintKind::StrNullable) => ArgBuild::Ok(BuiltArg {
                class: "ExpressionArgument",
                shape: "EXPRESSION",
                is_const: true,
                const_str: arg_text.to_string(),
                const_int: 0,
                term: Some(Expr::StrLiteral(arg_text.to_string())),
                multi: None,
            }),
        };
    }

    // VOID：余空只发告警，永不失败（`ArgBuilders.kt:279-284`）。
    if arg_type == "VOID" {
        return ArgBuild::Ok(BuiltArg {
            class: "VoidArgument",
            shape: "VOID",
            is_const: false,
            const_str: String::new(),
            const_int: 0,
            term: None,
            multi: None,
        });
    }

    // STR / KEYWORD / STR_NULLABLE：原文直取，不裁剪（`ArgBuilders.kt:287-294`）。
    if matches!(arg_type, "STR" | "KEYWORD" | "STR_NULLABLE") {
        if arg_text.is_empty() && arg_type != "STR_NULLABLE" {
            return ArgBuild::Fail;
        }
        return ArgBuild::Ok(BuiltArg {
            class: "ExpressionArgument",
            shape: "EXPRESSION",
            is_const: true,
            const_str: arg_text.to_string(),
            const_int: 0,
            term: Some(Expr::StrLiteral(arg_text.to_string())),
            multi: None,
        });
    }

    // 非 PRINT 家族但同形态的类型（全语料 12 个码：`LOADCHARA`/`PLAYBGM` 等 STR_EXPRESSION，
    // 以及 `SP_PRINTV` 直给的情况）。与上面的 PRINT 分支共用同一批构造函数。
    if arg_type == "SP_PRINTV" {
        return print_v(arg_text, line, loose);
    }
    if matches!(arg_type, "STR_EXPRESSION" | "STR_EXPRESSION_NULLABLE") {
        return str_expr(arg_text, line, loose, arg_type == "STR_EXPRESSION_NULLABLE");
    }
    // INT_ANY：可变个数的整型实参（`ArgBuilders.kt:415-435`，`ExpressionArrayArgument`）。
    if arg_type == "INT_ANY" {
        return int_any(code_name, arg_text, line, loose);
    }

    let nullable = arg_type.ends_with("_NULLABLE");    let terms = match pop_terms(arg_text, line, loose) {
        PopTerms::Terms(v) => v,
        PopTerms::FormSkip => return ArgBuild::Skip,
        PopTerms::Fail => return ArgBuild::Fail,
    };

    match arg_type {
        "EXPRESSION" | "EXPRESSION_NULLABLE" => {
            // types = [VOID] ⇒ 不做类型检查；非 nullable 时 list.size < 1 即「参数不足」。
            if !nullable && terms.is_empty() {
                return ArgBuild::Fail;
            }
            // 多余参数只发告警（`tooMany`），取第一个。
            let first = terms.into_iter().next();
            // 空槽位（`IF A, , B` 里的第二个）只要槽位类型非 null 就是「无法识别」，
            // `typeof(void)` 也不例外（CMP-47，`ArgBuilders.kt:230-233`）。
            if matches!(first, Some(None)) {
                return ArgBuild::Fail;
            }
            let term = first.flatten();
            if term.is_none() {
                return ArgBuild::Ok(BuiltArg {
                    class: "ExpressionArgument",
                    shape: "EXPRESSION",
                    is_const: true,
                    const_str: String::new(),
                    const_int: 0,
                    term: None,
                    multi: None,
                });
            }
            ArgBuild::Ok(BuiltArg {
                class: "ExpressionArgument",
                shape: "EXPRESSION",
                is_const: false,
                const_str: String::new(),
                const_int: 0,
                term,
                multi: None,
            })
        }
        "INT_EXPRESSION" | "INT_EXPRESSION_NULLABLE" => {
            let first = terms.into_iter().next();
            if matches!(first, Some(None)) {
                return ArgBuild::Fail;
            }
            let term = first.flatten();
            if let Some(t) = &term {
                if t.operand_type() != OperandType::Int {
                    return ArgBuild::Fail;
                }
            }
            // 省略实参 ⇒ 补 `IntLiteral(0)`；非 nullable 只发告警（`ArgBuilders.kt:362-412`）。
            let term = term.unwrap_or(Expr::IntLiteral(0));
            // REPEAT 会改写成 `SpForNextArgument`（`ArgBuilders.kt:383-397`），属 B4 批；
            // 调用方在 `arg-dump` 里按语句码 REPEAT 整行跳过。
            let ci = const_int_of(&Some(term.clone()));
            ArgBuild::Ok(BuiltArg {
                class: "ExpressionArgument",
                shape: "EXPRESSION",
                is_const: ci.is_some(),
                const_str: String::new(),
                const_int: ci.unwrap_or(0),
                term: Some(term),
                multi: None,
            })
        }
        _ => ArgBuild::Skip,
    }
}

/// PRINT 家族的参数形态（`PrintSpec.kt:44-125` 的后缀解析，只取影响参数构造的部分）。
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum PrintKind {
    /// `PRINTV…`：逐项输出表达式（`ArgType.SP_PRINTV`）。
    PrintV,
    /// `PRINTS…` / `PRINTFORMS…`：字符串表达式（`ArgType.STR_EXPRESSION`）。
    StrExpr,
    /// `PRINTFORM…`：FORM 串（`ArgType.FORM_STR_NULLABLE`，需 `FormStringTerm`）。
    FormStrNullable,
    /// 其余：原文直取（`ArgType.STR_NULLABLE`）。
    StrNullable,
}

/// 解析 PRINT 家族语句名 → 参数形态（等价 `PrintSpec.parse` 的 `argType` 分支）。
///
/// 后缀文法：`PRINT [SINGLE] [V|S|FORMS|FORM] [LC|C] [K] [D] [N] [L|W]`，
/// 未消费完即 `None`（原版 `FunctionIdentifier.cs:167-170` 抛 `ExeEE` ⇒ 构造失败）。
pub fn print_kind(code_name: &str) -> Option<PrintKind> {
    let mut s = code_name.strip_prefix("PRINT")?;
    if let Some(rest) = s.strip_prefix("SINGLE") {
        s = rest;
    }
    let (kind, rest) = if let Some(r) = s.strip_prefix('V') {
        (PrintKind::PrintV, r)
    } else if let Some(r) = s.strip_prefix('S') {
        (PrintKind::StrExpr, r)
    } else if let Some(r) = s.strip_prefix("FORMS") {
        (PrintKind::StrExpr, r)
    } else if let Some(r) = s.strip_prefix("FORM") {
        (PrintKind::FormStrNullable, r)
    } else {
        (PrintKind::StrNullable, s)
    };
    let mut s = rest;
    if let Some(r) = s.strip_prefix("LC") {
        s = r;
    } else if let Some(r) = s.strip_prefix('C') {
        s = r;
    }
    for tag in ["K", "D", "N"] {
        if let Some(r) = s.strip_prefix(tag) {
            s = r;
        }
    }
    if let Some(r) = s.strip_prefix('L') {
        s = r;
    } else if let Some(r) = s.strip_prefix('W') {
        s = r;
    }
    if !s.is_empty() {
        return None;
    }
    Some(kind)
}

/// `INT_ANY`：可变个数整型实参（`ArgBuilders.kt:415-435`）。
///
/// 类型闸走 `checkTypes(types=[INT], minArg=0, argAny=true)`（`ArgBuilders.kt:221-240`）：
/// 每个实参都按 INT 校验（空槽位 = 「无法识别」直接失败），多给不算错。
fn int_any(code_name: &str, text: &str, line: i32, loose: &LooseTables) -> ArgBuild {
    let mut terms = match pop_terms(text, line, loose) {
        PopTerms::Terms(v) => v,
        PopTerms::FormSkip => return ArgBuild::Skip,
        PopTerms::Fail => return ArgBuild::Fail,
    };
    for t in &terms {
        match t {
            None => return ArgBuild::Fail,
            Some(e) if e.operand_type() != OperandType::Int => return ArgBuild::Fail,
            _ => {}
        }
    }
    let mut is_const = false;
    let mut const_int = 0i64;
    if terms.is_empty() {
        // `RETURN` 省略实参 ⇒ 补 `0` 并记常量（`ArgBuilders.kt:419-425`）。
        if code_name.eq_ignore_ascii_case("RETURN") {
            terms.push(Some(Expr::IntLiteral(0)));
            is_const = true;
        } else {
            return ArgBuild::Fail;
        }
    }
    if terms.len() == 1 {
        if let Some(Some(Expr::IntLiteral(v))) = terms.first() {
            is_const = true;
            const_int = *v;
        }
    }
    let body = terms
        .iter()
        .map(|e| match e {
            Some(x) => x.describe(),
            None => "-".to_string(),
        })
        .collect::<Vec<_>>()
        .join("|");
    ArgBuild::Ok(BuiltArg {
        class: "ExpressionArrayArgument",
        shape: "INT_ANY",
        is_const,
        const_str: String::new(),
        const_int,
        term: None,
        multi: Some(format!("AR({body})")),
    })
}

/// `PRINTV` 族（`ArgBuilders.kt:521-535`，词法私用标志 `AllowSingleQuotationStr`）。
fn print_v(text: &str, line: i32, loose: &LooseTables) -> ArgBuild {
    let lexer = ErbLexer::new(LexerConfig::default());
    let mut st = CharStream::new(text);
    let tokens = match lexer.analyse(&mut st, LexEnd::EoL, flag::PRINT_V, line) {
        Ok(t) => t,
        Err(_) => return ArgBuild::Fail,
    };
    if tokens.iter().any(|t| t.kind == TokKind::Form) {
        return ArgBuild::Skip;
    }
    let mut p = expr::ExprParser::new(&tokens, loose);
    let list = match p.reduce_arguments(expr::ArgsEnd::Eol) {
        Ok(v) => v,
        Err(_) => return ArgBuild::Fail,
    };
    // 任一参数被省略即失败（`ArgBuilders.kt:531-533`）。
    if list.iter().any(|e| e.is_none()) {
        return ArgBuild::Fail;
    }
    let terms: Vec<Expr> = list.into_iter().flatten().collect();
    let body = terms
        .iter()
        .map(|e| e.describe())
        .collect::<Vec<_>>()
        .join("|");
    ArgBuild::Ok(BuiltArg {
        class: "SpPrintVArgument",
        shape: "SP_PRINTV",
        is_const: false,
        const_str: String::new(),
        const_int: 0,
        term: None,
        multi: Some(format!("PV({body})")),
    })
}

/// `PRINTS` / `PRINTFORMS` 族：字符串表达式（`ArgBuilders.kt:strExpression`）。
fn str_expr(text: &str, line: i32, loose: &LooseTables, nullable: bool) -> ArgBuild {
    let terms = match pop_terms(text, line, loose) {
        PopTerms::Terms(v) => v,
        PopTerms::FormSkip => return ArgBuild::Skip,
        PopTerms::Fail => return ArgBuild::Fail,
    };
    // 零实参：`nullable` 才落空串常量（`ArgBuilders.kt` 的 `strExpression` 空列表分支；
    // 非 nullable 时 `checkTypes(minArg = -1)` 已经先报「参数不足」，到不了那个分支）。
    if terms.is_empty() && !nullable {
        return ArgBuild::Fail;
    }
    if terms.is_empty() {
        return ArgBuild::Ok(BuiltArg {
            class: "ExpressionArgument",
            shape: "EXPRESSION",
            is_const: true,
            const_str: String::new(),
            const_int: 0,
            term: Some(Expr::StrLiteral(String::new())),
            multi: None,
        });
    }
    if matches!(terms[0], None) {
        return ArgBuild::Fail;
    }
    let term = terms.into_iter().next().flatten();
    match &term {
        Some(t) if t.operand_type() == OperandType::Str => ArgBuild::Ok(BuiltArg {
            class: "ExpressionArgument",
            shape: "EXPRESSION",
            is_const: false,
            const_str: String::new(),
            const_int: 0,
            term,
            multi: None,
        }),
        _ => ArgBuild::Fail,
    }
}

/// 夹具序列化：`A <行号> <类名> <shape> <isConst> "<constStr>" <constInt> <term>`。
pub fn describe_line(line: i32, arg: &BuiltArg) -> String {
    let term = match (&arg.multi, &arg.term) {
        (Some(m), _) => m.clone(),
        (None, Some(t)) => t.describe(),
        (None, None) => "-".to_string(),
    };
    format!(
        "A {} {} {} {} {} {} {}\n",
        line,
        arg.class,
        arg.shape,
        if arg.is_const { 1 } else { 0 },
        quote(&arg.const_str),
        arg.const_int,
        term
    )
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

/// `ArgType` 名是否在 B1 批（单表达式与常量族）。
pub fn in_b1(arg_type: &str) -> bool {
    matches!(
        arg_type,
        "VOID"
            | "EXPRESSION"
            | "EXPRESSION_NULLABLE"
            | "INT_EXPRESSION"
            | "INT_EXPRESSION_NULLABLE"
            | "STR"
            | "KEYWORD"
            | "STR_NULLABLE"
    )
}

/// B2 批（`PRINT` 家族）与同形态类型：`argType` 为 `PRINT` 时真正的形态要看语句名后缀；
/// `STR_EXPRESSION` / `SP_PRINTV` 则是直给的（`LOADCHARA`/`PLAYBGM` 等 12 个码）。
pub fn in_b2(arg_type: &str, code_name: &str) -> bool {
    matches!(
        arg_type,
        "STR_EXPRESSION" | "STR_EXPRESSION_NULLABLE" | "SP_PRINTV" | "INT_ANY"
    )
        || (arg_type == "PRINT" && code_name.to_ascii_uppercase().starts_with("PRINT"))
}

/// 便捷入口：当前 `FunctionCode` 的 argType（`funcodes.tsv` 的 `code` 段）。
pub fn arg_type_of(tables: &FunctionTables, code: &str) -> Option<String> {
    tables.code_of(code).map(|r| r.arg_type.clone())
}
