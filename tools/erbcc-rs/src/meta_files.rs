//! `program.meta` 侧车的「函数键 → 源文件」段（CONTRACT-RUSTFULL §6.3 第 5 项）。
//!
//! 为什么需要它：`firstdiff` 能把产物差异定位到「段/函数/pc/行」，但下一句总是「那这是哪个
//! ERB 文件里的？」——没有文件名的定位，每次 diff 还要回语料里 grep 一遍函数名。侧车
//! 已经持久化了这张表：`BootMetadata.kt:832` 写 `files <N>` + 每行
//! `file "<函数键>" "<源文件相对路径>"`，读侧对应 `fileOf`（`BootMetadata.kt:346`）。
//!
//! 口径（与 Kotlin 侧逐条对齐）：
//! - **函数键 = 函数签名键的大写**（`CorpusCompile.kt:1381-1384` 的
//!   `fileByKey[sigKey.uppercase(Locale.ROOT)] = def.file`），与 `.erbc` 函数表里的名字同形
//!   （实测 119,289 条、无 `@` 后缀），所以查表先按原样、再按 ASCII 大写兜底；
//! - 字符串是 `BootMetadata.kt:1016` 的 `quote()` 方言：转义 `\"` `\\` `\n` `\r` `\t`，
//!   控制字符写 `\uXXXX`；
//! - 只读 `files` 段：段前是 labels/dims 等大块，段后是 data-sections 等，**读到 N 行就停**，
//!   不把 58 MB 侧车整份解析成结构。

use std::collections::HashMap;
use std::path::Path;

/// 函数键 → 源文件（相对语料根的路径，如 `BODY_INFO/ALTER_IMAGE_SET.ERB`）。
#[derive(Debug, Default, Clone)]
pub struct FileMap {
    by_key: HashMap<String, String>,
}

impl FileMap {
    /// 从 `program.meta` 读 `files` 段。文件不存在或没有该段都返回错误（调用方决定是否降级）。
    pub fn load(path: &Path) -> Result<Self, String> {
        let bytes = std::fs::read(path).map_err(|e| format!("读 {} 失败: {e}", path.display()))?;
        let text = String::from_utf8(bytes).map_err(|e| format!("{} 不是合法 UTF-8: {e}", path.display()))?;
        Self::from_text(&text)
    }

    /// 解析侧车文本（抽出来供单测；`program.meta` 就是这个形状）。
    pub fn from_text(text: &str) -> Result<Self, String> {
        let mut by_key = HashMap::new();
        let mut lines = text.lines();
        // 段头：`files <N>`
        let mut declared: Option<usize> = None;
        for line in lines.by_ref() {
            if let Some(rest) = line.strip_prefix("files ") {
                declared = Some(
                    rest.trim()
                        .parse()
                        .map_err(|e| format!("files 段计数不是整数（{line}）: {e}"))?,
                );
                break;
            }
        }
        let n = declared.ok_or_else(|| "侧车缺 `files` 段（不是 erbcc 产物或版本不符）".to_string())?;

        for _ in 0..n {
            let line = lines
                .next()
                .ok_or_else(|| format!("files 段声明 {n} 条，实际提前结束"))?;
            let rest = line
                .strip_prefix("file ")
                .ok_or_else(|| format!("files 段内出现非 `file` 行: {line}"))?;
            let (key, rest) = take_quoted(rest).ok_or_else(|| format!("函数键不是引号串: {line}"))?;
            let rest = rest.trim_start();
            let (path, _) = take_quoted(rest).ok_or_else(|| format!("源文件不是引号串: {line}"))?;
            // 同键重复：后写覆盖前写（与侧车写侧的 LinkedHashMap 语义一致，写侧不会重复）
            by_key.insert(key, path);
        }
        if by_key.len() != n {
            return Err(format!("files 段声明 {n} 条，实际解析出 {} 条", by_key.len()));
        }
        Ok(Self { by_key })
    }

    pub fn len(&self) -> usize {
        self.by_key.len()
    }

    pub fn is_empty(&self) -> bool {
        self.by_key.is_empty()
    }

    /// 查函数 → 源文件。先原样查（键本来就是大写），再按 ASCII 大写兜底。
    pub fn file_of(&self, function: &str) -> Option<&str> {
        self.by_key
            .get(function)
            .or_else(|| self.by_key.get(function.to_ascii_uppercase().as_str()))
            .map(|s| s.as_str())
    }
}

/// 取一个引号串，返回 (解转义后的内容, 余下文本)。
fn take_quoted(s: &str) -> Option<(String, &str)> {
    let body = s.strip_prefix('"')?;
    let mut out = String::with_capacity(body.len());
    let mut chars = body.char_indices();
    while let Some((i, c)) = chars.next() {
        match c {
            '"' => return Some((out, &body[i + 1..])),
            '\\' => {
                let (_, e) = chars.next()?;
                match e {
                    '"' => out.push('"'),
                    '\\' => out.push('\\'),
                    'n' => out.push('\n'),
                    'r' => out.push('\r'),
                    't' => out.push('\t'),
                    'u' => {
                        // `\uXXXX`：写侧只在控制字符上用（`BootMetadata.kt:1027`）
                        let mut hex = String::with_capacity(4);
                        for _ in 0..4 {
                            let (_, h) = chars.next()?;
                            hex.push(h);
                        }
                        let v = u32::from_str_radix(&hex, 16).ok()?;
                        out.push(char::from_u32(v)?);
                    }
                    other => out.push(other),
                }
            }
            _ => out.push(c),
        }
    }
    None // 引号未闭合
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = "format-version 3\nengine \"x\"\nlabels 0\nfiles 2\n\
        file \"ITEM_EXPLANATION\" \"BODY_INFO/ITEM.ERB\"\n\
        file \"AA_帕琪_后入式\" \"魔改内容/AA字符画/AA_test_1.ERB\"\n\
        data-sections 0\n";

    #[test]
    fn parses_files_section_and_ignores_rest() {
        let m = FileMap::from_text(SAMPLE).expect("解析");
        assert_eq!(m.len(), 2);
        assert_eq!(m.file_of("ITEM_EXPLANATION"), Some("BODY_INFO/ITEM.ERB"));
        assert_eq!(m.file_of("AA_帕琪_后入式"), Some("魔改内容/AA字符画/AA_test_1.ERB"));
    }

    #[test]
    fn lookup_is_ascii_case_insensitive() {
        let m = FileMap::from_text(SAMPLE).unwrap();
        assert_eq!(m.file_of("item_explanation"), Some("BODY_INFO/ITEM.ERB"));
    }

    #[test]
    fn unescapes_quote_dialect() {
        let t = "files 1\nfile \"A\\\\B\\\"C\" \"D\\\\E/日\\t本.ERB\"\n";
        let m = FileMap::from_text(t).unwrap();
        assert_eq!(m.file_of("A\\B\"C"), Some("D\\E/日\t本.ERB"));
    }

    #[test]
    fn rejects_count_mismatch_and_missing_section() {
        let bad = "files 3\nfile \"X\" \"y.ERB\"\n";
        assert!(FileMap::from_text(bad).is_err(), "声明 3 只给 1 条必须报错");
        let none = "format-version 3\nengine \"x\"\n";
        assert!(FileMap::from_text(none).is_err(), "缺 files 段必须报错");
    }
}
