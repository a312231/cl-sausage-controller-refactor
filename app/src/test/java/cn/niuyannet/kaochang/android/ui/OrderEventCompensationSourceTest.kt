package cn.niuyannet.kaochang.android.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OrderEventCompensationSourceTest {

    @Test
    fun reportOrderEvent_shouldPersistOnlyStatusFiveMqttFailureThroughKaoChangOperate() {
        val source = sourceText()

        assertTrue(
            "reportOrderEvent 必须只在策略判定需要补偿时调用 KaoChangOperate 受控入口",
            source.contains("ActionGroupOrderEventCompensationPolicy.shouldPersistPendingEvent(status, ok)") &&
                source.contains("KaoChangOperate.savePendingOrderErrorEventAndTryFlush(") &&
                source.contains("panNo = 0")
        )
    }

    private fun sourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }
}
