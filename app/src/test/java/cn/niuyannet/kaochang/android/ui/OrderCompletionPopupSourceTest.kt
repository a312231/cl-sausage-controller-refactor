package cn.niuyannet.kaochang.android.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class OrderCompletionPopupSourceTest {

    @Test
    fun completedOrder_shouldNotShowFiveSecondCompletionPopup() {
        val source = sourceText()

        assertFalse("整单完成后不应再显示完成弹窗", source.contains("showCompletedDialog"))
        assertFalse("订单页不应再创建完成弹窗", source.contains("dialog_completed"))
        assertFalse("订单页不应再保留完成倒计时", source.contains("CountDownTimer"))
    }

    private fun sourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }
}
