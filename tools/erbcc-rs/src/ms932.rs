//! MS932（windows-31j）解码（CONTRACT-RUSTFULL §6.2 K21）。
//!
//! 为什么不能直接用 `encoding_rs::SHIFT_JIS`：JDK 的 `windows-31j` 与 WHATWG 的
//! `shift_jis` 不是同一张表。实测（单字节全空间 + 双字节全空间共 9,139 项）**只差 1 项**：
//! 单字节 `0x80`——JDK 判非法（解码抛 `MalformedInputException`），WHATWG 映射成 U+0080。
//! 差一项也够呛：语料读入路径按「解码成功/失败」分流（`EmueraConfigParser.kt:508` 的回落
//! 就是这条），差一项就会把同一段字节判到另一条分支上。
//!
//! 用法：编译期读语料一律走本模块，不要直接调 `encoding_rs::SHIFT_JIS`。

/// 实测差异表：`(单字节, JDK 判定)` —— 目前只有一项。
const MALFORMED_SINGLE: &[u8] = &[0x80];

/// 按 JDK `windows-31j` 的口径解码；不可解码返回 `None`（对应 Java 的 malformed 分支）。
pub fn decode(bytes: &[u8]) -> Option<String> {
    if bytes.len() == 1 && MALFORMED_SINGLE.contains(&bytes[0]) {
        return None;
    }
    let (text, _encoding, had_errors) = encoding_rs::SHIFT_JIS.decode(bytes);
    if had_errors {
        None
    } else {
        Some(text.into_owned())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lone_0x80_is_malformed_like_jdk() {
        assert!(decode(&[0x80]).is_none());
    }

    #[test]
    fn normal_bytes_decode() {
        assert_eq!(decode(&[0x82, 0xA0]).as_deref(), Some("あ"));
        assert_eq!(decode(&[0x5C]).as_deref(), Some("\\"));
    }

    #[test]
    fn invalid_trail_is_malformed() {
        // 0x81 是合法前导字节，但 0x7F 不在 0x40..=0xFC 的尾字节区间
        assert!(decode(&[0x81, 0x7F]).is_none());
    }
}
