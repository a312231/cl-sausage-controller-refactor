package cn.niuyannet.kaochang.android.init;

import java.io.Serializable;

public class AppConfigBean implements Serializable {
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

    // 设备启用状态：0=未启用，1=启用
    public int status;

    // 设备在线状态：0=未启用，1=运营中，2=休息中，3=维护中，4=工作中（正在出餐）
    public int onlineStatus;

    // 机械动作状态：0=待机，1=补肠，2=夹取/出餐，3=搬运，4=丢弃
    public int moveStatus = 0;

    // 当前预计可售/预计烤制时间，0 表示可直接售卖
    public long available = 0;

    // 并发模式：1=低并发，2=高并发
    public int modeType;

    // 并发切换中的过渡状态
    public int transitionMode;

    // 设备错误状态：0=正常，其它值表示故障
    public int errorStatus;

    // 烤制算法开关：0=关闭，1=开启
    public int isEnable = 0;

    // 温度与时间类配置
    public int keepWarmTemperature;
    public int heatingTemperature;
    public int bakingTime;
    public int discardTime;
    public int timeThresholdLow;
    public int timeBeforeClose;

    // “烤肠箱 -> 平台”动作超时时间（秒），单独控制自动补肠第一步
    public int boxToPlatformTimeoutSeconds = 90;

    // “烤盘 -> 售卖口”动作超时时间（秒），用于正式出餐和手动取肠
    public int trayToSellPlatformTimeoutSeconds = 90;

    // 售卖收尾后，补肠前保护窗和下位机稳定等待参数
    public int postSellSupplementGuardSeconds = 5;
    public int postSellIdleStableSeconds = 2;
    public int postSellIdleMaxWaitSeconds = 12;
    public int cameraPrewarmHoldSeconds = 8;
}
