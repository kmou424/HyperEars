package dev.hyperears.presentation

import android.bluetooth.BluetoothDevice
import android.content.Context
import dev.hyperears.hook.HookContext
import dev.hyperears.hook.ModuleLog
import dev.hyperears.hook.maskBluetoothAddress

/** Reuses the native AirPods connection notification, with a call-scoped read-only state source. */
internal class NativeConnectionHint : HookContext() {
    private val current = ThreadLocal<NativeHeadsetProjection?>()
    private lateinit var notificationType: Class<*>

    override fun install() {
        notificationType = findClass("com.android.bluetooth.ble.app.MiuiBluetoothNotification")
        NativeMembers.method(notificationType, "showConnectToastForAirPods",
            Int::class.javaPrimitiveType!!, BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
        val getter = findMethod("com.android.bluetooth.ble.app.offlinefind.OfflineFindProvider",
            "getAirPodsState", String::class.java, Context::class.java)
        hookBefore(getter) {
            val state = current.get() ?: return@hookBefore
            if (args[0] != state.address) return@hookBefore
            // This native getter uses a different ordering from the popup Controller's getter.
            result = arrayOf("false", state.left.percent?.toString() ?: "-",
                "false", state.right.percent?.toString() ?: "-",
                state.chargingCase.percent?.toString() ?: "-", state.left.charging.toString(),
                state.right.charging.toString(), state.chargingCase.charging.toString(), "", "true")
        }
    }

    fun show(projection: NativeHeadsetProjection, device: BluetoothDevice): Boolean {
        // A TWS aggregate stays one real number; the headwear animation would misrepresent its shape.
        if (projection.singleBattery && projection.form == dev.hyperears.integration.HeadsetFormFactor.TWS) return false
        val native = NativeMembers.field(notificationType, "mMiuiBluetoothNotification").get(null) ?: return false
        val previous = current.get()
        current.set(projection)
        return try {
            NativeMembers.method(notificationType, "showConnectToastForAirPods",
                Int::class.javaPrimitiveType!!, BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
                .invoke(native, 2, device, if (projection.singleBattery) 3 else 0)
            ModuleLog.debug("NativeHint", "submitted address=${maskBluetoothAddress(projection.address)}")
            true
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    fun flowOwnsPresentation(device: BluetoothDevice): Boolean {
        val native = NativeMembers.field(notificationType, "mMiuiBluetoothNotification").get(null) ?: return true
        val managers = NativeMembers.call(native, "getConnectManagerList") as? Map<*, *> ?: return false
        val record = managers[device] ?: return false
        return NativeMembers.call(record, "getRequestFlag") == 1 ||
            NativeMembers.call(record, "getIsShowFastConnectDialog") == true ||
            NativeMembers.call(record, "getAlreadyShowFastConnectDiaolog") == true
    }
}
