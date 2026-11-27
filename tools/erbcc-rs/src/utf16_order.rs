//! Java `String.compareTo` 的等价实现（CONTRACT-RUSTFULL §6.2 K20）。
//!
//! 为什么需要它：erbc 产物里有多处按**字符串键**排序后写盘的路径（`ErbcWriter.kt:488`
//! `map.entries.sortedBy { it.key }`）。Kotlin 侧用的是 `String.compareTo`，其语义是
//! **UTF-16 码元序**；Rust 的 `str::cmp` 是 UTF-8 字节序（= Unicode 码点序）。两者只在
//! 非 BMP 字符（emoji、增补平面汉字等）附近分叉——而且是「某个值恰好出现时才分叉」，
//! 属于最难在语料里撞见、最容易在上线后炸的形态。
//!
//! 处置：不再依赖「语料里没有非 BMP」这个假设，凡影响写盘顺序的比较一律调用本模块。

use std::cmp::Ordering;

/// 把字符串展开成 UTF-16 码元序列。
pub fn utf16_units(s: &str) -> impl Iterator<Item = u16> + '_ {
    s.chars().flat_map(|c| {
        let n = c.len_utf16();
        let mut out = [0u16; 2];
        if n == 1 {
            out[0] = (c as u32) as u16;
        } else {
            let v = (c as u32) - 0x1_0000;
            out[0] = 0xD800 + ((v >> 10) as u16);
            out[1] = 0xDC00 + ((v & 0x3FF) as u16);
        }
        out.into_iter().take(n)
    })
}

/// 等价于 Java/Kotlin 的 `a.compareTo(b)`：按 UTF-16 码元逐一比较。
pub fn cmp_utf16(a: &str, b: &str) -> Ordering {
    utf16_units(a).cmp(utf16_units(b))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cmp::Ordering;

    #[test]
    fn bmp_matches_codepoint_order() {
        assert_eq!(cmp_utf16("abc", "abd"), Ordering::Less);
        assert_eq!(cmp_utf16("あ", "い"), Ordering::Less);
        assert_eq!(cmp_utf16("", "a"), Ordering::Less);
    }

    #[test]
    fn supplementary_sorts_before_bmp_tail_like_java() {
        // UTF-16 里 U+1F600 的首码元是 0xD83D，排在 U+FF21 之后、但排在任何 0xE000..0xFFFF 之前。
        // 码点序会得到相反结论，这正是要防的分叉点。
        let emoji = "\u{1F600}";
        let fullwidth_a = "\u{FF21}";
        let bmp_high = "\u{FFFD}";
        assert_eq!(cmp_utf16(emoji, fullwidth_a), Ordering::Less);
        assert_eq!(cmp_utf16(emoji, bmp_high), Ordering::Less);
        // 而码点序：emoji(0x1F600) > 0xFF21
        assert_eq!(emoji > fullwidth_a, true);
    }

    #[test]
    fn surrogate_pairs_compare_by_units() {
        // 同一高代理内，低代理决定先后
        assert_eq!(cmp_utf16("\u{1F600}", "\u{1F601}"), Ordering::Less);
    }
}
