<div align="center">

# Kotera

[简体中文](README.md) | **English** | [日本語](README_JA.md)

[![License: MIT](https://img.shields.io/badge/license-MIT-263146?style=flat-square)](LICENSE)
[![CI](https://github.com/Poboking/Kotera/actions/workflows/ci.yml/badge.svg)](https://github.com/Poboking/Kotera/actions/workflows/ci.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?style=flat-square)
![JDK](https://img.shields.io/badge/JDK-17-263146?style=flat-square)
![Platform](https://img.shields.io/badge/platform-JVM-6a737d?style=flat-square)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)

**An ERA game engine written in Kotlin for the Java Virtual Machine.**

Kotera runs ERB game scripts, manages characters and variables, handles saves, and
hands rendering to an Android or desktop frontend. Think of it as the game's
**rules layer**: it determines how scripts behave, not what the interface looks like.

To play EraTW on Android, use
[eratw-pocket](https://github.com/Poboking/eratw-pocket). To build or port an era
game and connect your own frontend, start with this repository. Kotera ships no
game data and builds and tests independently of Android.

</div>

---

## What it is

- **A semantics layer, not a frontend.** Kotera owns the game rules: ERB
  bytecode execution, the variable and character systems, saves, built-in
  functions. Rendering is emitted as a display list—a drawing plan for text,
  buttons, and images—for frontends to present.
- **Emuera is the behavioral baseline.** The engine compares actual behavior by
  running the same input through both implementations—often called a differential
  test. For edge cases the documentation does not explain, Emuera source is also
  consulted and cited by version and `file:line`. See [NOTICE.md](NOTICE.md) for
  the boundary between source reference and translated adaptation.
- **Modern ERA runtime capabilities.** Beyond the base VM, the engine includes
  linkable patches / Mods, entities and virtual characters, SQL sessions,
  signed update manifests, sidecar saves with crash recovery, and memory tiers.
- **No Android dependency.** It is plain JVM and is tested in headless
  environments — which is what makes continuous differential auditing on CI
  possible at all.
- **Self-sufficient.** Building, running tests, and reproducing issues require
  no game data whatsoever.

`specs/` is the semantic authority of this project, covering the ISA, built-ins,
variables, loading, save format, RNG, patch container, and error texts. See
[specs/README.md](specs/README.md) for how the files fit together.

## Minimal Quickstart

You only need JDK 17+—no Android SDK and no game data. Run these commands from the
repository root (on Windows, use `.\gradlew.bat` instead of `./gradlew`). If Gradle
cannot find Java, set `JAVA_HOME` to your JDK 17+ installation directory:

```bash
git clone https://github.com/Poboking/Kotera.git
cd Kotera

./gradlew :core-vm:test :render:test
./gradlew :tools:uipreview:test --tests '*CliSmokeTest'
```

The first command builds and runs the self-contained engine and renderer tests.
The second creates a temporary mini ERB script, compiles it, starts the desktop
preview service, simulates clicks and text input, and exits. It verifies the full
script → engine → preview path. Real-corpus cases only run when their environment
variables are set; see "Build & Test" below.

## Performance: Evidence and Gaps

Kotera precompiles bytecode and memory-maps it at load time to reduce startup parsing
work. That does not establish that it beats an emulator on every device. **There is
no public same-version, same-scenario comparison of Kotera / eratw-pocket against an
emulator, so this README no longer claims that it runs more smoothly on low-end
devices.**

The available device record is an Android AOT-profile experiment, not an emulator
comparison:

| Item | Experimental condition |
|---|---|
| Date / device | 2026-10-27; vivo V2199A; Android 14 / API 34 / arm64-v8a |
| Comparison | The same 13.1 MB release APK with only AOT state changed—not Emuera or an Android emulator |
| Scenario | `am start` → title page with clickable options; 3 runs |
| Result | No device profile: 15.98 s; with 882 device-collected profile entries: 12.74 s |
| Cold-start `TotalTime` | Median of 5 runs: 336 ms → 293 ms |
| Limits | Reused 749 MB of already-unpacked device data (`--skipAssets`); SoC, physical RAM, and APK version were not recorded; title-page path only |

See the original [eratw-android T2-63 record](https://github.com/Poboking/eratw-android-port/blob/main/docs/agent/archive-20261128/CONTRACT-T2.md#L520-L535).
It demonstrates a gain for that build on that device's title-page startup path. It
does **not** demonstrate a win over an emulator or establish that the device is
low-end. Future performance claims should publish the device/SoC/RAM, OS and both
versions, identical game data, cold/warm-start conditions, scenario, sample count,
and raw frame-time or load-time records.

## Repository Layout

| Path | Description |
|---|---|
| `core-vm` | ERA semantic VM: ERB bytecode execution, variables & characters, save I/O, built-ins, SFMT RNG, patch / Mod runtime |
| `render` | Pure-JVM rendering: glyph cache, text grid, button hit regions, display list, graphics layers |
| `sqlite-adapter` | AndroidX SQLite adapter for the `SqlDriver` port: seeds bundled databases into writable copies with atomic refresh (platform wiring of the real SQLite engine) |
| `mod-api` | RT2 Mod SDK API surface: entity / event / function / hook declarations and the host context |
| `mod-family` / `mod-memory` / `mod-livingworld` | Built-in official Mods: family lineage, memory, living world (source of truth for the bundled manifests) |
| `mod-registry` | Carrier registry: scans the `@ModCarrier`-annotated declaration objects across the `mod-*` modules at build time and generates the single `ModRegistry` (the host no longer names carrier modules one by one) |
| `mod-template` | Mod author toolchain: a minimal carrier template skeleton (for copying only — it stays out of the production `ModRegistry`) |
| `mod-testing` | Mod author toolchain: in-memory implementations of the `:mod-api` capability surface plus `runMod` assembly, so authors can exercise their Mod logic without booting the engine |
| `tools/erbcc` | ERB / ERH → `erbc.v1` bytecode container compiler; corpus compilation, symbol audit, Rust parity wiring |
| `tools/erbcc-rs` | Rust-side erbcc implementation (Cargo project) used for byte-for-byte parity against the Kotlin compiler |
| `tools/modgen` | RT2 module / manifest generator (build entry for the built-in and demo Mods) |
| `tools/modgen-accessors` | Codegen from modgen declarations to type-safe accessors |
| `tools/vmdbg` | Non-interactive, agent-oriented debug CLI: call / trace / where / var / disasm / sweep / ledger |
| `tools/uipreview` | Desktop render preview: drives the real core-vm + render, viewable in a browser |
| `tools/fontsubset` | Font subsetting scripts (Python) that maintain `render/src/main/resources/fonts` |
| `specs/` | Semantic specs and differential evidence (ISA, built-ins, variables, loading, saves, RNG, patches, error texts) |
| `fixtures/` | Self-contained test fixtures: golden ERB, replay traces, Rust parity data |
| `buildSrc/` | Build-time tasks and pure helpers (corpus patches, erbc freshness, asset staging) |
| `devtools/` | Corpus content patches and the erbcc-rs parity driver |
| `gradle/libs.versions.toml` | Single source for dependency coordinates and JVM / toolchain versions |

## Build & Test

Requires JDK 17+. Run all commands from the repository root.

```bash
./gradlew :core-vm:test             # per-module tests
./gradlew :render:test
./gradlew :tools:erbcc:installDist  # compiler CLI
./gradlew :tools:vmdbg:installDist  # debug CLI
```

The CI-equivalent command runs all fifteen modules:

```bash
./gradlew --no-daemon \
  :core-vm:test :render:test :sqlite-adapter:test \
  :mod-api:test :mod-family:test :mod-memory:test :mod-livingworld:test \
  :mod-registry:test :mod-testing:test :mod-template:test \
  :tools:erbcc:test :tools:modgen:test :tools:modgen-accessors:test \
  :tools:vmdbg:test :tools:uipreview:test
```

A few tests depend on a real corpus, real artifacts, real saves, or the Android
compile surface. Environment variables decide whether they run. When a variable is
unset, some cases are **skipped** via a JUnit assumption and some dynamic cases are
not generated at all; everything else stays self-contained. That is exactly the tier
public CI runs, so a green test suite does not mean every real-corpus path is covered.

CI also checks out the sibling repo
[eratw-pocket](https://github.com/Poboking/eratw-pocket) alongside this one (reading
its `main`): the packaging checks in `tools/modgen` read the shell's actual sources,
and the shell's packaged assets must match the engine's declarations byte for byte.
Both cross-repo checks run only as part of this integration check. The cross-repo
contract (which repo owns each source check, the merge order, what a frozen fixture
compares, and which corpus root is authoritative) lives in
[specs/cross-repo-contract.md](specs/cross-repo-contract.md).

| Environment variable / property | Purpose |
|---|---|
| `ERATW_CORPUS` | Game `ERB/` directory; real-corpus compilation and corpus-audit cases |
| `ERATW_GAMEDIR` | Game root (with `CSV/`, `resources/`, `emuera.config`); XML / config / image readout cases |
| `ERATW_ERBC_DIR` | Compiled `erbc.v1` artifact directory; loading / sidecar / mmap cases |
| `ERATW_SAVE_SAMPLES` / `-PsaveSamples` | Real `.sav` sample directory; save round-trip and mutated-write cases |
| `ERATW_ANDROID_JAR` | Android `android.jar`; when unset, `ANDROID_HOME` / `ANDROID_SDK_ROOT` are probed, and only then is `AndroidApiCompatTest` skipped |
| `ERATW_ROOT` | Optional shared checkout root used by a few internal replay cases; no public default |

When `ERATW_CORPUS` is unset, corpus-dependent tasks such as `compileCorpus`
fail with a clear message instead of silently producing an empty package.

**The authoritative corpus root is the Kotera root** (`eratw-画蛇添足版eratw4.980`,
NOW_VERSION 20260326). Every corpus-dependent frozen reading (the label counts in
`ErbccCorpusProfile`, the `.webp` inventory in `WebpDecoderTest`, the `.als` aliases in
`AliasLoaderTest`, the source anchors in `RandSfCorpusE2ETest`, …) is frozen against that
root, and both public repos share one baseline — running against a different root will
disagree with the frozen values, which is a **corpus version change**, not a regression.
`RealCorpus` also probes that root by default (`GAME_DIR_NAME`).

### erbcc-rs parity

The Rust side needs Cargo. Build it, then use the Gradle wiring:

```bash
cargo build --release --manifest-path tools/erbcc-rs/Cargo.toml
./gradlew :tools:erbcc:erbccClasspathFile
./gradlew :tools:erbcc:rsParity -Pcorpus=<ERB dir>
./gradlew :tools:erbcc:regenRsBaselines -Plane=all
```

See [tools/erbcc-rs/README.md](tools/erbcc-rs/README.md) for details.

## Repository Relationship

```
Kotera (this repo, MIT)   eratw-pocket (GPL-3.0)
├── core-vm   ←────────────    ├── render-canvas
├── render    ←────────────    ├── platform
├── sqlite-adapter / mod-*     └── app (Compose shell)
└── tools/erbcc
```

eratw-pocket knows nothing about game semantics — on purpose. The authoritative
definitions of script behavior, built-in functions, and save formats all live in
this repo's `core-vm` and `specs/`, and shell changes are not allowed to alter
game behavior. So if something "plays wrong", please file it here rather than on
[eratw-pocket](https://github.com/Poboking/eratw-pocket), even when it happens on
a phone.

## Architecture & Design Decisions

**Behavioral differential testing + source-assisted archaeology.** A differential
test runs the same script or input through both implementations and compares
output, variables, saves, errors, and event traces; it answers “does the behavior
match?” Consulting Emuera source answers “why does it match, and where did this
edge case come from?” The two are complementary. Some Kotera algorithms, data
structures, and control flows are line-by-line or sentence-by-sentence translations
and adaptations, with their provenance recorded in source comments. The project
does not claim clean-room isolation. References include 1824+v24, the EM+EE lineage,
and the 4.981 Skia fork; they are not all “stock 1.824.”

**Spec-first differential development.** Specs under `specs/` are written before
implementation. Behavioral conclusions are grounded in differential evidence and,
where applicable, cite the referenced Emuera version and `file:line`. Differential
testing catches details that documentation alone misses—for instance, the default
RNG is not `System.Random` but a hand-rolled SFMT19937 (period 2^19937−1), reproduced
as-is. One simple rule governs the project: when the specs and the implementation
disagree, the specs win; and if the implementation proves a spec wrong, fix the spec
first and the implementation second—never the other way around.

**AOT bytecode + mmap zero-parse loading.** ERB/ERH sources are compiled at
build time by erbcc into `.erbc` containers (little-endian, i64 values, string
interning, fixed 4×u32 instructions, section table). At runtime they are
memory-mapped and executed with no parsing at all. This is intended to reduce
cold-start parsing work on Android; actual gains depend on the device, game, and
frontend and should be established by controlled measurement. The loading layer
also has a `FlatPoolBudget` heap
preflight, an instruction-page cache, and fused hot-path instructions, each with
dedicated tests and negative controls in `core-vm`.

**Linkable patches and Mods.** A patch container embeds a complete, valid
`.erbc`. At load time `ModuleLinker` / `CallRelocation` / `ExternalImportTable`
perform module linking, call relocation, and external-import resolution, while
`PatchCompiler` compiles patches into linkable modules. The format authority is
`specs/patch-format.yaml`.

**Deterministic compilation is tested, not assumed.** Output is byte-for-byte
reproducible (`ErbcDeterminismTest`), backed by a load-order audit: if the
relative order of duplicate definitions ever follows filesystem enumeration
order, a non-zero audit counter fails the build. Results therefore match across
machines and platforms.

**Pure-JVM layering.** `core-vm` bans Android APIs and `kotlin.random` and has
no static mutable globals (state lives in the store's aux slots), so the engine
is fully testable on desktop and CI. `render` is likewise a pure-JVM
display-list model; any front end (Compose, desktop preview) is merely a
consumer.

**Version reconciliation at build time.** erbcc's version has a single source
of truth in `Version.kt` under `core-vm`. Downstream builds verify the bytecode
artifact's compiler version and source fingerprint, and any drift fails the
build — stale artifacts never ship alongside a newer engine.

**Three-tier state-hash regression.** The L3 harness observes the entire VM
state (variables, XML, sprite pools, intern tables) exclusively through
`VariableStore`'s public read/write surface, summarizes it cluster by cluster
into a hash chain, and compares it byte-for-byte against a full PC-baseline
run. It never touches a line of engine code: internal layout refactors leave
the metric untouched, while semantic changes land faithfully in the chain
values.

## Compatibility Matrix & Game Validation

As of 2026-11-28. This is a capability map, not a compatibility percentage:
**Implemented** means a production path and self-contained tests exist; **Partial**
means the core capability exists but has a known gap or depends on a front end;
**Unimplemented** means no usable production path was found. Real corpora, real saves,
and device runs are a separate layer of evidence that unit tests cannot replace.

| Emuera capability | Status | Current scope and known gaps | Evidence |
|---|---|---|---|
| ERB/ERH compilation and loading | Implemented | AOT `.erbc`, ordinary reads, and mmap have consistency tests; real corpora require separate setup | [Compile tests](core-vm/src/test/kotlin/com/eratw/port/core/compile/GoldenCompileTest.kt), [mmap tests](core-vm/src/test/kotlin/com/eratw/port/core/loader/MmapLoaderTest.kt) |
| Expressions, control flow, user functions, and ISA | Implemented (audit ongoing) | Main paths are in place; the semantic audit is unfinished and a few fork additions remain unregistered | [ISA](specs/isa.yaml), [Built-ins](specs/builtin-functions.yaml), [Verification notes](specs/verify-notes.md) |
| Variables, characters, and CSV | Implemented | Multidimensional variables, defaults, arrays, character templates, and name tables have production code and fixtures | [Variable tests](core-vm/src/test/kotlin/com/eratw/port/core/vars/VariablePoolTest.kt), [Character CSV tests](core-vm/src/test/kotlin/com/eratw/port/core/data/csv/CharaCsvLoaderTest.kt) |
| Saves | Partial | Format, upgrades, and synthetic-save round trips are implemented; byte-exact round trips of real 4.980 saves are recorded. Real 4.981 save writing and PC read-back remain unverified | [Save format](specs/save-format.md), [Upgrade tests](core-vm/src/test/kotlin/com/eratw/port/core/save/SaveUpgradeSemanticsTest.kt), [Real-save probe](core-vm/src/test/kotlin/com/eratw/port/core/save/RealSaveSamplesProbeTest.kt) |
| Random numbers | Partial | Default SFMT19937 has PC golden vectors; the .NET 8 `xoshiro256**` path used by `UseNewRandom=true` is not implemented | [RNG spec](specs/rng.md), [SFMT tests](core-vm/src/test/kotlin/com/eratw/port/core/rand/Sfmt19937Test.kt) |
| Images and sprites | Partial | PNG decoding and the graphics model are implemented; the pure-JVM WebP decoder handles only part of the lossless corpus, lossy WebP is explicitly unsupported, and JPEG/GIF decoding is left to hosts | [PNG tests](core-vm/src/test/kotlin/com/eratw/port/core/image/PngDecoderTest.kt), [WebP gaps](core-vm/src/main/kotlin/com/eratw/port/core/image/WebpDecoder.kt) |
| Audio | Partial | Channel, looping, volume, and query models are tested; the default is a no-op and real decoding/playback must be injected by the host | [Audio adapters](core-vm/src/main/kotlin/com/eratw/port/core/audio/AudioAdapters.kt), [Audio tests](core-vm/src/test/kotlin/com/eratw/port/core/audio/AudioAdaptersTest.kt) |
| Video | Unimplemented | No video-playback implementation was found in the spec catalog or production code | [ISA catalog](specs/isa.yaml), [Built-in catalog](specs/builtin-functions.yaml) |
| UI, input, and native hosts | Partial (front-end owned) | Text grids, button hit regions, WAIT/INPUT, and input queues are implemented; Kotera does not reproduce Emuera's Windows/Skia window itself and leaves presentation to front ends | [Input-session tests](render/src/test/kotlin/com/eratw/port/render/input/InputSessionTest.kt), [Input-statement tests](core-vm/src/test/kotlin/com/eratw/port/core/sem/InputStatementsTest.kt) |
| SQL | Partial | Core ports, fake-based tests, and real-SQLite JVM tests exist; Android API 21 device execution is not established | [SQL method tests](core-vm/src/test/kotlin/com/eratw/port/core/sql/SqlMethodsTest.kt), [Real-driver tests](sqlite-adapter/src/test/kotlin/com/eratw/port/sqlite/AndroidXSqliteDriverTest.kt) |
| Networking and UPDATECHECK | Partial (host owned) | URL ordering, permissions, result codes, and prompt text are tested; real HTTP requests belong to the app host | [Update-check tests](core-vm/src/test/kotlin/com/eratw/port/core/update/UpdateCheckStatementTest.kt), [Telemetry boundary](core-vm/src/main/kotlin/com/eratw/port/core/update/UpdateTelemetry.kt) |
| Linkable patches | Implemented | On-disk loading, rejection of corrupt patches, and rollback have mini end-to-end tests | [Patch-loader tests](core-vm/src/test/kotlin/com/eratw/port/core/loader/ModPatchLoaderTest.kt), [Patch format](specs/patch-format.yaml) |
| RT2 Mod runtime (Kotera extension, not part of the Emuera baseline) | Partial | Containers, API, lifecycle, and failure isolation are tested; the production scheduler and full device path still require host wiring | [Mod host](core-vm/src/main/kotlin/com/eratw/port/core/mod/ModRuntimeHost.kt) |

### Which era Games Have Actually Been Validated?

Evidence strength is stated explicitly below. **Finding a defect while running a real corpus
is not the same as declaring the game fully compatible.**

| Game / version | Existing evidence | Current conclusion |
|---|---|---|
| eraTW 4.980 | Real-corpus, L3 golden-chain, and byte-exact real 4.980 save round-trip records exist; the public repo does not carry those external inputs by default | Historical regression baseline, not coverage of every gameplay path |
| eraTW 4.981 (current fork target; 261111 corpus) | Real corpus and selected E2E/L3 golden records; the Android project also has device-run records | Primary development target; real 4.981 save writing and PC read-back remain open |
| eratohoReverse (version not recorded) | Real compilation exposed a line-head pre-recognition defect; device runs later exposed sparse ARG bounds and a `COSPLAY` call-stack overflow, all now covered by synthetic regressions | Real-corpus/device defect validation; **no documented complete playthrough** |
| eratohoYMAEM (version not recorded) | `#LOCALSIZE 3000000` in the real corpus once crashed compilation; a large-size synthetic regression now exists | Real-corpus defect validation; **no documented complete playthrough** |
| eraFL (version not recorded) | Real configuration/corpus exposed `#DIM` dimension and VARI/VARS visibility defects; synthetic regressions now exist | Real-corpus defect validation; **no documented complete playthrough** |

See the [line-head pre-recognition tests](tools/erbcc/src/test/kotlin/com/eratw/port/erbcc/EngineProfileMiniCorpusTest.kt),
[sparse ARG tests](core-vm/src/test/kotlin/com/eratw/port/core/proc/SparseArgLengthTest.kt),
[keyword-precedence tests](core-vm/src/test/kotlin/com/eratw/port/core/expr/IndexKeywordBeatsBareFuncTest.kt),
[LOCALSIZE tests](tools/erbcc/src/test/kotlin/com/eratw/port/erbcc/compile/LocalSizeWideRangeTest.kt), and
[VARI/VARS tests](tools/erbcc/src/test/kotlin/com/eratw/port/erbcc/compile/VariWholeFunctionTest.kt).
**As of this document's date, no era game other than EraTW has a confirmed record of
booting and completing its main flow.**

## Corpus & Copyright

This repository distributes **no** era game data (scripts, images, audio, saves,
configs). The `GOLDEN_*.ERB` files, replay traces, and Rust parity data under
`fixtures/` are fixtures written by this project and contain no game text. Bring
your own legally obtained game data and point the environment variables at it.

See [NOTICE.md](NOTICE.md) for attribution and modification statements regarding
Emuera.

## Contributing

Development is ongoing and the differential audit against Emuera is not yet
complete; known issues live in the issue tracker. Issues and PRs of all kinds are
welcome. Semantic changes carry one prerequisite: write down the evidence in
`specs/` first. Behavioral conclusions should include reproducible differential
inputs and results; source-based explanations should also cite the Emuera version
and `file:line`. If you merely suspect a behavior difference and are not sure, just
open an issue—no need to draft a spec first. Translations or adaptations of upstream
code must also identify their source in the code and [NOTICE.md](NOTICE.md); MIT does
not override upstream terms.

## Acknowledgments

This project stands on work the era community has accumulated over the years.

- **MinorShift** and **妊）|дﾟ)の中の人**, authors of Emuera — every compatibility
  judgment in this engine defers to their work.
- **まだ名前は無い人**, the modifier/creator credited in eraThe World (eraTW)'s
  `GameBase.csv`, along with everyone who has written eraTW dialogues, events,
  data, and documentation.
- The era community's script authors, content creators, and tool maintainers.

These acknowledgments do not alter the copyright or license terms of any
third-party material; when using or redistributing such material, defer to the
files that accompany it.

## License

Original Kotera contributions that their rightsholders are entitled to license are
released under [MIT](LICENSE). MIT does not relicense Emuera source, translations,
extracted tables, or related fork materials, and does not replace their upstream
terms. See [NOTICE.md](NOTICE.md) for reference methods, modification notes, and the
complete Emuera license text. Bundled fonts carry their own open-source licenses.
