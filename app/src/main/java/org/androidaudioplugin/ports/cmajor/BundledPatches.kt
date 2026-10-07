package org.androidaudioplugin.ports.cmajor

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class PatchEntry(
    val identity: String,
    val name: String,
    val category: String,
    val isInstrument: Boolean,
    val hasView: Boolean,
)

/** The patches in assets/patches (cmajor's examples/patches, see app/build.gradle). */
object BundledPatches {
    private const val ASSET_ROOT = "patches"
    private const val IDENTITY_PREFIX = "cmajor-asset:///"

    /** Each folder under assets/patches holds a .cmajorpatch, or folders that do (e.g. Claude/FilterEQ). */
    fun list(context: Context): List<PatchEntry> {
        val assets = context.assets
        val result = mutableListOf<PatchEntry>()
        fun visit(dir: String, depth: Int) {
            val names = assets.list(dir) ?: return
            val manifests = names.filter { it.endsWith(".cmajorpatch") }
            if (manifests.isNotEmpty())
                manifests.forEach { describe(context, "$dir/$it")?.let(result::add) }
            else if (depth < 2)
                names.forEach { visit("$dir/$it", depth + 1) }
        }
        visit(ASSET_ROOT, 0)
        return result.sortedBy { it.name.lowercase() }
    }

    private fun describe(context: Context, path: String): PatchEntry? = runCatching {
        val manifest = JSONObject(context.assets.open(path).bufferedReader().use { it.readText() })
        val views = when (val view = manifest.opt("view")) {
            is JSONObject -> listOf(view)
            is JSONArray -> (0 until view.length()).mapNotNull { view.optJSONObject(it) }
            else -> emptyList()
        }
        PatchEntry(
            identity = IDENTITY_PREFIX + path,
            name = manifest.optString("name").ifEmpty { path.substringAfterLast('/').substringBeforeLast('.') },
            category = manifest.optString("category"),
            isInstrument = manifest.optBoolean("isInstrument"),
            hasView = views.any { it.optString("src").isNotEmpty() },
        )
    }.onFailure { Log.w("AAP.Cmajor", "Skipping unreadable patch manifest $path", it) }.getOrNull()
}
