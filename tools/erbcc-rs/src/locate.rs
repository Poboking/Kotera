//! 把「产物第几个字节不同」落到「哪个函数、哪条指令、哪一行源码」（CONTRACT-RUSTFULL §6.3 第 3 项）。
//!
//! `container` 模块只解头与段表（够新鲜度用）；本模块再往前一步：读**函数表**与**行号表**
//! 两个定位段，把绝对偏移折成可读坐标。段布局的权威是 Kotlin 侧：
//!
//! - 函数表段（`ErbcWriter.kt:393-450`）：`u32 count, u32 entryFunction, u32 globalSlots,
//!   <局部串池>, 记录 × count`；记录 = `u32 nameIdx, i32 entryPc, i32 arity, i32 slotCount,
//!   u32 flags, u32 labelCount, (u32 labelNameIdx, i32 labelPc) × labelCount`。
//! - 指令流段（`ErbcWriter.kt:453-469`）：`u32 条数 + 条数 × 4×u32`，单条 16 B。
//! - 行号表段（`ErbcWriter.kt:471-484`）：`u32 fileTableCount(=0), u32 count, i32[count]`，
//!   下标是绝对 pc。
//! - 局部串池（`ErbcFormat.kt:743-755`）：`u32 count, u32 dataLen, (count+1)×u32 offsets, data`。
//!
//! 只读这两段：`program.erbc` 全量 381 MB，为了报一个偏移把整份读进内存不划算。

use crate::container::{self, OpenedContainer, SECTION_CODE, SECTION_FUNCTIONS, SECTION_LINES};
use crate::types::{ErbccError, Outcome};
use std::io::{self, Read, Seek, SeekFrom};
use std::path::Path;

/// 单条指令 4 个 u32。
pub const INSTRUCTION_WORDS: u64 = 4;
/// 单条指令字节数。
pub const INSTRUCTION_BYTES: u64 = INSTRUCTION_WORDS * 4;

/// 函数表的一条（只取定位需要的两列；arity/slot/标签按需再扩）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FunctionEntry {
    pub name: String,
    pub entry_pc: i32,
}

#[derive(Debug, Clone)]
pub struct FunctionTable {
    pub entry_function: u32,
    pub global_slots: u32,
    pub functions: Vec<FunctionEntry>,
}

fn section_bytes(c: &OpenedContainer, section: usize) -> Outcome<Option<Vec<u8>>> {
    if !c.header.is_present(section) {
        return Ok(None);
    }
    let (start, end) = c
        .header
        .section_range(section, c.file_size as usize)
        .map_err(|e| match e {
            ErbccError::Io(m) => ErbccError::Io(m),
            other => other,
        })?;
    let mut f = std::fs::File::open(&c.path)
        .map_err(|e| ErbccError::Io(format!("{}: 打开失败（{e}）", c.path.display())))?;
    f.seek(SeekFrom::Start(start as u64))
        .map_err(|e| ErbccError::Io(format!("{}: 定位失败（{e}）", c.path.display())))?;
    let mut buf = vec![0u8; end - start];
    f.read_exact(&mut buf)
        .map_err(|e| ErbccError::Io(format!("{}: 读到 EOF（{e}）", c.path.display())))?;
    Ok(Some(buf))
}

fn u32_at(b: &[u8], at: usize, what: &str) -> Outcome<u32> {
    if at + 4 > b.len() {
        return Err(ErbccError::Io(format!(
            "{what}: 读 u32 越界（偏移 {at}，段长 {}）",
            b.len()
        )));
    }
    Ok(u32::from_le_bytes([b[at], b[at + 1], b[at + 2], b[at + 3]]))
}

fn i32_at(b: &[u8], at: usize, what: &str) -> Outcome<i32> {
    Ok(u32_at(b, at, what)? as i32)
}

/// 读函数表；段不存在返回 `Ok(None)`（v1 里函数表恒存在，缺失即畸形）。
pub fn read_functions(c: &OpenedContainer) -> Outcome<Option<FunctionTable>> {
    let Some(buf) = section_bytes(c, SECTION_FUNCTIONS)? else {
        return Ok(None);
    };
    let what = "函数表";
    let count = u32_at(&buf, 0, what)? as usize;
    let entry_function = u32_at(&buf, 4, what)?;
    let global_slots = u32_at(&buf, 8, what)?;
    // 局部串池
    let mut at = 12usize;
    let str_count = u32_at(&buf, at, what)? as usize;
    let str_len = u32_at(&buf, at + 4, what)? as usize;
    at += 8;
    let mut offsets = Vec::with_capacity(str_count + 1);
    for i in 0..=str_count {
        offsets.push(u32_at(&buf, at + i * 4, what)? as usize);
    }
    at += (str_count + 1) * 4;
    let data = at;
    if data + str_len > buf.len() {
        return Err(ErbccError::Io(format!(
            "{what}: 串池数据溢出（{data}+{str_len} > 段长 {}）",
            buf.len()
        )));
    }
    let mut functions = Vec::with_capacity(count);
    let mut cur = data + str_len;
    for _ in 0..count {
        let name_idx = u32_at(&buf, cur, what)? as usize;
        let entry_pc = i32_at(&buf, cur + 4, what)?;
        let label_count = u32_at(&buf, cur + 20, what)? as usize;
        let name = if name_idx < str_count {
            let from = data + offsets[name_idx];
            let to = data + offsets[name_idx + 1];
            if to > buf.len() || from > to {
                return Err(ErbccError::Io(format!("{what}: 第 {name_idx} 项区间越界")));
            }
            String::from_utf8_lossy(&buf[from..to]).into_owned()
        } else {
            format!("<nameIdx {name_idx} 越界>")
        };
        functions.push(FunctionEntry { name, entry_pc });
        cur += 24 + label_count * 8;
        if cur > buf.len() {
            return Err(ErbccError::Io(format!("{what}: 记录越界（cur={cur}）")));
        }
    }
    Ok(Some(FunctionTable {
        entry_function,
        global_slots,
        functions,
    }))
}

/// 读某个 pc 的源码行号；行号表缺失或 pc 越界返回 `Ok(None)`。
pub fn read_line(c: &OpenedContainer, pc: u32) -> Outcome<Option<i32>> {
    if !c.header.is_present(SECTION_LINES) {
        return Ok(None);
    }
    let (start, end) = c.header.section_range(SECTION_LINES, c.file_size as usize)?;
    if start + 8 > end {
        return Ok(None);
    }
    let mut f = std::fs::File::open(&c.path)
        .map_err(|e| ErbccError::Io(format!("{}: 打开失败（{e}）", c.path.display())))?;
    f.seek(SeekFrom::Start(start as u64 + 4))
        .map_err(|e| ErbccError::Io(format!("{}: 定位失败（{e}）", c.path.display())))?;
    let mut head = [0u8; 4];
    f.read_exact(&mut head)
        .map_err(|e| ErbccError::Io(format!("{}: 读到 EOF（{e}）", c.path.display())))?;
    let count = u32::from_le_bytes(head) as u64;
    if (pc as u64) >= count {
        return Ok(None);
    }
    let pos = start as u64 + 8 + pc as u64 * 4;
    if pos + 4 > end as u64 {
        return Ok(None);
    }
    f.seek(SeekFrom::Start(pos))
        .map_err(|e| ErbccError::Io(format!("{}: 定位失败（{e}）", c.path.display())))?;
    let mut one = [0u8; 4];
    f.read_exact(&mut one)
        .map_err(|e| ErbccError::Io(format!("{}: 读到 EOF（{e}）", c.path.display())))?;
    Ok(Some(i32::from_le_bytes(one)))
}

/// 一次定位的结果。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Place {
    pub section_index: usize,
    pub section: String,
    pub intra_offset: u64,
    pub pc: Option<u32>,
    pub function: Option<String>,
    pub line: Option<i32>,
    pub words: Option<[u32; 4]>,
}

/// 把绝对偏移落到段 / 函数 / pc / 行 / 指令四字上。
pub fn attribute(c: &OpenedContainer, offset: u64) -> Outcome<Place> {
    let mut place = Place::default();
    // 段号 = 段表里偏移不大于 offset 的最后一段（容器允许段体顺序任意，故不能按段号猜）。
    let mut hit: Option<(usize, u64)> = None;
    for (idx, off) in c.header.offsets.iter().enumerate() {
        if let Some(v) = off {
            let v = *v as u64;
            if v <= offset && Some(v) > hit.map(|(_, o)| o) {
                hit = Some((idx, v));
            }
        }
    }
    let Some((idx, start)) = hit else {
        place.section = "<段表之前>".to_string();
        return Ok(place);
    };
    place.section_index = idx;
    place.section = container::section_name(idx).to_string();
    place.intra_offset = offset - start;
    if idx != SECTION_CODE {
        return Ok(place);
    }
    // 指令流：u32 条数 + 每条 16 B
    if place.intra_offset < 4 {
        return Ok(place);
    }
    let pc = ((place.intra_offset - 4) / INSTRUCTION_BYTES) as u32;
    place.pc = Some(pc);
    if let Some(table) = read_functions(c)? {
        let mut entries: Vec<&FunctionEntry> =
            table.functions.iter().filter(|f| f.entry_pc >= 0).collect();
        entries.sort_by_key(|f| f.entry_pc);
        place.function = entries
            .iter()
            .rev()
            .find(|f| (f.entry_pc as u64) <= pc as u64)
            .map(|f| f.name.clone());
    }
    place.line = read_line(c, pc)?;
    // 该条指令四个 u32（段内对齐后再读，读到段尾之外就不填）
    let (seg_start, seg_end) = c.header.section_range(SECTION_CODE, c.file_size as usize)?;
    let at = seg_start as u64 + 4 + pc as u64 * INSTRUCTION_BYTES;
    if at + INSTRUCTION_BYTES <= seg_end as u64 {
        let mut f = std::fs::File::open(&c.path)
            .map_err(|e| ErbccError::Io(format!("{}: 打开失败（{e}）", c.path.display())))?;
        f.seek(SeekFrom::Start(at))
            .map_err(|e| ErbccError::Io(format!("{}: 定位失败（{e}）", c.path.display())))?;
        let mut w = [0u8; 16];
        f.read_exact(&mut w)
            .map_err(|e| ErbccError::Io(format!("{}: 读到 EOF（{e}）", c.path.display())))?;
        place.words = Some([
            u32::from_le_bytes([w[0], w[1], w[2], w[3]]),
            u32::from_le_bytes([w[4], w[5], w[6], w[7]]),
            u32::from_le_bytes([w[8], w[9], w[10], w[11]]),
            u32::from_le_bytes([w[12], w[13], w[14], w[15]]),
        ]);
    }
    Ok(place)
}

/// 两个文件的首个不同字节偏移（64 KiB 流式，不整读）；完全相同返回 `Ok(None)`。
/// 长度不同但公共前缀相同 ⇒ 差异点在短文件末尾（返回较短的结尾偏移）。
pub fn first_diff_offset(a: &Path, b: &Path) -> io::Result<Option<u64>> {
    let mut fa = std::fs::File::open(a)?;
    let mut fb = std::fs::File::open(b)?;
    let mut buf_a = vec![0u8; 1 << 16];
    let mut buf_b = vec![0u8; 1 << 16];
    let mut base = 0u64;
    loop {
        let na = read_fill(&mut fa, &mut buf_a)?;
        let nb = read_fill(&mut fb, &mut buf_b)?;
        let n = na.min(nb);
        for i in 0..n {
            if buf_a[i] != buf_b[i] {
                return Ok(Some(base + i as u64));
            }
        }
        if na != nb {
            return Ok(Some(base + n as u64));
        }
        if na == 0 {
            return Ok(None);
        }
        base += na as u64;
    }
}

fn read_fill(f: &mut std::fs::File, buf: &mut [u8]) -> io::Result<usize> {
    let mut got = 0usize;
    while got < buf.len() {
        let n = f.read(&mut buf[got..])?;
        if n == 0 {
            break;
        }
        got += n;
    }
    Ok(got)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    /// 造最小合法 v2 容器：指令流 2 条（F_A@pc0、F_B@pc1）、函数表两条（**故意逆序**，
    /// 验证定位按 pc 不按表序）、行号表两行。
    fn synth(path: &Path, second_word: u32) {
        let mut bodies: Vec<Vec<u8>> = vec![Vec::new(); 9];
        let mut funcs = Vec::new();
        let mut code = Vec::new();
        let mut lines = Vec::new();

        funcs.extend_from_slice(&2u32.to_le_bytes());
        funcs.extend_from_slice(&0u32.to_le_bytes());
        funcs.extend_from_slice(&3u32.to_le_bytes());
        funcs.extend_from_slice(&2u32.to_le_bytes());
        funcs.extend_from_slice(&6u32.to_le_bytes());
        for off in [0u32, 3, 6] {
            funcs.extend_from_slice(&off.to_le_bytes());
        }
        funcs.extend_from_slice(b"F_BF_A");
        for v in [0u32, 1, 0, 1, 0, 0] {
            funcs.extend_from_slice(&v.to_le_bytes());
        }
        for v in [1u32, 0, 0, 2, 0, 0] {
            funcs.extend_from_slice(&v.to_le_bytes());
        }

        code.extend_from_slice(&2u32.to_le_bytes());
        for v in [1u32, 2, 3, 4, second_word, 0, 0, 0] {
            code.extend_from_slice(&v.to_le_bytes());
        }

        lines.extend_from_slice(&0u32.to_le_bytes());
        lines.extend_from_slice(&2u32.to_le_bytes());
        for v in [10i32, 42] {
            lines.extend_from_slice(&v.to_le_bytes());
        }

        bodies[SECTION_FUNCTIONS] = funcs;
        bodies[SECTION_CODE] = code;
        bodies[SECTION_LINES] = lines;

        let mut out = Vec::new();
        out.extend_from_slice(b"ERBC");
        out.extend_from_slice(&2u16.to_le_bytes());
        // flags 必须与段表自洽：带行号表 ⇒ FLAG_HAS_LINES(0x0001) 必须置位，否则
        // `container::open` 在头层就按「标志位与段表不一致」拒掉（测试里踩过）。
        out.extend_from_slice(&1u16.to_le_bytes());
        out.extend_from_slice(&9u16.to_le_bytes());
        let mut offsets = vec![0u32; 9];
        let mut pos = (container::HEADER_BYTES + 9 * 4) as u32;
        for (i, body) in bodies.iter().enumerate() {
            if body.is_empty() {
                continue;
            }
            offsets[i] = pos;
            pos += body.len() as u32;
        }
        for o in &offsets {
            out.extend_from_slice(&o.to_le_bytes());
        }
        for body in &bodies {
            out.extend_from_slice(body);
        }
        std::fs::File::create(path)
            .unwrap()
            .write_all(&out)
            .unwrap();
    }

    fn tmp(name: &str) -> std::path::PathBuf {
        let mut p = std::env::temp_dir();
        p.push(format!("erbcc_locate_{}_{}", name, std::process::id()));
        p
    }

    #[test]
    fn functions_parse_with_reverse_order() {
        let p = tmp("fn.erbc");
        synth(&p, 7);
        let c = container::open(&p).unwrap();
        let t = read_functions(&c).unwrap().unwrap();
        assert_eq!(t.entry_function, 0);
        assert_eq!(t.global_slots, 3);
        assert_eq!(t.functions[0].name, "F_B");
        assert_eq!(t.functions[1].name, "F_A");
        std::fs::remove_file(&p).ok();
    }

    #[test]
    fn offset_maps_to_function_pc_line_words() {
        let p = tmp("attr.erbc");
        synth(&p, 7);
        let c = container::open(&p).unwrap();
        let (start, _) = c
            .header
            .section_range(SECTION_CODE, c.file_size as usize)
            .unwrap();
        // 第 2 条指令的首字
        let off = start as u64 + 4 + INSTRUCTION_BYTES;
        let place = attribute(&c, off).unwrap();
        assert_eq!(place.section, "CODE");
        assert_eq!(place.pc, Some(1));
        assert_eq!(place.function.as_deref(), Some("F_B"));
        assert_eq!(place.line, Some(42));
        assert_eq!(place.words, Some([7, 0, 0, 0]));
        std::fs::remove_file(&p).ok();
    }

    #[test]
    fn pc_zero_is_first_function() {
        let p = tmp("pc0.erbc");
        synth(&p, 7);
        let c = container::open(&p).unwrap();
        let (start, _) = c
            .header
            .section_range(SECTION_CODE, c.file_size as usize)
            .unwrap();
        let place = attribute(&c, start as u64 + 4).unwrap();
        assert_eq!(place.pc, Some(0));
        assert_eq!(place.function.as_deref(), Some("F_A"));
        assert_eq!(place.line, Some(10));
        std::fs::remove_file(&p).ok();
    }

    #[test]
    fn first_diff_offset_reports_exact_byte() {
        let a = tmp("a.erbc");
        let b = tmp("b.erbc");
        synth(&a, 7);
        synth(&b, 9);
        let c = container::open(&a).unwrap();
        let (start, _) = c
            .header
            .section_range(SECTION_CODE, c.file_size as usize)
            .unwrap();
        let off = first_diff_offset(&a, &b).unwrap().unwrap();
        assert_eq!(off, start as u64 + 4 + INSTRUCTION_BYTES);
        assert_eq!(first_diff_offset(&a, &a).unwrap(), None);
        std::fs::remove_file(&a).ok();
        std::fs::remove_file(&b).ok();
    }
}
