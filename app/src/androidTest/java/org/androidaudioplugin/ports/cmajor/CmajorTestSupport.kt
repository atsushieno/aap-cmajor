package org.androidaudioplugin.ports.cmajor

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.androidaudioplugin.AudioPluginServiceHelper
import org.androidaudioplugin.hosting.AudioPluginClientBase
import org.androidaudioplugin.hosting.NativeRemotePluginInstance
import org.junit.Assert.assertTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal const val INSTRUMENT_PLUGIN_ID = "urn:org.androidaudioplugin/samples/cmajor/AAPCMajorSample"
internal const val EFFECT_PLUGIN_ID = "urn:org.androidaudioplugin/samples/cmajor/AAPCmajorEffect"

internal fun List<PatchEntry>.patch(manifestFile: String) = single { it.identity.endsWith("/$manifestFile") }

/** Connects to this app's plugin service in-process, as a host would, for the duration of [block]. */
internal fun withPlugin(context: Context, pluginId: String, block: (instantiate: () -> TestInstance) -> Unit) {
    val pluginInfo = AudioPluginServiceHelper.getLocalAudioPluginService(context)
        .plugins.single { it.pluginId == pluginId }
    val host = AudioPluginClientBase(context)
    runBlocking { host.connectToPluginService(pluginInfo.packageName) }
    try {
        block { TestInstance(pluginId, host.instantiateNativePlugin(pluginInfo)) }
    } finally {
        host.disconnectPluginService(pluginInfo.packageName)
        host.dispose()
    }
}

/** A plugin instance, reachable both as a host sees it ([remote]) and as the plugin UI does ([service]). */
internal class TestInstance(private val pluginId: String, val remote: NativeRemotePluginInstance) {
    val instanceId get() = remote.instanceId

    // The native service that runs this instance, as the plugin UI gets it. A test process may have
    // several bound services (earlier tests' ones linger), so it has to be looked up by instance, which
    // AudioPluginServiceHelper only does within its view factory calls.
    val service: Long
        get() = AudioPluginServiceHelper::class.java
            .getDeclaredMethod("getServiceInstanceForInstance", String::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(null, pluginId, instanceId) as Long

    fun start(frameCount: Int = 1024) {
        remote.prepare(frameCount, 48000, 0x10000)
        remote.activate()
    }

    fun stop() {
        remote.deactivate()
        remote.destroy()
    }

    fun process(frameCount: Int) = remote.process(frameCount, 0)

    fun status() = requireNotNull(PatchStatus.read(service, instanceId)) { "the instance is not found" }

    fun load(identity: String) = CmajorNative.loadPatch(service, instanceId, identity)

    fun savedState() =
        ByteArray(remote.getStateSize().also { assertTrue("empty state", it > 0) }).also { remote.getState(it) }

    fun setState(data: ByteArray) = remote.setState(data)

    fun setAudio(port: Int, samples: FloatArray) {
        val buffer = ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(samples)
        remote.setPortBuffer(port, buffer, samples.size * 4)
    }

    fun getAudio(port: Int, frameCount: Int): FloatArray {
        val buffer = ByteBuffer.allocateDirect(frameCount * 4).order(ByteOrder.nativeOrder())
        remote.getPortBuffer(port, buffer, frameCount * 4)
        return FloatArray(frameCount).also { buffer.asFloatBuffer().get(it) }
    }

    // Keeps processing on another thread, as a host would. A patch only consumes the events sent to it
    // while it's processed, and some patch workers (e.g. PatchWorker's) block the main thread until then.
    fun whileProcessing(block: (blocksProcessed: AtomicInteger) -> Unit) {
        val running = AtomicBoolean(true)
        val blocks = AtomicInteger()
        val audio = thread(name = "test-audio") {
            while (running.get()) {
                process(256)
                blocks.incrementAndGet()
            }
        }
        try {
            block(blocks)
        } finally {
            running.set(false)
            audio.join()
        }
    }
}
