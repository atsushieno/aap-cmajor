package org.androidaudioplugin.ports.cmajor

import android.content.Context
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.androidaudioplugin.AudioPluginServiceHelper
import org.androidaudioplugin.AudioPluginViewFactory
import org.androidaudioplugin.NativeLocalPluginInstance
import org.androidaudioplugin.NativePluginService
import org.androidaudioplugin.ui.compose.ComposeAudioPluginViewFactory
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The plugin UI, hosted in the plugin process (by AudioPluginViewService, or the in-app plugin manager).
 * It has two tabs: "Patch" shows the current patch's own view (a WebView, see cmaj_AAPPlugin.cpp),
 * or the generic parameter UI for patches without one, and "Patches" switches to another patch.
 */
class CmajorPatchViewFactory : AudioPluginViewFactory() {
    companion object {
        private const val TAB_ROW_HEIGHT_DP = 48f
        private const val MIN_WIDTH_DP = 360f
        private const val MIN_HEIGHT_DP = 360f
        private const val DEFAULT_WIDTH_DP = 420f
        private const val DEFAULT_HEIGHT_DP = 640f

        // The patch view's CSS pixels map to dp, as the editor is attached with the display density as its scale.
        internal fun preferredSize(context: Context, nativeService: Long, instanceId: Int): Size {
            val viewSize = CmajorNative.getPatchViewSize(nativeService, instanceId)
                ?.takeIf { it[0] > 0 && it[1] > 0 }
            val width = viewSize?.get(0)?.toFloat() ?: DEFAULT_WIDTH_DP
            val height = viewSize?.get(1)?.toFloat() ?: DEFAULT_HEIGHT_DP
            val density = context.resources.displayMetrics.density
            return Size(
                (max(width, MIN_WIDTH_DP) * density).roundToInt(),
                ((max(height, MIN_HEIGHT_DP) + TAB_ROW_HEIGHT_DP) * density).roundToInt(),
            )
        }
    }

    override fun getPreferredSize(context: Context, pluginId: String, instanceId: Int): Size =
        preferredSize(context, AudioPluginServiceHelper.getServiceInstance(pluginId), instanceId)

    override fun createView(context: Context, pluginId: String, instanceId: Int): View {
        // Resolve inside AAP's instance scope, before callbacks leave createView().
        val nativeService = AudioPluginServiceHelper.getServiceInstance(pluginId)
        val localInstance = NativePluginService(pluginId).getInstance(instanceId)
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                MaterialTheme {
                    CmajorPluginUi(context, pluginId, instanceId, nativeService, localInstance,
                        onPatchChanged = { fitToPatch(context, nativeService, instanceId) })
                }
            }
        }
    }

    override fun maybeDestroyView(context: Context, pluginId: String, instanceId: Int, view: View) {
        (view as? ComposeView)?.disposeComposition()
    }

    // Where the host gave the view a fixed size (as AudioPluginViewService does), ask for the new
    // patch's size; AudioPluginViewService reports the change to the host.
    private fun ComposeView.fitToPatch(context: Context, nativeService: Long, instanceId: Int) {
        val params = layoutParams ?: return
        if (params.width <= 0 || params.height <= 0)
            return
        val size = preferredSize(context, nativeService, instanceId)
        if (params.width != size.width || params.height != size.height) {
            params.width = size.width
            params.height = size.height
            layoutParams = params
        }
    }
}

private const val LOG_TAG = "AAP.Cmajor"
private const val TAB_PATCH = 0
private const val TAB_PATCHES = 1

@Composable
private fun CmajorPluginUi(
    context: Context,
    pluginId: String,
    instanceId: Int,
    nativeService: Long,
    localInstance: NativeLocalPluginInstance,
    onPatchChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(PatchStatus.read(nativeService, instanceId)) }
    // aap-core rescans the parameters asynchronously after a patch change; the generic UI waits for it.
    var hostParameterCount by remember { mutableIntStateOf(localInstance.getParameterCount()) }
    var patches by remember { mutableStateOf<List<PatchEntry>?>(null) }
    var selectedTab by remember { mutableIntStateOf(TAB_PATCH) }
    var loadingPatch by remember { mutableStateOf<PatchEntry?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        patches = withContext(Dispatchers.IO) { BundledPatches.list(context) }
    }
    // The patch may also change without this UI, e.g. when the host restores a state.
    LaunchedEffect(Unit) {
        while (true) {
            PatchStatus.read(nativeService, instanceId)?.let { if (it != status) status = it }
            hostParameterCount = localInstance.getParameterCount()
            delay(300)
        }
    }
    var lastGeneration by remember { mutableIntStateOf(status?.generation ?: 0) }
    LaunchedEffect(status?.generation) {
        val generation = status?.generation ?: return@LaunchedEffect
        if (generation != lastGeneration) {
            lastGeneration = generation
            onPatchChanged()
        }
    }

    fun load(entry: PatchEntry) {
        loadingPatch = entry
        loadError = null
        scope.launch {
            val error = withContext(Dispatchers.IO) { CmajorNative.loadPatch(nativeService, instanceId, entry.identity) }
            loadingPatch = null
            status = PatchStatus.read(nativeService, instanceId)
            if (error == null)
                selectedTab = TAB_PATCH
            else
                loadError = error
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == TAB_PATCH, onClick = { selectedTab = TAB_PATCH },
                text = { Text(status?.name?.ifEmpty { null } ?: "Patch", maxLines = 1, overflow = TextOverflow.Ellipsis) })
            Tab(selected = selectedTab == TAB_PATCHES, onClick = { selectedTab = TAB_PATCHES },
                text = { Text("Patches") })
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            // Kept composed while the other tab is shown, so that switching tabs doesn't rebuild the WebView.
            PatchPane(context, pluginId, instanceId, nativeService, status, hostParameterCount,
                visible = selectedTab == TAB_PATCH)
            if (selectedTab == TAB_PATCHES)
                PatchesPane(patches, status, loadingPatch, loadError, onSelect = ::load)
        }
    }
}

@Composable
private fun PatchPane(
    context: Context,
    pluginId: String,
    instanceId: Int,
    nativeService: Long,
    status: PatchStatus?,
    hostParameterCount: Int,
    visible: Boolean,
) {
    when {
        status == null -> Message("The plugin instance is gone.")
        !status.loaded && status.building -> Message("Building ${status.name}…")
        !status.loaded -> Message(status.error.ifEmpty { "${status.name} will be built once the plugin is prepared." })
        status.hasView -> key(status.generation) { PatchEditor(nativeService, instanceId, visible) }
        else -> key(status.generation, hostParameterCount) {
            AndroidView(
                factory = { createParameterView(context, pluginId, instanceId) },
                update = { it.visibility = if (visible) View.VISIBLE else View.INVISIBLE },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Hosts the patch's own view, which the native side attaches as a child of a FrameLayout. */
@Composable
private fun PatchEditor(nativeService: Long, instanceId: Int, visible: Boolean) {
    val attached = remember { intArrayOf(-1) }
    AndroidView(
        factory = { context ->
            FrameLayout(context).also { host ->
                attached[0] = CmajorNative.attachEditor(nativeService, instanceId,
                    context.resources.displayMetrics.density.toDouble(), host)
                if (attached[0] < 0)
                    Log.w(LOG_TAG, "Could not attach the patch view")
            }
        },
        update = { it.visibility = if (visible) View.VISIBLE else View.INVISIBLE },
        onRelease = { host ->
            CmajorNative.detachEditor(nativeService, instanceId, attached[0])
            host.removeAllViews()
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun PatchesPane(
    patches: List<PatchEntry>?,
    status: PatchStatus?,
    loadingPatch: PatchEntry?,
    loadError: String?,
    onSelect: (PatchEntry) -> Unit,
) {
    val busy = loadingPatch != null || status?.building == true
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    loadingPatch != null -> "Building ${loadingPatch.name}…"
                    status?.building == true -> "Building ${status.name}…"
                    status == null -> "The plugin instance is gone."
                    status.loaded -> "Playing: ${status.name}"
                    else -> "Not playing: ${status.name}"
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            (loadError ?: status?.error?.ifEmpty { null })?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 6,
                    overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider()
            when {
                patches == null -> Text("Finding patches…")
                patches.isEmpty() -> Text("No patches are bundled.")
                else -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    item {
                        Text("Bundled examples", style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(vertical = 8.dp))
                    }
                    items(patches, key = { it.identity }) { entry ->
                        val selected = entry.identity == status?.identity
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                                .clickable(enabled = !busy) { onSelect(entry) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            val details = listOfNotNull(
                                entry.category.ifEmpty { null },
                                if (entry.isInstrument) "instrument" else null,
                                if (entry.hasView) "own UI" else null,
                            )
                            if (details.isNotEmpty())
                                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun createParameterView(context: Context, pluginId: String, instanceId: Int): View =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        ComposeAudioPluginViewFactory().createView(context, pluginId, instanceId)
    else
        ComposeView(context).apply {
            setContent { MaterialTheme { Message("Plugin parameters require Android 11 or later.") } }
        }
