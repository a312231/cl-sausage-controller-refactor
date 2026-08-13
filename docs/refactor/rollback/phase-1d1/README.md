# 1D-1 回滚材料

这不是实施前自动落盘的快照。本目录是在独立审核指出证据缺口后，根据主会话**实施前已输出的实际 SHA-256 与原始文件内容**补存，不能改写成“当时已经存档”。

## 实施前证据

以下哈希来自 1D-1 修改代码前的命令输出：

| 路径 | 实施前状态 | SHA-256 |
|---|---|---|
| `scheduler-core/.../SellCandidateStrategyCatalog.kt` | 未跟踪、存在 | `60E1587450D712DC54F422053261746D5555CC768DEE73FD5B4135F784659BCB` |
| `scheduler-core/.../PinnedSellCandidateStrategy.kt` | 未跟踪、存在 | `C25979347B1ACFA572A02544BAA8A783DA101EC1AA354CEF5190C4C71A2DA5AD` |
| `scheduler-core/.../StrategySwitchPolicy.kt` | 未跟踪、存在 | `94DB6576352988882F00484B3E69A04AD2CA053A970A619894F55533FC16D1D1` |
| `scheduler-core/.../StrategySwitchPolicyTest.kt` | 未跟踪、存在 | `F11B3C5DBAD595D6FC28661C33FC450B080C293F6DDAB5D58A98443F7A0476AA` |
| `scheduler-core/.../SellCandidatePolicyTest.kt` | 未跟踪、存在 | `9E5E07B5EBB276C2A856A130F0B0107B941A02DA1973EF4BBE99769BAC467BE2` |
| `app/.../SchedulerShadowRuntime.kt` | 未跟踪、存在 | `BEB26E26728B2984A82595D222077A0A95DC9C4D9B1EE0EB017898443C595986` |
| `app/.../KaoPanHelper.kt` | tracked、已有前序差异 | `3972420B9E3BA2EE7F424EB27CE53E238986C32A2A11A368C7716BF1CCD3843D` |
| `app/.../SupplementTasteMixPlanner.kt` | tracked、已有前序差异 | `590226FDACDDB722244ED09AD9A40EA007FF49849FC440720F28E55BAE0E6BC2` |
| `app/.../LowCurrentTrafficMode.kt` | tracked、已有前序差异 | `7123C28E6BB7FEF0554C3568832B4A58BA29F9D67DD9084BE4BEC9BC406F8272` |
| `app/.../HighCurrentTrafficMode.kt` | tracked、已有前序差异 | `8404E963BFCDEECD9BF4917E1BE1F1749E937424BA002FA752F34ECF38B6310E` |
| `app/.../HighHeatSupplementResumePlanner.kt` | tracked、已有前序差异 | `A1DE9F13201F93D9E3520A376DD47E2DF45AA13FAD932319ED32DA7EDD19242B` |
| `app/.../LowToHighDemandGate.kt` | tracked、已有前序差异 | `EF1BE111D3FDD9A75D848DAE4812B0653A928A5BAA29484155979CEAAA3F901C` |

四个已删除文件的 preimage 已保存为同名 `.preimage.txt`；复算哈希与上表逐个相等。`SellCandidatePolicyTest` 只删除两个旧 catalog 测试，App 8 点只替换 import 和直接构造表达式；这些都应按当前 diff 生成的精确逆向 hunk 恢复，禁止整文件覆盖。

## 精确回滚映射

1. 删除本切片新增的四个 Profile 主源码、两个 Profile 核心测试、App Runtime 和两个 App 测试；
2. 把四个 `.preimage.txt` 原样恢复到其表中原路径；
3. `SellCandidatePolicyTest.kt` 仅恢复被删除的两个旧 catalog 测试块；
4. `SchedulerShadowRuntime`、`KaoPanHelper`、`SupplementTasteMixPlanner`、`LowCurrentTrafficMode`、`HighCurrentTrafficMode`、`HighHeatSupplementResumePlanner`、`LowToHighDemandGate` 仅反向恢复 8 个 import/字段初始化 hunk；
5. 删除 1D-1 计划、验证和本回滚材料，并只反向恢复架构决策中 1D-1/长期门禁新增 hunk；
6. 回滚后复算上表 12 个 SHA。任何一个不一致都停止，不得用 `git restore`、`git checkout --`、reset、stash 或整文件覆盖“修复”。

本目录不授权现在执行回滚。
