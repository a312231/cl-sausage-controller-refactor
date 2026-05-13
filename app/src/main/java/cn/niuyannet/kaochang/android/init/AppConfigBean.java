package cn.niuyannet.kaochang.android.init;

import java.io.Serializable;
import java.util.ArrayList;

public class AppConfigBean implements Serializable {
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

    // MQTT 与云端通信配置
    public String mqttHost = "tcp://mqtt.cheerluck.com";
    public String mqttUsername = "";
    public String mqttPassword = "";
    public int mqttPort = 1883;

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

    // 设备启用状态：0=未启用，1=启用
    public int status;

    // 设备营业状态：0=未启用，1=运营中，2=休息中，3=维护中，4=工作中/正在出餐
    public int onlineStatus;

    // 补货状态：0=正常，1=售罄/待补货
    public int supplyStatus = SUPPLY_STATUS_NORMAL;

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
    public long faultHeatPauseStartedAtMs = 0L;
    public String faultHeatPauseReason;
    public String faultHeatPauseSource;

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

    // 视觉影子模式：0=关闭，1=仅记录视觉对账结果，不参与动作决策
    public int enableVisionShadowMode = 0;

    // 远程关闭动作组时，若当前动作未收口则暂存为待关闭：0=无，1=会话结束后关闭
    public int pendingDisableActionGroupV2 = 0;

    public int zone3Dirty = 0;
    public int zone3CleanPending = 0;
}
