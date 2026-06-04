package cn.niuyannet.kaochang.android.net

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DataManagementAPIHomeRefreshSourceTest {

    @Test
    fun getDeviceInfo_shouldRequestHomeRefreshWhenHomeDisplayFieldsChange() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/net/DataManagementAPI.kt")
        val body = extractFunctionBody(source, "fun getDeviceInfo()")

        assertTrue(
            "getDeviceInfo 合并远端配置后如果首页展示字段变化，必须通知首页刷新",
            body.contains("homeDisplayChanged") &&
                body.contains("HomeUiRefreshBridge.requestRefresh(")
        )
    }

    @Test
    fun refreshRemoteDeviceState_shouldTreatDeviceNameAndCodeAsHomeDisplayChanges() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/net/DataManagementAPI.kt")
        val body = extractFunctionBody(source, "fun refreshRemoteDeviceState")

        assertTrue(
            "首页定时补偿必须把设备名称变化纳入 changed，否则首页设备名会等到其他字段变化或 onResume 才刷新",
            body.contains("oldDeviceName") &&
                body.contains("oldDeviceName != mergedConfig.deviceName")
        )
        assertTrue(
            "首页定时补偿必须把设备编号变化纳入 changed，否则首页设备编号会等到其他字段变化或 onResume 才刷新",
            body.contains("oldDeviceCode") &&
                body.contains("oldDeviceCode != mergedConfig.deviceCode")
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
