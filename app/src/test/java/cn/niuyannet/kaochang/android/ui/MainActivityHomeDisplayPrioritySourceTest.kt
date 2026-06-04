package cn.niuyannet.kaochang.android.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityHomeDisplayPrioritySourceTest {

    @Test
    fun maintenanceStatus_shouldTakePriorityOverHomeQrHiddenText() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/ui/MainActivity.kt")
        val body = extractFunctionBody(source, "override fun setDataToView()")

        val statusMaintenanceIndex = body.indexOf("binding.deviceStatusText.text = \"维护中\"")
        val statusHomeQrHiddenIndex = body.indexOf("binding.deviceStatusText.text = \"首页码隐藏\"")
        assertTrue("首页设备状态文案必须处理维护中", statusMaintenanceIndex >= 0)
        assertTrue("首页设备状态文案必须处理首页码隐藏", statusHomeQrHiddenIndex >= 0)
        assertTrue(
            "维护态必须优先于首页码隐藏展示，否则设备已维护时首页仍可能显示“首页码隐藏”",
            statusMaintenanceIndex < statusHomeQrHiddenIndex
        )

        val tipMaintenanceIndex = body.indexOf("binding.tvScanTip.text = \"设备维护中\"")
        val tipHomeQrHiddenIndex = body.indexOf("binding.tvScanTip.text = \"首页二维码已隐藏\"")
        assertTrue("首页扫码提示必须处理维护中", tipMaintenanceIndex >= 0)
        assertTrue("首页扫码提示必须处理首页二维码隐藏", tipHomeQrHiddenIndex >= 0)
        assertTrue(
            "扫码提示必须优先显示设备维护中，不能被首页二维码隐藏文案覆盖",
            tipMaintenanceIndex < tipHomeQrHiddenIndex
        )
    }

    private fun sourceFile(path: String): String {
        val direct = File(path)
        if (direct.exists()) {
            return direct.readText().replace("\r\n", "\n")
        }
        val fromParent = File("..", path)
        if (fromParent.exists()) {
            return fromParent.readText().replace("\r\n", "\n")
        }
        throw AssertionError("找不到源码文件：$path，cwd=${File(".").absolutePath}")
    }

    private fun extractFunctionBody(source: String, functionSignature: String): String {
        val start = source.indexOf(functionSignature)
        assertTrue("找不到函数：$functionSignature", start >= 0)
        val bodyStart = source.indexOf('{', start)
        assertTrue("找不到函数体：$functionSignature", bodyStart >= 0)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(bodyStart + 1, index)
                    }
                }
            }
        }
        throw AssertionError("函数体未闭合：$functionSignature")
    }
}
