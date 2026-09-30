package org.androidaudioplugin.ports.cmajor;

import android.content.Context;
import android.util.Size;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import org.androidaudioplugin.AudioPluginServiceHelper;
import org.androidaudioplugin.AudioPluginViewFactory;
import org.androidaudioplugin.ui.compose.ComposeAudioPluginViewFactory;

// Embeds the cmajor patch's own view (a WebView, see cmaj_AAPPlugin.cpp) as the native UI.
// Patches without a view of their own get the generic Compose UI instead.
public class CmajorPatchViewFactory extends AudioPluginViewFactory {
    static {
        System.loadLibrary("aap-cmajor-sample");
    }

    private static native int[] getPatchViewSize();
    private static native boolean attachEditor(long serviceInstance, int instanceId, double scale, ViewGroup parent);
    private static native void detachEditor(long serviceInstance, int instanceId);

    private final ComposeAudioPluginViewFactory fallback = new ComposeAudioPluginViewFactory();

    private static final class EditorHost extends FrameLayout {
        EditorHost(Context context) {
            super(context);
        }
    }

    @Override
    public Size getPreferredSize(Context context, String pluginId, int instanceId) {
        int[] size = getPatchViewSize();
        if (size == null)
            return fallback.getPreferredSize(context, pluginId, instanceId);
        if (size[0] <= 0 || size[1] <= 0)
            return null;
        float density = context.getResources().getDisplayMetrics().density;
        return new Size(Math.round(size[0] * density), Math.round(size[1] * density));
    }

    @Override
    public View createView(Context context, String pluginId, int instanceId) {
        if (getPatchViewSize() != null) {
            EditorHost host = new EditorHost(context);
            double density = context.getResources().getDisplayMetrics().density;
            if (attachEditor(AudioPluginServiceHelper.getServiceInstance(pluginId), instanceId, density, host))
                return host;
        }
        return fallback.createView(context, pluginId, instanceId);
    }

    @Override
    public void maybeDestroyView(Context context, String pluginId, int instanceId, View view) {
        if (view instanceof EditorHost)
            detachEditor(AudioPluginServiceHelper.getServiceInstance(pluginId), instanceId);
        else
            fallback.maybeDestroyView(context, pluginId, instanceId, view);
    }
}
