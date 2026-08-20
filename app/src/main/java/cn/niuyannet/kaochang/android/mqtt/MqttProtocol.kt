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
    const val TYPE_PAN_STATE_DELTA = "pan_state_delta"
    const val TYPE_PAN_STATE_DELTA_ACK = "pan_state_delta_ack"
    const val TYPE_CONSUMABLE_EVENT = "consumable_event"
    const val TYPE_CONSUMABLE_EVENT_ACK = "consumable_event_ack"
    const val TYPE_RUNTIME_CONTROL_ACK = "runtime_control_ack"
    const val TYPE_CONCURRENCY_CONTROL_ACK = "concurrency_control_ack"
    const val TYPE_ACTION_GROUP_TELEMETRY = "action_group_telemetry"
    const val TYPE_ACTION_GROUP_TELEMETRY_ACK = "action_group_telemetry_ack"
    const val TYPE_ILLEGAL_SOURCE_BOX_BLOCKED = "illegal_source_box_blocked"
    const val TYPE_APP_LOG_ENTRY = "app_log_entry"
    const val TYPE_APP_UPGRADE_NOTICE = "app_upgrade_notice"
    const val TYPE_APP_UPDATE_CHECK = "app_update_check"
    const val TYPE_APP_UPDATE_CHECK_ACK = "app_update_check_ack"
    const val TYPE_APP_UPDATE_CHECK_RESULT = "app_update_check_result"
    const val TYPE_DEVICE_LOG_PACKAGE_REQUEST = "device_log_package_request"
    const val TYPE_ORDER_DISPATCH_OFFER_V2 = "order_dispatch_offer_v2"
    const val TYPE_ORDER_DISPATCH_OFFER_ACK_V2 = "order_dispatch_offer_ack_v2"
    const val TYPE_ORDER_DISPATCH_COMMIT_V2 = "order_dispatch_commit_v2"
    const val TYPE_ORDER_DISPATCH_COMMIT_REJECTED_V2 = "order_dispatch_commit_rejected_v2"
    const val ORDER_DISPATCH_PROTOCOL_VERSION = 2

    const val SOURCE_MQTT_ACTION_2 = "mqtt-action=2"
}
