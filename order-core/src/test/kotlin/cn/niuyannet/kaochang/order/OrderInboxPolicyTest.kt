package cn.niuyannet.kaochang.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class OrderInboxPolicyTest {

    @Test
    fun `legacy draft is normalized without truncating Long ids`() {
        val result = validate(
            draft(
                items = listOf(
                    item(
                        itemId = Long.MAX_VALUE.toString(),
                        productId = "0002147483648",
                        quantity = "2"
                    ),
                    item(itemId = "42", productId = "99", tasteCode = "spicy", quantity = "1")
                )
            )
        )

        val record = result.requireValid()
        assertEquals(Long.MAX_VALUE.toString(), record.items[0].itemId)
        assertEquals("2147483648", record.items[0].productId)
        assertEquals(1, record.items[0].firstDeliveryOrdinal)
        assertEquals(3, record.items[1].firstDeliveryOrdinal)
        assertEquals(OrderAdmissionStage.PERSISTED_PENDING_PREPARATION, record.admissionStage)
        assertEquals(OrderLifecycleStage.ACCEPTED_DURABLE, record.lifecycleStage)
        assertEquals(OrderClaimReleaseEligibility.NOT_APPLICABLE, record.claimReleaseEligibility)
        assertNull(record.wholeOrderOutcome)
        assertTrue(record.canonicalPayloadSha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `missing and inconsistent envelope fields are rejected with stable codes`() {
        val cases = listOf(
            draft(protocolKind = null) to OrderInboxValidationCode.PROTOCOL_KIND_MISSING,
            draft(orderNo = null) to OrderInboxValidationCode.ORDER_NO_MISSING,
            draft(orderNo = " ") to OrderInboxValidationCode.ORDER_NO_MISSING,
            draft(deviceId = null) to OrderInboxValidationCode.DEVICE_ID_MISSING,
            draft(deviceId = "other-device") to OrderInboxValidationCode.DEVICE_ID_MISMATCH,
            draft(receivedAtMs = null) to OrderInboxValidationCode.RECEIVED_AT_MISSING,
            draft(items = null) to OrderInboxValidationCode.ITEMS_MISSING,
            draft(items = emptyList()) to OrderInboxValidationCode.ITEMS_EMPTY
        )

        cases.forEach { (input, expectedCode) ->
            assertTrue(
                "expected $expectedCode for $input",
                validate(input).errorCodes().contains(expectedCode)
            )
        }
    }

    @Test
    fun `quantity validation is bounded and cannot overflow`() {
        val cases = listOf(
            "0" to OrderInboxValidationCode.ITEM_QUANTITY_NOT_POSITIVE,
            "-1" to OrderInboxValidationCode.ITEM_QUANTITY_NOT_POSITIVE,
            "5" to OrderInboxValidationCode.ITEM_QUANTITY_EXCEEDS_ORDER_LIMIT,
            "999999999999999999999999999999999999999999" to
                OrderInboxValidationCode.ITEM_QUANTITY_EXCEEDS_ORDER_LIMIT
        )

        cases.forEach { (quantity, expectedCode) ->
            val result = validate(draft(items = listOf(item(quantity = quantity))))
            assertTrue(result.errorCodes().contains(expectedCode))
        }

        val totalTooLarge = validate(
            draft(items = listOf(item(itemId = "1", quantity = "3"), item(itemId = "2", quantity = "2")))
        )
        assertTrue(totalTooLarge.errorCodes().contains(OrderInboxValidationCode.TOTAL_QUANTITY_OUT_OF_RANGE))

        val tooManyItems = validate(
            draft(items = (1..5).map { index -> item(itemId = index.toString()) })
        )
        assertTrue(
            tooManyItems.errorCodes().contains(OrderInboxValidationCode.ITEM_COUNT_EXCEEDS_ORDER_LIMIT)
        )
    }

    @Test
    fun `ids and item fields are strict and indexed`() {
        val invalid = validate(
            draft(
                items = listOf(
                    item(itemId = "0"),
                    item(itemId = "9223372036854775808", productId = "abc", tasteCode = " ")
                )
            )
        ).requireInvalid()

        assertTrue(invalid.errors.contains(OrderInboxValidationError(OrderInboxValidationCode.ITEM_ID_NOT_POSITIVE, 0)))
        assertTrue(invalid.errors.contains(OrderInboxValidationError(OrderInboxValidationCode.ITEM_ID_OUT_OF_LONG_RANGE, 1)))
        assertTrue(invalid.errors.contains(OrderInboxValidationError(OrderInboxValidationCode.PRODUCT_ID_INVALID_DECIMAL, 1)))
        assertTrue(invalid.errors.contains(OrderInboxValidationError(OrderInboxValidationCode.TASTE_CODE_MISSING, 1)))

        val veryLongDigits = "9".repeat(100_000)
        val oversized = validate(
            draft(items = listOf(item(itemId = veryLongDigits, productId = veryLongDigits)))
        )
        assertTrue(oversized.errorCodes().contains(OrderInboxValidationCode.ITEM_ID_OUT_OF_LONG_RANGE))
        assertTrue(oversized.errorCodes().contains(OrderInboxValidationCode.PRODUCT_ID_OUT_OF_LONG_RANGE))
    }

    @Test
    fun `v2 requires complete protocol metadata while legacy rejects it`() {
        val missingV2 = validate(draft(protocolKind = OrderProtocolKind.DISPATCH_V2))
        assertEquals(
            setOf(
                OrderInboxValidationCode.DISPATCH_ID_MISSING,
                OrderInboxValidationCode.ORDER_VERSION_MISSING,
                OrderInboxValidationCode.EXPIRES_AT_MISSING
            ),
            missingV2.errorCodes().filter {
                it in setOf(
                    OrderInboxValidationCode.DISPATCH_ID_MISSING,
                    OrderInboxValidationCode.ORDER_VERSION_MISSING,
                    OrderInboxValidationCode.EXPIRES_AT_MISSING
                )
            }.toSet()
        )

        val validV2 = validate(
            draft(
                protocolKind = OrderProtocolKind.DISPATCH_V2,
                dispatchId = "dispatch-001",
                orderVersion = "0002",
                expiresAtMs = "2000000000000"
            )
        ).requireValid()
        assertEquals("dispatch-001", validV2.dispatchId)
        assertEquals("2", validV2.orderVersion)
        assertEquals("2000000000000", validV2.expiresAtMs)

        val legacyWithV2Fields = validate(
            draft(dispatchId = "dispatch-001", orderVersion = "1", expiresAtMs = "2")
        )
        assertTrue(legacyWithV2Fields.errorCodes().contains(OrderInboxValidationCode.LEGACY_HAS_V2_METADATA))
    }

    @Test
    fun `v2 metadata and text-time boundaries are fail closed`() {
        val invalidVersions = listOf(
            "abc" to OrderInboxValidationCode.ORDER_VERSION_INVALID_DECIMAL,
            "0" to OrderInboxValidationCode.ORDER_VERSION_NOT_POSITIVE,
            "-1" to OrderInboxValidationCode.ORDER_VERSION_NOT_POSITIVE,
            "9223372036854775808" to OrderInboxValidationCode.ORDER_VERSION_OUT_OF_LONG_RANGE
        )
        invalidVersions.forEach { (value, code) ->
            assertTrue(validate(v2Draft().copy(orderVersion = value)).errorCodes().contains(code))
        }

        val invalidExpiries = listOf(
            "abc" to OrderInboxValidationCode.EXPIRES_AT_INVALID_DECIMAL,
            "0" to OrderInboxValidationCode.EXPIRES_AT_NOT_POSITIVE,
            "-1" to OrderInboxValidationCode.EXPIRES_AT_NOT_POSITIVE,
            "9223372036854775808" to OrderInboxValidationCode.EXPIRES_AT_OUT_OF_LONG_RANGE
        )
        invalidExpiries.forEach { (value, code) ->
            assertTrue(validate(v2Draft().copy(expiresAtMs = value)).errorCodes().contains(code))
        }

        assertTrue(
            validate(v2Draft().copy(dispatchId = "d".repeat(129))).errorCodes()
                .contains(OrderInboxValidationCode.DISPATCH_ID_TOO_LONG)
        )
        assertTrue(
            validate(draft(orderNo = "o".repeat(129))).errorCodes()
                .contains(OrderInboxValidationCode.ORDER_NO_TOO_LONG)
        )
        assertTrue(
            OrderInboxValidator.validate(
                draft(deviceId = "d".repeat(129)),
                expectedDeviceId = "d".repeat(129),
                createdAtMs = 123
            ).errorCodes().contains(OrderInboxValidationCode.DEVICE_ID_TOO_LONG)
        )
        assertTrue(
            validate(draft(items = listOf(item(tasteCode = "t".repeat(65))))).errorCodes()
                .contains(OrderInboxValidationCode.TASTE_CODE_TOO_LONG)
        )
        assertTrue(
            validate(draft(receivedAtMs = -1)).errorCodes()
                .contains(OrderInboxValidationCode.METADATA_TIME_NEGATIVE)
        )
        assertTrue(
            validate(draft(serverDispatchedAtMs = -1)).errorCodes()
                .contains(OrderInboxValidationCode.METADATA_TIME_NEGATIVE)
        )
        assertTrue(
            validate(draft(payTimeMs = -1)).errorCodes()
                .contains(OrderInboxValidationCode.METADATA_TIME_NEGATIVE)
        )

        assertTrue(validate(draft(orderNo = "o".repeat(128))) is OrderInboxValidationResult.Valid)
        assertTrue(validate(draft(items = listOf(item(tasteCode = "t".repeat(64))))) is OrderInboxValidationResult.Valid)
        assertTrue(validate(v2Draft().copy(dispatchId = "d".repeat(128))) is OrderInboxValidationResult.Valid)
    }

    @Test
    fun `canonical hash excludes volatile and display-only fields`() {
        val first = validate(
            draft(
                receivedAtMs = 100,
                serverDispatchedAtMs = 200,
                payTimeMs = 300,
                items = listOf(item(productName = "name-a", productImage = "image-a"))
            )
        ).requireValid()
        val second = validate(
            draft(
                receivedAtMs = 999,
                serverDispatchedAtMs = 888,
                payTimeMs = 777,
                items = listOf(item(productName = "name-b", productImage = "image-b"))
            )
        ).requireValid()

        assertEquals(first.canonicalPayloadSha256, second.canonicalPayloadSha256)
    }

    @Test
    fun `canonical hash changes for every stable execution field`() {
        val base = v2Draft()
        val baseHash = validate(base).requireValid().canonicalPayloadSha256
        val variants: List<Pair<IncomingOrderDraft, String>> = listOf(
            base.copy(orderNo = "order-002") to "device-001",
            base.copy(deviceId = "device-002") to "device-002",
            base.copy(dispatchId = "dispatch-002") to "device-001",
            base.copy(orderVersion = "2") to "device-001",
            base.copy(expiresAtMs = "2000000000001") to "device-001",
            base.copy(items = listOf(item(itemId = "2"), item(itemId = "3", productId = "2"))) to "device-001",
            base.copy(items = listOf(item(productId = "2"), item(itemId = "2", productId = "2"))) to "device-001",
            base.copy(items = listOf(item(tasteCode = "spicy"), item(itemId = "2", productId = "2"))) to "device-001",
            base.copy(items = listOf(item(quantity = "2"), item(itemId = "2", productId = "2", quantity = "1"))) to "device-001",
            base.copy(items = base.items!!.reversed()) to "device-001"
        )

        variants.forEach { (variant, expectedDeviceId) ->
            val record = OrderInboxValidator.validate(variant, expectedDeviceId, 123).requireValid()
            assertNotEquals(baseHash, record.canonicalPayloadSha256)
        }
    }

    @Test
    fun `canonical length prefixes distinguish ambiguous concatenations`() {
        val left = OrderInboxValidator.validate(
            draft(orderNo = "ab", deviceId = "c"),
            expectedDeviceId = "c",
            createdAtMs = 123
        ).requireValid()
        val right = OrderInboxValidator.validate(
            draft(orderNo = "a", deviceId = "bc"),
            expectedDeviceId = "bc",
            createdAtMs = 123
        ).requireValid()

        assertNotEquals(left.canonicalPayloadSha256, right.canonicalPayloadSha256)
    }

    @Test
    fun `admission distinguishes new duplicate conflict busy and terminal`() {
        val incoming = validate(draft()).requireValid()
        val newPlan = OrderInboxAdmissionPolicy.plan(OrderInboxSnapshot.empty(), incoming)
        assertEquals(OrderInboxAdmissionCode.ACCEPT_NEW, newPlan.code)
        assertTrue(newPlan.requiresPersistence)
        assertEquals(incoming, newPlan.snapshotToPersist?.activeRecord)

        val duplicate = OrderInboxAdmissionPolicy.plan(
            OrderInboxSnapshot(snapshotRevision = 1, activeRecord = incoming),
            incoming
        )
        assertEquals(OrderInboxAdmissionCode.DUPLICATE_SAME, duplicate.code)
        assertFalse(duplicate.requiresPersistence)

        val conflicting = validate(draft(items = listOf(item(quantity = "2")))).requireValid()
        assertEquals(
            OrderInboxAdmissionCode.CONFLICT,
            OrderInboxAdmissionPolicy.plan(OrderInboxSnapshot(snapshotRevision = 1, activeRecord = incoming), conflicting).code
        )

        val otherOrder = validate(draft(orderNo = "other-order")).requireValid()
        assertEquals(
            OrderInboxAdmissionCode.BUSY_OTHER_ORDER,
            OrderInboxAdmissionPolicy.plan(OrderInboxSnapshot(snapshotRevision = 1, activeRecord = incoming), otherOrder).code
        )

        val tombstone = terminalTombstone(incoming)
        assertEquals(
            OrderInboxAdmissionCode.DUPLICATE_TERMINAL,
            OrderInboxAdmissionPolicy.plan(
                OrderInboxSnapshot(snapshotRevision = 1, terminalTombstones = listOf(tombstone)),
                incoming
            ).code
        )
        assertEquals(
            OrderInboxAdmissionCode.CONFLICT,
            OrderInboxAdmissionPolicy.plan(
                OrderInboxSnapshot(snapshotRevision = 1, terminalTombstones = listOf(tombstone)),
                conflicting
            ).code
        )
    }

    @Test
    fun `storage failure never authorizes preparation or mechanical work`() {
        val plan = OrderInboxAdmissionPolicy.plan(
            OrderInboxSnapshot.empty(),
            validate(draft()).requireValid()
        )

        val failure = OrderInboxAdmissionPolicy.afterPersistence(plan, persisted = false)
        assertEquals(OrderInboxAdmissionCode.STORAGE_FAILED, failure.code)
        assertEquals(OrderInboxNextAction.NO_ORDER_SIDE_EFFECTS, failure.nextAction)

        val success = OrderInboxAdmissionPolicy.afterPersistence(plan, persisted = true)
        assertEquals(OrderInboxAdmissionCode.ACCEPT_NEW, success.code)
        assertEquals(OrderInboxNextAction.BEGIN_PREPARATION, success.nextAction)
    }

    @Test
    fun `every non-accept admission result has no order side effects`() {
        val incoming = validate(draft()).requireValid()
        val conflict = validate(draft(items = listOf(item(quantity = "2")))).requireValid()
        val other = validate(draft(orderNo = "other-order")).requireValid()
        val tombstone = terminalTombstone(incoming)
        val plans = listOf(
            OrderInboxAdmissionPolicy.plan(OrderInboxSnapshot(snapshotRevision = 1, activeRecord = incoming), incoming),
            OrderInboxAdmissionPolicy.plan(OrderInboxSnapshot(snapshotRevision = 1, activeRecord = incoming), conflict),
            OrderInboxAdmissionPolicy.plan(OrderInboxSnapshot(snapshotRevision = 1, activeRecord = incoming), other),
            OrderInboxAdmissionPolicy.plan(
                OrderInboxSnapshot(snapshotRevision = 1, terminalTombstones = listOf(tombstone)),
                incoming
            )
        )

        assertEquals(
            setOf(
                OrderInboxAdmissionCode.DUPLICATE_SAME,
                OrderInboxAdmissionCode.CONFLICT,
                OrderInboxAdmissionCode.BUSY_OTHER_ORDER,
                OrderInboxAdmissionCode.DUPLICATE_TERMINAL
            ),
            plans.map { it.code }.toSet()
        )
        plans.forEach { plan ->
            assertEquals(
                OrderInboxNextAction.NO_ORDER_SIDE_EFFECTS,
                OrderInboxAdmissionPolicy.afterPersistence(plan, persisted = false).nextAction
            )
        }
    }

    @Test
    fun `active construction and tombstone factory cannot bypass terminal policy`() {
        val accepted = validate(draft()).requireValid()

        assertTrue(
            ActiveOrderRecord::class.java.declaredConstructors
                .filterNot { it.isSynthetic }
                .all { Modifier.isPrivate(it.modifiers) }
        )
        assertTrue(
            ActiveOrderRecord.Companion::class.java.declaredMethods
                .filter { it.name.startsWith("accepted") }
                .all { it.isSynthetic }
        )
        assertEquals(OrderLifecycleStage.ACCEPTED_DURABLE, accepted.lifecycleStage)
        assertThrows(IllegalArgumentException::class.java) {
            OrderTerminalTombstone.fromReleaseAllowedTerminal(accepted)
        }

        listOf(
            WholeOrderOutcome.EVIDENCE_UNKNOWN,
            WholeOrderOutcome.FAILED,
            WholeOrderOutcome.UNKNOWN
        ).forEachIndexed { index, outcome ->
            val heldTerminal = OrderWholeOrderTerminalPolicy.close(
                current = accepted,
                outcome = outcome,
                revision = accepted.recordRevision + 1,
                updatedAtMs = 500L + index
            )
            assertEquals(OrderClaimReleaseEligibility.HOLD_REQUIRED, heldTerminal.claimReleaseEligibility)
            assertThrows(IllegalArgumentException::class.java) {
                OrderTerminalTombstone.fromReleaseAllowedTerminal(heldTerminal)
            }
        }

        val releasedTerminal = OrderWholeOrderTerminalPolicy.close(
            current = accepted,
            outcome = WholeOrderOutcome.SUCCESS,
            revision = accepted.recordRevision + 1,
            updatedAtMs = 600
        )
        assertEquals(
            releasedTerminal.updatedAtMs,
            OrderTerminalTombstone.fromReleaseAllowedTerminal(releasedTerminal).terminalAtMs
        )
    }

    @Test
    fun `records defensively copy collections and validate snapshot evidence`() {
        val record = validate(draft()).requireValid()
        val sourceItems = arrayListOf(record.items.single())
        val copiedRecord = recordWithItems(record, sourceItems)
        sourceItems += OrderItemSnapshot("2", "2", "original", 1, 2)
        assertEquals(1, copiedRecord.items.size)
        assertEquals(1, record.items.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (copiedRecord.items as MutableList).add(copiedRecord.items.single())
        }

        val tombstone = terminalTombstone(copiedRecord)
        val sourceTombstones = arrayListOf(tombstone)
        val snapshot = OrderInboxSnapshot(snapshotRevision = 1, terminalTombstones = sourceTombstones)
        sourceTombstones.clear()
        assertEquals(1, snapshot.terminalTombstones.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.terminalTombstones as MutableList).clear()
        }

        assertThrows(IllegalArgumentException::class.java) {
            OrderInboxSnapshot(
                snapshotRevision = 1,
                activeRecord = record,
                terminalTombstones = listOf(tombstone)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrderInboxSnapshot(
                snapshotRevision = 1,
                terminalTombstones = listOf(tombstone, tombstone)
            )
        }

        val conflictingTombstone = terminalTombstone(
            validate(draft(items = listOf(item(quantity = "2")))).requireValid()
        )
        listOf(
            listOf(tombstone, conflictingTombstone),
            listOf(conflictingTombstone, tombstone)
        ).forEach { ordered ->
            assertThrows(IllegalArgumentException::class.java) {
                OrderInboxSnapshot(snapshotRevision = 1, terminalTombstones = ordered)
            }
        }
    }

    @Test
    fun `record rejects hash mismatch duplicate items and discontinuous ordinals`() {
        val record = validate(draft()).requireValid()
        assertThrows(IllegalArgumentException::class.java) {
            recordWithItems(record, record.items, canonicalHash = "f".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            recordWithItems(
                record,
                listOf(
                    record.items.single(),
                    OrderItemSnapshot("1", "2", "original", 1, 2)
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            recordWithItems(
                record,
                listOf(OrderItemSnapshot("1", "1", "original", 1, 2))
            )
        }

        val duplicateItem = validate(
            draft(items = listOf(item(itemId = "1"), item(itemId = "01")))
        )
        assertTrue(duplicateItem.errorCodes().contains(OrderInboxValidationCode.DUPLICATE_ITEM_ID))
    }

    @Test
    fun `pending record has only explicit ready or hold transitions`() {
        val pending = validate(draft()).requireValid()
        val ready = OrderAdmissionTransitionPolicy.ready(pending, revision = 2, updatedAtMs = 200)
        assertEquals(OrderAdmissionStage.READY_FOR_EXECUTION, ready.admissionStage)
        assertNull(ready.holdReason)

        val held = OrderAdmissionTransitionPolicy.hold(
            pending,
            OrderInboxHoldReason.SUPPLEMENT_BARRIER_PERSIST_FAILED,
            revision = 2,
            updatedAtMs = 201
        )
        assertEquals(OrderAdmissionStage.HOLD_REQUIRED, held.admissionStage)
        assertEquals(OrderInboxHoldReason.SUPPLEMENT_BARRIER_PERSIST_FAILED, held.holdReason)

        assertThrows(IllegalArgumentException::class.java) {
            OrderAdmissionTransitionPolicy.ready(ready, revision = 3, updatedAtMs = 300)
        }
        assertThrows(IllegalArgumentException::class.java) {
            pending.withAdmissionState(
                OrderAdmissionStage.PERSISTED_PENDING_PREPARATION,
                holdReason = null,
                recordRevision = 2,
                updatedAtMs = 200
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            pending.withAdmissionState(
                OrderAdmissionStage.READY_FOR_EXECUTION,
                holdReason = null,
                recordRevision = pending.recordRevision,
                updatedAtMs = 200
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            pending.withAdmissionState(
                OrderAdmissionStage.READY_FOR_EXECUTION,
                holdReason = null,
                recordRevision = 2,
                updatedAtMs = pending.updatedAtMs - 1
            )
        }
    }

    @Test
    fun `whole-order terminal policy requires accepted state and increasing revision`() {
        val accepted = validate(draft()).requireValid()
        val terminal = OrderWholeOrderTerminalPolicy.close(
            accepted,
            outcome = WholeOrderOutcome.SUCCESS,
            revision = accepted.recordRevision + 1,
            updatedAtMs = 500
        )
        assertEquals(OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE, terminal.lifecycleStage)
        assertEquals(OrderClaimReleaseEligibility.RELEASE_ALLOWED, terminal.claimReleaseEligibility)

        assertThrows(IllegalArgumentException::class.java) {
            OrderWholeOrderTerminalPolicy.close(
                accepted,
                outcome = WholeOrderOutcome.SUCCESS,
                revision = accepted.recordRevision,
                updatedAtMs = 500
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrderWholeOrderTerminalPolicy.close(
                accepted,
                outcome = WholeOrderOutcome.SUCCESS,
                revision = accepted.recordRevision - 1,
                updatedAtMs = 500
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrderWholeOrderTerminalPolicy.close(
                accepted,
                outcome = WholeOrderOutcome.SUCCESS,
                revision = accepted.recordRevision + 1,
                updatedAtMs = accepted.updatedAtMs - 1
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrderWholeOrderTerminalPolicy.close(
                terminal,
                outcome = WholeOrderOutcome.SUCCESS,
                revision = terminal.recordRevision + 1,
                updatedAtMs = 600
            )
        }
    }

    @Test
    fun `inbox model has no scheduler strategy state`() {
        val fieldNames = ActiveOrderRecord::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fieldNames.none { it.contains("strategy") || it.contains("scheduler") })
    }

    private fun validate(input: IncomingOrderDraft): OrderInboxValidationResult =
        OrderInboxValidator.validate(input, expectedDeviceId = "device-001", createdAtMs = 123)

    private fun OrderInboxValidationResult.requireValid(): ActiveOrderRecord = when (this) {
        is OrderInboxValidationResult.Valid -> record
        is OrderInboxValidationResult.Invalid -> error("expected valid, errors=$errors")
    }

    private fun OrderInboxValidationResult.requireInvalid(): OrderInboxValidationResult.Invalid = when (this) {
        is OrderInboxValidationResult.Valid -> error("expected invalid, record=$record")
        is OrderInboxValidationResult.Invalid -> this
    }

    private fun OrderInboxValidationResult.errorCodes(): List<OrderInboxValidationCode> =
        requireInvalid().errors.map { it.code }

    private fun terminalTombstone(accepted: ActiveOrderRecord): OrderTerminalTombstone =
        OrderTerminalTombstone.fromReleaseAllowedTerminal(
            OrderWholeOrderTerminalPolicy.close(
                current = accepted,
                outcome = WholeOrderOutcome.SUCCESS,
                revision = accepted.recordRevision + 1,
                updatedAtMs = 500
            )
        )

    private fun recordWithItems(
        record: ActiveOrderRecord,
        items: List<OrderItemSnapshot>,
        canonicalHash: String = record.canonicalPayloadSha256
    ) = ActiveOrderRecord.accepted(
        protocolKind = record.protocolKind,
        orderNo = record.orderNo,
        deviceId = record.deviceId,
        dispatchId = record.dispatchId,
        orderVersion = record.orderVersion,
        expiresAtMs = record.expiresAtMs,
        receivedAtMs = record.receivedAtMs,
        serverDispatchedAtMs = record.serverDispatchedAtMs,
        payTimeMs = record.payTimeMs,
        canonicalPayloadSha256 = canonicalHash,
        items = items,
        createdAtMs = record.createdAtMs
    )

    private fun draft(
        protocolKind: OrderProtocolKind? = OrderProtocolKind.LEGACY_V1,
        orderNo: String? = "order-001",
        deviceId: String? = "device-001",
        dispatchId: String? = null,
        orderVersion: String? = null,
        expiresAtMs: String? = null,
        receivedAtMs: Long? = 100,
        serverDispatchedAtMs: Long? = 90,
        payTimeMs: Long? = 80,
        items: List<IncomingOrderItemDraft>? = listOf(item())
    ) = IncomingOrderDraft(
        protocolKind = protocolKind,
        orderNo = orderNo,
        deviceId = deviceId,
        dispatchId = dispatchId,
        orderVersion = orderVersion,
        expiresAtMs = expiresAtMs,
        receivedAtMs = receivedAtMs,
        serverDispatchedAtMs = serverDispatchedAtMs,
        payTimeMs = payTimeMs,
        items = items
    )

    private fun v2Draft() = draft(
        protocolKind = OrderProtocolKind.DISPATCH_V2,
        dispatchId = "dispatch-001",
        orderVersion = "1",
        expiresAtMs = "2000000000000",
        items = listOf(item(itemId = "1"), item(itemId = "2", productId = "2"))
    )

    private fun item(
        itemId: String? = "1",
        productId: String? = "1",
        tasteCode: String? = "original",
        quantity: String? = "1",
        productName: String? = "display-name",
        productImage: String? = "display-image"
    ) = IncomingOrderItemDraft(
        itemId = itemId,
        productId = productId,
        tasteCode = tasteCode,
        quantity = quantity,
        productName = productName,
        productImage = productImage
    )
}
