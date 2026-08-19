# 最小耐久高转低计时 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让高转低间隔只从 25～33 整批成熟并成功回搬收口后开始，并在重启时失败关闭，绝不提前降并发。

**Architecture:** 新增一个只保存 `armed/anchorAtMs` 的 SharedPreferences store 和纯 Kotlin repository。`HighCurrentTrafficMode` 在 25～33 硬件调用前同步 disarm，在现有回搬证据完全收口且已进入稳定高并发后 arm；不新增线程、锁、轮询或硬件校验。

**Tech Stack:** Kotlin、Android SharedPreferences、Fastjson、JUnit 4、Gradle 8.7。

---

### Task 1: 小型耐久计时状态

**Files:**
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/HighToLowTimerState.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/HighToLowTimerStateTest.kt`

- [ ] **Step 1: 写 repository 失败测试**

测试使用内存 fake store，证明：disarm/arm 只在状态变化时写盘；写盘失败返回 false 且不更新缓存；损坏或非法锚点按 disarmed 处理。

```kotlin
@Test
fun `disarm writes once and does not rewrite unchanged state`() {
    val store = FakeHighToLowTimerStateStore(HighToLowTimerState(armed = true, anchorAtMs = 100L))
    val repository = HighToLowTimerStateRepository(store)

    assertTrue(repository.disarmDurably())
    assertTrue(repository.disarmDurably())
    assertEquals(1, store.saveCount)
    assertEquals(HighToLowTimerState(false, 0L), store.state)
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.HighToLowTimerStateTest"`

Expected: FAIL，因为状态类型和 repository 尚不存在。

- [ ] **Step 3: 写最小实现**

```kotlin
data class HighToLowTimerState(val armed: Boolean = false, val anchorAtMs: Long = 0L)

interface HighToLowTimerStateStore {
    fun load(): HighToLowTimerState?
    fun saveDurable(state: HighToLowTimerState): Boolean
}

class HighToLowTimerStateRepository(private val store: HighToLowTimerStateStore) {
    fun current(): HighToLowTimerState = store.load()?.takeIf { !it.armed || it.anchorAtMs > 0L }
        ?: HighToLowTimerState()

    fun disarmDurably(): Boolean = saveIfChanged(HighToLowTimerState())

    fun armDurably(nowMs: Long): Boolean {
        if (nowMs <= 0L) return false
        return saveIfChanged(HighToLowTimerState(armed = true, anchorAtMs = nowMs))
    }
}
```

SharedPreferences 实现复用 `AppConfig.Sp_File_URL` 和 `PreferenceUtils.saveStringPreferenceDurable()`；不修改 `PreferenceUtils`。

- [ ] **Step 4: 运行测试并确认 GREEN**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.HighToLowTimerStateTest"`

Expected: PASS。

### Task 2: 计时 arm 判定与重启策略

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficModeStateHelper.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficModeStateHelperTest.kt`

- [ ] **Step 1: 写判定失败测试**

用参数化场景证明：只有稳定高并发、25～33 全空、没有高热 intent/binding 且恢复门禁未阻塞时可以 arm；低转高、源区有肠或存在恢复证据均不可 arm。

```kotlin
assertTrue(canArmHighToLowTimer(2, 0, true, false, false))
assertFalse(canArmHighToLowTimer(2, 1, true, false, false))
assertFalse(canArmHighToLowTimer(2, 0, false, false, false))
assertFalse(canArmHighToLowTimer(2, 0, true, true, false))
assertFalse(canArmHighToLowTimer(2, 0, true, false, true))
```

- [ ] **Step 2: 运行测试并确认 RED**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.HighCurrentTrafficModeStateHelperTest"`

Expected: FAIL，因为 `canArmHighToLowTimer` 尚不存在。

- [ ] **Step 3: 写纯判定函数**

```kotlin
internal fun canArmHighToLowTimer(
    modeType: Int,
    transitionMode: Int,
    highHeatZoneClear: Boolean,
    hasPendingHighHeatEvidence: Boolean,
    recoveryBlocked: Boolean
): Boolean = modeType == 2 && transitionMode == 0 && highHeatZoneClear &&
    !hasPendingHighHeatEvidence && !recoveryBlocked
```

- [ ] **Step 4: 运行测试并确认 GREEN**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.HighCurrentTrafficModeStateHelperTest"`

Expected: PASS。

### Task 3: 接入三个 25～33 路径和回搬完成点

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficModeSourceTest.kt`

- [ ] **Step 1: 写 source gate 失败测试**

测试读取源码，证明：正常 25～33 新批次、低转高 25～33 新批次、未完成批次续补在 `moveSausagesToKaoPansByTaste()` 前调用同一个 `disarmHighToLowTimerBeforeHighHeatMove()`；回搬清空后先清 binding，再尝试 arm；高转低判定不再直接以内存 0 值无条件建立锚点。

- [ ] **Step 2: 运行 source gate 并确认 RED**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.HighCurrentTrafficModeSourceTest"`

Expected: FAIL，指出三个入口尚未接入耐久 disarm。

- [ ] **Step 3: 接入最小运行时代码**

新增对象内 repository 和一次性恢复标记，不新增线程或锁：

```kotlin
private var timerStateRestoredInProcess = false

private fun disarmHighToLowTimerBeforeHighHeatMove(reason: String): Boolean {
    if (!highToLowTimerRepository.disarmDurably()) {
        LogUtils.e(TAG, "【并发切换】高转低计时失效落盘失败，禁止启动25~33补肠：reason=$reason")
        return false
    }
    lastFillPitTime = 0L
    timerStateRestoredInProcess = true
    return true
}
```

每个 25～33 硬件调用在该函数返回 false 时立即返回，不加等待或重试循环。回搬完成后复用当前盘面、intent/binding 和动作恢复门禁判定，成功才 `armDurably(now)`。进程首次恢复旧 armed 时以当前时间重新锚定，不沿用可能提前触发的旧 elapsed。

- [ ] **Step 4: 运行切片 A 定向测试并确认 GREEN**

Run:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.HighToLowTimerStateTest" --tests "cn.niuyannet.kaochang.android.services.HighCurrentTrafficModeStateHelperTest" --tests "cn.niuyannet.kaochang.android.services.HighCurrentTrafficModeSourceTest"
```

Expected: PASS。

### Task 4: 回归、资源检查和提交

**Files:**
- Verify only the files listed above plus this plan/spec.

- [ ] **Step 1: 检查 diff 范围和资源使用**

Run:

```powershell
git status --short
git diff --check
git diff --stat
git grep -n -E "Thread\(|Timer\(|Executor|Mutex|delay\(|while \(true\)" -- app/src/main/java/cn/niuyannet/kaochang/android/services/HighToLowTimerState.kt app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt
```

Expected: 无空白错误；本切片未新增线程、Timer、Executor、Mutex、delay 或无限循环。

- [ ] **Step 2: 运行全量测试**

Run: `./gradlew.bat test --no-daemon`

Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 精确暂存并提交切片 A**

只暂存计划列出的文件，确认 `git diff --cached --name-only` 后提交：

```powershell
git commit -m "fix(controller): 修正高转低计时起点与重启恢复"
```

- [ ] **Step 4: 交审核者 A 只读复核**

发送 commit SHA、`git show --stat`、定向测试和全量测试结果。审核通过前不得开始切片 B。
