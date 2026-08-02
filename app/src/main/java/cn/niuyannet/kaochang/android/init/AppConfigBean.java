package cn.niuyannet.kaochang.android.init;

import java.io.Serializable;
import java.util.ArrayList;

public class AppConfigBean implements Serializable {
    private static final long serialVersionUID = -4057341811446770064L;

    public static final int SUPPLY_STATUS_NORMAL = 0;
    public static final int SUPPLY_STATUS_SOLD_OUT = 1;
    public static final int HOME_QR_HIDDEN_SHOW = 0;
    public static final int HOME_QR_HIDDEN_HIDDEN = 1;
    public static final int SCAN_BLOCK_STATUS_NORMAL = HOME_QR_HIDDEN_SHOW;
    public static final int SCAN_BLOCK_STATUS_BLOCKED = HOME_QR_HIDDEN_HIDDEN;
    public static final int INSPECTION_MODE_NORMAL = 0;
    public static final int INSPECTION_MODE_ACTIVE = 1;
    public static final int ERROR_STATUS_NORMAL = 0;
    public static final int ERROR_STATUS_PROCESS = 1;
    public static final int ERROR_STATUS_HARDWARE = 2;
    public static final String REST_SOURCE_NONE = "NONE";
    public static final String REST_SOURCE_UNKNOWN = "UNKNOWN";
    public static final String REST_SOURCE_AUTO_END_BUSINESS = "AUTO_END_BUSINESS";
    public static final String REST_SOURCE_MANUAL_REMOTE = "MANUAL_REMOTE";
    public static final String REST_SOURCE_MANUAL_LOCAL = "MANUAL_LOCAL";
    public static final String LOWER_PLATFORM_STATUS_NORMAL = "NORMAL";
    public static final String LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED = "SUPPLY_DEGRADED";
    public static final String LOWER_PLATFORM_STATUS_ISOLATED = "ISOLATED";
    public static final int SUPPLEMENT_ENABLED_NO = 0;
    public static final int SUPPLEMENT_ENABLED_YES = 1;
    public static final String ORDER_BLOCK_REASON_DISPATCH_SAFETY_CHECKING = "DISPATCH_SAFETY_CHECKING";
    public static final String ORDER_BLOCK_REASON_LOWER_PLATFORM_RECOVERY_REQUIRED = "LOWER_PLATFORM_RECOVERY_REQUIRED";
    public static final String DEFAULT_API_BASE_URL = "https://iotadmin.cheerluck.com/prod-api/api/app/device";

    // MQTT 与云端通信配置
    public String apiBaseUrl = DEFAULT_API_BASE_URL;
    public String mqttHost = "tcp://mqtt.cheerluck.com";
    public String mqttUsername = "";
    public String mqttPassword = "";
    public int mqttPort = 1883;

    // 上位机本地管理员登录密码 SHA-256 哈希；远端未下发时默认 cl123
    public String adminPasswordHash = "e7d5ef5d4a76223f930e1264c0690f7aa0fea6faf27eefa0110c1a16bfe8bf2d";

    // Modbus 串口配置
    public String modbusAddress = "/dev/ttyS2";
    public int modbusBaudRate = 115200;

    // 设备基础信息
    public String qcodeURL = "";
    public String deviceCode;
    public String deviceName;
    public String deviceAddress;
    public BusinessTime businessTime;

    // 云端设备配置版本：由服务端保存配置时递增，用于 MQTT 配置补丁幂等与过期拒收
    public long configVersion = 0L;

    // 最近一次已应用的 runtime_control 序号，随 AppConfig JSON 持久化
    public long lastAppliedControlSeq = -1L;

    // 上位机本地运行心跳时间戳，用于冷启动后识别断电/App 停止期间的停热缺口。
    public long lastRuntimeAliveAtMs = 0L;

    // 历史本地兼容字段：0=未激活，1=已激活；不等同于后端 kc_device.status（0=正常/启用，1=停用）
    public int status;

    // 设备营业状态：0=待投运，1=运营中，2=休息中，3=维护中，4=工作中/正在出餐
    public int onlineStatus;

    // 补货状态：0=正常，1=售罄/待补货
    public int supplyStatus = SUPPLY_STATUS_NORMAL;

    // 下平台/补肠链路状态：NORMAL=正常，SUPPLY_DEGRADED=供给降级，ISOLATED=下平台隔离
    public String lowerPlatformStatus = LOWER_PLATFORM_STATUS_NORMAL;

    // 最近一次下平台隔离对应的补肠动作名，用于区分只停补肠和需暂停自动搬盘的隔离态
    public String lowerPlatformIsolationActionName = "";

    // 下平台半故障聚合版本及事件墓碑；由本机单调递增并同时通过 MQTT/HTTP 上报。
    public long lowerPlatformAggregateVersion = 0L;
    public String lowerPlatformGeneration = "";
    public ArrayList<LowerPlatformEpisodeBean> lowerPlatformEpisodes = new ArrayList<>();

    // 自动补肠开关：0=禁止，1=允许；不等同于烤制算法开关 isEnable
    public int supplementEnabled = SUPPLEMENT_ENABLED_YES;

    // 下单阻断原因：空字符串/空表示不阻断，非空由后端按原因阻断下单
    public String orderBlockReason = "";

    // 首页二维码隐藏：0=正常显示首页二维码，1=隐藏首页二维码
    public int homeQrHidden = HOME_QR_HIDDEN_SHOW;

    // 旧字段兼容：0=正常展示，1=隐藏二维码并禁止小程序下单/支付（仅兼容过渡，v2 删除）
    public int scanBlockStatus = SCAN_BLOCK_STATUS_NORMAL;

    // 检修模式：0=正常，1=检修中
    public int inspectionMode = INSPECTION_MODE_NORMAL;

    // 休息中来源：
    // - NONE：当前不是休息中
    // - UNKNOWN：历史旧数据或来源未知，按保守策略不自动恢复
    // - AUTO_END_BUSINESS：系统因营业结束自动切到休息中
    // - MANUAL_REMOTE：后台远端手动切到休息中
    // - MANUAL_LOCAL：本地人工切到休息中
    public String restStatusSource = REST_SOURCE_UNKNOWN;

    // 机械动作状态：0=空闲，1=补肠，2=夹取/出餐，3=搬运，4=丢弃
    public int moveStatus = 0;

    // 当前二维码页展示的“预计烤制时间”，0 表示不展示倒计时
    public long available = 0;

    // 当前这一批次的第一根预计时间是否已经完成展示：
    // 0=未完成，1=第一根已可售，不再继续滚动显示第二根/第三根
    public int availableCountdownLatched = 0;

    // 并发模式：1=低并发，2=高并发
    public int modeType;

    // 并发切换中的过渡态：0=无过渡态，1=低转高过渡，2=高转低过渡
    public int transitionMode;

    // 高并发残留消化态：0=无残留，1=低并发下 10~21 或 25~33 仍有烤肠/动作/保温状态
    public int highConcurrencyResidualDrain = 0;

    // 设备故障状态：0=正常，1=识别/流程错误，2=通信/硬件级故障
    public int errorStatus;

    // 当 errorStatus != 0 时随上报的结构化故障码（FaultCode 枚举名）；恢复时清空
    public String errorCode;
    // 故障类别中文标签（FaultCategory.display），用于飞书告警按类别动态生成处置建议
    public String errorCategory;
    // 故障原因（人类可读简述），用于告警里"原因"一行
    public String errorReason;

    // 烤制算法开关：0=关闭，1=开启
    public int isEnable = 0;

    // 故障停热暂停状态：0=未暂停，1=故障停热暂停中。该状态仅由本机维护，不参与远端配置合并。
    public int faultHeatPauseActive = 0;
    // 三区目标温度是否都已确认写0：0=未确认/无需确认，1=已确认。与计时暂停分开持久化。
    public int faultHeatStopConfirmed = 0;
    public long faultHeatPauseStartedAtMs = 0L;
    public String faultHeatPauseReason;
    public String faultHeatPauseSource;
    // 故障恢复补偿 journal；保存绝对烤盘目标，重启时按 journal 幂等重放。
    public String faultHeatRecoveryJournalJson;

    // 食品安全护栏阈值。远端未下发或下发非法值时使用默认值，不能关闭护栏。
    public long faultBakingPauseAbortMs = 2L * 60L * 60L * 1000L;
    public long faultPauseAnomalyMs = 4L * 60L * 60L * 1000L;
    public long httpFallbackMinIntervalMs = 30_000L;

    // 待丢弃烤盘位，跨重启保留。该状态仅由本机维护，不参与远端配置合并。
    public ArrayList<Integer> pendingDiscardPositions = new ArrayList<>();
    public String foodSafetyAlertCode;
    public String foodSafetyAlertReason;

    // 温度与时间配置
    public int keepWarmTemperature;
    public int heatingTemperature;
    public int zone1HeatingOffset = 0;
    public int zone2HeatingOffset = 0;
    public int zone3HeatingOffset = 0;
    public int bakingTime;
    public int discardTime;
    public int timeThresholdLow;
    public int timeBeforeClose;

    // 烤肠箱点击“加满”时写入的目标库存数，允许范围 1..15
    public int boxRefillTargetNum = 10;

    // “烤肠箱 -> 平台”动作超时时间（秒）
    public int boxToPlatformTimeoutSeconds = 90;

    // “烤盘 -> 售卖口”动作超时时间（秒）
    public int trayToSellPlatformTimeoutSeconds = 90;

    // 售卖收尾后，补肠前保护窗和下位机稳定等待参数
    public int postSellSupplementGuardSeconds = 5;
    public int postSellIdleStableSeconds = 2;
    public int postSellIdleMaxWaitSeconds = 12;

    // 相机预热保持时长（秒）
    public int cameraPrewarmHoldSeconds = 8;

    // 强制跳过视觉识别并固定返回成功（仅用于下位机模拟器本地联调）：0=关闭，1=开启
    public int forceVisionSuccess = 0;

    // 300/400 动作组协议开关：1=允许运行新动作组，0=进入维护/暂停新单，不回退旧视觉链路
    public int useActionGroupV2 = 1;

    // 动作组补肠预取开关：0=关闭，1=允许按资源锁提前预取。默认开启，后台明确下发 0 时才关闭。
    public int enableActionGroupPrefetch = 1;

    // 动作组补肠预取开关是否由后台明确配置过：0=未明确配置，1=后台已明确配置
    public int actionGroupPrefetchRemoteConfigured = 0;

    // 双 lane 并行补肠灰度开关：0=关闭，1=允许；默认关闭，需后台明确开启。
    public int enableParallelSupplementPrefetch = 0;

    // 视觉影子模式：0=关闭，1=仅记录视觉对账结果，不参与动作决策
    public int enableVisionShadowMode = 0;

    // 远程关闭动作组时，若当前动作未收口则暂存为待关闭：0=无，1=会话结束后关闭
    public int pendingDisableActionGroupV2 = 0;

    public static class LowerPlatformEpisodeBean implements Serializable {
        private static final long serialVersionUID = 1L;

        public String eventId = "";
        public long revision = 1L;
        public String faultType = "";
        public String lifecycle = "OPEN";
        public String actionName = "";
        public String actionLabel = "";
        public String errorCode = "";
        public String rawResult = "";
        public String reason = "";
        public Integer sourceBoxNo;
        public String tasteCode = "";
        public Integer targetPanNo;
        public String batchId = "";
        public String batchType = "";
        public long firstOccurredAt = 0L;
        public long lastOccurredAt = 0L;
        public int occurrenceCount = 1;
        public Long recoveredAt;
        public boolean requiresManualConfirm = false;
    }
}
