package dev.hyperears.presentation

import dev.hyperears.integration.EarbudAdapterRegistry
import dev.hyperears.integration.HeadsetArtwork
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

class NativeArtworkCoverageTest {
    @Test
    fun everyBundledModelResourceExistsAndSurvivesResourceShrinking() {
        val resources = File("src/main/res/drawable-nodpi")
        val defaults = listOf(HeadsetArtwork.EARBUDS, HeadsetArtwork.HEADPHONES)
        val declarations = EarbudAdapterRegistry.adapters.map { it.artwork } + defaults
        declarations.flatMap { listOfNotNull(it.headset, it.chargingCase) }.distinct().forEach {
            assertTrue("Missing bundled resource ${it.value}", File(resources, "${it.value}.png").isFile)
            val image = ImageIO.read(File(resources, "${it.value}.png"))
            assertTrue("Expected transparent artwork ${it.value}", image.colorModel.hasAlpha())
            assertTrue(image.width in 1..4096 && image.height in 1..4096)
        }
        assertTrue(File("src/main/res/raw/headset_artwork_keep.xml").readText()
            .contains("@drawable/headset_*"))
    }
}
