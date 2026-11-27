//! 侧车两件的重写闸：`program.stmt.idx` 与 `program.meta`（CONTRACT-RUSTFULL §6.4 P1.5）。
//!
//! 与 `rewrite.rs`（容器）分工相同：读 → 模型 → 重写 → **逐字节相同**。两件的证明力不同：
//!
//! * **`program.stmt.idx`**：格式是定长的（`StmtOffsetSidecar.kt` 头部注释：magic `ERSTMT01`
//!   + version 2 + stmtCount + recordBytes + recordCrc32 + u32×count 偏移），重编码只是
//!   把值按同一布局落回去——所以本模块的**主要价值不是往返，而是配对校验**：
//!   侧车声明的条数 / 记录区字节数 / CRC-32 必须与 `program.erbc` 段 3 的真实内容对齐。
//!   侧车是可选加速件（读侧不可信就回落扫描），但构建期写错它等于每个玩家多花 9.5 s
//!   （`StmtOffsetSidecar.kt` 注释里的真机读数），所以构建期必须自己先对账。
//! * **`program.meta`**：行级文本。本模块按「词元」重建每一行：引号串**解转义后重新
//!   `quote()` 一遍**（`BootMetadata.kt:1015-1031` 的方言：`\"` `\\` `\n` `\r` `\t`，
//!   其余 C0 走 `\uXXXX`），裸词元原样回写。逐字节相同 ⇒ 这套方言对真产物里 100 多万个
//!   引号串（函数键、源文件路径、signature、宏、常量…）全部成立。
//!
//! 不做的事：`program.meta` 的**语义**分节（labelset / function / dims …）不在本模块解析——
//! 那是装载侧的事（`meta_files.rs` 只读 `files` 段）。本模块只保证「同一份值 → 同一份字节」。

use crate::container;

/// `program.stmt.idx` 的魔数与版本（`StmtOffsetSidecar.kt:73-84`）。
pub const IDX_MAGIC: [u8; 8] = *b"ERSTMT01";
pub const IDX_VERSION_PAIRED: u32 = 2;
pub const IDX_HEADER_BYTES: usize = 24;

/// `stmt.idx` 结论。
#[derive(Debug, Clone)]
pub struct IdxReport {
    pub stmt_count: usize,
    pub record_bytes: usize,
    /// 侧车声明的条数是否等于 `program.erbc` 段 3 的语句条数。
    pub count_matches_program: bool,
    /// 侧车 CRC 是否等于段 3 记录区尾部的真实 CRC。
    pub crc_matches_program: bool,
    pub same: bool,
}

/// `meta` 结论。
#[derive(Debug, Clone)]
pub struct MetaReport {
    pub lines: usize,
    /// 解转义后重新引用过的引号串条数。
    pub quoted_values: usize,
    pub same: bool,
}

/// `program.stmt.idx`：解头 + 偏移表 → 与 `program.erbc` 段 3 对账 → 按同一布局重写。
pub fn rewrite_stmt_idx(idx: &[u8], erbc: &[u8]) -> Result<(Vec<u8>, IdxReport), String> {
    if idx.len() < IDX_HEADER_BYTES {
        return Err(format!("stmt.idx 长度 {} 不足 {IDX_HEADER_BYTES} 字节头", idx.len()));
    }
    if idx[..8] != IDX_MAGIC {
        return Err("stmt.idx 魔数不是 ERSTMT01".to_string());
    }
    let version = u32::from_le_bytes(idx[8..12].try_into().unwrap());
    if version != IDX_VERSION_PAIRED {
        return Err(format!("stmt.idx 版本 {version} ≠ {IDX_VERSION_PAIRED}（v1 无配对指纹，不重建）"));
    }
    let count = u32::from_le_bytes(idx[12..16].try_into().unwrap()) as usize;
    let record_bytes = u32::from_le_bytes(idx[16..20].try_into().unwrap()) as usize;
    let crc = u32::from_le_bytes(idx[20..24].try_into().unwrap());
    let expect = IDX_HEADER_BYTES + count * 4;
    if idx.len() != expect {
        return Err(format!("stmt.idx 长度 {} ≠ 24 + {count}×4 = {expect}", idx.len()));
    }
    let mut offsets = Vec::with_capacity(count);
    for i in 0..count {
        let at = IDX_HEADER_BYTES + i * 4;
        offsets.push(u32::from_le_bytes(idx[at..at + 4].try_into().unwrap()));
    }
    if count > 0 {
        if offsets[0] != 0 {
            return Err(format!("stmt.idx 首条偏移 {} ≠ 0", offsets[0]));
        }
        for i in 1..count {
            if offsets[i] <= offsets[i - 1] {
                return Err(format!(
                    "stmt.idx 偏移非单调递增（第 {i} 条 {} ≤ 前一条 {}）",
                    offsets[i],
                    offsets[i - 1]
                ));
            }
        }
        if offsets[count - 1] as usize >= record_bytes {
            return Err(format!(
                "stmt.idx 末条偏移 {} 不小于记录区长度 {record_bytes}",
                offsets[count - 1]
            ));
        }
    }

    // 与容器段 3 对账：条数取段头声明，CRC 取记录区尾部 recordBytes 字节。
    let header = container::parse(erbc).map_err(|e| container::message_of(&e))?;
    if !header.is_present(container::SECTION_STATEMENTS) {
        return Err("program.erbc 没有段 3（语句池），侧车无从对账".to_string());
    }
    let (from, to) = header
        .section_range(container::SECTION_STATEMENTS, erbc.len())
        .map_err(|e| container::message_of(&e))?;
    let body = &erbc[from..to];
    if body.len() < 8 {
        return Err(format!("段 3 正文只有 {} 字节，读不出段头", body.len()));
    }
    let stmt_count = u32::from_le_bytes(body[4..8].try_into().unwrap()) as usize;
    let count_matches_program = stmt_count == count;
    if record_bytes > body.len() {
        return Err(format!("stmt.idx 记录区 {record_bytes} 字节大于段 3 正文 {}", body.len()));
    }
    let records = &body[body.len() - record_bytes..];
    let real_crc = crc32(records);
    let crc_matches_program = real_crc == crc;

    let mut out = Vec::with_capacity(idx.len());
    out.extend_from_slice(&IDX_MAGIC);
    out.extend_from_slice(&IDX_VERSION_PAIRED.to_le_bytes());
    out.extend_from_slice(&(count as u32).to_le_bytes());
    out.extend_from_slice(&(record_bytes as u32).to_le_bytes());
    out.extend_from_slice(&crc.to_le_bytes());
    for o in &offsets {
        out.extend_from_slice(&o.to_le_bytes());
    }
    let same = out == idx;
    Ok((
        out,
        IdxReport { stmt_count: count, record_bytes, count_matches_program, crc_matches_program, same },
    ))
}

/// `program.meta`：按行、按词元重建（引号串解转义后重新 `quote()`）。
pub fn rewrite_meta(meta: &[u8]) -> Result<(Vec<u8>, MetaReport), String> {
    let text = std::str::from_utf8(meta).map_err(|e| format!("meta 不是合法 UTF-8：{e}"))?;
    let mut out = String::with_capacity(text.len());
    let mut lines = 0usize;
    let mut quoted_values = 0usize;
    // 保留结尾：逐行切、逐行重写，最后一行是否带换行由原文决定。
    let ends_with_lf = text.ends_with('\n');
    let mut split = text.split('\n');
    let body_lines: Vec<&str> = if ends_with_lf {
        let mut v: Vec<&str> = split.by_ref().collect();
        v.pop(); // 末尾空串
        v
    } else {
        split.collect()
    };
    for (i, line) in body_lines.iter().enumerate() {
        if i > 0 || lines > 0 {
            out.push('\n');
        }
        let (rendered, quoted) = render_line(line)?;
        quoted_values += quoted;
        out.push_str(&rendered);
        lines += 1;
    }
    if ends_with_lf {
        out.push('\n');
    }
    let same = out.as_bytes() == meta;
    Ok((out.into_bytes(), MetaReport { lines, quoted_values, same }))
}

/// 一行 → 重建行。返回（重建结果, 该行的引号串条数）。
fn render_line(line: &str) -> Result<(String, usize), String> {
    let bytes = line.as_bytes();
    let mut out = String::with_capacity(line.len());
    let mut quoted = 0usize;
    let mut i = 0usize;
    while i < bytes.len() {
        match bytes[i] {
            b' ' => {
                out.push(' ');
                i += 1;
            }
            b'"' => {
                let start = i + 1;
                let mut j = start;
                while j < bytes.len() {
                    match bytes[j] {
                        b'\\' => j += 2,
                        b'"' => break,
                        _ => j += 1,
                    }
                }
                if j >= bytes.len() {
                    return Err(format!("行内引号未闭合：{line}"));
                }
                let value = unescape(&line[start..j])?;
                out.push_str(&quote(&value));
                quoted += 1;
                i = j + 1;
            }
            _ => {
                let start = i;
                while i < bytes.len() && bytes[i] != b' ' {
                    i += 1;
                }
                out.push_str(&line[start..i]);
            }
        }
    }
    Ok((out, quoted))
}

/// `quote()` 的转义在读取侧还原（`BootMetadata.kt:1015-1031` 的方言）。
fn unescape(raw: &str) -> Result<String, String> {
    let mut out = String::with_capacity(raw.len());
    let mut it = raw.chars();
    while let Some(c) = it.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        let Some(e) = it.next() else { return Err("转义结尾缺字符".to_string()) };
        match e {
            '"' => out.push('"'),
            '\\' => out.push('\\'),
            'n' => out.push('\n'),
            'r' => out.push('\r'),
            't' => out.push('\t'),
            'u' => {
                let mut hex = String::new();
                for _ in 0..4 {
                    hex.push(it.next().ok_or("\\u 只有不足 4 位")?);
                }
                let v = u32::from_str_radix(&hex, 16).map_err(|_| format!("\\u{hex} 不是十六进制"))?;
                let ch = char::from_u32(v).ok_or_else(|| format!("\\u{hex} 不是合法码点"))?;
                out.push(ch);
            }
            other => return Err(format!("未知转义 \\{other}")),
        }
    }
    Ok(out)
}

/// `quote()` 的写出方言（与 `BootMetadata.kt:1015-1031` 逐字对齐）。
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
            c if (c as u32) < 0x20 => {
                out.push_str(&format!("\\u{:04x}", c as u32));
            }
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

/// CRC-32/ISO-HDLC（与 `java.util.zip.CRC32` 同多项式、同初值、同反射）。
pub fn crc32(bytes: &[u8]) -> u32 {
    let table = crc32_table();
    let mut crc = 0xFFFF_FFFFu32;
    for &b in bytes {
        let idx = ((crc ^ b as u32) & 0xFF) as usize;
        crc = (crc >> 8) ^ table[idx];
    }
    crc ^ 0xFFFF_FFFF
}

fn crc32_table() -> [u32; 256] {
    let mut t = [0u32; 256];
    for (i, slot) in t.iter_mut().enumerate() {
        let mut c = i as u32;
        for _ in 0..8 {
            c = if c & 1 != 0 { 0xEDB8_8320 ^ (c >> 1) } else { c >> 1 };
        }
        *slot = c;
    }
    t
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crc32_matches_public_vector() {
        // CRC-32/ISO-HDLC("123456789") = 0xCBF43926
        assert_eq!(crc32(b"123456789"), 0xCBF4_3926);
        assert_eq!(crc32(b""), 0);
    }

    #[test]
    fn quote_unescape_roundtrip() {
        for s in ["", "plain", "带\"引号\"", "a\\b", "tab\there", "line\nbreak", "\u{1}ctrl", "日本語 パス/ファイル.ERB"] {
            let q = quote(s);
            let back = unescape(&q[1..q.len() - 1]).unwrap();
            assert_eq!(back, s, "往返失败: {s:?}");
        }
    }

    #[test]
    fn meta_lines_rebuild_identically() {
        let src = "format-version 3\nengine \"erbcc 0.1.0-SNAPSHOT\"\nfile \"A\\\"B\" \"x/y.ERB\"\n";
        let (out, rep) = rewrite_meta(src.as_bytes()).unwrap();
        assert!(rep.same, "重建结果不同：{out:?}");
        assert_eq!(rep.lines, 3);
        assert_eq!(rep.quoted_values, 3);
    }

    #[test]
    fn meta_rejects_bad_escape() {
        let src = "file \"a\\qb\" \"x\"\n";
        assert!(rewrite_meta(src.as_bytes()).is_err());
    }

    #[test]
    fn stmt_idx_roundtrip_and_pair_check() {
        // 造一个极简容器：段 3 = nodeCount(=0) / count(=2) / 空串池 / 记录区 4 字节
        let records: &[u8] = &[1, 2, 3, 4];
        let mut body = Vec::new();
        body.extend_from_slice(&0u32.to_le_bytes()); // nodeCount
        body.extend_from_slice(&2u32.to_le_bytes()); // count
        body.extend_from_slice(&0u32.to_le_bytes()); // 串池 count
        body.extend_from_slice(&0u32.to_le_bytes()); // 串池 dataLen
        body.extend_from_slice(&0u32.to_le_bytes()); // 串池 offsets[0]
        body.extend_from_slice(records);
        let section_count = 9usize;
        let mut erbc = Vec::new();
        erbc.extend_from_slice(&container::MAGIC);
        erbc.extend_from_slice(&2u16.to_le_bytes());
        erbc.extend_from_slice(&4u16.to_le_bytes()); // FLAG_HAS_STATEMENTS
        erbc.extend_from_slice(&(section_count as u16).to_le_bytes());
        let mut offset = container::first_section_offset(section_count);
        let mut offs = vec![0u32; section_count];
        offs[container::SECTION_STATEMENTS] = offset as u32;
        offset += body.len();
        for o in &offs {
            erbc.extend_from_slice(&o.to_le_bytes());
        }
        erbc.extend_from_slice(&body);

        let mut idx = Vec::new();
        idx.extend_from_slice(&IDX_MAGIC);
        idx.extend_from_slice(&IDX_VERSION_PAIRED.to_le_bytes());
        idx.extend_from_slice(&2u32.to_le_bytes());
        idx.extend_from_slice(&(records.len() as u32).to_le_bytes());
        idx.extend_from_slice(&crc32(records).to_le_bytes());
        idx.extend_from_slice(&0u32.to_le_bytes());
        idx.extend_from_slice(&2u32.to_le_bytes());

        let (out, rep) = rewrite_stmt_idx(&idx, &erbc).unwrap();
        assert!(rep.same && rep.count_matches_program && rep.crc_matches_program);
        assert_eq!(out, idx);

        // 改一个记录字节：CRC 必须对不上（不静默）
        let mut erbc2 = erbc.clone();
        let tail = erbc2.len() - 1;
        erbc2[tail] ^= 0xFF;
        let (_, rep2) = rewrite_stmt_idx(&idx, &erbc2).unwrap();
        assert!(!rep2.crc_matches_program);
    }

    #[test]
    fn stmt_idx_rejects_non_monotonic() {
        let empty_erbc = {
            let mut b = Vec::new();
            b.extend_from_slice(&container::MAGIC);
            b.extend_from_slice(&2u16.to_le_bytes());
            b.extend_from_slice(&0u16.to_le_bytes());
            b.extend_from_slice(&9u16.to_le_bytes());
            for _ in 0..9 {
                b.extend_from_slice(&0u32.to_le_bytes());
            }
            b
        };
        let mut idx = Vec::new();
        idx.extend_from_slice(&IDX_MAGIC);
        idx.extend_from_slice(&IDX_VERSION_PAIRED.to_le_bytes());
        idx.extend_from_slice(&2u32.to_le_bytes());
        idx.extend_from_slice(&8u32.to_le_bytes());
        idx.extend_from_slice(&0u32.to_le_bytes());
        idx.extend_from_slice(&3u32.to_le_bytes());
        idx.extend_from_slice(&3u32.to_le_bytes());
        assert!(rewrite_stmt_idx(&idx, &empty_erbc).is_err());
    }
}
