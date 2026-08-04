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
        assertTrue(LogUtils.shouldWriteToFileForTest("W", "【取肠语音】音频焦点申请失败，继续尝试播放"))
        assertTrue(LogUtils.shouldWriteToFileForTest("I", "【烤制算法】状态变化：低并发稳态 -> 低转高过渡态"))
        assertTrue(LogUtils.shouldWriteToFileForTest("I", "【低并发调度】本轮未触发低并发初始化：原因=本地烤盘未全空"))
        assertTrue(LogUtils.shouldWriteToFileForTest("I", "【动作组通信】下发完成：动作=out3，命令寄存器=430"))
        assertTrue(LogUtils.shouldWriteToFileForTest("I", "【管理员操作】清空烤盘：页面=烤盘信息，操作结果=开始"))
    }

    @Test
    fun shouldWriteToFileForTest_shouldKeepAppUpdateLogs() {
        assertTrue(LogUtils.shouldWriteToFileForTest("I", "【应用更新】开始检查应用版本：deviceCode=CL1"))
        assertTrue(LogUtils.shouldWriteToFileForTest("I", "【应用更新】已是最新版本：v2.1.22(92)"))
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
    fun shouldWriteToFileForTest_shouldKeepWarningsAndErrorsWithoutAllowlistKeywords() {
        assertTrue(LogUtils.shouldWriteToFileForTest("W", "普通警告但不包含日志白名单关键词"))
        assertTrue(LogUtils.shouldWriteToFileForTest("E", "普通错误但不包含日志白名单关键词"))
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
        assertTrue(LogUtils.shouldWriteToFileForTest("W", "串口探测失败：device=/dev/ttyS2, error=Port not found"))
        assertFalse(LogUtils.shouldWriteToFileForTest("D", "【Modbus】复用刚完成的重连结果，避免连续重复完整串口探测：status=false"))
    }

    @Test
    fun shouldWriteToFileForTest_shouldFilterSnapshotNoChangeDebugLogs() {
        assertFalse(
            LogUtils.shouldWriteToFileForTest(
                "D",
                "【云端同步】设备快照投影未变化，跳过HTTP同步：reason=poll_loop，skipCount=60"
            )
        )
    }
}
