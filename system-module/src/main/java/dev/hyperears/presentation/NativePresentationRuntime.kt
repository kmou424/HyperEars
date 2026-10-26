package dev.hyperears.presentation

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import dev.hyperears.bridge.BroadcastIdentity
import dev.hyperears.bridge.DeviceStateRegistry
import dev.hyperears.bridge.ModuleContract
import dev.hyperears.bridge.NativePresentationContract
import dev.hyperears.hook.HookContext
import dev.hyperears.hook.ModuleLog
import dev.hyperears.hook.maskBluetoothAddress
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.StandardControlRequest
import dev.hyperears.settings.HeadsetConnectionPresentation
import dev.hyperears.settings.ModuleSettingsRuntime
import java.util.UUID
import java.util.concurrent.Executors

/** One serialized consumer of accepted Adapter state in each native headset host process. */
internal class NativePresentationRuntime(
    private val context: Context,
    private val uiProcess: Boolean,
    private val configure: (HookContext, ClassLoader) -> Unit,
) {
    private data class Record(val state: EarbudState, val session: String,
        val metadata: NativePresentationMetadata, val producer: IBinder)
    private val handler = Handler(Looper.getMainLooper())
    private val registry = DeviceStateRegistry()
    private val records = linkedMapOf<String, Record>()
    private val gate = ConnectionPresentationGate()
    private var producer: IBinder? = null
    private var producerDeath: IBinder.DeathRecipient? = null
    private val retiredProducers = ArrayDeque<IBinder>()
    private var windowBridge: AirPodsWindowBridge? = null
    private var windowAttempted = false
    private var popupCompatible = false
    private var hint: NativeConnectionHint? = null
    private var details: NativeDetailNotification? = null
    private var detailResolutionStarted = false
    private var settings = ModuleSettingsRuntime.current
    private var activeWindow: String? = null
    private var windowLifetime: IBinder? = null
    private var windowDeath: IBinder.DeathRecipient? = null
    private var windowDeadline: Runnable? = null

    init {
        if (!uiProcess) {
            runCatching { NativeConnectionHint().also { configure(it, context.classLoader); it.install() } }
                .onSuccess { hint = it }
                .onFailure { ModuleLog.warn(TAG, "connection hint unavailable", it) }
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching {
                    when (intent.action) {
                        ModuleContract.ACTION_STATE_CHANGED -> {
                            if (BroadcastIdentity.accepts(context, sentFromPackage, sentFromUid,
                                    ModuleContract.BLUETOOTH_PACKAGE)) accept(intent)
                        }
                        NativePresentationContract.ACTION_PREVIEW -> if (!uiProcess &&
                            BroadcastIdentity.accepts(context, sentFromPackage, sentFromUid,
                                ModuleContract.MODULE_PACKAGE)) preview(NativePresentationContract.kind(intent))
                        NativePresentationContract.ACTION_WINDOW -> if (!uiProcess &&
                            BroadcastIdentity.accepts(context, sentFromPackage, sentFromUid,
                                ModuleContract.NATIVE_HEADSET_PACKAGE)) windowChanged(intent)
                        Intent.ACTION_SCREEN_OFF -> windowBridge?.closeAll("screen off")
                        BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            if (device?.address in records) windowBridge?.closeAll("Bluetooth disconnected")
                        }
                    }
                }.onFailure { ModuleLog.warn(TAG, "native presentation event failed", it) }
            }
        }, IntentFilter().apply {
            addAction(ModuleContract.ACTION_STATE_CHANGED)
            addAction(NativePresentationContract.ACTION_PREVIEW)
            addAction(NativePresentationContract.ACTION_WINDOW)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }, Context.RECEIVER_EXPORTED)
        if (!uiProcess) context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    ?.address?.let { runCatching { details?.dismiss(it) } }
            }
        }, IntentFilter(NativeDetailNotification.ACTION_DISMISSED), Context.RECEIVER_NOT_EXPORTED)
        ModuleSettingsRuntime.observe { updated -> handler.post {
            val old = settings
            settings = updated
            if (updated.modulePaused || old.headsetConnectionPresentation != updated.headsetConnectionPresentation) {
                windowBridge?.closeAll("settings changed")
            }
            if (updated.modulePaused || !updated.headsetDetailNotification) details?.clear()
            prepareDetails()
            records.values.toList().forEach { render(it, allowTransient = false) }
        } }
        BroadcastIdentity.send(context, ModuleContract.requestState(ModuleContract.NATIVE_HEADSET_PACKAGE))
        ModuleLog.debug(TAG, "receiver ready ui=$uiProcess")
    }

    // A feature-plugin class in the explicitly scoped Xiaomi host, not an Android framework API.
    @SuppressLint("PrivateApi")
    fun pluginReady(loader: ClassLoader) {
        if (windowAttempted) return
        if (runCatching { Class.forName(AirPodsWindowBridge.CONTROLLER, false, loader) }.isFailure) return
        windowAttempted = true
        popupCompatible = runCatching { AirPodsWindowSymbols(loader) }.isSuccess
        if (!popupCompatible) {
            ModuleLog.debug(TAG, "popup contract unavailable for loaded plugin")
            return
        }
        if (!uiProcess) {
            records.values.toList().forEach { render(it, allowTransient = true) }
            return
        }
        runCatching {
            AirPodsWindowBridge(context,
                onOpened = { id, token -> sendWindowEvent(id, token) },
                onClosed = { id -> sendWindowEvent(id, null) },
            ).also { configure(it, loader); it.install() }
        }.onSuccess { windowBridge = it }
            .onFailure { ModuleLog.warn(TAG, "native popup unavailable", it) }
    }

    private fun sendWindowEvent(id: String, token: IBinder?) {
        BroadcastIdentity.send(context, Intent(NativePresentationContract.ACTION_WINDOW)
            .setPackage(ModuleContract.NATIVE_HEADSET_PACKAGE)
            .putExtra(NativePresentationContract.EXTRA_WINDOW, id)
            .putExtras(Bundle().apply { putBinder("lifetime", token) }))
    }

    private fun windowChanged(intent: Intent) {
        if (intent.getStringExtra(NativePresentationContract.EXTRA_WINDOW) != activeWindow) return
        windowDeadline?.let(handler::removeCallbacks)
        windowDeadline = null
        val token = intent.extras?.getBinder("lifetime")
        if (token == null) { releaseWindow(); return }
        windowDeath?.let { previous -> windowLifetime?.unlinkToDeath(previous, 0) }
        val death = IBinder.DeathRecipient { handler.post { releaseWindow() } }
        windowLifetime = token
        windowDeath = death
        runCatching { token.linkToDeath(death, 0) }.onFailure { releaseWindow() }
    }

    private fun releaseWindow() {
        windowDeadline?.let(handler::removeCallbacks)
        windowDeath?.let { runCatching { windowLifetime?.unlinkToDeath(it, 0) } }
        windowDeadline = null
        windowDeath = null
        windowLifetime = null
        activeWindow = null
    }

    private fun accept(intent: Intent) {
        val nextProducer = NativePresentationContract.producer(intent) ?: return
        val metadata = NativePresentationContract.metadata(intent) ?: return
        val state = with(ModuleContract) { intent.readState() } ?: return
        val token = with(ModuleContract) { intent.readSessionToken() } ?: return
        if (nextProducer != producer) {
            if (!nextProducer.isBinderAlive || nextProducer in retiredProducers) return
            val death = IBinder.DeathRecipient { handler.post {
                if (producer == nextProducer) clear("producer died")
            } }
            if (runCatching { nextProducer.linkToDeath(death, 0) }.isFailure) return
            producerDeath?.let { runCatching { producer?.unlinkToDeath(it, 0) } }
            producer?.let { retiredProducers.addLast(it) }
            while (retiredProducers.size > 16) retiredProducers.removeFirst()
            clear("producer changed")
            producer = nextProducer
            producerDeath = death
        }
        val accepted = registry.accept(state, token) ?: return
        val address = accepted.address ?: return
        windowBridge?.update(accepted, token, metadata, nextProducer)
        if (!accepted.sessionActive || metadata.admission == NativeAdmission.SYSTEM) {
            records.remove(address)
            details?.remove(address, metadata.admission == NativeAdmission.SYSTEM)
            // Retain the consumed connection through logical session replacement.
            return
        }
        val record = Record(accepted, token, metadata, nextProducer)
        records[address] = record
        render(record, allowTransient = true)
    }

    private fun clear(reason: String) {
        windowBridge?.closeAll(reason)
        details?.clear()
        records.clear()
        registry.clear()
        gate.clear()
        releaseWindow()
    }

    private fun prepareDetails() {
        if (uiProcess || !settings.headsetDetailNotification || detailResolutionStarted) return
        detailResolutionStarted = true
        val executor = Executors.newSingleThreadExecutor { Thread(it, "HyperEars-native-symbols") }
        executor.execute {
            val result = runCatching { NativeDetailNotificationResolver.resolve(
                context.applicationInfo.sourceDir, context.classLoader) }
            handler.post {
                result.onSuccess { methods ->
                    runCatching { NativeDetailNotification(context, methods).also {
                        configure(it, context.classLoader); it.install()
                    } }.onSuccess {
                        details = it
                        records.values.toList().forEach { record -> render(record, false) }
                    }.onFailure { ModuleLog.warn(TAG, "detail notification unavailable", it) }
                }.onFailure { ModuleLog.warn(TAG, "detail notification fingerprint unavailable", it) }
            }
            executor.shutdown()
        }
    }

    @SuppressLint("MissingPermission")
    private fun render(record: Record, allowTransient: Boolean) {
        if (uiProcess || settings.modulePaused) return
        val p = NativeHeadsetProjection.from(record.state) ?: return
        if (record.metadata.replay) gate.consume(record.metadata, SystemClock.elapsedRealtime())
        if (record.metadata.admission != NativeAdmission.HYPEREARS) return
        val device = context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(p.address) ?: return
        if (settings.headsetDetailNotification) details?.update(p, device, record.metadata.connection.id)
        if (!allowTransient || settings.headsetConnectionPresentation == HeadsetConnectionPresentation.OFF) return
        if (settings.headsetConnectionPresentation == HeadsetConnectionPresentation.BATTERY_POPUP && !popupCompatible) return
        if (gate.consume(record.metadata, SystemClock.elapsedRealtime())) {
            present(record, settings.headsetConnectionPresentation)
        }
    }

    private fun preview(kind: String?) {
        val record = records.values.lastOrNull { it.metadata.admission == NativeAdmission.HYPEREARS } ?: run {
            ModuleLog.debug(TAG, "preview rejected: no admitted connected headset")
            return
        }
        val mode = when (kind) {
            "hint" -> HeadsetConnectionPresentation.SYSTEM_HINT
            "popup" -> HeadsetConnectionPresentation.BATTERY_POPUP
            else -> return
        }
        present(record, mode)
    }

    @SuppressLint("MissingPermission")
    private fun present(record: Record, mode: HeadsetConnectionPresentation) {
        if (settings.modulePaused) return
        val p = NativeHeadsetProjection.from(record.state) ?: return
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val user = context.getSystemService(UserManager::class.java)
        // Native Activity stores a wall-clock timestamp and a textual owner tag, respectively.
        val busy = Settings.Global.getLong(context.contentResolver, "fast_connect_show_dialog", 0L) != 0L ||
            !Settings.Global.getString(context.contentResolver, "miui_nearby_show_dialog").isNullOrEmpty()
        if (!user.isUserUnlocked || !user.isUserForeground || !power.isInteractive ||
            keyguard.isKeyguardLocked || busy || activeWindow != null) {
            ModuleLog.debug(TAG, "presentation skipped: screen, lock or native window owns display")
            return
        }
        if (Settings.Global.getInt(context.contentResolver, "miui_disable_fast_connect_bt_off", 1) != 0) {
            ModuleLog.debug(TAG, "presentation skipped: native FastConnect disabled")
            return
        }
        val device = context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(p.address) ?: return
        if (BluetoothDevice::class.java.getMethod("isConnected").invoke(device) != true) return
        if (hint?.flowOwnsPresentation(device) != false) {
            ModuleLog.debug(TAG, "presentation skipped: native circulation owns display")
            return
        }
        when (mode) {
            HeadsetConnectionPresentation.OFF -> Unit
            HeadsetConnectionPresentation.SYSTEM_HINT -> hint?.show(p, device)
            HeadsetConnectionPresentation.BATTERY_POPUP -> {
                if (!popupCompatible) {
                    ModuleLog.debug(TAG, "popup unavailable: host contract not ready")
                    return
                }
                val id = UUID.randomUUID().toString()
                activeWindow = id
                windowDeadline = Runnable { if (activeWindow == id && windowLifetime == null) releaseWindow() }
                    .also { handler.postDelayed(it, 5_000) }
                runCatching { NativeWindowRequest.launch(context,
                    NativeWindowRequest(id, record.state, record.session, record.metadata, record.producer), device)
                }.onFailure { releaseWindow(); ModuleLog.warn(TAG, "popup launch failed", it) }
                BroadcastIdentity.send(context,
                    ModuleContract.control(StandardControlRequest.Refresh, p.address, record.session))
                ModuleLog.debug(TAG, "popup submitted address=${maskBluetoothAddress(p.address)}")
            }
        }
    }

    private companion object { const val TAG = "NativePresentation" }
}
