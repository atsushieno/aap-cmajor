package org.androidaudioplugin.ports.cmajor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

// How the plugins' stereo audio ports are connected to patches with other channel layouts
// (see AudioRouting in cmaj_AAPPlugin.cpp).
class AudioRoutingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val patches by lazy { BundledPatches.list(context) }

    private companion object {
        const val FRAMES = 1024
        const val BLOCKS = 16

        // the effect's ports, see aap_metadata.xml
        const val EFFECT_IN_L = 0
        const val EFFECT_IN_R = 1
        const val EFFECT_OUT_L = 2
        const val EFFECT_OUT_R = 3

        // the instrument's ports (aap-core's defaults for instruments)
        const val INSTRUMENT_OUT_L = 0
        const val INSTRUMENT_OUT_R = 1
    }

    private class Output(val left: FloatArray, val right: FloatArray) {
        val leftPeak get() = left.maxOf { abs(it) }
        val rightPeak get() = right.maxOf { abs(it) }
    }

    private fun sine(block: Int) = FloatArray(FRAMES) { 0.5f * sin(2 * PI * 440 * (block * FRAMES + it) / 48000).toFloat() }

    // Processes BLOCKS blocks, feeding a sine to the chosen inputs (and silence to the others), and
    // returns all of the output.
    private fun TestInstance.runEffect(feedLeft: Boolean, feedRight: Boolean): Output {
        val left = mutableListOf<Float>()
        val right = mutableListOf<Float>()
        val silence = FloatArray(FRAMES)
        for (block in 0 until BLOCKS) {
            setAudio(EFFECT_IN_L, if (feedLeft) sine(block) else silence)
            setAudio(EFFECT_IN_R, if (feedRight) sine(block) else silence)
            process(FRAMES)
            left += getAudio(EFFECT_OUT_L, FRAMES).toList()
            right += getAudio(EFFECT_OUT_R, FRAMES).toList()
        }
        return Output(left.toFloatArray(), right.toFloatArray())
    }

    @Test
    fun monoEffectHearsBothInputsAndPlaysOnBothOutputs() = withPlugin(context, EFFECT_PLUGIN_ID) { instantiate ->
        val instance = instantiate()
        instance.start(FRAMES)
        assertEquals("Tremolo (mono in, mono out) is the default", patches.patch("Tremolo.cmajorpatch").identity,
            instance.status().identity)

        for ((feedLeft, feedRight) in listOf(true to false, false to true)) {
            val label = "feedLeft=$feedLeft feedRight=$feedRight"
            val output = instance.runEffect(feedLeft, feedRight)
            assertTrue("$label: left peak ${output.leftPeak}", output.leftPeak > 0.01f)
            assertArrayEquals("$label: the mono output goes to both channels", output.left, output.right, 0f)
        }
        instance.stop()
    }

    @Test
    fun stereoEffectKeepsChannelsApart() = withPlugin(context, EFFECT_PLUGIN_ID) { instantiate ->
        val instance = instantiate()
        instance.start(FRAMES)
        assertNull(instance.load(patches.patch("FilterEQ.cmajorpatch").identity))

        val output = instance.runEffect(feedLeft = true, feedRight = false)
        assertTrue("left peak ${output.leftPeak}", output.leftPeak > 0.01f)
        assertTrue("right peak ${output.rightPeak}", output.rightPeak < 0.0001f)
        instance.stop()
    }

    @Test
    fun instrumentMonoOutputPlaysOnBothChannels() = withPlugin(context, INSTRUMENT_PLUGIN_ID) { instantiate ->
        val instance = instantiate()
        instance.start(FRAMES)
        assertNull(instance.load(patches.patch("SineSynth.cmajorpatch").identity))

        // MIDI 2.0 note on, middle C at full velocity
        val noteOn = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        noteOn.putInt(0x40903C00).putInt(0xFFFF0000.toInt())
        instance.remote.addEventUmpInput(noteOn, 8)

        val left = mutableListOf<Float>()
        val right = mutableListOf<Float>()
        repeat(BLOCKS) {
            instance.process(FRAMES)
            left += instance.getAudio(INSTRUMENT_OUT_L, FRAMES).toList()
            right += instance.getAudio(INSTRUMENT_OUT_R, FRAMES).toList()
        }
        val output = Output(left.toFloatArray(), right.toFloatArray())
        assertTrue("left peak ${output.leftPeak}", output.leftPeak > 0.01f)
        assertArrayEquals("the mono output goes to both channels", output.left, output.right, 0f)
        instance.stop()
    }
}
