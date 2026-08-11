# Order Prefetch Claim Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent a completed order from entering maintenance only because its matching paused double-lane supplement evidence is still waiting to resume.

**Architecture:** Keep the order claim and existing owner/revision release. Remove only the unrelated double-lane-evidence condition from normal order claim release; after release, the existing order-finally path resumes or safely defers the paused supplement. Corrupted or failed supplement resume still enters maintenance, but the completed order is no longer downgraded to unknown.

**Tech Stack:** Kotlin, Android controller state, JUnit source regression tests.

---

### Task 1: Lock the regression

**Files:**
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/ParallelSupplementOrderEntrySourceTest.kt`

- [x] Add a test proving normal order claim release does not require double-lane evidence to be absent.
- [x] Run the focused test and confirm it fails on the current release gate.

### Task 2: Apply the minimum fix

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`

- [x] Remove only `hasBlockingSupplementPrefetchEvidence()` from the order claim release predicate.
- [x] Preserve action-group session, pan-move journal, discard journal, resource-lock, owner, revision, and `moveStatus=2` checks.
- [x] Run the focused test and confirm it passes.

### Task 3: Verify

- [x] Run the related order/supplement tests.
- [x] Run the full Debug unit-test suite.
- [x] Compile Debug Kotlin/Java and run `git diff --check`.
- [x] Report that no APK installation or real-device action was performed.
