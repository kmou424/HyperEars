package dev.hyperears.bridge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

internal object BluetoothDeviceSettings {
    fun open(context: Context, address: String): Boolean {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val details = Intent("com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS")
            .setPackage("com.android.settings")
            .putExtra("device_address", address)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString("device_address", address) })
            .addFlags(flags)
        return runCatching { context.startActivity(details) }.isSuccess ||
            runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                .setPackage("com.android.settings").addFlags(flags)) }.isSuccess
    }
}
