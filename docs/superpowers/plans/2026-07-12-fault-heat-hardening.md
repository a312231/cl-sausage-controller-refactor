# 故障停热与下平台半故障加固 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 修复故障停热与半故障边界问题，同时保持既有烤肠调度算法不变。

**Architecture:** 在现有 `FaultHeatTransitionLock` 和 `FaultHeatPauseManager` 边界内增加统一温控入口与可重放补偿记录；服务端 generation 与通知改动保持独立，不改订单调度核心。

**Tech Stack:** Kotlin/Android SharedPreferences、Java/Spring Boot/MyBatis、JUnit。

---

### Task 1: 温控写入闸门

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/FaultHeatRecoverySourceTest.kt`

- [ ] 写失败测试，断言 `heating`、`keepWarm`、`stopHeating` 和全盘清零入口都经过故障锁，暂停期间正温写入被拒绝，半故障仍允许正温。
- [ ] 运行目标测试确认先失败。
- [ ] 将所有温控入口接入同一锁和状态门控，保留写 0 路径。
- [ ] 运行目标测试和 Controller 全量测试。

### Task 2: 暂停补偿 journal 与启动顺序

**Files:**
- Modify: `FaultHeatPauseManager.kt`, `KaoPanHelper.kt`, `AppConfig.kt`, `AppConfigBean.java`, `PowerLossRecoveryGuard.kt`, `MainActivity.kt`
- Test: `FaultHeatRecoverySourceTest.kt`, `PowerLossRecoveryGuardTest.kt`

- [ ] 写 journal 掉电阶段和绝对时间重放测试，确认先失败。
- [ ] 增加同步保存 API 和幂等 journal 重放；失败保持暂停。
- [ ] 将冷启动断电恢复移到营业纠偏之前。
- [ ] 运行目标测试和全量测试。

### Task 3: 半故障动作组恢复与上报顺序

**Files:**
- Modify: `ActionGroupRecoveryBootstrap.kt`, `KaoChangOperate.kt`, `LowerPlatformRuntimeStateReporter.kt`
- Test: `ActionGroupRecoveryBootstrapTest.kt`, `LowerPlatformRuntimeStateReporterTest.kt`

- [ ] 写匹配/不匹配 episode、危险码和终态清理测试，确认先失败。
- [ ] 仅放行匹配的安全 `in1..in5` 隔离终态，其他情况保持维护。
- [ ] 失败终态先清本地动作状态，再发布半故障快照。
- [ ] 运行目标测试和全量测试。

### Task 4: Server generation、索引和 outbox

**Files:**
- Modify: `LowerPlatformFaultReportServiceImpl.java`, `KcLowerPlatformFaultEpisodeMapper.xml`, `LowerPlatformFaultOutboxRetryPolicy.java`, `sql/kc_lower_platform_fault_episode_20260711.sql`
- Test: `LowerPlatformFaultReportServiceImplTest.java`, `LowerPlatformFaultOutboxRetryPolicyTest.java`, `KcLowerPlatformFaultEpisodeMapperSqlTest.java`

- [ ] 写未知 generation 覆盖和 jitter/索引测试，确认先失败。
- [ ] 有当前 generation 时拒绝普通切代；仅保留显式恢复入口作为切代边界。
- [ ] 增加复合索引、可重入 DDL 和确定性 jitter。
- [ ] 运行 Server 定向测试和 Maven 编译。

### Task 5: 双模型审核与收口

- [ ] 获取同一子 agent 的独立 diff 审核。
- [ ] 修复所有 P0/P1，重复测试和审核直到无阻断项。
- [ ] 核对两仓工作区，只保留本次相关变更。
- [ ] 按用户授权更新版本、提交并 push；若 push 仍受远端升级阻断，保留明确证据。
