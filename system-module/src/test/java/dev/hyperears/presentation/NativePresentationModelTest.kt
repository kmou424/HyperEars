package dev.hyperears.presentation

import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test

class NativePresentationModelTest {
    private fun state(battery: EarbudBattery, privateBattery: Boolean = false) = EarbudState(
        adapter = StandardEarbudAdapter().snapshot().copy(
            batterySource = if (privateBattery) BatterySource.PRIVATE_PROTOCOL else BatterySource.SYSTEM_AGGREGATE,
        ),
        address = "02:00:00:00:00:01",
        lifecycle = DeviceLifecycle(systemProfile = SystemProfileState.CONNECTED),
    ).withFeature(BatteryFeatureState(battery))

    @Test
    fun aggregateNeverBecomesTwoIndependentBatteryReadings() {
        for (privateBattery in listOf(false, true)) {
            val p = requireNotNull(NativeHeadsetProjection.from(state(EarbudBattery.fromAggregate(30), privateBattery)))
            assertTrue(p.singleBattery)
            assertEquals(30, p.left.percent)
            assertNull(p.right.percent)
            assertNull(p.chargingCase.percent)
        }
    }

    @Test
    fun partialTelemetryPreservesZeroChargingAndUnknownComponents() {
        val p = requireNotNull(NativeHeadsetProjection.from(state(EarbudBattery(
            left = BatteryReading(0, true), right = BatteryReading(null, false),
            case = BatteryReading(76, false)), true)))
        assertFalse(p.singleBattery)
        assertEquals(0, p.left.percent)
        assertTrue(p.left.charging)
        assertNull(p.right.percent)
        assertEquals(76, p.chargingCase.percent)
    }

    @Test
    fun protocolAvailabilityDoesNotChangePhysicalConnectionEligibility() {
        val state = state(EarbudBattery.fromAggregate(52)).copy(lifecycle = DeviceLifecycle(
            systemProfile = SystemProfileState.CONNECTED,
            privateTransport = PrivateTransportState.DORMANT,
            protocolHandshake = ProtocolHandshakeState.PENDING,
        ))
        assertFalse(state.connected)
        assertNotNull(NativeHeadsetProjection.from(state))
        assertNull(NativeHeadsetProjection.from(state.copy(lifecycle = DeviceLifecycle())))
    }

    @Test
    fun replayReconnectAndUnknownOwnershipCannotGenerateDuplicateHints() {
        val gate = ConnectionPresentationGate()
        val connected = HeadsetConnection("physical-1", 100)
        val pending = NativePresentationMetadata(connected)
        assertFalse(gate.consume(pending, 150))
        val admitted = pending.copy(admission = NativeAdmission.HYPEREARS)
        assertTrue(gate.consume(admitted, 200))
        assertFalse(gate.consume(admitted, 300))
        assertFalse(gate.consume(admitted.copy(admission = NativeAdmission.SYSTEM), 300))
        assertFalse(gate.consume(admitted.copy(connection = HeadsetConnection("replay", 100), replay = true), 400))
        assertFalse(gate.consume(admitted.copy(connection = HeadsetConnection("replay", 100)), 500))
        assertTrue(gate.consume(admitted.copy(connection = HeadsetConnection("physical-2", 400)), 500))
        assertFalse(gate.consume(admitted.copy(connection = HeadsetConnection("old", 0)), 20_000))
    }
}
