<div align="center">

# Kotera

[简体中文](README.md) | [English](README_EN.md) | **日本語**

[![License: MIT](https://img.shields.io/badge/license-MIT-263146?style=flat-square)](LICENSE)
[![CI](https://github.com/Poboking/Kotera/actions/workflows/ci.yml/badge.svg)](https://github.com/Poboking/Kotera/actions/workflows/ci.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?style=flat-square)
![JDK](https://img.shields.io/badge/JDK-17-263146?style=flat-square)
![Platform](https://img.shields.io/badge/platform-JVM-6a737d?style=flat-square)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)

**Kotlin で実装した、Java 仮想マシンで動く ERA ゲームエンジン。**

Kotera は ERB ゲームスクリプトを実行し、キャラクターと変数を管理し、セーブを
扱い、描画は Android またはデスクトップのフロントエンドに任せます。ゲームの
「ルール層」と考えると分かりやすく、画面の見た目をつくる部分ではありません。

Android で EraTW を遊びたいだけなら
[eratw-pocket](https://github.com/Poboking/eratw-pocket) を使ってください。era
ゲームを作ったり移植したりして、独自のフロントエンドを接続したい場合はこの
リポジトリから始められます。Kotera はゲームデータを一切含まず、Android 環境が
なくても独立してビルドとテストができます。

</div>

---

## これは何か

- **セマンティクス層であり、フロントエンドではありません。** Kotera が持つのは
  ゲームのルールそのもの：ERB バイトコード実行、変数・キャラクターシステム、
  セーブ、組み込み関数です。描画はディスプレイリスト（文字・ボタン・画像を描く
  手順書）として出力し、見た目は各フロントエンドに任せます。
- **互換性の基準は Emuera。** 同じ入力を Emuera と Kotera の両方で実行し、結果を
  比べる「差分検証」を基準にしています。文書だけでは説明できない境界動作については
  Emuera のソースも調べ、版と `ファイル:行番号` を根拠として記録します。ソース参照と
  翻訳・適応の境界は [NOTICE.md](NOTICE.md) を参照してください。
- **現代的な ERA ランタイム機能。** 基本 VM に加え、リンク可能なパッチ / Mod、
  エンティティと仮想キャラクター、SQL セッション、更新マニフェスト署名、
  サイドカーセーブとクラッシュ復旧、メモリ階層などを備えます。
- **Android に依存しない。** 純 JVM 実装で、グラフィックのない環境でもテストが
  できます。これが CI での継続的な差分検証を可能にしています。
- **自己完結。** ゲームデータがなくても、ビルド・テスト・再現がすべて可能です。

`specs/` は本プロジェクトの意味論的な権威で、ISA・組み込み関数・変数・ロード・
セーブ形式・RNG・パッチコンテナ・エラーメッセージを扱います。各ファイルの関係は
[specs/README.md](specs/README.md) を参照してください。

## 最小クイックスタート

必要なのは JDK 17 以上だけです。Android SDK もゲームデータも不要です。コマンドは
リポジトリのルートで実行します（Windows では `./gradlew` を `.\gradlew.bat` に
置き換えてください）。Gradle が Java を見つけられない場合は、`JAVA_HOME` を
JDK 17 以上のインストール先に設定してください：

```bash
git clone https://github.com/Poboking/Kotera.git
cd Kotera

./gradlew :core-vm:test :render:test
./gradlew :tools:uipreview:test --tests '*CliSmokeTest'
```

最初のコマンドはエンジンと描画層の自己完結テストをビルドして実行します。2 番目は
一時的な mini ERB スクリプトを作ってコンパイルし、デスクトッププレビューサービスを
起動してクリックと文字入力を模擬し、最後に終了します。「スクリプト → エンジン →
プレビュー」の経路が動くことを確認するための例です。実ゲームのコーパスに依存する
用例は環境変数を設定した場合だけ実行されます。詳細は下の「ビルドとテスト」を
参照してください。

## 性能：根拠と不足点

Kotera はバイトコードを事前コンパイルし、ロード時にメモリマップして起動時の解析作業を
減らす設計です。それがすべての端末でエミュレータより速いことを意味するわけではありません。
**現時点では「Kotera / eratw-pocket 対エミュレータ」の同一版・同一シナリオ比較は
公開されておらず、この README は「一部のローエンド端末ではより快適」という主張を
取り下げます。**

利用できる実機記録は Android の AOT profile 実験であり、エミュレータ比較ではありません。

| 項目 | 実験条件 |
|---|---|
| 日付 / 端末 | 2026-10-27；vivo V2199A；Android 14 / API 34 / arm64-v8a |
| 比較対象 | 同じ 13.1 MB の release APK で AOT 状態だけを変更。Emuera や Android エミュレータではない |
| シナリオ | `am start` → タイトルページに選択可能な項目が表示されるまで；3 回 |
| 結果 | 実機 profile なし：15.98 s；実機で収集した 882 条の profile あり：12.74 s |
| 冷起動の `TotalTime` | 5 回の中央値：336 ms → 293 ms |
| 制限 | 端末上で展開済みの 749 MB のデータを再利用（`--skipAssets`）；SoC、物理 RAM、APK 版は未記録；タイトルページ経路のみ |

原記録は [eratw-android T2-63](https://github.com/Poboking/eratw-android-port/blob/main/docs/agent/archive-20261128/CONTRACT-T2.md#L520-L535) を参照してください。
これはそのビルドがその端末のタイトルページ起動経路で改善したことを示しますが、
**エミュレータに勝った証拠にはならず、その端末をローエンドと断定することもできません。**
今後の性能主張には、端末/SoC/RAM、OS と両者の版、同じゲームデータ、冷/温起動の条件、
シナリオ、サンプル数、フレーム時間またはロード時間の原記録を掲載してください。

## リポジトリ構成

| パス | 概要 |
|---|---|
| `core-vm` | ERA セマンティック VM：ERB バイトコード実行、変数・キャラクター、セーブ I/O、組み込み関数、SFMT 乱数、パッチ / Mod ランタイム |
| `render` | 純 JVM レンダリング：グリフキャッシュ、テキストグリッド、ボタン当たり判定、ディスプレイリスト、グラフィックレイヤー |
| `sqlite-adapter` | `SqlDriver` ポートの AndroidX SQLite アダプタ：同梱 seed DB を書き込み可能なコピーに落とし、原子リフレッシュ（SQLite 実エンジンのプラットフォーム接続） |
| `mod-api` | RT2 Mod SDK の API 面：エンティティ / イベント / 関数 / Hook などの宣言とホストコンテキスト |
| `mod-family` / `mod-memory` / `mod-livingworld` | 内蔵公式 Mod：家族系譜・記憶・生界（同梱 manifest の真のソース） |
| `mod-registry` | キャリアレジストリ：ビルド時に各 `mod-*` モジュールの `@ModCarrier` 付き宣言 object を走査し、唯一の `ModRegistry` を生成する（ホストはキャリアモジュールを個別に列挙しなくてよい） |
| `mod-template` | Mod 作者ツールチェーン：最小キャリアのテンプレート骨格（写すためのもので、本番の `ModRegistry` には**入らない**） |
| `mod-testing` | Mod 作者ツールチェーン：`:mod-api` の能力面に対するインメモリ実装と `runMod` 組み立て。エンジンを起動せずに Mod のロジックを試せる |
| `tools/erbcc` | ERB / ERH → `erbc.v1` バイトコードコンテナコンパイラ。コーパスコンパイル・シンボル監査・Rust 対拍接続を含む |
| `tools/erbcc-rs` | erbcc の Rust 側実装（Cargo プロジェクト）。Kotlin コンパイラとのバイト単位対拍用 |
| `tools/modgen` | RT2 モジュール / manifest ジェネレータ（内蔵 Mod とデモモジュールのビルド入口） |
| `tools/modgen-accessors` | modgen 宣言 → 型安全アクセサのコード生成 |
| `tools/vmdbg` | エージェント向け非対話デバッグ CLI：call / trace / where / var / disasm / sweep / ledger |
| `tools/uipreview` | デスクトップ描画プレビュー：実物の core-vm + render を駆動し、ブラウザで画面を確認 |
| `tools/fontsubset` | フォントサブセット再生成スクリプト（Python）。`render/src/main/resources/fonts` の保守用 |
| `specs/` | 意味論仕様と差分検証の根拠（ISA・組み込み・変数・ロード・セーブ・RNG・パッチ・エラー文言） |
| `fixtures/` | 自己完結したテストフィクスチャ：golden ERB、リプレイ軌跡、Rust 対拍データ |
| `buildSrc/` | ビルド時タスクと純関数（コーパスパッチ、erbc 鮮度、アセット staging） |
| `devtools/` | コーパス内容パッチと erbcc-rs 対拍ドライバ |
| `gradle/libs.versions.toml` | 依存座標と JVM / ツールチェーンのバージョンの単一ソース |

## ビルドとテスト

JDK 17 以上が必要です。コマンドはすべてリポジトリのルートで実行します。

```bash
./gradlew :core-vm:test             # モジュール単位のテスト
./gradlew :render:test
./gradlew :tools:erbcc:installDist  # コンパイラ CLI
./gradlew :tools:vmdbg:installDist  # デバッグ CLI
```

CI と同等のコマンド（15 モジュールをまとめて実行）：

```bash
./gradlew --no-daemon \
  :core-vm:test :render:test :sqlite-adapter:test \
  :mod-api:test :mod-family:test :mod-memory:test :mod-livingworld:test \
  :mod-registry:test :mod-testing:test :mod-template:test \
  :tools:erbcc:test :tools:modgen:test :tools:modgen-accessors:test \
  :tools:vmdbg:test :tools:uipreview:test
```

一部のテストは実コーパス・実産物・実セーブ・Android コンパイル面に依存し、実行するか
どうかを環境変数が決めます。変数が未設定の場合は JUnit assumption により
**スキップ**される用例もあれば、動的用例として生成されないものもあります。それ以外は
自己完結しています。公開 CI が回すのはこの範囲であり、「テストが全緑」=「実コーパスの
経路をすべて検証済み」ではありません。

CI は姉妹リポジトリ [eratw-pocket](https://github.com/Poboking/eratw-pocket) も
同じ階層にチェックアウトします（相手の `main` を読みます）。`tools/modgen` の
パッケージング検査はシェルの実ソースを読み、シェルの同梱アセットはエンジンの宣言と
バイト単位で一致している必要があります。この 2 つの倉をまたぐ検査は、相互検査が
有効な場合にだけ実行されます。どのリポジトリがどのソース検査を担当するか、マージ順、
固定フィクスチャの比較対象、権威ある語料根は
[specs/cross-repo-contract.md](specs/cross-repo-contract.md) にあります。

| 環境変数 / プロパティ | 役割 |
|---|---|
| `ERATW_CORPUS` | ゲームの `ERB/` ディレクトリ。実コーパスコンパイルとコーパス監査 |
| `ERATW_GAMEDIR` | ゲームルート（`CSV/`・`resources/`・`emuera.config`）。XML / 設定 / 画像の実測 |
| `ERATW_ERBC_DIR` | コンパイル済み `erbc.v1` 産物ディレクトリ。ロード / サイドカー / mmap |
| `ERATW_SAVE_SAMPLES` / `-PsaveSamples` | 実 `.sav` サンプルディレクトリ。セーブ往復・変異書き込み |
| `ERATW_ANDROID_JAR` | Android `android.jar`。未設定時は `ANDROID_HOME` / `ANDROID_SDK_ROOT` を自動探索し、見つからない場合のみ `AndroidApiCompatTest` をスキップ |
| `ERATW_ROOT` | 任意の共有チェックアウトルート。少数の内部リプレイ用例のみ使用し、公開リポジトリに既定値はありません |

`ERATW_CORPUS` が未設定の場合、`compileCorpus` などのコーパス依存タスクは
空パッケージを黙って作らず、明確なエラーで停止します。

**権威ある語料根 = Kotera 根**（`eratw-画蛇添足版eratw4.980`、NOW_VERSION 20260326）。
語料関連の凍結読み数（`ErbccCorpusProfile` のラベル数、`WebpDecoderTest` の `.webp` 一覧、
`AliasLoaderTest` の `.als` 別名、`RandSfCorpusE2ETest` の原文アンカー等）はすべてこの根で
凍結しており、二つの公開倉は同一の基準を共有します —— 別の根で走ると凍結値と食い違いま
すが、それは**語料世代の交代**であって回退ではありません。`RealCorpus` の既定探索も
この根を指します（`GAME_DIR_NAME`）。

### erbcc-rs 対拍

Rust 側には Cargo が必要です。ビルド後、Gradle 接続を使います：

```bash
cargo build --release --manifest-path tools/erbcc-rs/Cargo.toml
./gradlew :tools/erbcc:erbccClasspathFile
./gradlew :tools:erbcc:rsParity -Pcorpus=<ERBディレクトリ>
./gradlew :tools/erbcc:regenRsBaselines -Plane=all
```

詳細は [tools/erbcc-rs/README.md](tools/erbcc-rs/README.md) を参照してください。

## リポジトリ関係

```
Kotera（本リポジトリ、MIT）  eratw-pocket（GPL-3.0）
├── core-vm   ←─────────────     ├── render-canvas
├── render    ←─────────────     ├── platform
├── sqlite-adapter / mod-*       └── app（Compose シェル）
└── tools/erbcc
```

eratw-pocket がゲームの意味論を一切知らないのは、意図的な設計です。スクリプトの
挙動・組み込み関数・セーブ形式の権威ある定義はすべて本リポジトリの `core-vm` と
`specs/` にあり、シェルの変更がゲームの挙動を変えることも許されていません。そのため、
「挙動がおかしい」系の問題は、現象がスマホ上で起きたものでも、
[eratw-pocket](https://github.com/Poboking/eratw-pocket) ではなく本リポジトリへ
お願いします。

## アーキテクチャと設計判断

**挙動の差分検証 + ソースによる考古調査。** 差分検証とは、同じスクリプトや入力を
二つの実装で実行し、出力・変数・セーブ・エラー・イベント軌跡を比べることです。
「挙動が一致するか」に答え、Emuera のソース調査は「なぜ一致するか、境界条件が
どこから来たか」に答えるため、両者は矛盾しません。Kotera の一部のアルゴリズム、
データ構造、制御フローは行単位または文単位で翻訳・適応されており、出所はソース
コメントに記録しています。クリーンルーム方式を採用したとは主張しません。参照版は
1824+v24、EM+EE 系譜、4.981 Skia fork であり、すべてを「原版 1.824」と呼ぶことは
できません。

**仕様先行の差分検証開発。** `specs/` の仕様は実装に先立って執筆され、挙動の結論は
差分検証の根拠に基づき、可能な限り参照した Emuera の版と `ファイル:行番号` を添えます。
ドキュメントだけでは見落とす細部を差分検証で拾えます。たとえばデフォルトの RNG は
`System.Random` ではなく自前実装の SFMT19937（周期 2^19937−1）であり、これを
そのまま再現しています。プロジェクト内のルールは単純です：仕様と実装が食い違ったら
仕様を優先する。実装によって仕様の誤りが判明した場合は、先に仕様を直し、それから
実装を直す——この順序だけは変えません。

**AOT バイトコード + mmap ゼロ解析ロード。** ERB/ERH はビルド時に erbcc が
`.erbc` コンテナ（リトルエンディアン、i64 値、文字列インターニング、命令固定長
4×u32、セクションテーブル）へコンパイルし、実行時は mmap で直接参照して解析なしで
実行します。これは Android の冷起動時に解析作業を減らすための設計であり、実際の
効果は端末・ゲーム・フロントエンドに依存するため、対照測定で確かめる必要があります。
ロード層には `FlatPoolBudget` によるヒープ予算の事前検査、命令ページキャッシュ、ホットパスの
融合命令もあり、それぞれ `core-vm` 内に専用テストと反証があります。

**リンク可能なパッチと Mod。** パッチコンテナは完全で正当な `.erbc` を内包し、
ロード時に `ModuleLinker` / `CallRelocation` / `ExternalImportTable` がモジュール
リンク・呼び出し再配置・外部インポート解決を行います。`PatchCompiler` はパッチを
リンク可能モジュールへコンパイルします。形式の権威は
`specs/patch-format.yaml` です。

**コンパイルの決定性はテスト対象であり、前提ではない。** 産物はバイト単位で
再現可能（`ErbcDeterminismTest`）に加え、ロード順序の監査もあります。同名定義
ファイルの相対順序がファイルシステムの列挙順に左右される場合、監査カウンタが
非ゼロで失敗とします。マシンやプラットフォームをまたいで産物は一致します。

**純 JVM レイヤリング。** `core-vm` は Android API と `kotlin.random` を禁止し、
静的ミュータブルグローバルも持ちません（状態は store の aux スロットへ）。
そのためデスクトップと CI で全量テストができます。`render` も純 JVM の
ディスプレイリストモデルで、フロントエンド（Compose、デスクトッププレビュー）は
すべてその消費者にすぎません。

**バージョン突合はビルド時に。** erbcc のバージョンの単一ソースは `core-vm` の
`Version.kt` です。下流ビルドはバイトコード産物のコンパイラバージョンとソース
フィンガープリントを検証し、逸脱があればビルド失敗。古い産物と新しいエンジンの
取り違えは出荷されません。

**三段階の状態ハッシュ回帰。** テスト側の L3 ハーネスは `VariableStore` の公開
読み書き面だけを通じて VM 状態全体（変数・XML・スプライトプール・インターン
テーブル）を観察し、クラスタ単位でハッシュチェーンに要約して、PC 基準の全量実行と
バイト単位で比較します。エンジンコードには一切手を入れません：内部レイアウトの
リファクタは指標に影響せず、意味論の変化はチェーン値にそのまま反映されます。

## 互換性マトリクスとゲーム検証

2026-11-28 時点。これは能力の地図であり、互換率のパーセントではありません。
**実装済み**は本番経路と自己完結テストがあること、**部分**は中核能力はあるが明確な
不足またはフロントエンド依存があること、**未実装**は利用できる本番経路が見つからない
ことを意味します。実コーパス・実セーブ・実機実行は別の証拠層であり、単体テストでは
代替できません。

| Emuera の能力 | 状態 | 現在の範囲と既知の不足 | 根拠 |
|---|---|---|---|
| ERB/ERH のコンパイルとロード | 実装済み | AOT `.erbc`、通常読み込み、mmap の一致性テストがある。実コーパスは別途設定が必要 | [コンパイルテスト](core-vm/src/test/kotlin/com/eratw/port/core/compile/GoldenCompileTest.kt)、[mmap テスト](core-vm/src/test/kotlin/com/eratw/port/core/loader/MmapLoaderTest.kt) |
| 式・制御フロー・ユーザー関数・ISA | 実装済み（監査中） | 主経路は実装済み。意味論の差分監査は未完で、一部の fork 追加も未登録 | [ISA](specs/isa.yaml)、[組み込み関数](specs/builtin-functions.yaml)、[検証記録](specs/verify-notes.md) |
| 変数・キャラクター・CSV | 実装済み | 多次元変数、既定値、配列、キャラクターテンプレート、名前表に本番実装とフィクスチャがある | [変数テスト](core-vm/src/test/kotlin/com/eratw/port/core/vars/VariablePoolTest.kt)、[キャラクター CSV テスト](core-vm/src/test/kotlin/com/eratw/port/core/data/csv/CharaCsvLoaderTest.kt) |
| セーブ | 部分 | 形式、昇格、合成セーブの往復は実装済み。実 4.980 セーブのバイト単位往復記録もある。実 4.981 セーブの書き出しと PC 読み戻しは未検証 | [セーブ形式](specs/save-format.md)、[昇格テスト](core-vm/src/test/kotlin/com/eratw/port/core/save/SaveUpgradeSemanticsTest.kt)、[実セーブ探針](core-vm/src/test/kotlin/com/eratw/port/core/save/RealSaveSamplesProbeTest.kt) |
| 乱数 | 部分 | 既定の SFMT19937 は PC ゴールデンベクトルあり。`UseNewRandom=true` で使う .NET 8 の `xoshiro256**` 経路は未実装 | [RNG 仕様](specs/rng.md)、[SFMT テスト](core-vm/src/test/kotlin/com/eratw/port/core/rand/Sfmt19937Test.kt) |
| 画像とスプライト | 部分 | PNG デコードとグラフィックスモデルは実装済み。純 JVM WebP は無損コーパスの一部だけ、有損 WebP は非対応、JPEG/GIF 等はホスト側デコード | [PNG テスト](core-vm/src/test/kotlin/com/eratw/port/core/image/PngDecoderTest.kt)、[WebP の不足](core-vm/src/main/kotlin/com/eratw/port/core/image/WebpDecoder.kt) |
| 音声 | 部分 | チャンネル、ループ、音量、照会モデルのテストはある。既定は空実装で、実デコードと再生はホストが注入する必要がある | [音声アダプタ](core-vm/src/main/kotlin/com/eratw/port/core/audio/AudioAdapters.kt)、[音声テスト](core-vm/src/test/kotlin/com/eratw/port/core/audio/AudioAdaptersTest.kt) |
| 動画 | 未実装 | 仕様目録と本番コードの双方に動画再生の実装は見つからない | [ISA 目録](specs/isa.yaml)、[組み込み関数目録](specs/builtin-functions.yaml) |
| UI・入力・ネイティブホスト | 部分（フロントエンド担当） | テキストグリッド、ボタン当たり判定、WAIT/INPUT、入力キューは実装済み。Emuera の Windows/Skia ウィンドウ自体は複製せず、描画はフロントエンドに任せる | [入力セッションテスト](render/src/test/kotlin/com/eratw/port/render/input/InputSessionTest.kt)、[入力文テスト](core-vm/src/test/kotlin/com/eratw/port/core/sem/InputStatementsTest.kt) |
| SQL | 部分 | core ポート、fake テスト、実 SQLite の JVM テストがある。Android API 21 の実機実行は未証明 | [SQL メソッドテスト](core-vm/src/test/kotlin/com/eratw/port/core/sql/SqlMethodsTest.kt)、[実ドライバテスト](sqlite-adapter/src/test/kotlin/com/eratw/port/sqlite/AndroidXSqliteDriverTest.kt) |
| ネットワークと UPDATECHECK | 部分（ホスト担当） | URL の判定順、権限、結果コード、文言はテスト済み。実 HTTP 要求は app ホストが担う | [更新確認テスト](core-vm/src/test/kotlin/com/eratw/port/core/update/UpdateCheckStatementTest.kt)、[テレメトリ境界](core-vm/src/main/kotlin/com/eratw/port/core/update/UpdateTelemetry.kt) |
| リンク可能なパッチ | 実装済み | ディスク上のバイト読み込み、破損パッチの拒否、ロールバックに mini E2E テストがある | [パッチロードテスト](core-vm/src/test/kotlin/com/eratw/port/core/loader/ModPatchLoaderTest.kt)、[パッチ形式](specs/patch-format.yaml) |
| RT2 Mod ランタイム（Kotera 拡張、Emuera 基準外） | 部分 | コンテナ、API、ライフサイクル、失敗隔離はテスト済み。本番スケジューラと実機の全経路はホストとの接続が必要 | [Mod ホスト](core-vm/src/main/kotlin/com/eratw/port/core/mod/ModRuntimeHost.kt) |

### 実際に検証した era ゲーム

証拠の強さを分けて記載します。**実コーパスを実行して不具合を見つけたことと、
ゲーム全体の互換性を確認したことは同義ではありません。**

| ゲーム / 版 | ある証拠 | 現在の結論 |
|---|---|---|
| eraTW 4.980 | 実コーパス、L3 ゴールデンチェーン、実 4.980 セーブのバイト単位往復の記録あり。公開倉はこれらの外部入力を既定で同梱しない | 過去の回帰基準であり、全プレイ経路の網羅を意味しない |
| eraTW 4.981（現在の fork 目標、261111 コーパス） | 実コーパスと選抜した E2E/L3 ゴールデン記録。Android プロジェクトには実機実行記録もある | 主な開発対象。実 4.981 セーブの書き出しと PC 読み戻しは未解決 |
| eratohoReverse（版は未記録） | 実コンパイルで行頭事前識別の不具合を発見。実機では疎な ARG の範囲外と `COSPLAY` の呼び出しスタック超過も発見し、合成回帰あり | 実コーパス・実機の不具合検証は実施。**完全プレイの成功記録はない** |
| eratohoYMAEM（版は未記録） | 実コーパスの `#LOCALSIZE 3000000` がコンパイルを落とした事例があり、大サイズの合成回帰あり | 実コーパスの不具合検証は実施。**完全プレイの成功記録はない** |
| eraFL（版は未記録） | 実設定・コーパスで `#DIM` の次元と VARI/VARS の可視性の不具合を発見し、合成回帰あり | 実コーパスの不具合検証は実施。**完全プレイの成功記録はない** |

他のゲームの根拠は [行頭事前識別テスト](tools/erbcc/src/test/kotlin/com/eratw/port/erbcc/EngineProfileMiniCorpusTest.kt)、
[疎な ARG テスト](core-vm/src/test/kotlin/com/eratw/port/core/proc/SparseArgLengthTest.kt)、
[キーワード優先順テスト](core-vm/src/test/kotlin/com/eratw/port/core/expr/IndexKeywordBeatsBareFuncTest.kt)、
[LOCALSIZE テスト](tools/erbcc/src/test/kotlin/com/eratw/port/erbcc/compile/LocalSizeWideRangeTest.kt)、
[VARI/VARS テスト](tools/erbcc/src/test/kotlin/com/eratw/port/erbcc/compile/VariWholeFunctionTest.kt) を参照してください。
**本文の日付時点で、EraTW 以外に起動から主要経路の完走まで確認された era ゲームはありません。**

## コーパスと著作権

本リポジトリは era ゲームのデータ（スクリプト・画像・音声・セーブ・設定）を一切
配布しません。`fixtures/` の `GOLDEN_*.ERB`、リプレイ軌跡、Rust 対拍データは
本プロジェクトが書いたテストフィクスチャで、ゲームテキストは含みません。正規に
入手したゲームデータをご自身でご用意のうえ、環境変数で指定してください。

Emuera に関する帰属表示と改変の明示は [NOTICE.md](NOTICE.md) をご覧ください。

## 貢献について

開発は進行中で、Emuera との差分検証は未完了です。既知の問題は issue トラッカーに
あります。issue と PR はどちらも歓迎します。意味論に関する変更には一つだけ前提が
あります：先に `specs/` に根拠を書くことです。挙動の結論には再現可能な差分検証の
入力と結果を付け、ソースに基づく説明には参照した Emuera の版と
`ファイル:行番号` も添えてください。挙動の差分を疑っているだけで確信が持てないなら、
仕様を書く前に issue を立てて相談するだけで十分です。上流コードの翻訳・適応は、
ソースと [NOTICE.md](NOTICE.md) に出所を明記し、MIT で上流の条件を上書きしないでください。

## 謝辞

このプロジェクトは、era コミュニティが長年積み重ねてきた成果の上にあります。

- **MinorShift** と **妊）|дﾟ)の中の人**——Emuera の著作者。本エンジンの互換性に
  関する判断は、すべてその作品を基準にしています。
- **まだ名前は無い人**——eraThe World（eraTW）の `GameBase.csv` に署名されている
  改変・制作者。そして eraTW の口上・イベント・データ・ドキュメントを書かれた
  すべての方々。
- era コミュニティのスクリプト作者、コンテンツ作者、ツール保守者の皆さま。

上記の謝辞は、いかなる第三者材料の著作権・ライセンス条件も変更するものでは
ありません。使用・再配布の際は添付ファイルの記載に従ってください。

## ライセンス

Kotera の原创で、権利者がライセンス付与できる貢献は [MIT](LICENSE) で公開しています。
この許可は Emuera のソース、翻訳、抽出表、関連 fork の材料を MIT に再ライセンスする
ものではなく、上流の条件にも代わりません。参照方法、改変説明、Emuera の完全な
ライセンス原文は [NOTICE.md](NOTICE.md) を参照してください。同梱フォントにはそれぞれ
別のオープンソースライセンスがあります。
