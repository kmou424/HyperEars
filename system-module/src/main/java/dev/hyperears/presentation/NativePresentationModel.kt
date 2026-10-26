package dev.hyperears.presentation

import dev.hyperears.integration.BatteryReading
import dev.hyperears.integration.BatterySource
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.HeadsetArtwork
import dev.hyperears.integration.HeadsetFormFactor

/** Physical connection provenance is separate from a replaceable protocol session. */
data class HeadsetConnection(
    val id: String,
    val connectedAtElapsedMs: Long,
)

enum class NativeAdmission { UNKNOWN, HYPEREARS, SYSTEM }

data class NativePresentationMetadata(
    val connection: HeadsetConnection,
    val admission: NativeAdmission = NativeAdmission.UNKNOWN,
    val replay: Boolean = false,
)

/** A read-only projection. Aggregate values copied into MiLink slots are not component telemetry. */
data class NativeHeadsetProjection(
    val address: String,
    val name: String,
    val form: HeadsetFormFactor,
    val artwork: HeadsetArtwork,
    val singleBattery: Boolean,
    val left: BatteryReading,
    val right: BatteryReading,
    val chargingCase: BatteryReading,
) {
    val hasBattery: Boolean get() = left.available || right.available || chargingCase.available

    companion object {
        fun from(state: EarbudState): NativeHeadsetProjection? {
            val adapter = state.adapter ?: return null
            val address = state.address ?: return null
            if (!state.sessionActive) return null
            val battery = state.battery
            val single = adapter.batterySource == BatterySource.SYSTEM_AGGREGATE ||
                battery.overall.available || adapter.formFactor == HeadsetFormFactor.HEADPHONES
            val unknown = BatteryReading(null, false)
            return NativeHeadsetProjection(
                address = address,
                name = state.deviceName?.takeIf(String::isNotBlank) ?: adapter.displayName,
                form = adapter.formFactor,
                artwork = adapter.artwork,
                singleBattery = single,
                left = if (single) battery.overall else battery.left,
                right = if (single) unknown else battery.right,
                chargingCase = if (single) unknown else battery.case,
            )
        }
    }
}

/** No timers or IO: the host calls this only for accepted events and capability changes. */
internal class ConnectionPresentationGate(private val freshnessMs: Long = 10_000) {
    private val consumed = mutableMapOf<String, Long>()

    fun consume(metadata: NativePresentationMetadata, now: Long): Boolean {
        consumed.entries.removeAll { now - it.value > freshnessMs }
        if (metadata.replay) {
            consumed[metadata.connection.id] = metadata.connection.connectedAtElapsedMs
            return false
        }
        if (metadata.admission != NativeAdmission.HYPEREARS) return false
        if (now - metadata.connection.connectedAtElapsedMs !in 0..freshnessMs) return false
        return consumed.putIfAbsent(metadata.connection.id, metadata.connection.connectedAtElapsedMs) == null
    }

    fun retire(id: String) { consumed.remove(id) }
    fun clear() { consumed.clear() }
}
