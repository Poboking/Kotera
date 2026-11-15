# 【入库配方】rust-meta-parity 的 CLI 两腿代价测量（产出 cost-readings.txt 的 B 批读数）
# 原件是车道跑测时临时放在 build/meta-parity/ 下的脚本（build/ 被 gitignore，重跑会丢），
# 随读数一起入库。args-*.txt / out-*.txt 仍落 build/meta-parity/（本机临时面，不入库）。
# 用法：bash tests/data/rust-meta-parity/run-cost.sh [small|full|all]（路径写死本机共享根，换机改顶部）
#!/bin/bash
# rust-meta-parity 的代价读数：Kotlin / Rust 两腿 × 小语料 / 全量语料。
# 用法：bash build/meta-parity/run.sh [small|full|all]
set -u
export JAVA_HOME='<JDK17>'
export PATH="$JAVA_HOME/bin:$PATH"
RS="${ERATW_ERBCC_RS:-../tools/erbcc-rs}"
REPO_WIN='<REPO>'
LIB="$RS/../erbcc/build/install/erbcc/lib"
CPWIN="$LIB/annotations-13.0.jar;$LIB/core-vm-0.1.0-SNAPSHOT.jar;$LIB/erbcc-0.1.0-SNAPSHOT.jar;$LIB/kotlin-stdlib-2.0.20.jar"
JAVA_EXE="$JAVA_HOME/bin/java.exe"
SMALL_CASE="$RS/build/meta-parity/small-case"
SMALL_CORPUS="$RS/tests/data/rust-sidecar-fuzz/corpus/ERB"
FULL_CASE="$RS/build/baseline/full"
FULL_CORPUS_WIN='<CORPUS-ERB>'

mkdir -p "$SMALL_CASE"
rm -rf "$SMALL_CASE/erbc.v1"
cp -r "$RS/tests/data/rust-sidecar-fuzz/seed/erbc.v1" "$SMALL_CASE/"

# $1=label $2=exe $3..=argv（逐行写进 UTF-8 文件，绕开命令行码页）
run() {
  local label="$1"; shift
  local exe="$1"; shift
  local af="$RS/build/meta-parity/args-$label.txt"
  : > "$af"
  for a in "$@"; do printf '%s\n' "$a" >> "$af"; done
  echo "=== $label"
  powershell -NoProfile -ExecutionPolicy Bypass -File "$RS/tests/data/rust-meta-parity/measure.ps1" \
    -Exe "$exe" -ArgFile "$af" -WorkDir "$REPO_WIN" -OutFile "$RS/build/meta-parity/out-$label.txt"
}

kotlin_check() { run "$1-kotlin" "$JAVA_EXE" -Xmx6g -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -cp "$CPWIN" com.eratw.port.erbcc.MainKt check --corpus "$2" --out "$3"; }
rust_check()   { run "$1-rust"   "$RS/target/release/erbcc.exe" check --corpus "$2" --out "$3"; }

case "${1:-all}" in
  small) kotlin_check small "$SMALL_CORPUS" "$SMALL_CASE"; rust_check small "$SMALL_CORPUS" "$SMALL_CASE" ;;
  full)  kotlin_check full "$FULL_CORPUS_WIN" "$FULL_CASE"; rust_check full "$FULL_CORPUS_WIN" "$FULL_CASE" ;;
  all)   kotlin_check small "$SMALL_CORPUS" "$SMALL_CASE"; rust_check small "$SMALL_CORPUS" "$SMALL_CASE"
         kotlin_check full "$FULL_CORPUS_WIN" "$FULL_CASE"; rust_check full "$FULL_CORPUS_WIN" "$FULL_CASE" ;;
esac
