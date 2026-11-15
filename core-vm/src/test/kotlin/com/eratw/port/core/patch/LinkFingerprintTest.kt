package com.eratw.port.core.patch

import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [LinkFingerprint] 十因子判据（ABI-8 候选）。
 *
 * 断言面：同输入同指纹；**任一因子变化 ⇒ 指纹变**（逐因子）；归一判定——
 * 顺序无语义的集合（overrideFunctions/requires/schema 名单）顺序漂移不影响指纹，
 * 列表序有语义的（externalCalls 哨兵槽 / 模块哈希链接序）顺序变化改变指纹。
 */
class LinkFingerprintTest {

    private fun manifest(
        pkg: String = "mod.a",
        overrides: Set<String> = emptySet(),
        external: List<String> = emptyList(),
        requires: List<String> = emptyList(),
        schemas: List<ComponentSchema> = emptyList(),
        archetypes: List<EntityArchetype> = emptyList(),
    ): ModuleDescriptor = ModuleDescriptor(
        packageId = pkg,
        requires = requires,
        externalCalls = external,
        overrideFunctions = overrides,
        componentSchemas = schemas,
        entityArchetypes = archetypes,
        artifactSha256 = "sha-$pkg",
    )

    /** 既有六参助手零改签（LongParameterList 阈值口径）：路由行经 data class copy 注入。 */
    private fun manifestWithRoutes(routes: List<CharaRouteDeclaration>): ModuleDescriptor =
        manifest().copy(charaRoutes = routes)

    private fun inputs(
        base: String = "base-sha",
        modules: List<String> = listOf("m1", "m2"),
        policy: ResolverPolicy = ResolverPolicy.MODERN,
        manifests: List<ModuleDescriptor> = listOf(manifest()),
        isa: Int = 2,
    ): LinkFingerprint.Inputs = LinkFingerprint.Inputs(
        baseProgramSha256 = base,
        moduleArtifactSha256s = modules,
        compilerAbi = "erbcc-1",
        linkerVersion = "link-1",
        bytecodeIsaVersion = isa,
        builtinRegistryFingerprint = "builtin-1",
        runtimeProfileFingerprint = "profile-1",
        resolutionPolicy = policy,
        variableSchemaFingerprint = "var-1",
        entitySchemaFingerprint = "ent-1",
        manifests = manifests,
    )

    @Test
    fun `同输入同指纹且形如sha256`() {
        val a = LinkFingerprint.compute(inputs())
        val b = LinkFingerprint.compute(inputs())
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
    }

    @Test
    fun `任一因子变化指纹变`() {
        val base = LinkFingerprint.compute(inputs())
        assertNotEquals(base, LinkFingerprint.compute(inputs(base = "base-sha2")))
        assertNotEquals(base, LinkFingerprint.compute(inputs(modules = listOf("m2", "m1")))) // 链接序进指纹
        assertNotEquals(base, LinkFingerprint.compute(inputs(modules = listOf("m1"))))       // 组合成员变化
        assertNotEquals(base, LinkFingerprint.compute(inputs(policy = ResolverPolicy.LEGACY_LAST_WINS)))
        assertNotEquals(base, LinkFingerprint.compute(inputs(isa = 3)))
        // Manifest 内容变化。
        assertNotEquals(base, LinkFingerprint.compute(inputs(manifests = listOf(manifest(overrides = setOf("F1"))))))
        assertNotEquals(base, LinkFingerprint.compute(inputs(manifests = listOf(manifest(external = listOf("F1"))))))
        assertNotEquals(base, LinkFingerprint.compute(inputs(manifests = listOf(manifest(pkg = "mod.b")))))
    }

    @Test
    fun `顺序无语义的集合排序归一`() {
        val f1 = LinkFingerprint.compute(
            inputs(manifests = listOf(manifest(overrides = setOf("F1", "F2"), requires = listOf("x", "y")))),
        )
        // 顺序漂移（含大小写漂移）同指纹：声明顺序、键归一后再哈希（v6.3-c P1-5）。
        val f2 = LinkFingerprint.compute(
            inputs(manifests = listOf(manifest(overrides = setOf("f2", "f1"), requires = listOf("Y", "X")))),
        )
        assertEquals(f1, f2)
    }

    @Test
    fun `哨兵槽与列表序保持`() {
        // externalCalls 列表序 = 哨兵槽位（有物理语义），顺序变化 ⇒ 指纹变。
        val f1 = LinkFingerprint.compute(inputs(manifests = listOf(manifest(external = listOf("A", "B")))))
        val f2 = LinkFingerprint.compute(inputs(manifests = listOf(manifest(external = listOf("B", "A")))))
        assertNotEquals(f1, f2)
    }

    // ------------------------------------------------------------------
    // P1-3：requires 版本约束自动参与指纹（LinkFingerprint 零代码改动的证明）
    // ------------------------------------------------------------------

    @Test
    fun `requires 加版本约束则指纹变`() {
        // 约束内嵌字符串经既有「大写 + 排序」通道自动进指纹：加约束 ⇒ 指纹变（期望行为）。
        val bare = LinkFingerprint.compute(inputs(manifests = listOf(manifest(requires = listOf("mod.core")))))
        val constrained = LinkFingerprint.compute(
            inputs(manifests = listOf(manifest(requires = listOf("mod.core@[1.2.0,2.0.0]")))),
        )
        assertNotEquals(bare, constrained, "版本约束改变链接语义，指纹必须改变")
    }

    @Test
    fun `无约束 requires 指纹与今日逐字节零漂移`() {
        // 逐字节漂移锁：本输入的全部十因子在此用例内固定；B 案对 LinkFingerprint
        // 零代码改动 ⇒ 哈希必须与改造前逐字节一致。若未来指纹算法显式换版，
        // 应由独立波次更新本值，而不是悄悄漂移。
        assertEquals(
            "a76133beb75fce4fb490824c74ba4d904dbd9107696f68d0bf0d92dbf9e88ce1",
            LinkFingerprint.compute(inputs(manifests = listOf(manifest(requires = listOf("mod.core"))))),
        )
    }

    // ------------------------------------------------------------------
    // M5-4 刀 2：charaRoutes 渲染行（CONTRACT-RT2-M54IMPL2 判据 4：路由参与指纹；
    // 无路由 = 不落渲染行，既有无路由 manifest 指纹逐字节不变——上一条字节锁即其证）
    // ------------------------------------------------------------------

    private fun routedManifest(routes: List<CharaRouteDeclaration>): ModuleDescriptor =
        manifestWithRoutes(routes)

    @Test
    fun `charaRoutes 参与指纹 路由变则指纹变`() {
        val bare = LinkFingerprint.compute(inputs())
        val withRoutes = LinkFingerprint.compute(
            inputs(manifests = listOf(routedManifest(listOf(CharaRouteDeclaration("CFLAG", listOf(0, 1)))))),
        )
        assertNotEquals(bare, withRoutes, "路由改变脱水面，指纹必须参与")
        assertNotEquals(
            withRoutes,
            LinkFingerprint.compute(
                inputs(manifests = listOf(routedManifest(listOf(CharaRouteDeclaration("CFLAG", listOf(0, 2)))))),
            ),
            "路由内容变化必须改变指纹",
        )
        assertNotEquals(
            withRoutes,
            LinkFingerprint.compute(
                inputs(manifests = listOf(routedManifest(listOf(CharaRouteDeclaration("TALENT", listOf(0, 1)))))),
            ),
            "路由族名变化必须改变指纹",
        )
    }

    @Test
    fun `charaRoutes 声明序与格序归一`() {
        // 行序 + 行内格号集合均无语义（freeze 派生对显式格同序排序）：声明序/格序漂移同指纹。
        val a = LinkFingerprint.compute(
            inputs(
                manifests = listOf(
                    routedManifest(
                        listOf(
                            CharaRouteDeclaration("CFLAG", listOf(2, 0, 1)),
                            CharaRouteDeclaration("TALENT", listOf(9)),
                        ),
                    ),
                ),
            ),
        )
        val b = LinkFingerprint.compute(
            inputs(
                manifests = listOf(
                    routedManifest(
                        listOf(
                            CharaRouteDeclaration("talent", listOf(9)),
                            CharaRouteDeclaration("cflag", listOf(1, 2, 0)),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(a, b)
    }
}
