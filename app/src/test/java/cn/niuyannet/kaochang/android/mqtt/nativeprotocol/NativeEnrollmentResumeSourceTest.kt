package cn.niuyannet.kaochang.android.mqtt.nativeprotocol

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 保证 native enrollment 重启后只恢复 confirm，不能因网络失败重放一次性 complete。 */
class NativeEnrollmentResumeSourceTest {

    @Test
    fun startupRecovery_shouldRetryOnlyPersistedPendingConfirm() {
        val client = source("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/nativeprotocol/NativeEnrollmentHttpClient.kt")
        val activity = source("app/src/main/java/cn/niuyannet/kaochang/android/ui/MainActivity.kt")
        val resume = body(client, "fun resumePendingConfirmation")

        assertTrue("恢复入口必须读取持久化 pending 元数据", resume.contains("NativeIdentityStore.pendingConfirmation()"))
        assertTrue("恢复入口只能调用 confirm", resume.contains("confirm(endpoint, pending.first, pending.second, pending.third, callback)"))
        assertFalse("恢复入口绝不能重放 complete", resume.contains("complete("))
        assertTrue("启动配置就绪后必须调用恢复入口", activity.contains("NativeIdentityStore.hasPendingConfirmation()") && activity.contains("NativeEnrollmentHttpClient.resumePendingConfirmation"))
    }

    @Test
    fun confirmFailure_shouldPreservePendingAndSuccessShouldClearIt() {
        val client = source("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/nativeprotocol/NativeEnrollmentHttpClient.kt")
        val confirm = body(client, "private fun confirm")

        assertTrue("确认成功才允许清除 pending", confirm.contains("NativeIdentityStore.markConfirmed()"))
        assertTrue("网络失败必须保留为可重试 confirm", confirm.contains("仅可重试 confirm"))
        assertTrue("服务端拒绝必须说明 pending 保留", confirm.contains("保留 pending"))
    }

    private fun source(path: String): String {
        val file = listOf(File(path), File("..", path)).firstOrNull { it.exists() }
            ?: throw AssertionError("找不到源码文件：$path")
        return file.readText().replace("\r\n", "\n")
    }

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("找不到函数：$signature", start >= 0)
        val bodyStart = source.indexOf('{', start)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart + 1, index)
                }
            }
        }
        throw AssertionError("函数体未闭合：$signature")
    }
}
