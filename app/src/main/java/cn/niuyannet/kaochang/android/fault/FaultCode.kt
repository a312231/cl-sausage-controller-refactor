package cn.niuyannet.kaochang.android.fault

/**
 * 设备故障类别。
 * 与后端 FeishuAlarmMessageBuilder.suggestionByCategory 的字符串保持一致。
 */
enum class FaultCategory(val display: String) {
    COMM("通信类"),
    MECHANICAL("机械类"),
    SELF_CLEAN("自清洁类"),
    PROCESS("流程类"),
    UNKNOWN("其它")
}

/**
 * 上位机置 errorStatus=2 时对应的故障枚举。
 * defaultReason 仅作为兜底文案，触发点应优先传入更具体的现场原因字符串。
 */
enum class FaultCode(val category: FaultCategory, val defaultReason: String) {
    MODBUS_DISCONNECT(FaultCategory.COMM, "Modbus 串口断连超过 5s"),
    MODBUS_CMD_REJECTED(FaultCategory.COMM, "Modbus 指令被拒"),
    MODBUS_COMM_DROP(FaultCategory.COMM, "动作期间下位机通信断开"),

    ACTION_BUSY_TIMEOUT(FaultCategory.MECHANICAL, "动作进入忙态超时"),
    ACTION_RUNNING_TIMEOUT(FaultCategory.MECHANICAL, "动作执行超时"),
    KAOPAN_MOVE_TIMEOUT(FaultCategory.MECHANICAL, "烤盘位移到位超时"),
    KAOPAN_MOVE_FAILED(FaultCategory.MECHANICAL, "烤盘位移动作失败"),
    KAOPAN_RESET_FAILED(FaultCategory.MECHANICAL, "营业结束烤盘复位异常"),

    SELF_CLEAN_FAILED(FaultCategory.SELF_CLEAN, "自清洁动作失败"),

    ACTION_GROUP_DISABLED(FaultCategory.PROCESS, "动作组 v2 被禁用"),
    ACTION_GROUP_FAILED(FaultCategory.PROCESS, "动作组执行失败"),
    ORDER_DELIVERY_ERROR(FaultCategory.PROCESS, "出餐流程未捕获异常"),

    UNKNOWN(FaultCategory.UNKNOWN, "未分类故障")
}
