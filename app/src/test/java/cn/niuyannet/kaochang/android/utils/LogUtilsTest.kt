package cn.niuyannet.kaochang.android.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogUtilsTest {

    @Test
    fun shouldWriteToFileForTest_shouldKeepActionGroupBracketLogs() {
        assertTrue(LogUtils.shouldWriteToFileForTest("D", "【补肠】补肠完成：目标烤盘=1"))
        assertTrue(LogUtils.shouldWriteToFileForTest("D", "【动作组】第1/3根 out1（从烤盘取熟肠到穿签位）开始"))
        assertTrue(LogUtils.shouldWriteToFileForTest("D", "【动作组】第1/3根 out1 完成，用时=23.1秒"))
        assertTrue(LogUtils.shouldWriteToFileForTest("D", "【补肠】烤盘13 in7（上平台从升降台夹肠并放入烤盘）完成，烤盘开始烤制计时"))
    }

    @Test
    fun shouldWriteToFileForTest_shouldKeepOperationMainlineLogs() {
        assertTrue(LogUtils.shouldWriteToFileForTest("D", "【订单】接单：订单号=CL1，数量=3，口味=A"))
        assertTrue(LogUtils.shouldWriteToFileForTest("W", "【订单流转】Modbus 重连失败，放弃本次出餐：orderNo=CL1"))
        assertTrue(LogUtils.shouldWriteToFileForTest("D", "【出餐】第2/3根开始：来源烤盘=9"))
        assertTrue(LogUtils.shouldWriteToFileForTest("D", "【动作组】第2/3根 out3（从穿签位放到预推送位置）开始"))
        assertTrue(LogUtils.shouldWriteToFileForTest("E", "【动作组故障锁存】已锁存设备故障，禁止自动补肠/续补继续启动"))
        assertTrue(LogUtils.shouldWriteToFileForTest("E", "动作组恢复失败：读取 out1 结果寄存器 411 失败"))
        assertTrue(LogUtils.shouldWriteToFileForTest("D", "【Modbus】串口连接成功：port=/dev/ttyS7"))
        assertTrue(LogUtils.shouldWriteToFileForTest("D", "【搬盘】开始搬移：来源烤盘=14，目标烤盘=5"))
        assertTrue(LogUtils.shouldWriteToFileForTest("D", "【失败原因】out3（从穿签位放到预推送位置）启动失败：下位机未确认执行"))
        assertTrue(LogUtils.shouldWriteToFileForTest("D", "【状态切换】设备进入维护：工作中/正在出餐 -> 维护中"))
        assertTrue(LogUtils.shouldWriteToFileForTest("D", "【云端同步】云端已确认设备状态：工作中/正在出餐"))
    }

    @Test
    fun shouldWriteToFileForTest_shouldStillFilterUnrelatedDebugLogs() {
        assertFalse(LogUtils.shouldWriteToFileForTest("D", "普通调试日志"))
    }

    @Test
    fun shouldWriteToFileForTest_shouldKeepThrowableLogs() {
        assertTrue(LogUtils.shouldWriteToFileForTest("E", "普通异常日志", hasThrowable = true))
    }

    @Test
    fun shouldWriteToFileForTest_shouldFilterHighFrequencyHomepageEstimateDebugLogs() {
        assertFalse(LogUtils.shouldWriteToFileForTest("D", "【MQTT展示态】已上报首页展示态：available=1174380"))
        assertFalse(LogUtils.shouldWriteToFileForTest("D", "【MQTT业务】收到首页展示态回执：action=2（设备状态同步）"))
        assertFalse(LogUtils.shouldWriteToFileForTest("D", "[算法轮询] 更新二维码预计时间：available=1174380->1164335"))
        assertFalse(LogUtils.shouldWriteToFileForTest("D", "【首页展示】刷新首页文案：status（设备启用状态）=1（启用/在线），onlineStatus（设备营业状态）=4（工作中/正在出餐）"))
        assertFalse(LogUtils.shouldWriteToFileForTest("D", "【MQTT运行态】服务端已确认运行时状态：runtimeSeq=1777445809899，freshAck=true，ackTs=1777447644642"))
        assertFalse(LogUtils.shouldWriteToFileForTest("D", "[300/400动作组] 轮询原子动作：action=out1，resultRegister=411，resultCode=0x0001，elapsedMs=686"))
        assertFalse(LogUtils.shouldWriteToFileForTest("D", "[300/400动作组] 准备下发原子动作：sessionId=out:1，action=out1"))
        assertFalse(LogUtils.shouldWriteToFileForTest("D", "[300/400动作组] 原子动作完成：sessionId=in_pipeline:1，action=in1，success=true"))
        assertFalse(LogUtils.shouldWriteToFileForTest("W", "串口探测失败：device=/dev/ttyS2, error=Port not found"))
    }
}
