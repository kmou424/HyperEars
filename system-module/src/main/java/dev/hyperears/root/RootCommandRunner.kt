package dev.hyperears.root

import dev.hyperears.bridge.ModuleContract
import dev.hyperears.integration.ControlAppCatalog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

enum class RootAction(
    val title: String,
    val detail: String,
    val command: String,
    val verificationCommand: String,
    val verificationSuccess: (String) -> Boolean,
) {
    RESTART_MILINK(
        title = "重启 MiLink",
        detail = "停止 MiLink；再次打开融合设备中心时由系统启动。",
        command = "am force-stop com.milink.service",
        verificationCommand = "pidof com.milink.service || true",
        verificationSuccess = String::isBlank,
    ),
    RESTART_BLUETOOTH(
        title = "重启蓝牙",
        detail = "重启蓝牙及系统耳机服务，已连接设备会暂时断开。",
        command = BluetoothRootCommands.restart,
        verificationCommand = "settings get global bluetooth_on",
        verificationSuccess = { it.trim() == "1" },
    ),
    STOP_VENDOR_APPS(
        title = "停止厂商应用",
        detail = "停止受支持的厂商应用，并恢复 HyperEars 控制。",
        command = VendorAppRootCommands.stop,
        verificationCommand = VendorAppRootCommands.verifyStopped,
        verificationSuccess = { it.isBlank() },
    ),
}

/** Reloads both Bluetooth hosts; switching the radio alone can leave an old module process alive. */
internal object BluetoothRootCommands {
    private val hosts = listOf(ModuleContract.BLUETOOTH_PACKAGE, ModuleContract.NATIVE_HEADSET_PACKAGE)
    private val processNames = (hosts + "${ModuleContract.NATIVE_HEADSET_PACKAGE}:ui")
        .joinToString(" ") { "'$it'" }

    val restart: String = buildList {
        add("restart_status=0")
        add("previous_pids=\$(pidof $processNames 2>/dev/null || true)")
        add("svc bluetooth disable || restart_status=1")
        add("sleep 1")
        for (host in hosts) {
            add("if pm path '$host' >/dev/null 2>&1; then am force-stop '$host' || restart_status=1; fi")
        }
        // Always request restoration, including when stopping an optional host failed.
        add("svc bluetooth enable || restart_status=1")
        add("current_pids=\$(pidof $processNames 2>/dev/null || true)")
        add("for old_pid in \$previous_pids; do case \" \$current_pids \" in " +
            "*\" \$old_pid \"*) echo \"Bluetooth host still running: \$old_pid\"; restart_status=1 ;; esac; done")
        add("echo \"Bluetooth host processes: \$previous_pids -> \$current_pids\"")
        add("exit \$restart_status")
    }.joinToString("; ")
}

/** Uses the same trusted package catalog as adapter control ownership and LSPosed scope. */
internal object VendorAppRootCommands {
    private val packages = ControlAppCatalog.packageNames.sorted().joinToString(" ") { packageName ->
        require(packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")))
        "'$packageName'"
    }
    val stop = "for p in $packages; do am force-stop \"\$p\" >/dev/null 2>&1 || true; done"
    val verifyStopped = "for p in $packages; do pidof \"\$p\" && exit 1; done; exit 0"
}

sealed interface RootActionState {
    data object Idle : RootActionState
    data class Running(val action: RootAction) : RootActionState
    data class Finished(val action: RootAction, val success: Boolean, val detail: String) : RootActionState
}

/** Small, bounded root-command boundary used only from the module's Settings screen. */
internal object RootCommandRunner {
    suspend fun isAvailable(): Boolean = RootShell.execute("id -u").let { result ->
        result.success && result.output.trim() == "0"
    }

    suspend fun run(action: RootAction): RootActionState.Finished {
        val result = RootShell.execute(action.command)
        if (!result.success) {
            return RootActionState.Finished(
                action = action,
                success = false,
                detail = result.describe("执行"),
            )
        }
        val verification = RootShell.execute(action.verificationCommand)
        val verified = verification.success && action.verificationSuccess(verification.output)
        return RootActionState.Finished(
            action = action,
            success = verified,
            detail = buildString {
                append(result.describe("执行"))
                append('\n')
                append(verification.describe("验证"))
            },
        )
    }
}

internal data class RootCommandResult(
    val exitCode: Int?,
    val output: String,
) {
    val success: Boolean
        get() = exitCode == 0

    fun describe(stage: String): String = buildString {
        append(stage)
        append(" exit=")
        append(exitCode?.toString() ?: "timeout")
        if (output.isNotBlank()) {
            append(" · ")
            append(output)
        }
    }
}

/** Shared, bounded root process boundary for settings actions and diagnostics export. */
internal object RootShell {
    suspend fun execute(
        command: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): RootCommandResult = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val outputReader = async(Dispatchers.IO) {
                process.inputStream.bufferedReader().use { it.readText().trim() }
            }
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                outputReader.cancel()
                return@runCatching RootCommandResult(null, "命令执行超时")
            }
            RootCommandResult(process.exitValue(), outputReader.await())
        }.getOrElse { error ->
            RootCommandResult(null, error.message.orEmpty())
        }
    }

    private const val DEFAULT_TIMEOUT_SECONDS = 12L
}
