package dev.hyperears.presentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toDrawable
import android.util.LruCache
import dev.hyperears.bridge.ModuleContract
import dev.hyperears.integration.HeadsetArtworkId

/** Only bundled, namespaced resources can cross into the native host. No paths or network URLs. */
internal class NativeArtworkLoader(context: Context) {
    private val resources = context.packageManager.getResourcesForApplication(ModuleContract.MODULE_PACKAGE)
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    @Suppress("DiscouragedApi")
    @android.annotation.SuppressLint("DiscouragedApi")
    fun load(id: HeadsetArtworkId, fallback: HeadsetArtworkId): Drawable {
        val bitmap = cache[id.value] ?: synchronized(cache) {
            cache[id.value] ?: run {
                fun decode(name: String): Bitmap? {
                    val resource = resources.getIdentifier(name, "drawable", ModuleContract.MODULE_PACKAGE)
                    if (resource == 0) return null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeResource(resources, resource, bounds)
                    if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return null
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
                    return BitmapFactory.decodeResource(resources, resource, BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inScaled = false
                    })
                }
                requireNotNull(decode(id.value) ?: decode(fallback.value)).also { cache.put(id.value, it) }
            }
        }
        // A fresh drawable per native view prevents shared bounds/state between windows.
        return bitmap.toDrawable(resources)
    }
}
