# Device Log Quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve diagnostic completeness and reduce repetitive device logging across Android, MQTT, and the Spring backend.

**Architecture:** Preserve the local device log as the authoritative diagnostic source, apply rate limits at the producing state transition, and use structured correlation fields across local and cloud paths. Add bounded offline retry only for critical cloud logs, then store device and receive timestamps separately with bounded backend retention.

**Tech Stack:** Kotlin/Android, JUnit 4, MQTT QoS 1, Java 8/Spring Boot/MyBatis, MySQL 8.

---

### Task 1: Local severity and repeated-state logging

**Files:**
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/utils/RepeatedLogGate.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/utils/RepeatedLogGateTest.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/utils/LogUtils.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/FaultHeatPauseManager.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/utils/LogUtilsTest.kt`

- [ ] Write tests requiring all warning/error entries to be retained and repeated-state gates to emit first/change/interval only.
- [ ] Run targeted tests and verify they fail because the policy is absent.
- [ ] Add the caller-confined gate and wire it only into the two proven spam sources.
- [ ] Run targeted tests and `./gradlew test`.
- [ ] Request independent review and resolve all P1 findings.

### Task 2: Correlated and complete local diagnostics

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/AtomicActionCore.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Modify: relevant action telemetry and logging tests under `app/src/test/java`

- [ ] Write failing tests for correlation fields, complete failure detail, and accurate post-in2 stock wording.
- [ ] Add `sessionId`, `panNo`, `boxNo`, and `orderId` to local communication lines.
- [ ] Remove local fault-detail truncation while keeping bounded structured summaries where required.
- [ ] Clarify that stock was already deducted at in2.
- [ ] Run tests and independent review.

### Task 3: Bounded critical cloud retry

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/mqtt/AppLogCloudReporter.kt`
- Create: a focused bounded critical-log outbox under the existing Android log package
- Test: focused reporter/outbox tests under `app/src/test/java`

- [ ] Write failing tests for bounded persistence, restart recovery, expiry, successful removal, and no coalescing of independent failures.
- [ ] Persist only warning/error cloud entries that fail immediate enqueue.
- [ ] Drain with bounded batches after MQTT reconnect without blocking MQTT callbacks.
- [ ] Run tests and independent review.

### Task 4: Backend event time and retention

**Files:**
- Modify: device log domain, mapper, MQTT ingestion, and admin presentation files in `D:/LeYe/cl-sausage-server`
- Create: one additive SQL migration in `D:/LeYe/cl-sausage-server/sql`
- Create/modify: focused backend tests

- [ ] Write failing tests that distinguish device event time from server receive time.
- [ ] Add nullable device event time with server receive time preserved for legacy devices.
- [ ] Add bounded scheduled deletion using an indexed cutoff and configurable retention days.
- [ ] Compile the backend, build the admin UI if changed, and request independent review.

### Task 5: Final verification

- [ ] Run the complete Android unit suite and backend targeted/full compile checks.
- [ ] Run `git diff --check` in both repositories.
- [ ] Re-audit nullability, lock usage, queue bounds, and SQL index use.
- [ ] Report unverified production MQTT, deployment, and physical-device gates without publishing or deploying.
