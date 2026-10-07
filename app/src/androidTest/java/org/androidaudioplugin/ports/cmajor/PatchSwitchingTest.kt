package org.androidaudioplugin.ports.cmajor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

// Switching patches (CmajorNative.loadPatch(), as the plugin UI does) and restoring them from state,
// on both plugins.
@RunWith(Parameterized::class)
class PatchSwitchingTest(private val pluginId: String, private val defaultPatch: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{1}")
        fun plugins() = listOf(
            arrayOf(INSTRUMENT_PLUGIN_ID, "Pro54.cmajorpatch"),
            arrayOf(EFFECT_PLUGIN_ID, "Tremolo.cmajorpatch"),
        )
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val patches by lazy { BundledPatches.list(context) }

    private fun withPlugin(block: (instantiate: () -> TestInstance) -> Unit) = withPlugin(context, pluginId, block)

    @Test
    fun listsBundledPatches() {
        assertTrue("too few patches: ${patches.map { it.name }}", patches.size >= 20)
        assertFalse("FaustFM must not be bundled", patches.any { it.identity.contains("FaustFM") })
        assertTrue("Pro54 has its own UI", patches.patch("Pro54.cmajorpatch").hasView)
        assertTrue("Claude/FilterEQ is nested one folder deeper", patches.any { it.identity.endsWith("/Claude/FilterEQ/FilterEQ.cmajorpatch") })
    }

    @Test
    fun loadsEveryBundledPatch() = withPlugin { instantiate ->
        val instance = instantiate()
        instance.start()
        assertEquals("the default patch", patches.patch(defaultPatch).identity, instance.status().identity)

        val failures = mutableListOf<String>()
        instance.whileProcessing { blocks ->
            for (patch in patches) {
                val error = instance.load(patch.identity)
                if (error != null) {
                    failures += "${patch.name}: $error"
                    continue
                }
                val before = blocks.get()
                Thread.sleep(300)
                assertTrue("${patch.name} must be processed", blocks.get() > before)
                val status = instance.status()
                assertEquals(patch.identity, status.identity)
                assertTrue("${patch.name} is not loaded", status.loaded)
                assertEquals(patch.hasView, status.hasView)
            }
        }
        instance.stop()
        assertTrue("Failed to load:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun keepsTheCurrentPatchWhenLoadingFails() = withPlugin { instantiate ->
        val instance = instantiate()
        instance.start()
        val before = instance.status()
        val error = instance.load("cmajor-asset:///patches/Nope/Nope.cmajorpatch")
        assertTrue("expected an error", !error.isNullOrEmpty())
        val after = instance.status()
        assertEquals(before.identity, after.identity)
        assertEquals(before.generation, after.generation)
        assertTrue(after.loaded)
        assertEquals(error, after.error)
        instance.stop()
    }

    @Test
    fun patchSelectionRoundTripsThroughState() = withPlugin { instantiate ->
        val freeverb = patches.patch("Freeverb.cmajorpatch")
        val source = instantiate()
        source.start()
        assertNull(source.load(freeverb.identity))
        repeat(4) { source.process(1024) }
        val parameterCount = source.status().parameterCount
        assertTrue("Freeverb has parameters", parameterCount > 0)

        val saved = source.savedState()
        val state = JSONObject(String(saved, Charsets.UTF_8))
        assertEquals(freeverb.identity, state.getString("patch"))
        val cmajorState = state.getString("cmajorState")
        source.stop()

        // Restore into fresh instances: before and after prepare(), and from the main thread,
        // as a host does from an ActivityResult callback.
        for (onMain in booleanArrayOf(false, true)) {
            for (beforePrepare in booleanArrayOf(false, true)) {
                val label = "onMain=$onMain beforePrepare=$beforePrepare"
                val restored = instantiate()
                if (!beforePrepare)
                    restored.start()
                if (onMain)
                    InstrumentationRegistry.getInstrumentation().runOnMainSync { restored.setState(saved) }
                else
                    restored.setState(saved)
                if (beforePrepare)
                    restored.start()
                repeat(4) { restored.process(1024) }

                val status = restored.status()
                assertEquals(label, freeverb.identity, status.identity)
                assertTrue(label, status.loaded)
                assertEquals(label, parameterCount, status.parameterCount)
                assertEquals(label, cmajorState, JSONObject(String(restored.savedState(), Charsets.UTF_8)).getString("cmajorState"))
                restored.stop()
            }
        }
    }

    @Test
    fun missingPatchInStateSilencesButIsRemembered() = withPlugin { instantiate ->
        val missing = "cmajor-asset:///patches/Nope/Nope.cmajorpatch"
        val saved = JSONObject().put("aapCmajorStateVersion", 1).put("patch", missing).put("cmajorState", "{}")
            .toString().toByteArray()
        val instance = instantiate()
        instance.start()
        instance.setState(saved)
        repeat(4) { instance.process(1024) }

        val status = instance.status()
        assertEquals(missing, status.identity)
        assertFalse(status.loaded)
        assertTrue(status.error.isNotEmpty())
        val resaved = JSONObject(String(instance.savedState(), Charsets.UTF_8))
        assertEquals(missing, resaved.getString("patch"))
        assertEquals("{}", resaved.getString("cmajorState"))

        // ...and a patch can still be loaded afterwards.
        assertNull(instance.load(patches.patch("SineSynth.cmajorpatch").identity))
        assertTrue(instance.status().loaded)
        instance.stop()
    }

    @Test
    fun switchesPatchesWhileProcessing() = withPlugin { instantiate ->
        val instance = instantiate()
        instance.start()
        instance.whileProcessing { blocks ->
            val sequence = listOf("SineSynth.cmajorpatch", "Tremolo.cmajorpatch", "ElectricPiano.cmajorpatch", "Pro54.cmajorpatch")
            repeat(2) {
                for (manifest in sequence) {
                    val before = blocks.get()
                    assertNull(manifest, instance.load(patches.patch(manifest).identity))
                    assertEquals(patches.patch(manifest).identity, instance.status().identity)
                    assertNotEquals("audio must keep running while $manifest builds", before, blocks.get())
                }
            }
        }
        instance.stop()
    }
}
