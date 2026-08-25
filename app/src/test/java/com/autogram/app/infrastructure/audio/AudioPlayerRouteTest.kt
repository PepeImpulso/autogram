package com.autogram.app.infrastructure.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.car.app.connection.CarConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure route-priority tests for the Android Auto playback bug. The Android
 * framework objects are hard to instantiate in local JVM tests, so the
 * production selector is intentionally factored down to device-type ints.
 */
class AudioPlayerRouteTest {

    @Test fun `projected car output leaves routing to the os`() {
        val selected = AudioPlayer.choosePreferredOutputType(
            routedTypes = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            outputTypes = listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_USB_ACCESSORY
            )
        )

        assertNull(selected)
    }

    @Test fun `projected car connection leaves routing to the os even without projected sink`() {
        val selected = AudioPlayer.choosePreferredOutputType(
            routedTypes = emptyList(),
            outputTypes = listOf(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            ),
            carConnectionType = CarConnection.CONNECTION_TYPE_PROJECTION
        )

        assertNull(selected)
    }

    @Test fun `routed bus output leaves routing to the os`() {
        val selected = AudioPlayer.choosePreferredOutputType(
            routedTypes = listOf(AudioDeviceInfo.TYPE_BUS, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            outputTypes = listOf(AudioDeviceInfo.TYPE_USB_DEVICE)
        )

        assertNull(selected)
    }

    @Test fun `bluetooth is preferred when no car output exists`() {
        val selected = AudioPlayer.choosePreferredOutputType(
            routedTypes = emptyList(),
            outputTypes = listOf(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            )
        )

        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, selected)
    }

    @Test fun `wired headset beats sco when no car or a2dp output exists`() {
        val selected = AudioPlayer.choosePreferredOutputType(
            routedTypes = emptyList(),
            outputTypes = listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_WIRED_HEADSET
            )
        )

        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADSET, selected)
    }

    @Test fun `sco is never forced for media voice playback`() {
        val selected = AudioPlayer.choosePreferredOutputType(
            routedTypes = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
            outputTypes = listOf(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            )
        )

        assertNull(selected)
    }

    @Test fun `no useful external output leaves routing to the os`() {
        val selected = AudioPlayer.choosePreferredOutputType(
            routedTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            outputTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        )

        assertNull(selected)
    }

    @Test fun `projected car output detection excludes plain usb headset`() {
        assertTrue(AudioPlayer.isProjectedCarOutputType(AudioDeviceInfo.TYPE_USB_ACCESSORY))
        assertTrue(!AudioPlayer.isProjectedCarOutputType(AudioDeviceInfo.TYPE_USB_HEADSET))
    }

    @Test fun `android auto connection promotes voice playback to navigation usage`() {
        assertTrue(
            AudioPlayer.shouldUseNavigationUsage(
                requestedNavigationUsage = false,
                carConnectionType = CarConnection.CONNECTION_TYPE_PROJECTION
            )
        )
    }

    @Test fun `projected output promotes playback when beta host connection state is stale`() {
        assertTrue(
            AudioPlayer.shouldUseNavigationUsage(
                requestedNavigationUsage = false,
                carConnectionType = CarConnection.CONNECTION_TYPE_NOT_CONNECTED,
                outputTypes = listOf(
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    AudioDeviceInfo.TYPE_BUS
                )
            )
        )
    }

    @Test fun `disconnected phone preserves requested navigation usage`() {
        assertTrue(
            AudioPlayer.shouldUseNavigationUsage(
                requestedNavigationUsage = true,
                carConnectionType = CarConnection.CONNECTION_TYPE_NOT_CONNECTED
            )
        )
        assertTrue(
            !AudioPlayer.shouldUseNavigationUsage(
                requestedNavigationUsage = false,
                carConnectionType = CarConnection.CONNECTION_TYPE_NOT_CONNECTED
            )
        )
    }

    @Test fun `permanent and transient focus loss terminate voice playback`() {
        assertTrue(AudioPlayer.shouldTerminateForFocusChange(AudioManager.AUDIOFOCUS_LOSS))
        assertTrue(AudioPlayer.shouldTerminateForFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
    }

    @Test fun `duck and gain focus changes keep voice playback alive`() {
        assertTrue(
            !AudioPlayer.shouldTerminateForFocusChange(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            )
        )
        assertTrue(!AudioPlayer.shouldTerminateForFocusChange(AudioManager.AUDIOFOCUS_GAIN))
    }
}
