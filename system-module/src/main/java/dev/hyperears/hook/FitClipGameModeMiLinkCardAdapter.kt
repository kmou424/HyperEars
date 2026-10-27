package dev.hyperears.hook

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.hyperears.integration.EdifierControlRequest
import dev.hyperears.integration.EdifierGameModeFeatureState
import dev.hyperears.integration.EdifierMiLinkPresentationIds
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.MiLinkCardPresentationId
import dev.hyperears.integration.NoiseMode
import java.lang.ref.WeakReference

/**
 * Presents FitClip Ultra game mode through MiLink's complete stock ANC-card slot.
 *
 * The stock section remains the sole owner of dimensions, spacing, clipping and animations. This
 * adapter temporarily replaces its three mode items with two instances of the same native item
 * class, labelled Standard mode and Game mode. The original title and items are restored on
 * unbind. No height override, host method, obfuscated field, or delayed layout mutation is used.
 */
internal object FitClipUltraGameModeMiLinkCardAdapter : MiLinkCardAdapter {
    override val presentationId: MiLinkCardPresentationId =
        EdifierMiLinkPresentationIds.GAME_MODE
    override val nativeSurface: MiLinkNativeCardSurface =
        MiLinkNativeCardSurface.ANC_THREE_STATE

    override fun nativeSurfaceNoiseMode(state: EarbudState): NoiseMode =
        if (state.features.get<EdifierGameModeFeatureState>()?.enabled == true) {
            NoiseMode.ANC
        } else {
            NoiseMode.OFF
        }

    override fun bind(
        root: View,
        address: String,
        environment: MiLinkCardEnvironment,
    ): MiLinkCardBinding? {
        val host = resolveNativeHost(root)
            ?: return skipped(address, "native-anc-card-unavailable")
        val standard = host.createItem(
            root = root,
            source = host.standardSource,
            label = STANDARD_MODE_LABEL,
        ) ?: return skipped(address, "standard-item-construction-failed")
        val game = host.createItem(
            root = root,
            source = host.gameSource,
            label = GAME_MODE_LABEL,
        ) ?: return skipped(address, "game-item-construction-failed")

        val binding = Binding(
            host = host,
            standard = standard,
            game = game,
            address = address,
            environment = environment,
        )
        host.install(standard, game)
        standard.setOnClickListener { binding.onChoiceClicked(gameMode = false) }
        game.setOnClickListener { binding.onChoiceClicked(gameMode = true) }
        ModuleLog.debug(
            COMPONENT,
            "bound FitClip native game-mode card layout=${host.generation} " +
                "address=${maskBluetoothAddress(address)}",
        )
        return binding
    }

    private fun skipped(address: String, reason: String): MiLinkCardBinding? {
        ModuleLog.debug(
            COMPONENT,
            "skipped FitClip native game-mode card reason=$reason " +
                "address=${maskBluetoothAddress(address)}",
        )
        return null
    }

    private class Binding(
        private val host: NativeAncHost,
        standard: View,
        game: View,
        private val address: String,
        private val environment: MiLinkCardEnvironment,
    ) : MiLinkCardBinding {
        private val standard = WeakReference(standard)
        private val game = WeakReference(game)
        private var lastRendered: FitClipGameModeCardPolicy.CardState? = null

        override fun render(state: EarbudState) {
            val standard = standard.get() ?: return
            val game = game.get() ?: return
            val projected = FitClipGameModeCardPolicy.render(state)
            standard.setNativeChoiceEnabled(projected.enabled)
            game.setNativeChoiceEnabled(projected.enabled)
            standard.setSelectedTree(!projected.gameMode)
            game.setSelectedTree(projected.gameMode)
            if (lastRendered != projected) {
                lastRendered = projected
                ModuleLog.debug(
                    COMPONENT,
                    "rendered FitClip native game-mode card enabled=${projected.enabled} " +
                        "game=${projected.gameMode} " +
                        "address=${maskBluetoothAddress(address)}",
                )
            }
        }

        fun onChoiceClicked(gameMode: Boolean) {
            val current = environment.stateProvider(address)
            val requested = FitClipGameModeCardPolicy.request(current, gameMode) ?: return
            environment.controlSender(
                address,
                EdifierControlRequest.SetGameMode(requested),
            )
        }

        override fun unbind() {
            standard.get()?.setOnClickListener(null)
            game.get()?.setOnClickListener(null)
            host.restore()
        }
    }

    private class NativeAncHost(
        title: TextView,
        row: NativeAncModeRow,
    ) {
        private data class OriginalItem(
            val view: View,
            val index: Int,
            val layoutParams: ViewGroup.LayoutParams,
            val visibility: Int,
        )

        private val layout = row.layout
        val generation: String = layout.logName
        val standardSource: View = row.item(NoiseMode.OFF)
        val gameSource: View = row.item(NoiseMode.ANC)

        private val title = WeakReference(title)
        private val container = WeakReference(row.container)
        private val originalTitle = title.text
        private val originals = row.items.map { item ->
            OriginalItem(
                view = item,
                index = row.container.indexOfChild(item),
                layoutParams = item.layoutParams,
                visibility = item.visibility,
            )
        }
        private val replacements = mutableListOf<WeakReference<View>>()

        fun createItem(root: View, source: View, label: String): View? =
            layout.createItem(root, source, label)

        fun install(standard: View, game: View) {
            val title = title.get() ?: return
            val container = container.get() ?: return
            title.text = GAME_MODE_LABEL
            originals.sortedByDescending(OriginalItem::index).forEach { original ->
                if (original.view.parent === container) container.removeView(original.view)
            }
            val insertion = originals.minOfOrNull(OriginalItem::index)
                ?.coerceIn(0, container.childCount)
                ?: container.childCount
            container.addView(standard, insertion)
            container.addView(game, insertion + 1)
            replacements += WeakReference(standard)
            replacements += WeakReference(game)
        }

        fun restore() {
            val title = title.get() ?: return
            val container = container.get() ?: return
            replacements.mapNotNull(WeakReference<View>::get).forEach { replacement ->
                if (replacement.parent === container) container.removeView(replacement)
            }
            originals.sortedBy(OriginalItem::index).forEach { original ->
                if (original.view.parent == null) {
                    original.view.layoutParams = original.layoutParams
                    original.view.visibility = original.visibility
                    container.addView(
                        original.view,
                        original.index.coerceIn(0, container.childCount),
                    )
                }
            }
            title.text = originalTitle
            replacements.clear()
        }
    }

    private fun resolveNativeHost(root: View): NativeAncHost? =
        listOf(NativeAncModeLayout.SELECT_CARD, NativeAncModeLayout.ORIGINAL).firstNotNullOfOrNull { layout ->
            val row = NativeAncModeRow.resolve(root, layout) ?: return@firstNotNullOfOrNull null
            val title = root.findMiLinkView(layout.titleId) as? TextView ?: return@firstNotNullOfOrNull null
            NativeAncHost(title, row)
        }

    private fun View.setNativeChoiceEnabled(enabled: Boolean) {
        isEnabled = enabled
        isClickable = enabled
        alpha = if (enabled) ENABLED_ALPHA else DISABLED_ALPHA
    }

    private fun View.setSelectedTree(selected: Boolean) {
        isSelected = selected
        if (this !is ViewGroup) return
        for (index in 0 until childCount) getChildAt(index).setSelectedTree(selected)
    }

    private const val COMPONENT = "MiLinkUi"
    private const val GAME_MODE_LABEL = "游戏模式"
    private const val STANDARD_MODE_LABEL = "标准模式"
    private const val ENABLED_ALPHA = 1.0f
    private const val DISABLED_ALPHA = 0.45f
}

internal object FitClipGameModeCardPolicy {
    data class CardState(
        val gameMode: Boolean,
        val enabled: Boolean,
    )

    fun render(state: EarbudState): CardState {
        val feature = state.features.get<EdifierGameModeFeatureState>()
        return CardState(
            gameMode = feature?.enabled == true,
            enabled = state.sessionActive && state.connected && feature != null,
        )
    }

    fun request(state: EarbudState, gameMode: Boolean): Boolean? {
        val current = render(state)
        if (!current.enabled || current.gameMode == gameMode) return null
        return gameMode
    }
}
