//! 跨语言语义表的 Rust 读取面（CONTRACT-RUSTFULL §6.3 第 2 项）。
//!
//! Kotlin 腿是这些属性的**唯一事实源**（内建函数在 `Builtins.registry`，指令在
//! `Opcode.NAMES` + `StatementAliases.ALL`）；Rust 腿不许手抄，只读导出的 TSV：
//!
//! ```text
//! erbcc dump-builtins --out <tsv>     # 函数表
//! erbcc dump-opcodes  --out <tsv>     # 指令表（opcode 编号 / 别名 / kind 段）
//! ```
//!
//! 约定（导出一侧已钉住，读取一侧据此校验）：
//! - 行首 `#` 是注释，`# section: <tag>` 开新段；
//! - TSV 制表符分隔，缺项写 `-`（不用空字段，避免解析歧义）；
//! - 排序一律 UTF-16 码元序（`String.compareTo`），因此同一注册面两次导出逐字节相同；
//! - 函数名查表大小写不敏感（源侧 `Builtins.kt:914` 的 `String.CASE_INSENSITIVE_ORDER`，
//!   ASCII 域内等价于逐字节 `to_ascii_lowercase` 比较）。
//!
//! 表解析错误用 `String` 而不是 `ErbccError`：这些表只在开发期/测试期装载，不进编译错误面
//! （编译产物错误的统一形态仍走 `ErbccError`）。

use std::collections::BTreeMap;

/// 成对变参组（`Builtins.kt:829` 的 `VariadicGroup`）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Variadic {
    pub fixed_prefix: i32,
    pub group_size: i32,
    pub exact_form_args: i32,
    pub min_group_args: i32,
}

/// 一条内建函数描述（`Builtins.kt:860` 的 `BuiltinMethod` 的可导出投影）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BuiltinRow {
    pub name: String,
    /// `OperandType` 的枚举名（`INT` / `STR` / `FLOAT` / `BOOL` …）。
    pub ret_type: String,
    pub min_args: i32,
    /// `-1` = 可变参数（原版 `ArgType.Variadic`）。
    pub max_args: i32,
    pub omit_start: i32,
    pub variadic: Option<Variadic>,
    /// 仍未落地的能力（调用会抛「尚未实现」）。
    pub placeholder: bool,
    /// 需要未求值的实参表达式（如 `IIF`、`ARRAYMSORT` 家族）。
    pub needs_expr_args: bool,
}

/// 函数表。保留导出顺序（UTF-16 码元序），另建小写索引供查询。
#[derive(Debug, Clone, Default)]
pub struct BuiltinTable {
    rows: Vec<BuiltinRow>,
    index: BTreeMap<String, usize>,
}

impl BuiltinTable {
    pub fn from_tsv(text: &str) -> Result<Self, String> {
        let mut rows = Vec::new();
        for (lineno, raw) in text.lines().enumerate() {
            let line = raw.trim_end_matches('\r');
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let cols: Vec<&str> = line.split('\t').collect();
            if cols.len() != 8 {
                return Err(format!("内建表第 {} 行列数 {} ≠ 8: {}", lineno + 1, cols.len(), line));
            }
            let variadic = match cols[5] {
                "-" => None,
                v => {
                    let parts: Vec<&str> = v.split('/').collect();
                    if parts.len() != 4 {
                        return Err(format!("内建表第 {} 行 variadic 段数 {} ≠ 4: {}", lineno + 1, parts.len(), v));
                    }
                    Some(Variadic {
                        fixed_prefix: parts[0].parse().map_err(|e| format!("fixedPrefix: {e}"))?,
                        group_size: parts[1].parse().map_err(|e| format!("groupSize: {e}"))?,
                        exact_form_args: parts[2].parse().map_err(|e| format!("exactFormArgs: {e}"))?,
                        min_group_args: parts[3].parse().map_err(|e| format!("minGroupArgs: {e}"))?,
                    })
                }
            };
            rows.push(BuiltinRow {
                name: cols[0].to_string(),
                ret_type: cols[1].to_string(),
                min_args: cols[2].parse().map_err(|e| format!("minArgs: {e}"))?,
                max_args: cols[3].parse().map_err(|e| format!("maxArgs: {e}"))?,
                omit_start: cols[4].parse().map_err(|e| format!("omitStart: {e}"))?,
                variadic,
                placeholder: cols[6] == "1",
                needs_expr_args: cols[7] == "1",
            });
        }
        let mut index = BTreeMap::new();
        for (i, r) in rows.iter().enumerate() {
            let key = r.name.to_ascii_lowercase(); // 源侧 CASE_INSENSITIVE_ORDER 的 ASCII 等价
            if index.insert(key, i).is_some() {
                return Err(format!("内建表重名（大小写折叠后）：{}", r.name));
            }
        }
        Ok(Self { rows, index })
    }

    pub fn len(&self) -> usize {
        self.rows.len()
    }

    pub fn is_empty(&self) -> bool {
        self.rows.is_empty()
    }

    pub fn rows(&self) -> &[BuiltinRow] {
        &self.rows
    }

    /// 大小写不敏感查询（对应 `BuiltinRegistry.get`）。
    pub fn get(&self, name: &str) -> Option<&BuiltinRow> {
        self.index.get(&name.to_ascii_lowercase()).map(|&i| &self.rows[i])
    }
}

/// 一个操作码（`Opcode.NAMES` 的一行）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OpcodeEntry {
    pub idx: u32,
    pub name: String,
    /// `internal` / kind 名（`flow`…`other`）/ `stack` / `extension` / `-`。
    pub class: String,
}

/// 一条语句别名（`StatementAliases.ALL` 的一行）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AliasEntry {
    pub name: String,
    pub opcode: u32,
    pub variant: i32,
    pub canonical: String,
}

/// 指令表：opcode 编号、语句别名、kind 段边界与三个常量。
#[derive(Debug, Clone, Default)]
pub struct OpcodeTable {
    pub internal_count: u32,
    pub max_statement_opcode: u32,
    opcodes: Vec<OpcodeEntry>,
    aliases: Vec<AliasEntry>,
    kinds: Vec<(String, u32, u32)>,
}

impl OpcodeTable {
    pub fn from_tsv(text: &str) -> Result<Self, String> {
        let mut t = OpcodeTable::default();
        let mut declared_count: Option<u32> = None;
        for (lineno, raw) in text.lines().enumerate() {
            let line = raw.trim_end_matches('\r');
            if line.is_empty() || line == "#" {
                continue;
            }
            let ctx = |what: &str| format!("指令表第 {} 行 {}: {}", lineno + 1, what, line);
            if let Some(rest) = line.strip_prefix("# const ") {
                let mut it = rest.split('\t');
                let k = it.next().ok_or_else(|| ctx("const 缺键"))?;
                let v: u32 = it.next().ok_or_else(|| ctx("const 缺值"))?
                    .parse().map_err(|e| ctx(&format!("const 值不是整数({e})")))?;
                match k {
                    "internalCount" => t.internal_count = v,
                    "maxStatementOpcode" => t.max_statement_opcode = v,
                    "opcodeCount" => declared_count = Some(v),
                    other => return Err(ctx(&format!("未知 const: {other}"))),
                }
                continue;
            }
            if line.starts_with('#') {
                continue;
            }
            let cols: Vec<&str> = line.split('\t').collect();
            match cols[0] {
                "opcode" => {
                    if cols.len() != 4 {
                        return Err(ctx("opcode 段列数 ≠ 4"));
                    }
                    t.opcodes.push(OpcodeEntry {
                        idx: cols[1].parse().map_err(|e| ctx(&format!("idx: {e}")))?,
                        name: cols[2].to_string(),
                        class: cols[3].to_string(),
                    });
                }
                "alias" => {
                    if cols.len() != 5 {
                        return Err(ctx("alias 段列数 ≠ 5"));
                    }
                    t.aliases.push(AliasEntry {
                        name: cols[1].to_string(),
                        opcode: cols[2].parse().map_err(|e| ctx(&format!("opcode: {e}")))?,
                        variant: cols[3].parse().map_err(|e| ctx(&format!("variant: {e}")))?,
                        canonical: cols[4].to_string(),
                    });
                }
                "kind" => {
                    if cols.len() != 4 {
                        return Err(ctx("kind 段列数 ≠ 4"));
                    }
                    t.kinds.push((
                        cols[1].to_string(),
                        cols[2].parse().map_err(|e| ctx(&format!("lo: {e}")))?,
                        cols[3].parse().map_err(|e| ctx(&format!("hi: {e}")))?,
                    ));
                }
                other => return Err(ctx(&format!("未知段名: {other}"))),
            }
        }
        // 编号必须连续无空洞：`tableswitch` 与「idx → 名字」都靠这一点。
        for (i, e) in t.opcodes.iter().enumerate() {
            if e.idx as usize != i {
                return Err(format!("opcode 编号不连续：第 {} 条落 idx={}（应 {}）", i + 1, e.idx, i));
            }
        }
        if let Some(dc) = declared_count {
            if dc as usize != t.opcodes.len() {
                return Err(format!("声明 opcodeCount={dc} 与实际 {} 行不符", t.opcodes.len()));
            }
        } else {
            return Err("指令表缺 opcodeCount 声明".to_string());
        }
        Ok(t)
    }

    pub fn opcodes(&self) -> &[OpcodeEntry] {
        &self.opcodes
    }

    pub fn aliases(&self) -> &[AliasEntry] {
        &self.aliases
    }

    pub fn kinds(&self) -> &[(String, u32, u32)] {
        &self.kinds
    }

    pub fn name_of(&self, idx: u32) -> Option<&str> {
        self.opcodes.get(idx as usize).map(|e| e.name.as_str())
    }

    /// 语句别名解析（对应 `StatementAliases.BY_NAME`，ERB 书写面大小写按原样）。
    pub fn alias(&self, name: &str) -> Option<&AliasEntry> {
        self.aliases.iter().find(|a| a.name == name)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builtin_table_parses_and_looks_up_case_insensitively() {
        let text = "# h\n# name\tret\tmin\tmax\tomit\tvariadic\tph\texpr\n\
                    ABS\tINT\t1\t1\t-1\t-\t0\t0\n\
                    IIF\tINT\t3\t3\t-1\t-\t0\t1\n";
        let t = BuiltinTable::from_tsv(text).expect("解析");
        assert_eq!(t.len(), 2);
        assert_eq!(t.get("abs").map(|r| r.name.as_str()), Some("ABS"));
        assert_eq!(t.get("AbS").map(|r| r.name.as_str()), Some("ABS"));
        assert!(!t.get("IIF").unwrap().placeholder);
        assert!(t.get("IIF").unwrap().needs_expr_args);
    }

    #[test]
    fn builtin_table_rejects_wrong_column_count() {
        let t = BuiltinTable::from_tsv("ABS\tINT\t1\n");
        assert!(t.is_err());
    }

    #[test]
    fn variadic_group_parses_four_fields() {
        let text = "MULTI\tINT\t2\t-1\t-1\t1/2/4/3\t0\t0\n";
        let t = BuiltinTable::from_tsv(text).unwrap();
        let v = t.get("MULTI").unwrap().variadic.unwrap();
        assert_eq!(v, Variadic { fixed_prefix: 1, group_size: 2, exact_form_args: 4, min_group_args: 3 });
    }

    #[test]
    fn opcode_table_rejects_gaps() {
        let text = "# const internalCount\t2\n# const maxStatementOpcode\t3\n# const opcodeCount\t2\n\
                    opcode\t0\t_NOP\tinternal\nopcode\t2\tB\tflow\n";
        let t = OpcodeTable::from_tsv(text);
        assert!(t.is_err(), "编号跳号必须被拒：{}", t.unwrap_err());
    }

    #[test]
    fn opcode_table_parses_all_three_sections() {
        let text = "# const internalCount\t1\n# const maxStatementOpcode\t1\n# const opcodeCount\t2\n\
                    opcode\t0\t_NOP\tinternal\nopcode\t1\tBEGIN\tflow\n\
                    alias\tBEGIN\t1\t0\tBEGIN\n\
                    kind\tflow\t1\t1\n";
        let t = OpcodeTable::from_tsv(text).unwrap();
        assert_eq!(t.opcodes().len(), 2);
        assert_eq!(t.name_of(1), Some("BEGIN"));
        assert_eq!(t.alias("BEGIN").unwrap().variant, 0);
        assert_eq!(t.kinds(), &[("flow".to_string(), 1, 1)]);
    }
}
