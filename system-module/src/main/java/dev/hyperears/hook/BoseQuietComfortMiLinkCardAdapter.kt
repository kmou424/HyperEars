package dev.hyperears.hook

import android.view.View
import android.view.ViewGroup
import dev.hyperears.integration.BoseMiLinkPresentationIds
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.MiLinkCardPresentationId
import dev.hyperears.integration.NoiseMode
import dev.hyperears.integration.StandardControlRequest
import java.lang.ref.WeakReference

/** Quiet/Aware/Wind: Bose's verified wind preset occupies MiLink's unsupported Off slot. */
internal object BoseQuietComfortMiLinkCardAdapter : MiLinkCardAdapter by BoseWindSlotCardAdapter(
    presentationId = BoseMiLinkPresentationIds.WIND_REPLACES_OFF,
    replacedMode = NoiseMode.OFF,
) {
    internal fun isModeSelected(itemMode: NoiseMode, currentMode: NoiseMode?): Boolean =
        itemMode == currentMode
}

/** High/Wind/Off: QC35's ANR wind state occupies MiLink's unsupported Transparency slot. */
internal object BoseAnrMiLinkCardAdapter : MiLinkCardAdapter by BoseWindSlotCardAdapter(
    presentationId = BoseMiLinkPresentationIds.WIND_REPLACES_TRANSPARENCY,
    replacedMode = NoiseMode.TRANSPARENCY,
)

/** Quiet/Aware devices have no verified Off command, so the native item remains visibly disabled. */
internal object BoseTwoModeMiLinkCardAdapter : MiLinkCardAdapter {
    override val presentationId = BoseMiLinkPresentationIds.TWO_MODE

    override fun bind(
        root: View,
        address: String,
        environment: MiLinkCardEnvironment,
    ): MiLinkCardBinding? {
        val off = NativeAncModeRow.resolve(root)?.item(NoiseMode.OFF) ?: return null
        val originalEnabled = off.isEnabled
        val originalClickable = off.isClickable
        val originalAlpha = off.alpha
        off.isEnabled = false
        off.isClickable = false
        off.alpha = DISABLED_ALPHA
        return MiLinkCardBinding {
            // MiLink owns selection; the model presentation owns unsupported-action availability.
            off.isEnabled = false
            off.isClickable = false
            off.alpha = DISABLED_ALPHA
        }.withUnbind {
            off.isEnabled = originalEnabled
            off.isClickable = originalClickable
            off.alpha = originalAlpha
        }
    }
}

/**
 * One-shot native-slot replacement shared by Bose products with an explicit wind mode.
 *
 * The host's own ANC item class, layout parameters and drawables are reused. This layer contains
 * no Bluetooth logic; concrete model Adapters decide whether this presentation is selected.
 */
private class BoseWindSlotCardAdapter(
    override val presentationId: MiLinkCardPresentationId,
    private val replacedMode: NoiseMode,
) : MiLinkCardAdapter {
    override fun projectNativeNoiseMode(mode: NoiseMode?): NoiseMode? = when (mode) {
        NoiseMode.WIND -> replacedMode
        else -> mode
    }

    override fun bind(
        root: View,
        address: String,
        environment: MiLinkCardEnvironment,
    ): MiLinkCardBinding? {
        val row = NativeAncModeRow.resolve(root) ?: return skipped(address, "native-mode-row-unavailable")
        val ancCard = row.container
        val transparency = row.item(NoiseMode.TRANSPARENCY)
        val noiseCancellation = row.item(NoiseMode.ANC)
        val off = row.item(NoiseMode.OFF)
        val replaced = row.item(replacedMode)
        val wind = row.createItem(root, replaced, WIND_LABEL, iconSource = noiseCancellation)
            ?: return skipped(address, "native-wind-item-construction-failed")

        val index = ancCard.indexOfChild(replaced).takeIf { it >= 0 } ?: return null
        val originalLayoutParams = replaced.layoutParams
        val originalVisibility = replaced.visibility

        ancCard.removeViewAt(index)
        ancCard.addView(wind, index)

        val binding = Binding(
            parent = ancCard,
            originalIndex = index,
            originalLayoutParams = originalLayoutParams,
            originalVisibility = originalVisibility,
            replaced = replaced,
            transparency = transparency,
            noiseCancellation = noiseCancellation,
            off = off,
            wind = wind,
            address = address,
            environment = environment,
        )
        wind.setOnClickListener { binding.onWindClick() }
        ModuleLog.debug("MiLinkUi") {
            "bound Bose native wind mode layout=${row.layout.logName} slot=$replacedMode " +
                "address=${maskBluetoothAddress(address)}"
        }
        return binding
    }

    private fun skipped(address: String, reason: String): MiLinkCardBinding? {
        ModuleLog.debug("MiLinkUi") {
            "skipped Bose native wind mode reason=$reason address=${maskBluetoothAddress(address)}"
        }
        return null
    }

    private class Binding(
        parent: ViewGroup,
        private val originalIndex: Int,
        private val originalLayoutParams: ViewGroup.LayoutParams,
        private val originalVisibility: Int,
        replaced: View,
        transparency: View,
        noiseCancellation: View,
        off: View,
        wind: View,
        private val address: String,
        private val environment: MiLinkCardEnvironment,
    ) : MiLinkCardBinding {
        private val parent = WeakReference(parent)
        // Keep the detached native item alive until unbind so restoration survives GC.
        private var replaced: View? = replaced
        private val transparency = WeakReference(transparency)
        private val noiseCancellation = WeakReference(noiseCancellation)
        private val off = WeakReference(off)
        private val wind = WeakReference(wind)

        override fun render(state: EarbudState) {
            wind.get()?.apply {
                isEnabled = state.sessionActive && state.connected && state.noiseMode != null
                alpha = if (isEnabled) ENABLED_ALPHA else DISABLED_ALPHA
            }
            val mode = state.noiseMode ?: return
            transparency.get()?.setBoseSelectedTree(mode == NoiseMode.TRANSPARENCY)
            noiseCancellation.get()?.setBoseSelectedTree(mode == NoiseMode.ANC)
            off.get()?.setBoseSelectedTree(mode == NoiseMode.OFF)
            wind.get()?.setBoseSelectedTree(mode == NoiseMode.WIND)
        }

        fun onWindClick() {
            val current = environment.stateProvider(address)
            if (!current.sessionActive || !current.connected || current.noiseMode == NoiseMode.WIND) {
                return
            }
            environment.controlSender(
                address,
                StandardControlRequest.SetNoiseMode(NoiseMode.WIND),
            )
        }

        override fun unbind() {
            val parent = parent.get() ?: return
            val wind = wind.get() ?: return
            val replaced = replaced ?: return
            if (wind.parent !== parent) return

            wind.setOnClickListener(null)
            parent.removeView(wind)
            if (replaced.parent == null) {
                replaced.layoutParams = originalLayoutParams
                replaced.visibility = originalVisibility
                parent.addView(replaced, originalIndex.coerceAtMost(parent.childCount))
            }
            this.replaced = null
        }
    }

}

private fun View.setBoseSelectedTree(selected: Boolean) {
    isSelected = selected
    if (this !is ViewGroup) return
    for (index in 0 until childCount) getChildAt(index).setBoseSelectedTree(selected)
}

private class UnbindingCardBinding(
    private val renderBlock: (EarbudState) -> Unit,
    private val unbindBlock: () -> Unit,
) : MiLinkCardBinding {
    override fun render(state: EarbudState) = renderBlock(state)
    override fun unbind() = unbindBlock()
}

private fun MiLinkCardBinding.withUnbind(block: () -> Unit): MiLinkCardBinding =
    UnbindingCardBinding(renderBlock = ::render, unbindBlock = block)

private const val WIND_LABEL = "抗风噪"
private const val ENABLED_ALPHA = 1.0f
private const val DISABLED_ALPHA = 0.45f
