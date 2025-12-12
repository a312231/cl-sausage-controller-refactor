package cn.niuyannet.kaochang.android.init;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

public class AppConfigBean implements Serializable {
    public String mqttHost="tcp://mqtt.cheerluck.com";//mqtt连接信息
    public String mqttUsername="";//mqtt 初始化用户名
    public String mqttPassword="";//mqtt 初始化密码
    public int mqttPort=1883;//mqtt初始化端口
    //modbus
    public String modbusAddress="/dev/ttyS2";//modbus串口地址
    public int modbusBaudRate=115200;//modbus 波特率
    //小程序二维码
    public String qcodeURL="";
    //设备deviceID
    public String deviceCode;
    //设备名称
    public String deviceName;
    public String deviceAddress;
    //营业时间
    public BusinessTime businessTime;

    public int status;//状态（0离线 1在线）
    public int onlineStatus; // 在线状态（0未启用 1运营中 2休息中 3维护中 4工作中（正在出烤肠））
    //运动状态
    public int moveStatus=0; // （0 待机状态 1 补肠状态 2 夹取状态 3 搬运状态 4 丢弃烤肠）

    public int modeType; // 模式类型：1低并发，2高并发
    public int transitionMode;//过渡模式0是默认，1是从低并发过渡到高并发，2是高并发过渡到低并发
    public int errorStatus;//0是正常的，其他是错误的

}
