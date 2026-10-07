package org.androidaudioplugin.ports.cmajor

import android.view.ViewGroup
import org.json.JSONObject

/** The plugin UI's access to its plugin instance (see cmaj_AAPPlugin.cpp in external/cmajor).
 * The UI runs in the plugin's own process, so these work on the local instance directly. */
object CmajorNative {
    init {
        System.loadLibrary("aap-cmajor-sample")
    }

    /** Switches the instance to the patch. It blocks while the patch is built (which can take seconds),
     * so don't call it on the main thread. Returns an error message, or null on success. */
    @JvmStatic external fun loadPatch(nativeService: Long, instanceId: Int, identity: String): String?

    /** A JSON object, see [PatchStatus], or null if the instance is gone. */
    @JvmStatic external fun getPatchStatus(nativeService: Long, instanceId: Int): String?

    /** The current patch's own view size in CSS pixels (which may be zero), or null if it has no view. */
    @JvmStatic external fun getPatchViewSize(nativeService: Long, instanceId: Int): IntArray?

    /** Attaches the current patch's view to [parent]. Returns its [PatchStatus.generation], or -1. */
    @JvmStatic external fun attachEditor(nativeService: Long, instanceId: Int, scale: Double, parent: ViewGroup): Int

    /** Closes the view attached by [attachEditor], unless the patch has changed meanwhile. */
    @JvmStatic external fun detachEditor(nativeService: Long, instanceId: Int, generation: Int)
}

/** What the plugin instance is playing. [generation] changes whenever the patch does. */
data class PatchStatus(
    val identity: String,
    val name: String,
    val loaded: Boolean,
    val building: Boolean,
    val hasView: Boolean,
    val error: String,
    val generation: Int,
    val revision: Int,
    val parameterCount: Int,
) {
    companion object {
        fun read(nativeService: Long, instanceId: Int): PatchStatus? =
            CmajorNative.getPatchStatus(nativeService, instanceId)?.let { json ->
                val o = JSONObject(json)
                PatchStatus(
                    identity = o.optString("identity"),
                    name = o.optString("name"),
                    loaded = o.optBoolean("loaded"),
                    building = o.optBoolean("building"),
                    hasView = o.optBoolean("hasView"),
                    error = o.optString("error"),
                    generation = o.optInt("generation"),
                    revision = o.optInt("revision"),
                    parameterCount = o.optInt("parameterCount"),
                )
            }
    }
}
