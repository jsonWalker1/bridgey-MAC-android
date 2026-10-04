package dev.bridgey.android

// PER-DEVICE TELEMETRY (MD-4c)
//
// Telemetry is device state: every value belongs to the peer that sent it. Three small pieces:
//   DeviceTelemetry / telemetry map   deviceId -> the latest values that peer sent us (display side)
//   TelemetrySubscription             which peer this device is displaying (and has subscribed to)
//   TelemetrySubscribers              which peers are displaying THIS device (publish side), with the
//                                     storage/memory dead-band kept per subscriber
// The wire is unchanged: battery.update, telemetry.update, telemetry.subscribe/unsubscribe.

/** The latest telemetry one peer sent us. Absent values were never received (or were cleared). */
data class DeviceTelemetry(
    val battery: RemoteBatteryStatus? = null,
    val storage: RemoteStorageStatus? = null,
    val memory: RemoteMemoryStatus? = null,
    val cpu: RemoteCpuStatus? = null,
    val temperature: RemoteTemperatureStatus? = null,
) {
    val isEmpty: Boolean get() = this == DeviceTelemetry()

    fun without(metric: TelemetryMetric): DeviceTelemetry = when (metric) {
        TelemetryMetric.BATTERY -> copy(battery = null)
        TelemetryMetric.STORAGE -> copy(storage = null)
        TelemetryMetric.MEMORY -> copy(memory = null)
        TelemetryMetric.CPU -> copy(cpu = null)
        TelemetryMetric.TEMPERATURE -> copy(temperature = null)
    }
}

enum class TelemetryMetric(val feature: BridgeyFeature) {
    BATTERY(BridgeyFeature.BATTERY),
    STORAGE(BridgeyFeature.STORAGE),
    MEMORY(BridgeyFeature.MEMORY),
    CPU(BridgeyFeature.CPU),
    TEMPERATURE(BridgeyFeature.TEMPERATURE),
}

/** Pure transitions of the deviceId -> telemetry map (held in a StateFlow by the coordinator). */
internal object DeviceTelemetryStore {
    /** Changes only [deviceId]'s values. */
    fun update(map: Map<String, DeviceTelemetry>, deviceId: String, change: (DeviceTelemetry) -> DeviceTelemetry): Map<String, DeviceTelemetry> {
        val next = change(map[deviceId] ?: DeviceTelemetry())
        return if (next.isEmpty) map - deviceId else map + (deviceId to next)
    }

    /** The peer's session ended: its values are gone; other peers are untouched. */
    fun remove(map: Map<String, DeviceTelemetry>, deviceId: String): Map<String, DeviceTelemetry> = map - deviceId

    /** Drops every metric [isAllowed] no longer allows. */
    fun prune(map: Map<String, DeviceTelemetry>, isAllowed: (String, TelemetryMetric) -> Boolean): Map<String, DeviceTelemetry> =
        map.mapValues { (deviceId, values) ->
            TelemetryMetric.entries.fold(values) { acc, metric -> if (isAllowed(deviceId, metric)) acc else acc.without(metric) }
        }.filterValues { !it.isEmpty }
}

/** Display side: subscribe to exactly the peer the UI shows, only while it is shown. Thread-safe. */
internal class TelemetrySubscription {
    sealed interface Change {
        data class Subscribe(val deviceId: String) : Change
        data class Unsubscribe(val deviceId: String) : Change
    }

    /** The peer the UI wants to see (null = app not visible). */
    @Volatile var desiredDeviceId: String? = null
        private set
    /** The peer we actually sent telemetry.subscribe to. */
    @Volatile var subscribedDeviceId: String? = null
        private set

    /** The UI now shows [deviceId] (or nothing). Returns the messages to send. */
    @Synchronized
    fun show(deviceId: String?, isConnected: (String) -> Boolean): List<Change> {
        desiredDeviceId = deviceId
        val changes = mutableListOf<Change>()
        subscribedDeviceId?.takeIf { it != deviceId }?.let {
            changes += Change.Unsubscribe(it)
            subscribedDeviceId = null
        }
        if (deviceId != null && subscribedDeviceId == null && isConnected(deviceId)) {
            changes += Change.Subscribe(deviceId)
            subscribedDeviceId = deviceId
        }
        return changes
    }

    /** A peer's session started: resubscribe if the UI is showing it. */
    @Synchronized
    fun sessionStarted(deviceId: String): List<Change> {
        if (desiredDeviceId != deviceId || subscribedDeviceId == deviceId) return emptyList()
        subscribedDeviceId = deviceId
        return listOf(Change.Subscribe(deviceId))
    }

    /** A peer's session ended: that subscription is gone with it (the UI intent stays). */
    @Synchronized
    fun sessionEnded(deviceId: String) {
        if (subscribedDeviceId == deviceId) subscribedDeviceId = null
    }
}

/**
 * Publish side: the peers currently displaying this device, plus the storage/memory values last
 * sent to each (the 100 MiB dead-band is per subscriber, so a new subscriber always gets values).
 * Thread-safe.
 */
internal class TelemetrySubscribers {
    private val subscribers = mutableSetOf<String>()
    private val lastStorage = mutableMapOf<String, LocalStorageStatus>()
    private val lastMemory = mutableMapOf<String, LocalMemoryStatus>()

    @Synchronized fun deviceIds(): Set<String> = subscribers.toSet()
    @Synchronized fun isEmpty(): Boolean = subscribers.isEmpty()

    /** True when this is the first subscriber (the sampling loop must start). */
    @Synchronized
    fun add(deviceId: String): Boolean {
        val wasEmpty = subscribers.isEmpty()
        subscribers += deviceId
        lastStorage -= deviceId
        lastMemory -= deviceId
        return wasEmpty
    }

    /** True when no subscriber remains (the sampling loop must stop). */
    @Synchronized
    fun remove(deviceId: String): Boolean {
        val removed = subscribers.remove(deviceId)
        lastStorage -= deviceId
        lastMemory -= deviceId
        return removed && subscribers.isEmpty()
    }

    /** The subscriber's grant/capability changed: its next storage/memory values go out regardless. */
    @Synchronized
    fun resetDeadBand(deviceId: String) {
        lastStorage -= deviceId
        lastMemory -= deviceId
    }

    @Synchronized
    fun reset() {
        subscribers.clear()
        lastStorage.clear()
        lastMemory.clear()
    }

    @Synchronized
    fun shouldSend(storage: LocalStorageStatus, to: String): Boolean =
        changed(storage.usedBytes, storage.totalBytes, lastStorage[to]?.let { it.usedBytes to it.totalBytes })

    @Synchronized
    fun shouldSend(memory: LocalMemoryStatus, to: String): Boolean =
        changed(memory.usedBytes, memory.totalBytes, lastMemory[to]?.let { it.usedBytes to it.totalBytes })

    @Synchronized fun sent(storage: LocalStorageStatus, to: String) { lastStorage[to] = storage }
    @Synchronized fun sent(memory: LocalMemoryStatus, to: String) { lastMemory[to] = memory }

    private fun changed(used: Long, total: Long, previous: Pair<Long, Long>?): Boolean =
        previous == null || total != previous.second || kotlin.math.abs(used - previous.first) >= CHANGE_THRESHOLD_BYTES

    companion object {
        const val CHANGE_THRESHOLD_BYTES = 100L * 1024 * 1024
    }
}
