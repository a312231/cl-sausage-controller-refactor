package cn.niuyannet.kaochang.android.model.bean;

import com.alibaba.fastjson.annotation.JSONField;
import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

/**
 * 烤盘数据结构
 */
public class KaoPan implements Serializable {
    public static final int STATUS_EMPTY = 0;
    public static final int STATUS_BAKING = 1;
    public static final int STATUS_SELLABLE_KEEP_WARM = 2;
    public static final int STATUS_WAITING_RETURN_KEEP_WARM = 3;

    @SerializedName("id")
    private int id; // 序列id
    
    @SerializedName("position_sn")
    private int positionSn; // 烤肠位置1-33
    
    @SerializedName("position_region")
    private int positionRegion; // 所属区1，2，3区
    
    @SerializedName("start_time")
    private long startTime; // 开始加热时间
    
    @SerializedName("baking_time")
    private long bakingTime; // 烤制时间 单位毫秒 20分钟
    
    @SerializedName("holding_time")
    private long holdingTime; // 开始保温时间 单位毫秒
    
    @SerializedName("close_time")
    private long closeTime; // 丢弃时间，即超过保温时间多久就丢弃 单位毫秒 4个小时丢弃
    
    @SerializedName("taste")
    private Taste taste; // 烤制口味信息
    
    @SerializedName("temperature")
    private int temperature; // 烤盘温度 0是未工作，60是保温中，160是烤制中
    
    @SerializedName("status")
    private int status; // 当前烤盘状态：0空缺，1烤制中，2保温可售，3等待回搬保温不可售
    
    @SerializedName("has_sausage")
    private boolean hasSausage; // 是否有烤肠

    @SerializedName("has_grilling")
    private boolean hasGrilling; // 烤制区是否有对应口味的烤肠在烤制
    
    @SerializedName("cmd_value_move")
    private int cmdValueMove; // 从平台把烤肠移动到烤盘
    
    @SerializedName("cmd_value_take")
    private int cmdValueTake; // 从烤盘取出烤肠
    
    @SerializedName("cmd_status_move")
    private int cmdStatusMove; // 命令执行状态 （无符号16位整型，0为正常，1为运动中，其余为错误码）
    
    @SerializedName("cmd_status_take")
    private int cmdStatusTake; // 命令执行状态 （无符号16位整型，0为正常，1为运动中，其余为错误码）
    
    @SerializedName("cmd_value_discard")
    private int cmdValueDiscard; // 丢弃烤肠的命令值
    
    @SerializedName("cmd_status_discard")
    private int cmdStatusDiscard; // 丢弃烤肠命令执行状态（0为正常，1为运动中，其余为错误码）

    public static final int UI_RESERVATION_NONE = 0;
    public static final int UI_RESERVATION_WAIT_SUPPLEMENT = 1;
    public static final int UI_RESERVATION_WAIT_RETURN = 2;
    public static final int UI_RESERVATION_CANCELLED_SUPPLEMENT = 3;

    @JSONField(serialize = false, deserialize = false)
    private transient int uiReservationState; // 仅管理页展示使用，不参与持久化/调度
    @JSONField(serialize = false, deserialize = false)
    private transient Taste uiReservationTaste; // 仅管理页展示使用，不参与持久化/调度
    
    // 默认构造函数
    public KaoPan() {
        this.id = 0;
        this.positionSn = 0;
        this.positionRegion = 0;
        this.startTime = 0L;
        this.bakingTime = 0L;
        this.holdingTime = 0L;
        this.closeTime = 0L;
        this.taste = null;
        this.temperature = 0;
        this.status = STATUS_EMPTY;
        this.hasSausage = false;
        this.hasGrilling = false;
        this.cmdValueMove = 0;
        this.cmdValueTake = 0;
        this.cmdStatusMove = 0;
        this.cmdStatusTake = 0;
        this.cmdValueDiscard = 0;
        this.cmdStatusDiscard = 0;
        this.uiReservationState = UI_RESERVATION_NONE;
        this.uiReservationTaste = null;
    }
    
    // 带参数的构造函数
    public KaoPan(int id, int positionSn, int positionRegion, long startTime, 
                  long bakingTime, long holdingTime, long closeTime, Taste taste, 
                  int temperature, int status, boolean hasSausage,
                  int cmdValueMove, int cmdValueTake, int cmdStatusMove, 
                  int cmdStatusTake, int cmdValueDiscard, int cmdStatusDiscard) {
        this.id = id;
        this.positionSn = positionSn;
        this.positionRegion = positionRegion;
        this.startTime = startTime;
        this.bakingTime = bakingTime;
        this.holdingTime = holdingTime;
        this.closeTime = closeTime;
        this.taste = taste;
        this.temperature = temperature;
        this.status = status;
        this.hasSausage = hasSausage;
        this.hasGrilling = false;
        this.cmdValueMove = cmdValueMove;
        this.cmdValueTake = cmdValueTake;
        this.cmdStatusMove = cmdStatusMove;
        this.cmdStatusTake = cmdStatusTake;
        this.cmdValueDiscard = cmdValueDiscard;
        this.cmdStatusDiscard = cmdStatusDiscard;
        this.uiReservationState = UI_RESERVATION_NONE;
        this.uiReservationTaste = null;
    }
    
    // Getter 和 Setter 方法
    public int getId() {
        return id;
    }
    
    public void setId(int id) {
        this.id = id;
    }
    
    public int getPositionSn() {
        return positionSn;
    }
    
    public void setPositionSn(int positionSn) {
        this.positionSn = positionSn;
    }
    
    public int getPositionRegion() {
        return positionRegion;
    }
    
    public void setPositionRegion(int positionRegion) {
        this.positionRegion = positionRegion;
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }
    
    public long getBakingTime() {
        return bakingTime;
    }
    
    public void setBakingTime(long bakingTime) {
        this.bakingTime = bakingTime;
    }
    
    public long getHoldingTime() {
        return holdingTime;
    }
    
    public void setHoldingTime(long holdingTime) {
        this.holdingTime = holdingTime;
    }
    
    public long getCloseTime() {
        return closeTime;
    }
    
    public void setCloseTime(long closeTime) {
        this.closeTime = closeTime;
    }
    
    public Taste getTaste() {
        return taste;
    }
    
    public void setTaste(Taste taste) {
        this.taste = taste;
    }
    
    public int getTemperature() {
        return temperature;
    }
    
    public void setTemperature(int temperature) {
        this.temperature = temperature;
    }
    
    public int getStatus() {
        return status;
    }
    
    public void setStatus(int status) {
        this.status = status;
    }
    
    public boolean isHasSausage() {
        return hasSausage;
    }
    
    public void setHasSausage(boolean hasSausage) {
        this.hasSausage = hasSausage;
    }

    public boolean isHasGrilling() {
        return hasGrilling;
    }

    public void setHasGrilling(boolean hasGrilling) {
        this.hasGrilling = hasGrilling;
    }
    
    public int getCmdValueMove() {
        return cmdValueMove;
    }
    
    public void setCmdValueMove(int cmdValueMove) {
        this.cmdValueMove = cmdValueMove;
    }
    
    public int getCmdValueTake() {
        return cmdValueTake;
    }
    
    public void setCmdValueTake(int cmdValueTake) {
        this.cmdValueTake = cmdValueTake;
    }
    
    public int getCmdStatusMove() {
        return cmdStatusMove;
    }
    
    public void setCmdStatusMove(int cmdStatusMove) {
        this.cmdStatusMove = cmdStatusMove;
    }
    
    public int getCmdStatusTake() {
        return cmdStatusTake;
    }
    
    public void setCmdStatusTake(int cmdStatusTake) {
        this.cmdStatusTake = cmdStatusTake;
    }
    
    public int getCmdValueDiscard() {
        return cmdValueDiscard;
    }
    
    public void setCmdValueDiscard(int cmdValueDiscard) {
        this.cmdValueDiscard = cmdValueDiscard;
    }
    
    public int getCmdStatusDiscard() {
        return cmdStatusDiscard;
    }
    
    public void setCmdStatusDiscard(int cmdStatusDiscard) {
        this.cmdStatusDiscard = cmdStatusDiscard;
    }

    public int getUiReservationState() {
        return uiReservationState;
    }

    public void setUiReservationState(int uiReservationState) {
        this.uiReservationState = uiReservationState;
    }

    public Taste getUiReservationTaste() {
        return uiReservationTaste;
    }

    public void setUiReservationTaste(Taste uiReservationTaste) {
        this.uiReservationTaste = uiReservationTaste;
    }
}
