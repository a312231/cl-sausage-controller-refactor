package cn.niuyannet.kaochang.android.services.paramtuning

internal enum class LowerBoardParamArea {
    UPPER,
    LOWER
}

internal sealed class LowerBoardParamDefinition(
    open val key: String,
    open val displayName: String,
    open val area: LowerBoardParamArea
) {
    data class UInt16(
        val address: Int,
        override val key: String,
        override val displayName: String,
        override val area: LowerBoardParamArea
    ) : LowerBoardParamDefinition(key, displayName, area)

    data class Float32(
        val addressLow: Int,
        override val key: String,
        override val displayName: String,
        override val area: LowerBoardParamArea,
        val coordinate: Boolean = true
    ) : LowerBoardParamDefinition(key, displayName, area)
}

internal object LowerBoardParamDefinitions {
    val all: List<LowerBoardParamDefinition> = listOf(
        LowerBoardParamDefinition.UInt16(1200, "z_reset_sgthrs", "Z 轴回零堵转阈值", LowerBoardParamArea.UPPER),
        upperFloat(1201, "bakeware_x_origin", "烤位 X 起点"),
        upperFloat(1203, "bakeware_dis", "烤位列间距", coordinate = false),
        upperFloat(1205, "bakeware_y_1", "第 1 排烤位 Y 坐标"),
        upperFloat(1207, "bakeware_y_2", "第 2 排烤位 Y 坐标"),
        upperFloat(1209, "bakeware_y_3", "第 3 排烤位 Y 坐标"),
        upperFloat(1211, "bakeware_z", "烤位统一 Z 高度"),
        upperFloat(1213, "lift_x", "升降台取肠点 X"),
        upperFloat(1215, "lift_y", "升降台取肠点 Y"),
        upperFloat(1217, "lift_z", "升降台取肠点 Z"),
        upperFloat(1219, "stick_x", "穿签点 X"),
        upperFloat(1221, "stick_y", "穿签点 Y"),
        upperFloat(1223, "stick_z", "穿签点 Z"),
        upperFloat(1225, "push_ready_x", "推送给顾客前准备位 X"),
        upperFloat(1227, "push_ready_y", "推送给顾客前准备位 Y"),
        upperFloat(1229, "push_ready_z", "推送给顾客前准备位 Z"),
        upperFloat(1231, "discard_x", "废弃池丢弃点 X"),
        upperFloat(1233, "discard_y", "废弃池丢弃点 Y"),
        upperFloat(1235, "discard_z", "废弃池丢弃点 Z"),
        upperFloat(1237, "push_to_x", "售卖推送终点 X"),
        upperFloat(1239, "push_to_y", "售卖推送终点 Y"),
        upperFloat(1241, "push_back_x", "推送结束后回退位置 X"),
        upperFloat(1243, "push_back_y", "推送结束后回退位置 Y"),
        LowerBoardParamDefinition.UInt16(
            1300,
            "bottom_load_ir_check_enable",
            "下平台红外判断开关",
            LowerBoardParamArea.LOWER
        ),
        lowerFloat(1301, "exit_x", "下平台预留出口位置 X"),
        lowerFloat(1303, "exit_y", "下平台预留出口位置 Y"),
        lowerFloat(1305, "box_y_1", "第 1 排料仓 Y 坐标"),
        lowerFloat(1307, "box_y_2", "第 2 排料仓 Y 坐标"),
        lowerFloat(1309, "throw_y", "抛肠点 Y 坐标"),
        lowerFloat(1311, "x_push_ofs", "推肠时向前推进的 X 偏移量", coordinate = false),
        lowerFloat(1313, "x_reverse_push_ofs", "反向拨动时附加的 X 位移偏移", coordinate = false)
    )

    val byKey: Map<String, LowerBoardParamDefinition> = all.associateBy { it.key }

    private fun upperFloat(addressLow: Int, key: String, displayName: String, coordinate: Boolean = true): LowerBoardParamDefinition.Float32 {
        return LowerBoardParamDefinition.Float32(addressLow, key, displayName, LowerBoardParamArea.UPPER, coordinate)
    }

    private fun lowerFloat(addressLow: Int, key: String, displayName: String, coordinate: Boolean = true): LowerBoardParamDefinition.Float32 {
        return LowerBoardParamDefinition.Float32(addressLow, key, displayName, LowerBoardParamArea.LOWER, coordinate)
    }
}

