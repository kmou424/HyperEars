package dev.hyperears.presentation

import android.annotation.SuppressLint
import android.view.View
import android.widget.RelativeLayout
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Optional adaptation of the existing case containers after the native battery renderer finishes. */
internal class NativeCaseVisibility private constructor(
    val render: Method,
    private val imageId: Int,
    private val batteryId: Int,
) {
    fun apply(root: View, visible: Boolean): Boolean {
        // Validate the complete layout contract before changing either container.
        fun container(id: Int, name: String): RelativeLayout {
            require(root.resources.getResourceTypeName(id) == "id")
            require(root.resources.getResourceEntryName(id) == name)
            return requireNotNull(root.findViewById<View>(id) as? RelativeLayout) {
                "Native case container unavailable: $name"
            }
        }
        val image = container(imageId, IMAGE)
        val battery = container(batteryId, BATTERY)
        val visibility = if (visible) View.VISIBLE else View.GONE
        val changed = image.visibility != visibility || battery.visibility != visibility
        image.visibility = visibility
        battery.visibility = visibility
        return changed
    }

    companion object {
        private const val IMAGE = "imageViewBoxLayout"
        private const val BATTERY = "textViewBoxBattery"

        // Exact R classes from the verified host plugins; numeric IDs are read from the loaded APK.
        private val RESOURCE_CLASSES = listOf(
            "com.xiaomi.bluetooth.peripheral.R\$id",
            "com.xiaomi.bluetooth.sample.preloadedFeature.R\$id",
        )

        // These are the scoped host's plugin classes, not Android hidden framework APIs.
        @SuppressLint("PrivateApi")
        fun resolve(controller: Class<*>, loader: ClassLoader): NativeCaseVisibility {
            val render = controller.getDeclaredMethod("setConnectSuccessLayoutDetail", View::class.java)
                .apply { isAccessible = true }
            require(render.returnType == Void.TYPE)
            for (className in RESOURCE_CLASSES) {
                val ids = runCatching {
                    val type = Class.forName(className, false, loader)
                    fun id(name: String): Int {
                        val field = type.getDeclaredField(name).apply { isAccessible = true }
                        require(Modifier.isStatic(field.modifiers) && field.type == Int::class.javaPrimitiveType)
                        return field.getInt(null).also { require(it != 0) }
                    }
                    id(IMAGE) to id(BATTERY)
                }.getOrNull() ?: continue
                require(ids.first != ids.second)
                return NativeCaseVisibility(render, ids.first, ids.second)
            }
            throw NoSuchFieldException("Native case resource contract unavailable")
        }
    }
}
