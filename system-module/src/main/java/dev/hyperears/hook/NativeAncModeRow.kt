package dev.hyperears.hook

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.hyperears.integration.NoiseMode

/** Verified MiLink layout contracts. Select-card items use the stock order: aware, ANC, off. */
internal enum class NativeAncModeLayout(
    val logName: String,
    val containerId: String,
    val titleId: String,
    val itemClassName: String,
    val itemTextId: String,
    val itemIconId: String,
) {
    ORIGINAL("original", "anc_card", "anc_card_title", HOST_ANC_ITEM_CLASS, "anc_title", "anc_icon"),
    SELECT_CARD(
        "select-card", "anc_select_card", "anc_card_text",
        "com.miui.circulate.world.headset.ui.HeadsetSelectItemView", "tools_text", "tools_icon",
    );

    fun accepts(containerClassName: String, itemClasses: List<String>): Boolean =
        (this != SELECT_CARD || containerClassName == SELECT_CARD_CLASS) &&
            itemClasses.size == MODES.size && itemClasses.all { it == itemClassName }

    companion object {
        const val SELECT_CARD_CLASS = "com.miui.circulate.world.headset.ui.HeadsetSelectCardView"
        val MODES = listOf(NoiseMode.TRANSPARENCY, NoiseMode.ANC, NoiseMode.OFF)
        val ORIGINAL_ITEM_IDS = listOf("anc_clear", "anc_noise_cancel", "anc_off")
    }
}

/** Resolves one native row without depending on a title, and creates items before any host mutation. */
internal class NativeAncModeRow private constructor(
    val layout: NativeAncModeLayout,
    val container: LinearLayout,
    val items: List<View>,
) {
    fun item(mode: NoiseMode): View = items[NativeAncModeLayout.MODES.indexOf(mode)]

    fun createItem(
        root: View,
        source: View,
        label: String,
        iconSource: View = source,
    ): View? = layout.createItem(root, source, label, iconSource)

    companion object {
        fun resolve(root: View): NativeAncModeRow? =
            resolve(root, NativeAncModeLayout.SELECT_CARD) ?: resolve(root, NativeAncModeLayout.ORIGINAL)

        fun resolve(root: View, layout: NativeAncModeLayout): NativeAncModeRow? {
            val container = root.findMiLinkView(layout.containerId) as? LinearLayout ?: return null
            val items = when (layout) {
                NativeAncModeLayout.ORIGINAL -> NativeAncModeLayout.ORIGINAL_ITEM_IDS.map { id ->
                    root.findMiLinkView(id) ?: return null
                }
                NativeAncModeLayout.SELECT_CARD -> (0 until container.childCount).map(container::getChildAt)
            }
            if (items.any { it.parent !== container } ||
                !layout.accepts(container.javaClass.name, items.map { it.javaClass.name })) return null
            return NativeAncModeRow(layout, container, items)
        }
    }
}

/** Stateless native-item construction, so a binding does not have to retain the attached row. */
internal fun NativeAncModeLayout.createItem(
    root: View,
    source: View,
    label: String,
    iconSource: View = source,
): View? = runCatching {
    require(source.javaClass.name == itemClassName && iconSource.javaClass.name == itemClassName)
    val item = source.javaClass.getConstructor(Context::class.java).newInstance(root.context) as View
    item.id = source.id
    item.layoutParams = source.layoutParams.nativeCopy()
    item.visibility = View.VISIBLE
    item.isSaveEnabled = false
    item.contentDescription = label
    val text = requireNotNull(item.findMiLinkView(itemTextId) as? TextView)
    val icon = requireNotNull(item.findMiLinkView(itemIconId) as? ImageView)
    val sourceIcon = requireNotNull(iconSource.findMiLinkView(itemIconId) as? ImageView)
    text.text = label
    text.contentDescription = label
    icon.setImageDrawable(
        sourceIcon.drawable?.constantState?.newDrawable(root.resources)?.mutate() ?: sourceIcon.drawable,
    )
    item
}.onFailure {
    ModuleLog.warn("MiLinkUi", "native mode item construction failed layout=$logName", it)
}.getOrNull()

private fun ViewGroup.LayoutParams.nativeCopy(): ViewGroup.LayoutParams = when (this) {
    is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(this)
    is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(this)
    else -> ViewGroup.LayoutParams(this)
}
