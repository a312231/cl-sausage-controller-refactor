# Device Log Quality Design

## Goal

Keep complete fault evidence while reducing repeated polling logs, MQTT traffic, and unbounded backend storage growth.

## Scope

1. Android local logs always retain warning and error entries. Informational and debug entries continue to use the existing allowlist.
2. Repeated fault-pause and maintenance-idle polling logs are emitted on first observation, meaningful state change, terminal outcome, or a 30-minute fault summary.
3. Local action communication lines include the existing action telemetry correlation fields. Fault details remain complete in remote packages; compact summaries are used only for bounded UI/database fields.
4. Critical warning/error cloud logs use bounded disk-backed retry. Repeated state summaries are rate limited, while independent action failures are never coalesced.
5. The backend stores device event time separately from server receive time and removes expired realtime log rows in bounded batches.

## Constraints

- Do not change mechanical execution, fault recovery, inventory, or ordering behavior.
- Do not add business locks or database transactions for logging changes.
- Any in-memory log gate is caller-confined or uses an existing synchronization boundary.
- Offline retry storage is bounded by count, bytes, and age.
- Remote package request security, ZIP validation, SHA-256 validation, single-device concurrency, and seven-day package retention remain unchanged.

## Verification

Each batch uses test-first implementation, targeted tests, module compilation, and an independent Codex review before the next batch begins. Hardware and production MQTT behavior remain separate release gates.
