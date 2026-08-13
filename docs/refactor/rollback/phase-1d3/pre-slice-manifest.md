# 1D-3 实施前文件清单

记录时间：2026-08-13
仓库：`D:\LeYe\cl-sausage-controller-refactor`
分支：`develop`
实施前 HEAD：`2cab28607e2fbeded50b0677717b7c7a9429564b`
源码版本：`versionName=2.5.42`、`versionCode=292`
实施前工作树：干净

本清单只记录路径、存在状态、tracked 状态和 SHA-256，不复制文件内容。回滚禁止使用 `git restore`、`git checkout --`、reset、stash 或整文件覆盖，只允许逆向 1D-3 的紧小 hunk。

| 路径 | 存在 | tracked | 实施前 SHA-256 |
|---|---:|---:|---|
| `app/src/main/java/cn/niuyannet/kaochang/android/MyApp.kt` | 是 | 是 | `683D867C0C76E037E05C0DA44FF6265946112D23D9C8F153C422FC40BB751EF1` |
| `app/src/main/java/cn/niuyannet/kaochang/android/services/scheduler/SchedulerProfileRuntime.kt` | 是 | 是 | `0ED22B3DD70258ED07F810713BA36408BCAC367C6E12C1EE6A74627EACE22DA0` |
| `scheduler-core/src/main/kotlin/cn/niuyannet/kaochang/scheduler/ProcessLifetimeSchedulerProfilePin.kt` | 否 | 否 | `ABSENT` |
| `scheduler-core/src/test/kotlin/cn/niuyannet/kaochang/scheduler/ProcessLifetimeSchedulerProfilePinTest.kt` | 否 | 否 | `ABSENT` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/scheduler/SchedulerProfileTestBootstrap.kt` | 否 | 否 | `ABSENT` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/scheduler/SchedulerProfileRuntimeTest.kt` | 是 | 是 | `EA074BE29BCE97D390A29D13C5794DF42E594A1B747A5433D53D149C6A4885AC` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/scheduler/SchedulerProfileWiringSourceTest.kt` | 是 | 是 | `2FDA2A97EDEBEE93F8EC99D2139EC3B6FEB64E990CB1731FA0149F0076EB13DB` |
| `app/src/test/java/cn/niuyannet/kaochang/android/model/KaoPanHelperBakingTimeTest.kt` | 是 | 是 | `25B8D5107AC5E9BDF95362E61C78B0FC7F1B594B185F7B5D1A7CB0525D89CB4A` |
| `app/src/test/java/cn/niuyannet/kaochang/android/model/KaoPanHelperRatioTest.kt` | 是 | 是 | `627F2739B201B843C36380FB7330E780717ACE5B33820F65A0B97CD8E52FD778` |
| `app/src/test/java/cn/niuyannet/kaochang/android/model/KaoPanHelperSellableTest.kt` | 是 | 是 | `39D170D643AA27496B9353B754F53B42BEA1F472443B04F28C2E00060A0CC067` |
| `app/src/test/java/cn/niuyannet/kaochang/android/model/KaoPanHelperTasteMetadataTest.kt` | 是 | 是 | `B45B8D4DC0C83F40A4154E0C7D8E1035B7C0B9641DC2B4BE7F27E81D9C301F0C` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/HighHeatSupplementResumePlannerTest.kt` | 是 | 是 | `125A7CA900BDFDF6BED5BF24E91276354E20931E1DA6DE9B87C411DD871C82C2` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/LowHeatSupplementReservationResultTest.kt` | 是 | 是 | `181CB41F209CF029FA82EEA6FFE4A0DAB982D5EB918CE6D556F3BE8C5F81606A` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/LowHeatSupplementResumePlannerTest.kt` | 是 | 是 | `8E068BD3D9DB50DE092641842A46C3C180D75DA12BBA9E3493C4FF2C73704825` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/LowToHighDemandGateTest.kt` | 是 | 是 | `3D0F77A0B444F0F7BF1BE0002E0944E969F8BE8FEA685CE6CAFA3029AA55C5B9` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/SupplementPanStateWriterTest.kt` | 是 | 是 | `C30C90D8C79C5DE543A168D5FDD89A1BD2C4AF8A93D9B5642AC6306A29C7389B` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/SupplementSuccessPanStateTest.kt` | 是 | 是 | `68A52A98E8305F0A4A0660C0C20CB310B95494880C7F7D3210A1D9716A61FB44` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/SupplementTasteMixPlannerTest.kt` | 是 | 是 | `EB91ABC9B45CDF77EEA5C571E0B5FAA73DF6C3A9463515D982C4F6AF37A5F05F` |
| `docs/refactor/2026-08-13-phase-1d3-process-lifetime-scheduler-pin-plan.md` | 是 | 是 | `97605B22DA9754E0195A0D8B94E157994FC1CC2BB00C721E5ECD6823380C08A8` |
| `docs/refactor/2026-08-13-phase-1d3-process-lifetime-scheduler-pin-verification.md` | 否 | 否 | `ABSENT` |
| `docs/refactor/rollback/phase-1d3/pre-slice-manifest.md` | 否 | 否 | `ABSENT` |

## 精确回滚边界

- `MyApp.kt`：删除本切片新增的 bootstrap import 和 `super.onCreate()` 后单一 bootstrap 调用。
- `SchedulerProfileRuntime.kt`：逆向显式协调器包装，恢复实施前固定 A hunk。
- 三个实施前不存在的新文件：仅删除本切片新增文件。
- 11 个既有 App 测试：仅删除 test bootstrap import 和类级初始化调用，不改变原断言与业务数据。
- 两个既有 scheduler App 测试：只逆向本切片新增断言/源码门禁 hunk。
- 验证记录和本目录材料按新增文件处理。

不得触碰基础里程碑 `2cab286` 中的 1A~1D-2、2A~2C-1、3A~3B-1 成果。
