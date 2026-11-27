//! JDK 17 与 rustc 1.99 的大写表差异补丁（CONTRACT-RUSTFULL §6.2 K19）。
//!
//! 来源：`python devtools/erbcc_semantics_diff.py upper` 的全 Unicode 标量空间对拍——
//! 拿**真实 JDK 17** 的 `String.toUpperCase(Locale.ROOT)` 做基线，与 rustc 1.99 的
//! `char::to_uppercase` 逐码点比。实测 1,112,064 行里 **95 行**不同，全部是「JDK 17 的
//! Unicode 表还没有、新版 Unicode 才补上映射」的新字符（U+019B / U+0264 / U+1C8A /
//! U+2C5F / U+A7C1..U+A7DB / U+10597..），没有一个是多字符展开。
//!
//! 为什么要补：canonical 键 = `name.uppercase()`（`Program.kt:100-112`），键一旦分叉，
//! 函数查找与写盘顺序都会跟着变。不能靠「语料里不会出现这些字符」放行——第三方语料是
//! UTF-8 的，随时可能出现。
//!
//! 重新生成：`python devtools/erbcc_semantics_diff.py upper --regen-patch`。

/// 覆盖表：`(码点, JDK 17 视角的大写码点)`，按码点升序。查不到就退回 rustc 的 `to_uppercase`。
const OVERRIDES: &[(u32, u32)] = &[
    (0x019B, 0x019B), // ƛ
    (0x0264, 0x0264), // ɤ
    (0x1C8A, 0x1C8A), // ᲊ
    (0x2C5F, 0x2C5F), // ⱟ
    (0xA7C1, 0xA7C1), // ꟁ
    (0xA7CD, 0xA7CD), // ꟍ
    (0xA7CF, 0xA7CF), // ꟏
    (0xA7D1, 0xA7D1), // ꟑ
    (0xA7D3, 0xA7D3), // ꟓ
    (0xA7D5, 0xA7D5), // ꟕ
    (0xA7D7, 0xA7D7), // ꟗ
    (0xA7D9, 0xA7D9), // ꟙ
    (0xA7DB, 0xA7DB), // ꟛ
    (0x10597, 0x10597), // 𐖗
    (0x10598, 0x10598), // 𐖘
    (0x10599, 0x10599), // 𐖙
    (0x1059A, 0x1059A), // 𐖚
    (0x1059B, 0x1059B), // 𐖛
    (0x1059C, 0x1059C), // 𐖜
    (0x1059D, 0x1059D), // 𐖝
    (0x1059E, 0x1059E), // 𐖞
    (0x1059F, 0x1059F), // 𐖟
    (0x105A0, 0x105A0), // 𐖠
    (0x105A1, 0x105A1), // 𐖡
    (0x105A3, 0x105A3), // 𐖣
    (0x105A4, 0x105A4), // 𐖤
    (0x105A5, 0x105A5), // 𐖥
    (0x105A6, 0x105A6), // 𐖦
    (0x105A7, 0x105A7), // 𐖧
    (0x105A8, 0x105A8), // 𐖨
    (0x105A9, 0x105A9), // 𐖩
    (0x105AA, 0x105AA), // 𐖪
    (0x105AB, 0x105AB), // 𐖫
    (0x105AC, 0x105AC), // 𐖬
    (0x105AD, 0x105AD), // 𐖭
    (0x105AE, 0x105AE), // 𐖮
    (0x105AF, 0x105AF), // 𐖯
    (0x105B0, 0x105B0), // 𐖰
    (0x105B1, 0x105B1), // 𐖱
    (0x105B3, 0x105B3), // 𐖳
    (0x105B4, 0x105B4), // 𐖴
    (0x105B5, 0x105B5), // 𐖵
    (0x105B6, 0x105B6), // 𐖶
    (0x105B7, 0x105B7), // 𐖷
    (0x105B8, 0x105B8), // 𐖸
    (0x105B9, 0x105B9), // 𐖹
    (0x105BB, 0x105BB), // 𐖻
    (0x105BC, 0x105BC), // 𐖼
    (0x10D70, 0x10D70), // 𐵰
    (0x10D71, 0x10D71), // 𐵱
    (0x10D72, 0x10D72), // 𐵲
    (0x10D73, 0x10D73), // 𐵳
    (0x10D74, 0x10D74), // 𐵴
    (0x10D75, 0x10D75), // 𐵵
    (0x10D76, 0x10D76), // 𐵶
    (0x10D77, 0x10D77), // 𐵷
    (0x10D78, 0x10D78), // 𐵸
    (0x10D79, 0x10D79), // 𐵹
    (0x10D7A, 0x10D7A), // 𐵺
    (0x10D7B, 0x10D7B), // 𐵻
    (0x10D7C, 0x10D7C), // 𐵼
    (0x10D7D, 0x10D7D), // 𐵽
    (0x10D7E, 0x10D7E), // 𐵾
    (0x10D7F, 0x10D7F), // 𐵿
    (0x10D80, 0x10D80), // 𐶀
    (0x10D81, 0x10D81), // 𐶁
    (0x10D82, 0x10D82), // 𐶂
    (0x10D83, 0x10D83), // 𐶃
    (0x10D84, 0x10D84), // 𐶄
    (0x10D85, 0x10D85), // 𐶅
    (0x16EBB, 0x16EBB), // 𖺻
    (0x16EBC, 0x16EBC), // 𖺼
    (0x16EBD, 0x16EBD), // 𖺽
    (0x16EBE, 0x16EBE), // 𖺾
    (0x16EBF, 0x16EBF), // 𖺿
    (0x16EC0, 0x16EC0), // 𖻀
    (0x16EC1, 0x16EC1), // 𖻁
    (0x16EC2, 0x16EC2), // 𖻂
    (0x16EC3, 0x16EC3), // 𖻃
    (0x16EC4, 0x16EC4), // 𖻄
    (0x16EC5, 0x16EC5), // 𖻅
    (0x16EC6, 0x16EC6), // 𖻆
    (0x16EC7, 0x16EC7), // 𖻇
    (0x16EC8, 0x16EC8), // 𖻈
    (0x16EC9, 0x16EC9), // 𖻉
    (0x16ECA, 0x16ECA), // 𖻊
    (0x16ECB, 0x16ECB), // 𖻋
    (0x16ECC, 0x16ECC), // 𖻌
    (0x16ECD, 0x16ECD), // 𖻍
    (0x16ECE, 0x16ECE), // 𖻎
    (0x16ECF, 0x16ECF), // 𖻏
    (0x16ED0, 0x16ED0), // 𖻐
    (0x16ED1, 0x16ED1), // 𖻑
    (0x16ED2, 0x16ED2), // 𖻒
    (0x16ED3, 0x16ED3), // 𖻓
];

/// 整串大写（等价于 `String.toUpperCase(Locale.ROOT)` 在 ROOT 语境下的行为）。
///
/// 逐字符处理，**保留多字符展开**（`ß` → `SS`、`ǰ` → `J` + U+030C）；只有命中补丁表的
/// 那 95 个码点才改用 JDK 17 的单字符结果。
pub fn uppercase_str(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match OVERRIDES.binary_search_by_key(&(c as u32), |e| e.0) {
            Ok(i) => out.push(char::from_u32(OVERRIDES[i].1).unwrap_or(c)),
            Err(_) => out.extend(c.to_uppercase()),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn patches_apply() {
        assert_eq!(uppercase_str("\u{019B}"), "\u{019B}");
        assert_eq!(uppercase_str("\u{0264}"), "\u{0264}");
        assert_eq!(uppercase_str("a"), "A");
        assert_eq!(uppercase_str("\u{FF41}abc"), "\u{FF21}ABC");
    }

    #[test]
    fn multi_char_expansion_survives() {
        // JDK 与 rustc 在这里一致：ß 大写是两个字符，不能被单字符化掉
        assert_eq!(uppercase_str("ß"), "SS");
    }

    #[test]
    fn table_is_sorted() {
        assert!(OVERRIDES.windows(2).all(|w| w[0].0 < w[1].0));
    }
}
