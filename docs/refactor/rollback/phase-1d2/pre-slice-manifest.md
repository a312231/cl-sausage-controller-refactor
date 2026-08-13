# 1D-2 实施前白名单快照

记录时间：2026-08-13
记录阶段：计划送审前、生产代码实施前
仓库 HEAD：`fad00d530bb88c0fb3ace82dcdda038f24626438`

以下 SHA-256 针对当前重构脏工作树中的实际文件，不代表 Git HEAD 内容。后续只能精确逆向 1D-2 hunk，禁止整文件恢复。

| 文件 | 存在 | tracked | SHA-256 |
|---|---:|---:|---|
| `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt` | 是 | 是 | `1928A9A4A7F6E85209C732AE447F92FDE405841A2E19AAB49D060B1769B7ACD0` |
| `app/src/main/java/cn/niuyannet/kaochang/android/utils/OrderStateManager.kt` | 是 | 是 | `FA2BCC741DBE3D65811D07E731E5AE5D35AAFAC3BC5B09B16B20EF9FB2F45BD9` |
| `app/src/main/java/cn/niuyannet/kaochang/android/services/ManualOutOrderRecoveryCoordinator.kt` | 是 | 是 | `B73967705912F46C9D248D5D2A53FB6AFA29D73CDD0DE5FEFCE2D77B847CB9E4` |
| `app/src/main/java/cn/niuyannet/kaochang/android/services/SupplementBatchIntent.kt` | 是 | 是 | `230CE6E4E754EA55D9D9DEE5677AEB5B538D9973C64991F852974B5276BBE4EC` |
| `app/src/main/java/cn/niuyannet/kaochang/android/model/KaoPanHelper.kt` | 是 | 是 | `1A66F975EDCE3AA66CA6102641C3D9F6E53C158E75D4785846E16DF13FB4FC74` |
| `app/src/main/java/cn/niuyannet/kaochang/android/init/AppConfig.kt` | 是 | 是 | `837ECC879C295DC2C1B846CDE45C9535258062A2F2B15BF7AB724002130F36E5` |
| `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/OrderDeliveryClaim.kt` | 是 | 是 | `AF1934375445ED15F03578DCDC579E2D09D8402E431DED46966F0F91A9BE929C` |
| `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/EmergencyResetRecoveryFence.kt` | 是 | 是 | `643D897EECD6B5000F3768D24D8F475F90C47B9CB4A03C734BB73A79B4C12BF4` |
| `app/src/main/java/cn/niuyannet/kaochang/android/services/PanMoveJournal.kt` | 是 | 是 | `45CCC19417613FEB0C1FB0B9E7AE64C54DDCC9395725C33CA44D212C1B141385` |
| `app/src/main/java/cn/niuyannet/kaochang/android/services/DiscardJournal.kt` | 是 | 是 | `285941221B62E8DCFF4EB1AD76113CAEB200A95831F8050DA87AD33B8BEEA769` |
| `app/src/main/java/cn/niuyannet/kaochang/android/services/recovery/DurableEvidenceReadResult.kt` | 是 | 是 | `57E298A1F8DD484066E83999A6DF480EFF7B63D4BDDC6C40D0E68B6400B08061` |
| `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/SharedPreferenceActionGroupSessionStore.kt` | 是 | 是 | `79E1006E5526E2C25CEC0E60384831247092D9E9EED09A79F3CC3CC99CEB07FA` |
| `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/SharedPreferenceSupplementPrefetchSessionStore.kt` | 是 | 是 | `DFFE72CE25C2B8CB8730814684B2CD4294C2AEB0D0E1E6EED1F81BECF05DD3AD` |
| `app/src/main/java/cn/niuyannet/kaochang/android/services/scheduler/SchedulerProfileSwitchReadiness.kt` | 否 | 否 | `ABSENT` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/scheduler/SchedulerProfileSwitchReadinessTest.kt` | 否 | 否 | `ABSENT` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/SchedulerProfileSwitchReadinessWiringSourceTest.kt` | 否 | 否 | `ABSENT` |
| `app/src/test/java/cn/niuyannet/kaochang/android/model/KaoPanHelperSchedulerSwitchSnapshotTest.kt` | 否 | 否 | `ABSENT` |
| `app/src/test/java/cn/niuyannet/kaochang/android/utils/OrderStateManagerSnapshotTest.kt` | 否 | 否 | `ABSENT` |
| `app/src/test/java/cn/niuyannet/kaochang/android/init/AppConfigReadinessPeekTest.kt` | 否 | 否 | `ABSENT` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/actiongroup/OrderDeliveryClaimStoreTest.kt` | 是 | 是 | `022C2F569E3B0974EC8180915A52C0048BB77BB8768C86665593467A9BF659FD` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/actiongroup/EmergencyResetRecoveryFenceTest.kt` | 是 | 是 | `8C594EE9514B412CD242837D39F06B5C424B60087890C5DCB376A7EB4EABAA50` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/PanMoveJournalTest.kt` | 是 | 是 | `DD5170BC60DE9BF0C703145518E6BB94854FD074824D0E803ECFB569518F714D` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/DiscardJournalTest.kt` | 是 | 是 | `12CC629C120ECEC4C4980C8CF1D08036F26C48D9B697FF5BC07CD9752ED59C46` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/recovery/DurableEvidenceCodecTest.kt` | 是 | 是 | `11F8582583A7A471CE3A489598D58C6F2FC432BF03967D933DB4C0FE958DC788` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/actiongroup/SupplementPrefetchSessionRepositoryTest.kt` | 是 | 是 | `5941C5F8E157B48C735ACD8BFE1D1792544774FFDFBA81397BE7F17D9BF82898` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/actiongroup/StrictReadinessStoreControlFlowTest.kt` | 否 | 否 | `ABSENT` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/ManualOutOrderRecoveryCoordinatorTest.kt` | 是 | 是 | `6F7EBE29D6C66D5D97AB6A5CFF1CBBA2880E55E8596C17C7BCCD5AB4FBF96D6E` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/SupplementBatchIntentRepositoryTest.kt` | 是 | 是 | `F78DC893A9A8892D9A79D4FDD92CBCB207C59CBF4D9984D4E243ECB296006E42` |
| `docs/refactor/2026-08-13-target-architecture-decisions.md` | 是 | 否 | `5DED515AF5DC76C87235CDBBDEAB81694DB9D6FF350AAF165C00202435DF1E90` |
| `docs/refactor/2026-08-13-phase-1d2-authoritative-scheduler-switch-readiness-verification.md` | 否 | 否 | `ABSENT` |

计划文件和本快照本身是实施前新增文档，不属于生产代码实现。审核若要求调整白名单，必须先更新本表，再开始 TDD。
