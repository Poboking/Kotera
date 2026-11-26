// Kotera 根工程 —— Kotlin 重写的 ERA 运行时（纯 JVM，零 Android 依赖）
//
// 模块：
//   :core-vm        ERA 语义虚拟机（ERB 字节码执行、变量/存档/内建函数）
//   :render         纯 JVM 渲染层（字形缓存 / 网格 / 命中区 / 显示列表）
//   :mod-*          RT2 Kotlin Mod SDK（契约层 / 参考实现 / 载体注册表 / 作者工具链）
//   :tools:erbcc    ERB → erbc 字节码容器编译器
//   :tools:vmdbg    面向 agent 的无交互 VM 调试 CLI
//   :tools:uipreview 桌面渲染预览（浏览器贴帧）
//   :tools:fontsubset 字体内子集生成（维护 resources/fonts 的再生成工具）
//   :tools:erbcc-rs erbcc 的 Rust 侧（Cargo 工程，由 :tools:erbcc 的 Gradle 对拍脚本接线）

pluginManagement {
    repositories {
        // 阿里云 gradle-plugin 镜像作兜底/加速（国内网络对 plugins.gradle.org 不稳）。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // androidx.sqlite（:sqlite-adapter）在 Google Maven；阿里云 google 镜像作兜底/加速。
        maven("https://maven.aliyun.com/repository/google")
        google()
        // 阿里云 public 镜像（聚合 central+jcenter），主源不可达时兜底。
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
}

rootProject.name = "Kotera"

include(":core-vm")
// SQLite 迁移：纯 JVM 适配器（core 端口 ↔ AndroidX SQLite 通用 API），桌面走 bundled-jvm 驱动。
include(":sqlite-adapter")
// RT2 Kotlin Mod SDK：契约层 + 三个参考实现（mod 作者只依赖 :mod-api）。
include(":mod-api")
include(":mod-family")
include(":mod-memory")
include(":mod-livingworld")
// 内置载体注册表：扫各 mod-* 模块上打了 @ModCarrier 的声明 object，生成唯一 ModRegistry。
// 取代原来「构建期 assets 生产者 + 运行时装配各抄一行」的双登记（漏一处即静默不生效）。
include(":mod-registry")
// mod 作者工具链：最小载体模板。**不进生产 ModRegistry** —— 它是给人抄的
// 骨架，不是要装进产物的 mod；`:mod-registry` 的载体目录扫描显式排除本模块。
include(":mod-template")
// mod 作者工具链：`:mod-api` 能力面的内存实现 + `runMod` 装配，
// 让作者不开引擎就能验 mod 业务逻辑。main 只依赖 :mod-api（与业务 mod 同一条依赖方向铁律）。
include(":mod-testing")
include(":render")
include(":tools:erbcc")
include(":tools:modgen")
include(":tools:modgen-accessors")
include(":tools:vmdbg")
include(":tools:uipreview")
// :tools:fontsubset 是纯 Python 工具目录（字体内子集再生成），不是 Gradle 模块。
