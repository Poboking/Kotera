//! `firstdiff` 的源文件定位：侧车 `files` 段的真夹具校验（CONTRACT-RUSTFULL §6.3）。
//!
//! 夹具 `tests/data/rust-manifest/erbc.v1/program.meta` 是真产物侧车（小语料），
//! 用它钉住「函数键 → 源文件」这条链：`firstdiff` 报出的源文件名要能和语料里的
//! `@函数名` 定义对得上，否则定位给出的文件就是假的，比不给更坏。

use erbcc_rs::meta_files::FileMap;
use std::path::PathBuf;

fn fixture_meta() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/data/rust-manifest/erbc.v1/program.meta")
}

#[test]
fn real_sidecar_files_section_is_usable() {
    let m = FileMap::load(&fixture_meta()).expect("真侧车必须能解析 files 段");
    assert!(m.len() >= 2, "该夹具至少两条函数键，实际 {}", m.len());
    // 键就是 `.erbc` 函数表里的名字（大写），值 = 相对语料根的源文件
    assert_eq!(m.file_of("SYSTEM_TITLE"), Some("01_main.ERB"));
    assert_eq!(m.file_of("MINI_UTIL"), Some("02_util.ERB"));
    // 不存在/大小写兜底行为：ASCII 小写也能命中，真没有就 None（不许编一个文件名）
    assert_eq!(m.file_of("system_title"), Some("01_main.ERB"));
    assert_eq!(m.file_of("NO_SUCH_FUNCTION"), None);
}

#[test]
fn reported_file_really_contains_the_function() {
    // 与语料侧交叉验证：`02_util.ERB` 里必须真有 `@MINI_UTIL` 定义。
    // 这条守的是「映射指错文件」——那会让 P2 的每次 diff 都把人带到错的地方。
    let corpus = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/data/rust-manifest/corpus");
    let util = std::fs::read_to_string(corpus.join("02_util.ERB")).expect("读夹具语料");
    assert!(
        util.to_ascii_uppercase().contains("@MINI_UTIL"),
        "02_util.ERB 应含 @MINI_UTIL 定义，实际内容:\n{util}"
    );
}
