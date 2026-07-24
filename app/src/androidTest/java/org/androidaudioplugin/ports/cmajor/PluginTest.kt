package org.androidaudioplugin.ports.cmajor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.androidaudioplugin.androidaudioplugin.testing.AudioPluginServiceTesting
import org.junit.Test

// On-device verification for the experimental cmajor AAP wrapper (see
// external/cmajor/modules/plugin/include/aap/cmaj_AAPPlugin.cpp). This exercises the
// real AAP hosting path (service connect, instantiate, prepare, activate, process,
// deactivate, destroy) against a connected device via `./gradlew connectedDebugAndroidTest`.
class PluginTest {
    private val applicationContext = ApplicationProvider.getApplicationContext<Context>()
    private val testing = AudioPluginServiceTesting(applicationContext)

    @Test
    fun basicServiceOperationsForAllPlugins() {
        testing.basicServiceOperationsForAllPlugins()
    }
}
