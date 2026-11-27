//! Java `Double.toString` 的等价实现（CONTRACT-RUSTFULL §6.2 K18）。
//!
//! 为什么需要它：语料里的浮点常量/折叠结果若要写成文本（`FormStringTerm.kt:98-109` 已记录
//! .NET 与 JVM 的文案差），Rust 的 `{}` 与 Java 的规则不同：Java 恒带小数点（`1.0`），
//! 且远离 1 的量级改用大写 `E` 记法（`1.0E-5`）；Rust 的 `{}` 是 `1`、`0.00001`。
//!
//! 本模块按 Java 规范复刻「最短可回环数字 + Java 展示规则」：
//! - `10^-3 <= |v| < 10^7`：定点，且小数部分至少一位（`1.0`、`0.001`、`123456.789`）；
//! - 其余量级：科学记数，一位整数位 + 至少一位小数 + 大写 `E`，指数不带 `+`（`1.0E7`、`1.0E-4`）；
//! - 特殊值：`NaN`、`Infinity`、`-Infinity`、`-0.0`。
//!
//! 已知边界：最短数字的选择在「同一长度有多个可回环候选」时，Java 的取舍与 Rust 的
//! `{:e}` 可能不同（经典例子：最小次正规数 Java 出 `4.9E-324`）。差异清单由
//! `devtools/erbcc_semantics_diff.py` 的全空间/边界对拍给出，不靠猜。

const MAX_PLAIN_EXP10: i32 = 6; // |v| < 10^7 用定点
const MIN_PLAIN_EXP10: i32 = -3; // |v| >= 10^-3 用定点

/// 按 Java 规则把 f64 转成字符串。
pub fn java_double_to_string(v: f64) -> String {
    if v.is_nan() {
        return "NaN".to_string();
    }
    if v.is_infinite() {
        return if v > 0.0 { "Infinity" } else { "-Infinity" }.to_string();
    }
    if v == 0.0 {
        return if v.is_sign_negative() { "-0.0" } else { "0.0" }.to_string();
    }

    let neg = v.is_sign_negative();
    let abs = v.abs();

    // Rust 的 `{:e}` 给出最短尾数 + 十进制指数：如 "1.2345e-7"、"1e0"。
    let sci = format!("{:e}", abs);
    let (mant, exp) = sci.split_once('e').expect("LowerExp 形如 <mant>e<exp>");
    let exp: i32 = exp.parse().expect("十进制指数");
    let digits: String = mant.chars().filter(|c| *c != '.').collect();
    debug_assert!(!digits.is_empty() && !digits.starts_with('0'));
    // mant 已是 d.ddd 规格化尾数 ⇒ 首位数字的权重就是 10^exp
    let exp10 = exp;

    let mut out = String::new();
    if neg {
        out.push('-');
    }

    if (MIN_PLAIN_EXP10..=MAX_PLAIN_EXP10).contains(&exp10) {
        if exp10 >= 0 {
            let int_len = (exp10 + 1) as usize;
            if digits.len() <= int_len {
                out.push_str(&digits);
                for _ in 0..(int_len - digits.len()) {
                    out.push('0');
                }
                out.push_str(".0");
            } else {
                out.push_str(&digits[..int_len]);
                out.push('.');
                out.push_str(&digits[int_len..]);
            }
        } else {
            let zeros = (-exp10 - 1) as usize;
            out.push_str("0.");
            for _ in 0..zeros {
                out.push('0');
            }
            out.push_str(&digits);
        }
    } else {
        out.push_str(&digits[..1]);
        out.push('.');
        if digits.len() > 1 {
            out.push_str(&digits[1..]);
        } else {
            out.push('0');
        }
        out.push('E');
        out.push_str(&exp10.to_string());
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn integer_keeps_one_fraction_digit() {
        assert_eq!(java_double_to_string(1.0), "1.0");
        assert_eq!(java_double_to_string(0.0), "0.0");
        assert_eq!(java_double_to_string(-0.0), "-0.0");
        assert_eq!(java_double_to_string(100.0), "100.0");
    }

    #[test]
    fn plain_band_boundaries() {
        assert_eq!(java_double_to_string(0.001), "0.001");
        assert_eq!(java_double_to_string(0.0001), "1.0E-4");
        assert_eq!(java_double_to_string(1_000_000.0), "1000000.0");
        assert_eq!(java_double_to_string(10_000_000.0), "1.0E7");
    }

    #[test]
    fn fractions() {
        assert_eq!(java_double_to_string(0.1), "0.1");
        assert_eq!(java_double_to_string(1.5), "1.5");
        assert_eq!(java_double_to_string(123456.789), "123456.789");
    }

    #[test]
    fn specials() {
        assert_eq!(java_double_to_string(f64::NAN), "NaN");
        assert_eq!(java_double_to_string(f64::INFINITY), "Infinity");
        assert_eq!(java_double_to_string(f64::NEG_INFINITY), "-Infinity");
    }
}
