package dev.hyperears.bridge

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent

/** The platform supplies both fields; Intent extras are never treated as sender credentials. */
internal object BroadcastIdentity {
    fun accepts(context: Context, sender: String?, uid: Int, expected: String): Boolean =
        sender == expected && uid >= 0 &&
            expected in context.packageManager.getPackagesForUid(uid).orEmpty()

    fun send(context: Context, intent: Intent) {
        context.sendBroadcast(
            intent,
            null,
            BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
        )
    }
}
