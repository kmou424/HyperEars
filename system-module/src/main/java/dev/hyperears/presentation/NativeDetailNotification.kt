package dev.hyperears.presentation

import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import dev.hyperears.bridge.ModuleContract
import dev.hyperears.bridge.BluetoothDeviceSettings
import dev.hyperears.hook.HookContext
import dev.hyperears.hook.ModuleLog
import java.util.concurrent.ConcurrentHashMap

/** Original notification builder/actions, scoped data projection and exact instance cleanup. */
internal class NativeDetailNotification(
    private val context: Context,
    private val methods: NativeDetailNotificationResolver.Methods,
) : HookContext() {
    private data class Entry(val device: BluetoothDevice, val projection: NativeHeadsetProjection,
        val connection: String, val postedAt: Long)
    private val current = ThreadLocal<NativeHeadsetProjection?>()
    private val entries = ConcurrentHashMap<String, Entry>()
    private val dismissed = ConcurrentHashMap<String, String>()
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override fun install() {
        hookBefore(methods.support) {
            if (current.get()?.address == args[1]) result = true
        }
        hookBefore(methods.notificationType) {
            current.get()?.takeIf { it.singleBattery }?.let { result = "singlebatteryheadset" }
        }
        hookBefore(methods.deleteIntent) {
            val p = current.get() ?: return@hookBefore
            if ((args[1] as? BluetoothDevice)?.address != p.address) return@hookBefore
            result = PendingIntent.getBroadcast(context, 0,
                Intent(ACTION_DISMISSED).setPackage(ModuleContract.NATIVE_HEADSET_PACKAGE)
                    .setIdentifier("HyperEars:${p.address}").putExtra(BluetoothDevice.EXTRA_DEVICE, args[1] as BluetoothDevice),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val click = findMethod("com.android.bluetooth.ble.app.headset.BluetoothHeadsetService\$HeadSetReceiver",
            "onReceive", Context::class.java, Intent::class.java)
        hookBefore(click) {
            val intent = args[1] as? Intent ?: return@hookBefore
            if (intent.action != "com.android.bluetooth.headset.click.detail_notification") return@hookBefore
            val address = intent.getStringExtra("bluetoothaddress") ?: return@hookBefore
            if (!entries.containsKey(address)) return@hookBefore
            BluetoothDeviceSettings.open(context, address)
            result = null
        }
    }

    fun update(p: NativeHeadsetProjection, device: BluetoothDevice, connection: String) {
        if (!p.hasBattery || dismissed[p.address] == connection) return
        val old = entries[p.address]
        if (old?.projection == p && old.connection == connection) return
        val existing = notification(p.address)
        if (existing != null && (old == null || existing.postTime != old.postedAt)) return
        withProjection(p) {
            val values = if (p.singleBattery) intArrayOf(127, p.left.percent ?: 127, 127)
            else intArrayOf(p.left.percent ?: 127, p.right.percent ?: 127, p.chargingCase.percent ?: 127)
            methods.create.invoke(null, context, device, values, true, false)
        }
        val posted = notification(p.address) ?: return
        entries[p.address] = Entry(device, p, connection, posted.postTime)
        ModuleLog.debug("NativeDetail", "published revision of native battery notification")
    }

    fun dismiss(address: String) {
        entries[address]?.let { dismissed[address] = it.connection }
        remove(address, systemOwned = false)
    }

    fun remove(address: String, systemOwned: Boolean) {
        val entry = entries.remove(address) ?: return
        // A platform ownership claim may already have replaced the same native notification.
        if (systemOwned) return
        val visible = notification(address)
        if (visible != null && visible.postTime != entry.postedAt) return
        withProjection(entry.projection) { methods.cancel.invoke(null, context, entry.device) }
        // Preserve the native channel and the user's per-device notification preferences.
    }

    fun clear() {
        entries.keys.toList().forEach { remove(it, false) }
        dismissed.clear()
    }

    private fun notification(address: String) = notifications.activeNotifications
        .singleOrNull { it.tag == tag(address) && it.id == NOTIFICATION_ID }

    private fun withProjection(p: NativeHeadsetProjection, action: () -> Unit) {
        val previous = current.get()
        current.set(p)
        try { action() } finally { if (previous == null) current.remove() else current.set(previous) }
    }

    companion object {
        const val ACTION_DISMISSED = "dev.hyperears.action.NATIVE_NOTIFICATION_DISMISSED"
        private const val NOTIFICATION_ID = 10003
        private fun tag(address: String) = "BTHeadset$address"
    }
}
