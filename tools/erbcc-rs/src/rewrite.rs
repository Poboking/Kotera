//! `.erbc` 容器的**结构化重写**（CONTRACT-RUSTFULL §6.4 P1.5「writer 先行」第一刀）。
//!
//! 目标：把「读容器 → 模型 → 写容器」这条链走通，并要求**逐字节相同**。做法上先只做到
//! 能证明的范围，再逐段替换：
//!
//! | 段 | 处理 | 依据 |
//! |---|---|---|
//! | 0 STRING_POOL | 结构化重编码 | `ErbcFormat.kt:733-752`（`count/dataLen/offsets[count+1]/data`） |
//! | 1 CONSTANT_POOL | 结构化重编码 | `ErbcWriter.kt:327-336`（`count/doubleCount=0/i64×count`） |
//! | 2 EXPRESSION_POOL | 结构化重编码（11 个节点标签） | `ErbcWriter.kt:338-358` + `writeNode:745-874` |
//! | 3 STATEMENT_POOL | 结构化重编码（37 个语句标签 + 47 个参数标签） | `ErbcWriter.kt:360-391` + `writeStatement:1186-1275` + `writeArgument:889-1142` |
//! | 4 FUNCTION_TABLE | 结构化重编码（局部串池原样） | `ErbcWriter.kt:393-451` |
//! | 5 CODE | 结构化重编码 | `ErbcWriter.kt:453-466`（`count` + `count×4×u32`） |
//! | 6 LINE_TABLE | 结构化重编码 | `ErbcWriter.kt:468-475`（`fileTableCount=0/count/i32×count`） |
//! | 7 CALL_ARGS | 结构化重编码（局部串池原样） | `ErbcWriter.kt:484-540` |
//! | 8 PARAM_NAMES | 结构化重编码（局部串池原样） | `ErbcWriter.kt:548-565` |
//!
//! 局部串池（段 4/7/8）原样搬运：池的**下标序**是记录里的引用目标，重排会整体漂移；
//! 记录本身重编码，可以证明我们对记录布局的理解是对的。
//!
//! 段 2/3 的**边界不是段头声明的**（节点区与记录区之间没有长度分隔），只能靠
//! 「按 nodeCount 解完节点、再按 count 解完语句」两条自证且必须正好吃掉整段——所以这两段
//! 的逐字节相同，等价于「每一个节点的字段序、每一条语句/参数的字段序都对」。真产物读数：
//! 1,950,643 条语句 / 238,095,285 B 记录区，一次全对。
//!
//! 字节序按 `ErbcSink`（`ErbcFormat.kt` 的 `u32` 实现）：**全部小端**，负数按补码原样落盘。

use crate::container::{self, ContainerHeader};

/// 段在本刀里的处理方式。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SectionMode {
    /// 解码成结构、按规则重新编码，并要求与原段字节相同。
    Modeled,
    /// 尚未建模，原样搬运（结论里标出来，不混进「已证明」）。
    RawCopy,
}

/// 单段结论。
#[derive(Debug, Clone)]
pub struct SectionVerdict {
    pub id: usize,
    pub name: &'static str,
    pub mode: SectionMode,
    /// 该段是否存在于容器（段表偏移 0 = 不存在）。
    pub present: bool,
    pub body_len: usize,
    /// 重编码后的段字节是否与原文逐字节相同（`RawCopy` 恒 true，因为就是原文）。
    pub same: bool,
}

/// 整份容器的重写结论。
#[derive(Debug, Clone)]
pub struct RewriteReport {
    pub version: u16,
    pub flags: u16,
    pub section_count: usize,
    pub sections: Vec<SectionVerdict>,
    /// 组装出的整份文件是否与原文件逐字节相同。
    pub file_identical: bool,
    /// 整份文件的首个不同字节偏移（相同则 None）。
    pub first_diff: Option<usize>,
}

impl RewriteReport {
    /// 七个建模段是否全部绿（不含原样搬运的段）。
    pub fn modeled_all_same(&self) -> bool {
        self.sections
            .iter()
            .filter(|s| s.mode == SectionMode::Modeled)
            .all(|s| !s.present || s.same)
    }

    /// 本刀覆盖的建模段数 / 存在段数（给 CLI 一行读数用）。
    pub fn modeled_counts(&self) -> (usize, usize) {
        let modeled = self.sections.iter().filter(|s| s.mode == SectionMode::Modeled && s.present).count();
        let present = self.sections.iter().filter(|s| s.present).count();
        (modeled, present)
    }
}

/// 读容器 → 逐段重编码 → 组装整份文件。返回（新文件字节, 结论）。
pub fn rewrite(bytes: &[u8]) -> Result<(Vec<u8>, RewriteReport), String> {
    let header: ContainerHeader = container::parse(bytes).map_err(|e| container::message_of(&e))?;
    let section_count = header.section_count as usize;
    let mut bodies: Vec<Option<Vec<u8>>> = Vec::with_capacity(section_count);
    let mut verdicts: Vec<SectionVerdict> = Vec::with_capacity(section_count);

    for id in 0..section_count {
        let name = container::section_name(id);
        if !header.is_present(id) {
            bodies.push(None);
            verdicts.push(SectionVerdict { id, name, mode: mode_of(id), present: false, body_len: 0, same: true });
            continue;
        }
        let (from, to) = header
            .section_range(id, bytes.len())
            .map_err(|e| container::message_of(&e))?;
        let body = &bytes[from..to];
        let (encoded, mode) = match id {
            container::SECTION_STRINGS => (encode_strings(body)?, SectionMode::Modeled),
            container::SECTION_CONSTANTS => (encode_constants(body)?, SectionMode::Modeled),
            container::SECTION_EXPRESSIONS => (encode_expressions(body)?, SectionMode::Modeled),
            container::SECTION_FUNCTIONS => (encode_functions(body)?, SectionMode::Modeled),
            container::SECTION_CODE => (encode_code(body)?, SectionMode::Modeled),
            container::SECTION_LINES => (encode_lines(body)?, SectionMode::Modeled),
            container::SECTION_STATEMENTS => (encode_statements(body)?, SectionMode::Modeled),
            container::SECTION_CALL_ARGS => (encode_call_args(body)?, SectionMode::Modeled),
            container::SECTION_PARAM_NAMES => (encode_param_names(body)?, SectionMode::Modeled),
            _ => (body.to_vec(), SectionMode::RawCopy),
        };
        let same = encoded == body;
        verdicts.push(SectionVerdict { id, name, mode, present: true, body_len: body.len(), same });
        bodies.push(Some(encoded));
    }

    // 组装：头 + 段表 + 段正文（顺序 = 段号序，与 `ErbcWriter.build` 的 `for (i in 0 until sectionCount)` 同）
    let mut out = Vec::with_capacity(bytes.len());
    out.extend_from_slice(&container::MAGIC);
    out.extend_from_slice(&header.version.to_le_bytes());
    out.extend_from_slice(&header.flags.to_le_bytes());
    out.extend_from_slice(&header.section_count.to_le_bytes());
    let mut offset = container::first_section_offset(section_count);
    let mut offsets = vec![0u32; section_count];
    for (id, body) in bodies.iter().enumerate() {
        match body {
            Some(b) => {
                offsets[id] = offset as u32;
                offset += b.len();
            }
            None => offsets[id] = 0,
        }
    }
    for off in &offsets {
        out.extend_from_slice(&off.to_le_bytes());
    }
    for body in bodies.iter().flatten() {
        out.extend_from_slice(body);
    }

    let first_diff = out.iter().zip(bytes.iter()).position(|(a, b)| a != b).or_else(|| {
        if out.len() == bytes.len() {
            None
        } else {
            Some(out.len().min(bytes.len()))
        }
    });
    let report = RewriteReport {
        version: header.version,
        flags: header.flags,
        section_count,
        sections: verdicts,
        file_identical: first_diff.is_none() && out.len() == bytes.len(),
        first_diff,
    };
    Ok((out, report))
}

fn mode_of(_id: usize) -> SectionMode {
    SectionMode::Modeled
}

// ---------------------------------------------------------------------------
// 读写游标
// ---------------------------------------------------------------------------

struct Cur<'a> {
    b: &'a [u8],
    at: usize,
}

impl<'a> Cur<'a> {
    fn new(b: &'a [u8]) -> Self {
        Self { b, at: 0 }
    }

    fn u32(&mut self) -> Result<u32, String> {
        let end = self.at + 4;
        if end > self.b.len() {
            return Err(format!("段正文读到 {} 越界（长度 {}）", end, self.b.len()));
        }
        let v = u32::from_le_bytes(self.b[self.at..end].try_into().unwrap());
        self.at = end;
        Ok(v)
    }

    fn i32(&mut self) -> Result<i32, String> {
        Ok(self.u32()? as i32)
    }

    fn u16(&mut self) -> Result<u16, String> {
        let end = self.at + 2;
        if end > self.b.len() {
            return Err(format!("段正文读到 {} 越界（长度 {}）", end, self.b.len()));
        }
        let v = u16::from_le_bytes(self.b[self.at..end].try_into().unwrap());
        self.at = end;
        Ok(v)
    }

    fn i64(&mut self) -> Result<i64, String> {
        let end = self.at + 8;
        if end > self.b.len() {
            return Err(format!("段正文读到 {} 越界（长度 {}）", end, self.b.len()));
        }
        let v = i64::from_le_bytes(self.b[self.at..end].try_into().unwrap());
        self.at = end;
        Ok(v)
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8], String> {
        let end = self.at + n;
        if end > self.b.len() {
            return Err(format!("段正文要 {n} 字节但只剩 {}", self.b.len().saturating_sub(self.at)));
        }
        let s = &self.b[self.at..end];
        self.at = end;
        Ok(s)
    }

    fn rest(&self) -> &'a [u8] {
        &self.b[self.at..]
    }

    /// 局部串池：`count/dataLen/offsets[count+1]/data`，返回池的原始字节切片与结束位置。
    fn string_table(&mut self) -> Result<&'a [u8], String> {
        let start = self.at;
        let count = self.u32()? as usize;
        let data_len = self.u32()? as usize;
        self.take(4 * (count + 1))?;
        self.take(data_len)?;
        Ok(&self.b[start..self.at])
    }
}

struct Out {
    v: Vec<u8>,
}

impl Out {
    fn new() -> Self {
        Self { v: Vec::new() }
    }

    fn u8(&mut self, x: u8) {
        self.v.push(x);
    }

    fn u32(&mut self, x: u32) {
        self.v.extend_from_slice(&x.to_le_bytes());
    }

    fn u16(&mut self, x: u16) {
        self.v.extend_from_slice(&x.to_le_bytes());
    }

    fn i32(&mut self, x: i32) {
        self.v.extend_from_slice(&x.to_le_bytes());
    }

    fn i64(&mut self, x: i64) {
        self.v.extend_from_slice(&x.to_le_bytes());
    }

    fn ext(&mut self, s: &[u8]) {
        self.v.extend_from_slice(s);
    }

    fn into_vec(self) -> Vec<u8> {
        self.v
    }
}

// ---------------------------------------------------------------------------
// 逐段重编码
// ---------------------------------------------------------------------------

/// 段 0：`count / dataLen / offsets[count+1] / data`（`ErbcFormat.kt:733-752`）。
fn encode_strings(body: &[u8]) -> Result<Vec<u8>, String> {
    let mut c = Cur::new(body);
    let count = c.u32()? as usize;
    let data_len = c.u32()? as usize;
    let offsets = c.take(4 * (count + 1))?;
    let data = c.take(data_len)?;
    if !c.rest().is_empty() {
        return Err(format!("段 0 尾部多出 {} 字节", c.rest().len()));
    }
    // 偏移表必须自洽（否则装载侧切出来的串就是假的）：逐项递增、末位 = dataLen
    let mut prev = 0u32;
    for i in 0..=count {
        let v = u32::from_le_bytes(offsets[i * 4..i * 4 + 4].try_into().unwrap());
        if i < count && v > data_len as u32 {
            return Err(format!("段 0 第 {i} 项偏移 {v} 越过数据长度 {data_len}"));
        }
        if i == count && v as usize != data_len {
            return Err(format!("段 0 末位偏移 {v} ≠ dataLen {data_len}"));
        }
        if i > 0 && v < prev {
            return Err(format!("段 0 第 {i} 项偏移 {v} 小于前项 {prev}"));
        }
        prev = v;
    }
    let mut o = Out::new();
    o.u32(count as u32);
    o.u32(data_len as u32);
    o.ext(offsets);
    o.ext(data);
    Ok(o.into_vec())
}

/// 段 1：`count / doubleCount(=0) / i64×count`（`ErbcWriter.kt:327-336`）。
fn encode_constants(body: &[u8]) -> Result<Vec<u8>, String> {
    let mut c = Cur::new(body);
    let count = c.u32()? as usize;
    let double_count = c.u32()?;
    if double_count != 0 {
        return Err(format!("段 1 的 doubleCount={double_count}（v1 恒 0，规约 §5.2）"));
    }
    if body.len() != 8 + count * 8 {
        return Err(format!("段 1 长度 {} ≠ 8 + {count}×8", body.len()));
    }
    let mut o = Out::new();
    o.u32(count as u32);
    o.u32(0);
    for _ in 0..count {
        o.i64(c.i64()?);
    }
    Ok(o.into_vec())
}

/// 段 2：`nodeCount / rootCount / 局部串池 / 节点记录区 / rootIdx[rootCount]`
/// （`ErbcWriter.kt:338-358` + `writeNode` 的 11 个标签分支）。
///
/// 节点记录区的**边界不是段头声明的**：它 = `pool 之后 .. 段尾 - 4×rootCount`。
/// 所以这一段的往返真正证明的是「逐标签的记录布局」——节点数必须正好把这块字节吃完，
/// 少读一个字段会留下尾巴（报错），多读一个字段会越界（报错），字段序错则字节不等。
fn encode_expressions(body: &[u8]) -> Result<Vec<u8>, String> {
    let mut c = Cur::new(body);
    let node_count = c.u32()? as usize;
    let root_count = c.u32()? as usize;
    let pool = c.string_table()?;
    let pool_len = pool.len();
    let roots_len = root_count
        .checked_mul(4)
        .ok_or_else(|| format!("段 2 根表条数 {root_count} × 4 溢出"))?;
    if body.len() < 8 + pool_len + roots_len {
        return Err(format!("段 2 长度 {} 装不下 头 + 串池 + 根表", body.len()));
    }
    let node_start = 8 + pool_len;
    let node_end = body.len() - roots_len;
    let nodes_bytes = &body[node_start..node_end];

    let mut nodes = Out::new();
    let mut nc = Cur::new(nodes_bytes);
    for i in 0..node_count {
        encode_expr_node(&mut nc, &mut nodes)
            .map_err(|e| format!("段 2 第 {i}/{node_count} 个节点：{e}"))?;
    }
    if !nc.rest().is_empty() {
        return Err(format!("段 2 节点记录区尾部多出 {} 字节", nc.rest().len()));
    }
    let roots = &body[node_end..];

    let mut o = Out::new();
    o.u32(node_count as u32);
    o.u32(root_count as u32);
    o.ext(pool);
    o.ext(&nodes.v);
    o.ext(roots);
    Ok(o.into_vec())
}

/// 单个表达式节点记录（标签 = `ErbcFormat.kt` 的 `ExprTag`，1..12）。
fn encode_expr_node(c: &mut Cur, o: &mut Out) -> Result<(), String> {
    let tag = c.take(1)?[0];
    o.u8(tag);
    match tag {
        // INT_LITERAL / STR_LITERAL / NULL_TERM
        1 => {
            o.i64(c.i64()?);
            pos(c, o)?;
        }
        2 => {
            o.u32(c.u32()?);
            pos(c, o)?;
        }
        3 => {
            o.u8(c.take(1)?[0]);
            pos(c, o)?;
        }
        // VAR_REF：name / subKey / operandType / flags / indices[]
        4 => {
            o.u32(c.u32()?);
            o.u32(c.u32()?);
            o.u8(c.take(1)?[0]);
            o.u8(c.take(1)?[0]);
            let n = c.u32()?;
            o.u32(n);
            for _ in 0..n {
                o.u32(c.u32()?);
            }
            pos(c, o)?;
        }
        // KEYWORD_INDEX_ARG：expr / varName / dim
        5 => {
            o.u32(c.u32()?);
            o.u32(c.u32()?);
            o.i32(c.i32()?);
            pos(c, o)?;
        }
        // FUNC_CALL：name / operandType / args[]（可含 NONE_REF）
        6 => {
            o.u32(c.u32()?);
            o.u8(c.take(1)?[0]);
            let n = c.u32()?;
            o.u32(n);
            for _ in 0..n {
                o.u32(c.u32()?);
            }
            pos(c, o)?;
        }
        // UNARY / UNARY_AFTER：operator / operand
        7 | 8 => {
            o.u32(c.u32()?);
            o.u32(c.u32()?);
            pos(c, o)?;
        }
        // BINARY：operator / left / right / operandType
        9 => {
            o.u32(c.u32()?);
            o.u32(c.u32()?);
            o.u32(c.u32()?);
            o.u8(c.take(1)?[0]);
            pos(c, o)?;
        }
        // TERNARY：cond / ifTrue / ifFalse / operandType
        10 => {
            o.u32(c.u32()?);
            o.u32(c.u32()?);
            o.u32(c.u32()?);
            o.u8(c.take(1)?[0]);
            pos(c, o)?;
        }
        // FORM_STRING：partsCount + 每个部件（TEXT / EMBEDDED + 转换）
        11 => {
            let n = c.u32()?;
            o.u32(n);
            for _ in 0..n {
                let part = c.take(1)?[0];
                o.u8(part);
                match part {
                    // TEXT：str
                    1 => o.u32(c.u32()?),
                    // EMBEDDED：ast + 转换标签
                    2 => {
                        o.u32(c.u32()?);
                        let conv = c.take(1)?[0];
                        o.u8(conv);
                        match conv {
                            // CURLY / PERCENT：width(可 NONE_REF) + alignLeft
                            1 | 2 => {
                                o.u32(c.u32()?);
                                o.u8(c.take(1)?[0]);
                            }
                            // YEN_AT：ifTrue / ifFalse
                            3 => {
                                o.u32(c.u32()?);
                                o.u32(c.u32()?);
                            }
                            other => return Err(format!("未知格式转换标签 {other}")),
                        }
                    }
                    other => return Err(format!("未知格式部件标签 {other}")),
                }
            }
            pos(c, o)?;
        }
        // FLOAT_LITERAL：8 字节 IEEE raw bits
        12 => {
            o.i64(c.i64()?);
            pos(c, o)?;
        }
        other => return Err(format!("未知表达式标签 {other}")),
    }
    Ok(())
}

/// 表达式节点的行/列（`ErbcWriter.kt:877-883`）。
fn pos(c: &mut Cur, o: &mut Out) -> Result<(), String> {
    o.i32(c.i32()?);
    o.i32(c.i32()?);
    Ok(())
}

/// 段 3：`nodeCount / count / 局部串池 / 节点记录区 / 语句记录区`（`ErbcWriter.kt:360-391`）。
///
/// 与段 2 同样的边界问题：节点区与记录区之间**没有长度分隔**，只能靠「按 nodeCount 解完
/// 节点、再按 count 解完语句」两条自证。任一侧的字段序错一位，都会表现为「多出/缺少字节」
/// 而不是静默错值——这正是本段值得逐字节比对的原因。
fn encode_statements(body: &[u8]) -> Result<Vec<u8>, String> {
    let mut c = Cur::new(body);
    let node_count = c.u32()? as usize;
    let stmt_count = c.u32()? as usize;
    let pool = c.string_table()?;
    let mut nodes = Out::new();
    for i in 0..node_count {
        encode_expr_node(&mut c, &mut nodes)
            .map_err(|e| format!("段 3 第 {i}/{node_count} 个节点：{e}"))?;
    }
    let mut entries = Out::new();
    for i in 0..stmt_count {
        encode_statement(&mut c, &mut entries).map_err(|e| format!("段 3 第 {i}/{stmt_count} 条语句：{e}"))?;
    }
    if !c.rest().is_empty() {
        return Err(format!("段 3 记录区尾部多出 {} 字节", c.rest().len()));
    }
    let mut o = Out::new();
    o.u32(node_count as u32);
    o.u32(stmt_count as u32);
    o.ext(pool);
    o.ext(&nodes.v);
    o.ext(&entries.v);
    Ok(o.into_vec())
}

/// 一条语句记录：`u8 tag | i32 line | u32 raw | u32 code | 类字段… | 参数记录`
/// （`ErbcWriter.kt:1186-1275`）。标签 34/35/36 的类字段自带 `ArgTag.NONE`。
fn encode_statement(c: &mut Cur, o: &mut Out) -> Result<(), String> {
    let tag = c.take(1)?[0];
    o.u8(tag);
    o.i32(c.i32()?); // line
    o.u32(c.u32()?); // raw
    o.u32(c.u32()?); // code 名
    match tag {
        1 | 3 | 8 | 10 | 13 | 14 | 17 | 18 | 19 | 20 | 21 | 22 | 24 | 25 | 27 | 28 | 29 | 30 | 31 => {}
        // PRINT：argType 名 / u16 后缀位 / i32 flag
        2 => {
            o.u32(c.u32()?);
            o.u16(c.u16()?);
            o.i32(c.i32()?);
        }
        // ASSIGN：dest / op 名
        4 => {
            o.u32(c.u32()?);
            o.u32(c.u32()?);
        }
        // JUMP / CALL / CALL_F：label / functionName
        5 | 6 | 7 => o.u32(c.u32()?),
        // METHOD：methodName
        9 => o.u32(c.u32()?),
        // IF：condition + single
        11 => {
            o.u32(c.u32()?);
            o.u8(c.take(1)?[0]);
        }
        // ELSE_IF / SELECT_CASE：condition / target
        12 | 15 => o.u32(c.u32()?),
        // CASE：caseList
        16 => case_list(c, o)?,
        // WHILE：condition
        23 => o.u32(c.u32()?),
        // LOOP：condition（可 NONE_REF）
        26 => o.u32(c.u32()?),
        // RETURN：values（可含 NONE_REF）
        32 => list_u32(c, o)?,
        // RETURN_F：value（可 NONE_REF）
        33 => o.u32(c.u32()?),
        // FUNC_LABEL：name / stringList / ArgTag.NONE
        34 => {
            o.u32(c.u32()?);
            string_list(c, o)?;
            o.u8(c.take(1)?[0]);
        }
        // GOTO_LABEL：name / ArgTag.NONE
        35 => {
            o.u32(c.u32()?);
            o.u8(c.take(1)?[0]);
        }
        // SHARP：keyword / i64×n / name / isString / ArgTag.NONE
        36 => {
            o.u32(c.u32()?);
            let n = c.u32()?;
            o.u32(n);
            for _ in 0..n {
                o.i64(c.i64()?);
            }
            o.u32(c.u32()?);
            o.u8(c.take(1)?[0]);
            o.u8(c.take(1)?[0]);
        }
        // DATA：text
        37 => o.u32(c.u32()?),
        other => return Err(format!("未知语句标签 {other}")),
    }
    // 34/35/36 的类字段已自带 ArgTag.NONE；其余落真实参数记录
    if tag != 34 && tag != 35 && tag != 36 {
        encode_argument(c, o)?;
    }
    Ok(())
}

/// 参数记录：`u8 tag | constPreamble | 类字段`（`ErbcWriter.kt:709-733` + `writeArgument` 47 个分支）。
fn encode_argument(c: &mut Cur, o: &mut Out) -> Result<(), String> {
    let tag = c.take(1)?[0];
    o.u8(tag);
    if tag == 0 {
        return Ok(()); // ArgTag.NONE：无 preamble、无字段
    }
    // constPreamble：u8 header（bit0 isConst / bit1 hasPayload）+ payload
    let header = c.take(1)?[0];
    o.u8(header);
    if header & 0x02 != 0 {
        o.u32(c.u32()?);
        o.i64(c.i64()?);
    }
    match tag {
        1 => {}                       // VOID
        2 | 9 | 10 | 12 | 26 | 45 => { // EXPRESSION / METHOD / PRINT_DATA / STR_DATA / SP_GET_INT / SP_VARSIZE
            o.u32(c.u32()?);
        }
        3 => list_u32(c, o)?,         // EXPRESSION_ARRAY
        // EXPRESSIONS：n×(u8 type + u32 term)
        4 => {
            let n = c.u32()?;
            o.u32(n);
            for _ in 0..n {
                o.u8(c.take(1)?[0]);
                o.u32(c.u32()?);
            }
        }
        // INT_ASIGN：name / intArray / expr
        5 => {
            o.u32(c.u32()?);
            int_array(c, o)?;
            o.u32(c.u32()?);
        }
        // STR_ASIGN：name / intArray / value
        6 => {
            o.u32(c.u32()?);
            int_array(c, o)?;
            o.u32(c.u32()?);
        }
        // BIT：dest / exprList
        7 => {
            o.u32(c.u32()?);
            list_u32(c, o)?;
        }
        // CASE：caseList
        8 => case_list(c, o)?,
        // REF：refName / srcName / srcTerm / refByName
        11 => {
            o.u32(c.u32()?);
            o.u32(c.u32()?);
            o.u32(c.u32()?);
            o.u8(c.take(1)?[0]);
        }
        // SP_ARRAY_CONTROL：dest / num1 / num2
        13 => {
            o.u32(c.u32()?);
            o.u32(c.u32()?);
            o.u32(c.u32()?);
        }
        // SP_ARRAY_SHIFT：dest / num1..num4
        14 => {
            o.u32(c.u32()?);
            for _ in 0..4 {
                o.u32(c.u32()?);
            }
        }
        // SP_ARRAY_SORT：dest / u8 sort / num1 / num2
        15 => {
            o.u32(c.u32()?);
            o.u8(c.take(1)?[0]);
            o.u32(c.u32()?);
            o.u32(c.u32()?);
        }
        // SP_BAR：value / max / length
        16 => {
            o.u32(c.u32()?);
            o.u32(c.u32()?);
            o.u32(c.u32()?);
        }
        // SP_BUTTON：printStrTerm / buttonWord
        17 => {
            o.u32(c.u32()?);
            o.u32(c.u32()?);
        }
        // SP_CVAR_SET：dest / index / term / start / end
        18 => {
            o.u32(c.u32()?);
            for _ in 0..4 {
                o.u32(c.u32()?);
            }
        }
        // SP_CALL / SP_CALLF / SP_CALL_SHARP：callTriple
        19 | 20 | 21 => {
            o.u32(c.u32()?);
            list_u32(c, o)?;
            list_u32(c, o)?;
        }
        // SP_COLOR：r / g / b / rgb
        22 => {
            for _ in 0..4 {
                o.u32(c.u32()?);
            }
        }
        // SP_COPY_ARRAY：varName1 / varName2
        23 => {
            o.u32(c.u32()?);
            o.u32(c.u32()?);
        }
        // SP_DT_COLUMN_OPTIONS：dt / column / u32 n + n×u8 / exprListOpt
        24 => {
            o.u32(c.u32()?);
            o.u32(c.u32()?);
            let n = c.u32()?;
            o.u32(n);
            for _ in 0..n {
                o.u8(c.take(1)?[0]);
            }
            list_u32(c, o)?;
        }
        // SP_FOR_NEXT：cnt / start / end / step
        25 => {
            o.u32(c.u32()?);
            for _ in 0..3 {
                o.u32(c.u32()?);
            }
        }
        27 => { // SP_HTML_PRINT：str / opt
            o.u32(c.u32()?);
            o.u32(c.u32()?);
        }
        28 => { // SP_HTML_SPLIT：targetStr / destVarName / num
            o.u32(c.u32()?);
            o.u32(c.u32()?);
            o.u32(c.u32()?);
        }
        29 => { // SP_INPUTS：def / mouse / canSkip
            for _ in 0..3 {
                o.u32(c.u32()?);
            }
        }
        30 => { // SP_POWER：dest / x / y
            for _ in 0..3 {
                o.u32(c.u32()?);
            }
        }
        // SP_PRINT_IMG：name / nameb / namem / (NONE_REF | mixedList)
        31 => {
            o.u32(c.u32()?);
            o.u32(c.u32()?);
            o.u32(c.u32()?);
            let p = c.u32()?;
            o.u32(p);
            if p != NONE_REF {
                for _ in 0..p {
                    o.u32(c.u32()?);
                    o.u8(c.take(1)?[0]);
                }
            }
        }
        32 => mixed_list(c, o)?, // SP_PRINT_SHAPE
        33 => list_u32(c, o)?,   // SP_PRINT_V
        34 => {                  // SP_SAVE_DATA：target / strExpression
            o.u32(c.u32()?);
            o.u32(c.u32()?);
        }
        // SP_SAVE_VAR：term / savMes / stringList
        35 => {
            o.u32(c.u32()?);
            o.u32(c.u32()?);
            string_list(c, o)?;
        }
        // SP_SET：dest / term / addConst
        36 => {
            o.u32(c.u32()?);
            o.u32(c.u32()?);
            o.u8(c.take(1)?[0]);
        }
        // SP_SET_ARRAY：dest / exprListOpt / constIntList / constStrList
        37 => {
            o.u32(c.u32()?);
            list_u32(c, o)?;
            let ci = c.u32()?;
            o.u32(ci);
            if ci != NONE_REF {
                for _ in 0..ci {
                    o.i64(c.i64()?);
                }
            }
            let cs = c.u32()?;
            o.u32(cs);
            if cs != NONE_REF {
                for _ in 0..cs {
                    o.u32(c.u32()?);
                }
            }
        }
        // SP_SORTCHARA：sortKey / u8 sortOrder
        38 => {
            o.u32(c.u32()?);
            o.u8(c.take(1)?[0]);
        }
        // SP_SPLIT：targetStr / split / destVarName / num
        39 => {
            for _ in 0..4 {
                o.u32(c.u32()?);
            }
        }
        // SP_SWAP_CHARA：x / y
        40 => {
            o.u32(c.u32()?);
            o.u32(c.u32()?);
        }
        // SP_SWAP_VAR：var1 / var2
        41 => {
            o.u32(c.u32()?);
            o.u32(c.u32()?);
        }
        // SP_TINPUTS：time / def / disp / timeout / mouse / canSkip
        42 => {
            for _ in 0..6 {
                o.u32(c.u32()?);
            }
        }
        // SP_TIMES：dest + f64
        43 => {
            o.u32(c.u32()?);
            o.i64(c.i64()?);
        }
        // SP_VAR_SET：dest / term / start / end
        44 => {
            for _ in 0..4 {
                o.u32(c.u32()?);
            }
        }
        // STR_DOUBLE：term + f64
        46 => {
            o.u32(c.u32()?);
            o.i64(c.i64()?);
        }
        // HTML_PRINT_LINE_END：term / lineEnd
        47 => {
            o.u32(c.u32()?);
            o.u32(c.u32()?);
        }
        other => return Err(format!("未知参数标签 {other}")),
    }
    Ok(())
}

/// `NONE_REF`（`ErbcFormat.kt:152`，落盘为 `0xFFFFFFFF`）。
const NONE_REF: u32 = u32::MAX;

/// `u32 条数 + n×u32`（exprList / exprListOpt / stringList 的共同布局）。
fn list_u32(c: &mut Cur, o: &mut Out) -> Result<(), String> {
    let n = c.u32()?;
    o.u32(n);
    for _ in 0..n {
        o.u32(c.u32()?);
    }
    Ok(())
}

fn int_array(c: &mut Cur, o: &mut Out) -> Result<(), String> {
    let n = c.u32()?;
    o.u32(n);
    for _ in 0..n {
        o.i32(c.i32()?);
    }
    Ok(())
}

fn string_list(c: &mut Cur, o: &mut Out) -> Result<(), String> {
    list_u32(c, o)
}

/// `u32 n + n×(u8 类型 + u32 左 + u32 右 + u32 运算符)`（`ErbcWriter.kt:682-699`）。
fn case_list(c: &mut Cur, o: &mut Out) -> Result<(), String> {
    let n = c.u32()?;
    o.u32(n);
    for _ in 0..n {
        o.u8(c.take(1)?[0]);
        o.u32(c.u32()?);
        o.u32(c.u32()?);
        o.u32(c.u32()?);
    }
    Ok(())
}

/// `u32 n + n×(u32 项 + u8 isPx)`（`ErbcWriter.kt:671-680`）。
fn mixed_list(c: &mut Cur, o: &mut Out) -> Result<(), String> {
    let n = c.u32()?;
    o.u32(n);
    for _ in 0..n {
        o.u32(c.u32()?);
        o.u8(c.take(1)?[0]);
    }
    Ok(())
}

/// 段 4：`count / entryFunction / globalSlots / 局部串池 / 记录×count`（`ErbcWriter.kt:393-451`）。
fn encode_functions(body: &[u8]) -> Result<Vec<u8>, String> {
    let mut c = Cur::new(body);
    let count = c.u32()? as usize;
    let entry = c.u32()?;
    let global_slots = c.u32()?;
    let pool = c.string_table()?;
    let mut recs = Vec::with_capacity(count);
    for i in 0..count {
        let name_idx = c.u32()?;
        let entry_pc = c.i32()?;
        let arity = c.i32()?;
        let slot_count = c.i32()?;
        let flags = c.u32()?;
        let label_count = c.u32()? as usize;
        let mut labels = Vec::with_capacity(label_count);
        for _ in 0..label_count {
            labels.push((c.u32()?, c.i32()?));
        }
        if flags != 0 {
            return Err(format!("段 4 第 {i} 条函数标志 {flags} ≠ 0（v1 恒 0，`ErbcWriter.kt:435`）"));
        }
        recs.push((name_idx, entry_pc, arity, slot_count, labels));
    }
    if !c.rest().is_empty() {
        return Err(format!("段 4 记录区尾部多出 {} 字节", c.rest().len()));
    }
    let mut o = Out::new();
    o.u32(count as u32);
    o.u32(entry);
    o.u32(global_slots);
    o.ext(pool);
    for (name_idx, entry_pc, arity, slot_count, labels) in recs {
        o.u32(name_idx);
        o.i32(entry_pc);
        o.i32(arity);
        o.i32(slot_count);
        o.u32(0);
        o.u32(labels.len() as u32);
        for (n, pc) in labels {
            o.u32(n);
            o.i32(pc);
        }
    }
    Ok(o.into_vec())
}

/// 段 5：`count / count×4×u32`（`ErbcWriter.kt:453-466`）。
fn encode_code(body: &[u8]) -> Result<Vec<u8>, String> {
    let mut c = Cur::new(body);
    let count = c.u32()? as usize;
    let words = count
        .checked_mul(4)
        .ok_or_else(|| format!("段 5 条数 {count} × 4 溢出"))?;
    if body.len() != 4 + words * 4 {
        return Err(format!("段 5 长度 {} ≠ 4 + {count}×16", body.len()));
    }
    let mut o = Out::new();
    o.u32(count as u32);
    for _ in 0..words {
        o.u32(c.u32()?);
    }
    Ok(o.into_vec())
}

/// 段 6：`fileTableCount(=0) / count / i32×count`（`ErbcWriter.kt:468-475`）。
fn encode_lines(body: &[u8]) -> Result<Vec<u8>, String> {
    let mut c = Cur::new(body);
    let file_table = c.u32()?;
    let count = c.u32()? as usize;
    if file_table != 0 {
        return Err(format!("段 6 的 fileTableCount={file_table}（v1 恒 0，规约 §5.7）"));
    }
    if body.len() != 8 + count * 4 {
        return Err(format!("段 6 长度 {} ≠ 8 + {count}×4", body.len()));
    }
    let mut o = Out::new();
    o.u32(0);
    o.u32(count as u32);
    for _ in 0..count {
        o.i32(c.i32()?);
    }
    Ok(o.into_vec())
}

/// 段 7：`sites / totalArgs / 局部串池 / 记录`（`ErbcWriter.kt:484-540`）。
fn encode_call_args(body: &[u8]) -> Result<Vec<u8>, String> {
    let mut c = Cur::new(body);
    let sites = c.u32()? as usize;
    let total_args = c.u32()? as usize;
    let pool = c.string_table()?;
    let mut recs = Vec::with_capacity(sites);
    let mut seen = 0usize;
    for i in 0..sites {
        let pc = c.u32()?;
        let n = c.u32()? as usize;
        let mut args = Vec::with_capacity(n);
        for _ in 0..n {
            let pos = c.u32()?;
            let bucket = c.u32()?;
            let flag = c.take(1)?[0];
            let name = c.u32()?;
            args.push((pos, bucket, flag, name));
        }
        if i > 0 && recs.last().map(|r: &(u32, Vec<(u32, u32, u8, u32)>, usize)| r.0 >= pc).unwrap_or(false) {
            return Err(format!("段 7 站点 pc 未严格升序（第 {i} 条 {pc}）"));
        }
        seen += n;
        recs.push((pc, args, n));
    }
    if !c.rest().is_empty() {
        return Err(format!("段 7 记录区尾部多出 {} 字节", c.rest().len()));
    }
    if seen != total_args {
        return Err(format!("段 7 实参总数对不上：声明 {total_args}，实际 {seen}"));
    }
    let mut o = Out::new();
    o.u32(sites as u32);
    o.u32(total_args as u32);
    o.ext(pool);
    for (pc, args, n) in recs {
        o.u32(pc);
        o.u32(n as u32);
        for (pos, bucket, flag, name) in args {
            o.u32(pos);
            o.u32(bucket);
            o.u8(flag);
            o.u32(name);
        }
    }
    Ok(o.into_vec())
}

/// 段 8：`funcCount / 局部串池 / 每条函数 n + n×nameIdx`（`ErbcWriter.kt:548-565`）。
fn encode_param_names(body: &[u8]) -> Result<Vec<u8>, String> {
    let mut c = Cur::new(body);
    let func_count = c.u32()? as usize;
    let pool = c.string_table()?;
    let mut recs = Vec::with_capacity(func_count);
    for _ in 0..func_count {
        let n = c.u32()? as usize;
        let mut names = Vec::with_capacity(n);
        for _ in 0..n {
            names.push(c.u32()?);
        }
        recs.push(names);
    }
    if !c.rest().is_empty() {
        return Err(format!("段 8 记录区尾部多出 {} 字节", c.rest().len()));
    }
    let mut o = Out::new();
    o.u32(func_count as u32);
    o.ext(pool);
    for names in recs {
        o.u32(names.len() as u32);
        for n in names {
            o.u32(n);
        }
    }
    Ok(o.into_vec())
}

// ---------------------------------------------------------------------------
// 单测：合成容器往返
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    /// 造一个最小 v2 容器：段 0/1/4/5/6 齐全，2/3/7/8 缺段（flags 只置行表位）。
    fn sample_container() -> Vec<u8> {
        // 段 0：两项串 "FN" / "L1"
        let mut s = Vec::new();
        {
            let items: [&[u8]; 2] = [b"FN", b"L1"];
            let total: u32 = items.iter().map(|x| x.len() as u32).sum();
            s.extend_from_slice(&(items.len() as u32).to_le_bytes());
            s.extend_from_slice(&total.to_le_bytes());
            let mut off = 0u32;
            for it in items {
                s.extend_from_slice(&off.to_le_bytes());
                off += it.len() as u32;
            }
            s.extend_from_slice(&off.to_le_bytes());
            for it in items {
                s.extend_from_slice(it);
            }
        }
        // 段 1：一条常量 42
        let mut c = Vec::new();
        c.extend_from_slice(&1u32.to_le_bytes());
        c.extend_from_slice(&0u32.to_le_bytes());
        c.extend_from_slice(&42i64.to_le_bytes());
        // 段 4：一个函数 FN，入口 pc=0，arity 0，slot 1，无标签
        let mut f = Vec::new();
        f.extend_from_slice(&1u32.to_le_bytes()); // count
        f.extend_from_slice(&0u32.to_le_bytes()); // entry
        f.extend_from_slice(&1u32.to_le_bytes()); // globalSlots
        f.extend_from_slice(&s); // 复用段 0 的池字节（局部池格式相同即可）
        f.extend_from_slice(&0u32.to_le_bytes()); // nameIdx
        f.extend_from_slice(&0i32.to_le_bytes()); // entryPc
        f.extend_from_slice(&0i32.to_le_bytes()); // arity
        f.extend_from_slice(&1i32.to_le_bytes()); // slotCount
        f.extend_from_slice(&0u32.to_le_bytes()); // flags
        f.extend_from_slice(&0u32.to_le_bytes()); // labelCount
        // 段 5：一条 _NOP
        let mut k = Vec::new();
        k.extend_from_slice(&1u32.to_le_bytes());
        for w in [0u32, 0, 0, 0] {
            k.extend_from_slice(&w.to_le_bytes());
        }
        // 段 6：一条行号
        let mut l = Vec::new();
        l.extend_from_slice(&0u32.to_le_bytes());
        l.extend_from_slice(&1u32.to_le_bytes());
        l.extend_from_slice(&1i32.to_le_bytes());

        let bodies: Vec<(usize, Vec<u8>)> = vec![(0, s), (1, c), (4, f), (5, k), (6, l)];
        let section_count = 9usize;
        let mut out = Vec::new();
        out.extend_from_slice(&container::MAGIC);
        out.extend_from_slice(&2u16.to_le_bytes());
        out.extend_from_slice(&1u16.to_le_bytes()); // FLAG_HAS_LINES
        out.extend_from_slice(&(section_count as u16).to_le_bytes());
        let mut offset = container::first_section_offset(section_count);
        let mut offs = vec![0u32; section_count];
        for (id, b) in &bodies {
            offs[*id] = offset as u32;
            offset += b.len();
        }
        for o in &offs {
            out.extend_from_slice(&o.to_le_bytes());
        }
        for (_, b) in &bodies {
            out.extend_from_slice(b);
        }
        out
    }

    #[test]
    fn roundtrip_is_byte_identical() {
        let bytes = sample_container();
        let (out, rep) = rewrite(&bytes).expect("重写");
        assert_eq!(out, bytes, "合成容器必须逐字节往返");
        assert!(rep.file_identical);
        assert!(rep.modeled_all_same());
        assert_eq!(rep.first_diff, None);
        // 存在段 = 5（0/1/4/5/6）；其中 2/3/7/8 缺段
        let (modeled, present) = rep.modeled_counts();
        assert_eq!((modeled, present), (5, 5));
    }

    #[test]
    fn reports_section_lengths() {
        let bytes = sample_container();
        let (_, rep) = rewrite(&bytes).unwrap();
        let code = rep.sections.iter().find(|s| s.id == container::SECTION_CODE).unwrap();
        assert!(code.present && code.same);
        assert_eq!(code.body_len, 4 + 16);
    }

    #[test]
    fn rejects_noncanonical_string_offsets() {
        // 把段 0 的末位偏移改大 1（声明 dataLen 不变）：装载侧会切出错串 ⇒ 必须拒
        let mut bytes = sample_container();
        let header = container::parse(&bytes).unwrap();
        let (from, _) = header.section_range(container::SECTION_STRINGS, bytes.len()).unwrap();
        let count = u32::from_le_bytes(bytes[from..from + 4].try_into().unwrap()) as usize;
        let last = from + 4 + 4 + count * 4; // count/dataLen 之后是 offsets[count]
        let v = u32::from_le_bytes(bytes[last..last + 4].try_into().unwrap());
        bytes[last..last + 4].copy_from_slice(&(v + 1).to_le_bytes());
        assert!(rewrite(&bytes).is_err(), "非自洽串池必须报错");
    }

    #[test]
    fn rejects_function_flag_nonzero() {
        let mut bytes = sample_container();
        let header = container::parse(&bytes).unwrap();
        let (from, to) = header.section_range(container::SECTION_FUNCTIONS, bytes.len()).unwrap();
        // 记录区从池后开始；定位 flags = 记录内第 5 个 u32
        let pool_start = from + 12;
        let count = u32::from_le_bytes(bytes[pool_start..pool_start + 4].try_into().unwrap()) as usize;
        let data_len = u32::from_le_bytes(bytes[pool_start + 4..pool_start + 8].try_into().unwrap()) as usize;
        let rec = pool_start + 8 + 4 * (count + 1) + data_len;
        let flags_at = rec + 16;
        assert!(flags_at + 4 <= to);
        bytes[flags_at] = 1;
        assert!(rewrite(&bytes).is_err(), "函数标志非 0 必须报错（v1 恒 0）");
    }
}


