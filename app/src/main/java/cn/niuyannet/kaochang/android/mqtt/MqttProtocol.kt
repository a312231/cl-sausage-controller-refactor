package cn.niuyannet.kaochang.android.mqtt

object MqttProtocol {
    const val ACTION_ORDER_EVENT = 1
    const val ACTION_SERVICE_STATE = 2
    const val ACTION_DEVICE_LOG = 3

    const val TYPE_RUNTIME_CONTROL = "runtime_control"
    const val TYPE_CONCURRENCY_CONTROL = "concurrency_control"
    const val TYPE_RUNTIME_STATE = "runtime_state"
    const val TYPE_RUNTIME_STATE_ACK = "runtime_state_ack"
    const val TYPE_AVAILABLE_STATE = "available_state"
    const val TYPE_AVAILABLE_STATE_ACK = "available_state_ack"
    const val TYPE_CONSUMABLE_EVENT = "consumable_event"
    const val TYPE_CONSUMABLE_EVENT_ACK = "consumable_event_ack"
    const val TYPE_RUNTIME_CONTROL_ACK = "runtime_control_ack"
    const val TYPE_CONCURRENCY_CONTROL_ACK = "concurrency_control_ack"
    const val TYPE_ACTION_GROUP_TELEMETRY = "action_group_telemetry"
    const val TYPE_ILLEGAL_SOURCE_BOX_BLOCKED = "illegal_source_box_blocked"
    const val TYPE_APP_LOG_ENTRY = "app_log_entry"
    const val TYPE_APP_UPGRADE_NOTICE = "app_upgrade_notice"
    const val TYPE_APP_UPDATE_CHECK = "app_update_check"
    const val TYPE_APP_UPDATE_CHECK_ACK = "app_update_check_ack"
    const val TYPE_APP_UPDATE_CHECK_RESULT = "app_update_check_result"

    const val SOURCE_MQTT_ACTION_2 = "mqtt-action=2"
}
