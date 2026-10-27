package dev.hyperears.hook

import dev.hyperears.integration.NoiseMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAncModeRowTest {
    private val selectCard = "com.miui.circulate.world.headset.ui.HeadsetSelectCardView"
    private val selectItem = "com.miui.circulate.world.headset.ui.HeadsetSelectItemView"
    private val originalItem = "com.miui.circulate.world.headset.ui.HeadsetControlAncItemView"

    @Test
    fun originalAndSelectContractsAcceptTheirOwnNativeItems() {
        assertTrue(NativeAncModeLayout.ORIGINAL.accepts("android.widget.LinearLayout", List(3) { originalItem }))
        assertTrue(NativeAncModeLayout.SELECT_CARD.accepts(selectCard, List(3) { selectItem }))
        assertFalse(NativeAncModeLayout.ORIGINAL.accepts(selectCard, List(3) { selectItem }))
        assertFalse(NativeAncModeLayout.SELECT_CARD.accepts(selectCard, List(3) { originalItem }))
    }

    @Test
    fun unknownOrPartialSelectLayoutsCannotBeReinterpretedAsThreeModes() {
        for (count in listOf(0, 1, 2, 4)) {
            assertFalse(NativeAncModeLayout.SELECT_CARD.accepts(selectCard, List(count) { selectItem }))
        }
        assertFalse(NativeAncModeLayout.SELECT_CARD.accepts("android.widget.LinearLayout", List(3) { selectItem }))
        assertFalse(NativeAncModeLayout.SELECT_CARD.accepts(selectCard, listOf(selectItem, "obfuscated.a", selectItem)))
    }

    @Test
    fun boseWindProjectionUsesTheSameSlotThatTheNativeRowReplaces() {
        val slots = NativeAncModeLayout.MODES
        // The verified select-card order is transparency, cancellation, off.
        assertEquals(NoiseMode.TRANSPARENCY, slots[0])
        assertEquals(NoiseMode.ANC, slots[1])
        assertEquals(NoiseMode.OFF, slots[2])
        assertEquals(slots[2], BoseQuietComfortMiLinkCardAdapter.projectNativeNoiseMode(NoiseMode.WIND))
        assertEquals(slots[0], BoseAnrMiLinkCardAdapter.projectNativeNoiseMode(NoiseMode.WIND))
        for (mode in slots) {
            assertEquals(mode, BoseQuietComfortMiLinkCardAdapter.projectNativeNoiseMode(mode))
            assertEquals(mode, BoseAnrMiLinkCardAdapter.projectNativeNoiseMode(mode))
        }
        assertEquals(null, BoseQuietComfortMiLinkCardAdapter.projectNativeNoiseMode(null))
    }
}
