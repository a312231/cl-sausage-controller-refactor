package cn.niuyannet.kaochang.android.services

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerProfileSwitchReadinessWiringSourceTest {

    @Test
    fun readiness_shouldReadEachSensitiveOwnerWithoutRecoveryOrMutation() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"
        )
        val body = extractBlock(source, "fun readSchedulerProfileSwitchReadiness()")

        assertEquals(1, occurrences(body, "actionGroupSessionStore.readEvidence()"))
        assertEquals(1, occurrences(body, "pausedSupplementSessionStore.readEvidence()"))
        assertEquals(1, occurrences(body, "supplementPrefetchSessionStore.read()"))
        assertEquals(1, occurrences(body, "orderDeliveryClaimStore.load()"))
        assertEquals(1, occurrences(body, "supplementBatchIntentStore.hasRawEvidence()"))
        assertTrue(body.contains("KaoPanHelper.peekSchedulerSwitchPanActivitySnapshot"))
        assertTrue(body.contains("OrderStateManager.hasProcessingOrderSnapshot()"))
        assertTrue(body.contains("AppConfig.peekInitializedAppConfig()"))

        listOf(
            "isActionGroupIdleForMoveStatusRecovery(",
            "restoreClaimedOrderMoveStatusIfNeeded(",
            "supplementBatchIntentRepository.loadActive(",
            "KaoPanHelper.getKaoPanList(",
            "AppConfig.getAppConfig(",
            "actionGroupSessionStore.load(",
            "hasGuardedSupplementCloseoutResidue(config)",
            ".save(",
            ".clear(",
            ".commit(",
            ".apply(",
            "SendServerHelper",
            "NetApi",
            "VMModbusHelper",
            "publishRuntimeState"
        ).forEach { forbidden ->
            assertFalse("readiness 入口含禁用调用：$forbidden", body.contains(forbidden))
        }
    }

    @Test
    fun readiness_shouldRemainDiagnosticOnlyAndNotExposeProfileActivation() {
        val runtime = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/scheduler/SchedulerProfileRuntime.kt"
        )
        val readiness = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/scheduler/SchedulerProfileSwitchReadiness.kt"
        )
        val appMain = sourceTree("app/src/main/java")

        assertTrue(runtime.contains("legacy") || runtime.contains("LegacySchedulerProfileA"))
        listOf("fun switch", "fun activate", "fun set", "var activeProfile", "val catalog:").forEach {
            assertFalse(runtime.contains(it))
        }
        assertFalse(readiness.contains("SchedulerProfileCatalog"))
        assertFalse(readiness.contains("PinnedSchedulerProfile"))
        assertFalse(readiness.contains("requiresPersistence"))
        assertEquals(0, occurrences(appMain, "scheduler-profile-b"))
        assertEquals(0, occurrences(appMain, "SchedulerProfileB"))
    }

    @Test
    fun readinessResult_shouldNotHavePublicNonSyntheticConstructorsOrCopy() {
        val type = cn.niuyannet.kaochang.android.services.scheduler.SchedulerProfileSwitchReadinessResult::class.java
        assertTrue(type.declaredConstructors.filterNot { it.isSynthetic }.all { Modifier.isPrivate(it.modifiers) })
        assertTrue(type.methods.none { it.name == "copy" })
    }

    @Test
    fun readinessSigningApi_shouldOnlyBeReferencedByItsDefinitionAndAuthoritativeOwner() {
        val root = sourceFilePath("app/src/main/java")
        val allowed = setOf(
            "SchedulerProfileSwitchReadiness.kt",
            "KaoChangOperate.kt"
        )
        val forbiddenReferences = root.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") && it.name !in allowed }
            .filter { file ->
                val source = file.readText()
                source.contains("SchedulerProfileSwitchReadinessMapper") ||
                    source.contains("SchedulerProfileSwitchReadinessResult.available(") ||
                    source.contains("SchedulerProfileSwitchReadinessResult.blocked(") ||
                    source.contains("SchedulerProfileSwitchReadinessResult.unavailable(")
            }
            .toList()

        assertTrue("非权威生产代码引用了 readiness 签发 API：$forbiddenReferences", forbiddenReferences.isEmpty())
    }

    private fun sourceTree(path: String): String = sourceFilePath(path)
        .walkTopDown()
        .filter { it.isFile && it.extension in setOf("kt", "java") }
        .joinToString("\n") { it.readText() }

    private fun sourceFile(path: String): String = sourceFilePath(path).readText().replace("\r\n", "\n")

    private fun occurrences(source: String, token: String): Int =
        Regex(Regex.escape(token), RegexOption.IGNORE_CASE).findAll(source).count()

    private fun sourceFilePath(path: String): File {
        val direct = File(path)
        if (direct.exists()) return direct
        val fromParent = File("..", path)
        if (fromParent.exists()) return fromParent
        throw AssertionError("找不到源码路径：$path")
    }

    private fun extractBlock(source: String, marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("找不到代码块：$marker", start >= 0)
        val bodyStart = source.indexOf('{', start)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(bodyStart + 1, index)
            }
        }
        throw AssertionError("代码块未闭合：$marker")
    }
}
