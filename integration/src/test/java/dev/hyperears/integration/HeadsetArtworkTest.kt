package dev.hyperears.integration

import org.junit.Assert.*
import org.junit.Test

class HeadsetArtworkTest {
    @Test
    fun everyRegisteredModelInheritsArtworkWithoutOpeningPrivateCapabilities() {
        val adapters = EarbudAdapterRegistry.adapters
        assertTrue(adapters.isNotEmpty())
        adapters.forEach { adapter ->
            val before = adapter.snapshot()
            assertEquals(adapter.artwork, before.artwork)
            assertEquals(adapter.artwork,
                HeadsetArtworkTransport.decode(HeadsetArtworkTransport.encode(before.artwork)))
            assertEquals(before, adapter.snapshot())
        }
    }

    @Test
    fun modelOverridesOnlyItsArtworkAndTransportPreservesIt() {
        val custom = HeadsetArtwork(HeadsetArtworkId("headset_example_model"),
            HeadsetArtworkId("headset_example_case"))
        val adapter = object : EarbudAdapter() {
            override val id = "example"
            override val displayName = "Example"
            override val artwork = custom
            override fun matches(identity: EarbudIdentity) = false
        }
        assertEquals(custom, adapter.snapshot().artwork)
        assertEquals(custom, HeadsetArtworkTransport.decode(HeadsetArtworkTransport.encode(custom)))
        assertFalse(adapter.snapshot().capabilities.noiseControl)
    }

    @Test
    fun resourcesCannotReferenceExternalPackagesOrFilesystemPaths() {
        listOf("../secret", "android:drawable/icon", "https://example.com/a.png", "headset_a/b")
            .forEach { assertTrue(runCatching { HeadsetArtworkId(it) }.isFailure) }
        assertNull(HeadsetArtworkTransport.decode("{}"))
    }
}
