package cn.niuyannet.kaochang.android.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMaintenanceRefreshCoverageSourceTest {

    @Test
    fun literalMaintenanceAssignmentsInMainSource_shouldHaveHomeRefreshCoverage() {
        val sources = loadMainSources()
        val assignments = sources.flatMap { (sourceName, source) ->
            findLiteralMaintenanceAssignments(sourceName, source)
        }
        val violations = sources.flatMap { (sourceName, source) -> analyze(sourceName, source) }

        assertEquals(
            "当前主源码中锚定正则 `.onlineStatus = 3` 应只命中 9 处真实维护态赋值；" +
                "新增命中点必须补首页刷新或显式白名单",
                9,
            assignments.size
        )
        assertTrue(
            "字面 onlineStatus=3 维护态写入缺少首页刷新覆盖：\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun analyzer_shouldReportLiteralMaintenanceAssignmentWithoutRefresh() {
        val source = """
            object BadMaintenanceState {
                fun enterMaintenance(config: AppConfigBean) {
                    config.onlineStatus = 3
                    AppConfig.saveAppConfig(config)
                }
            }
        """.trimIndent()

        val violations = analyze("BadMaintenanceState.kt", source)

        assertEquals("缺少刷新或白名单的字面维护态赋值必须被拦截", 1, violations.size)
    }

    @Test
    fun analyzer_shouldAllowRefreshHelperBeforeLiteralMaintenanceAssignment() {
        val source = """
            object CoveredMaintenanceState {
                private fun enterActionGroupMaintenance(config: AppConfigBean) {
                    markDeviceErrorAndUpload(FaultCode.ACTION_GROUP_FAILED, "动作组进入维护")
                    config.onlineStatus = 3
                    AppConfig.saveAppConfig(config)
                }
            }
        """.trimIndent()

        val violations = analyze("CoveredMaintenanceState.kt", source)

        assertTrue("赋值前已经调用已知刷新收口函数时不应误报", violations.isEmpty())
    }

    @Test
    fun analyzer_shouldIgnoreMaintenanceTextInLogs() {
        val source = """
            object MaintenanceLogOnly {
                fun logOnly() {
                    LogUtils.w("useActionGroupV2=0，onlineStatus=3（维护中）")
                }
            }
        """.trimIndent()

        val assignments = findLiteralMaintenanceAssignments("MaintenanceLogOnly.kt", source)

        assertTrue("锚定正则不能命中日志字符串中的 onlineStatus=3", assignments.isEmpty())
    }

    @Test
    fun whitelistEntries_shouldBeSpecificReasonedAndResolvable() {
        val sources = loadMainSources()
        val assignments = sources.flatMap { (sourceName, source) ->
            findLiteralMaintenanceAssignments(sourceName, source)
        }

        whitelist.forEach { entry ->
            assertTrue("白名单必须限定到具体 Kotlin/Java 文件：$entry", entry.sourceSuffix.endsWith(".kt") || entry.sourceSuffix.endsWith(".java"))
            assertTrue("白名单禁止整文件跳过，必须指定函数名：$entry", entry.functionName.isNotBlank())
            assertTrue("白名单必须写明原因：$entry", entry.reason.isNotBlank())
            assertTrue(
                "白名单项必须匹配当前真实维护态赋值，防止豁免腐烂：$entry",
                assignments.any { assignment -> entry.matches(assignment) }
            )
        }
    }

    private fun loadMainSources(): List<Pair<String, String>> {
        val (root, sourcePrefix) = listOf(
            File("app/src/main/java") to "app/src/main/java",
            File("src/main/java") to "app/src/main/java"
        ).firstOrNull { (sourceRoot, _) -> sourceRoot.exists() }
            ?: throw AssertionError("找不到主源码目录，cwd=${File(".").absolutePath}")
        return root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .map { file ->
                val sourceName = "$sourcePrefix/${file.relativeTo(root).invariantSeparatorsPath}"
                sourceName to file.readText().replace("\r\n", "\n")
            }
            .toList()
    }

    private fun analyze(sourceName: String, source: String): List<Violation> {
        return findLiteralMaintenanceAssignments(sourceName, source).mapNotNull { assignment ->
            val functionBody = extractEnclosingFunctionBody(source, assignment.offset)
            val coveredByRefresh = knownRefreshMarkers.any { marker -> functionBody.contains(marker) }
            val coveredByWhitelist = whitelist.any { it.matches(assignment) }
            if (coveredByRefresh || coveredByWhitelist) {
                null
            } else {
                Violation(
                    sourceName = sourceName,
                    lineNumber = assignment.lineNumber,
                    functionName = assignment.functionName,
                    message = "字面写入 onlineStatus=3 后未发现首页刷新或已知收口函数"
                )
            }
        }
    }

    private fun findLiteralMaintenanceAssignments(sourceName: String, source: String): List<MaintenanceAssignment> {
        return literalMaintenanceAssignmentRegex.findAll(source).map { match ->
            MaintenanceAssignment(
                sourceName = sourceName,
                lineNumber = source.lineNumberAt(match.range.first),
                offset = match.range.first,
                functionName = extractEnclosingFunctionName(source, match.range.first),
                text = match.value
            )
        }.toList()
    }

    private fun extractEnclosingFunctionName(source: String, offset: Int): String {
        val functionStart = findEnclosingFunctionStart(source, offset)
        if (functionStart < 0) {
            return "<unknown>"
        }
        val header = source.substring(functionStart, source.indexOf('{', functionStart).takeIf { it >= 0 } ?: source.length)
        return functionNameRegex.find(header)?.groupValues?.get(1) ?: "<unknown>"
    }

    private fun extractEnclosingFunctionBody(source: String, offset: Int): String {
        val functionStart = findEnclosingFunctionStart(source, offset)
        if (functionStart < 0) {
            return source
        }
        val bodyStart = source.indexOf('{', functionStart)
        if (bodyStart < 0) {
            return source.substring(functionStart)
        }
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(functionStart, index + 1)
                    }
                }
            }
        }
        return source.substring(functionStart)
    }

    private fun findEnclosingFunctionStart(source: String, offset: Int): Int {
        val beforeAssignment = source.substring(0, offset)
        return functionDeclarationRegex.findAll(beforeAssignment).lastOrNull()?.range?.first ?: -1
    }

    private fun String.lineNumberAt(offset: Int): Int =
        substring(0, offset.coerceIn(0, length)).count { it == '\n' } + 1

    private data class MaintenanceAssignment(
        val sourceName: String,
        val lineNumber: Int,
        val offset: Int,
        val functionName: String,
        val text: String
    )

    private data class Violation(
        val sourceName: String,
        val lineNumber: Int,
        val functionName: String,
        val message: String
    ) {
        override fun toString(): String =
            "$sourceName:$lineNumber $functionName: $message"
    }

    private data class WhitelistEntry(
        val sourceSuffix: String,
        val functionName: String,
        val reason: String
    ) {
        fun matches(assignment: MaintenanceAssignment): Boolean =
            assignment.sourceName.endsWith(sourceSuffix) &&
                assignment.functionName == functionName
    }

    companion object {
        private val literalMaintenanceAssignmentRegex = Regex("""\.onlineStatus\s*=\s*3\b""")
        private val functionDeclarationRegex = Regex(
            pattern = """(?m)^\s*(?:private\s+|internal\s+|public\s+|protected\s+|override\s+|suspend\s+)*fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\("""
        )
        private val functionNameRegex = Regex("""\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        private val knownRefreshMarkers = listOf(
            "HomeUiRefreshBridge.requestRefresh(",
            "markDeviceErrorAndUpload(",
            "stopHeatingAndDispatchForFault(",
            "markSelfCleanFailure("
        )
        private val whitelist = listOf(
            WhitelistEntry(
                sourceSuffix = "app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderDeliveryFaultPolicy.kt",
                functionName = "applySingleDeliveryExceptionFault",
                reason = "单根出餐异常由 OrderActivity 订单收尾 setDataToView() 刷新首页"
            )
        )
    }
}
