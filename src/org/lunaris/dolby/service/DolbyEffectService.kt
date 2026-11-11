/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.data.DeviceStateManager
import org.lunaris.dolby.data.DolbyRepository

class DolbyEffectService : Service() {

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val dolbyPrefs: SharedPreferences by lazy {
        getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)
    }
    private val isDeviceStateMemoryEnabled: Boolean
        get() = dolbyPrefs.getBoolean(DolbyConstants.PREF_DEVICE_STATE_MEMORY, false)

    private lateinit var workerThread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var repository: DolbyRepository
    private lateinit var deviceStateManager: DeviceStateManager
    private var previousActiveDevice: AudioDeviceInfo? = null
    private var wasPlaying = false

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            Log.d(TAG, "Devices added: ${addedDevices.map { it.debugString() }}")
            handleDeviceChange()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            Log.d(TAG, "Devices removed: ${removedDevices.map { it.debugString() }}")
            if (isDeviceStateMemoryEnabled) {
                removedDevices.forEach { device ->
                    val key = deviceStateManager.deviceKey(device)
                    Log.d(TAG, "Snapshotting state for removed device: $key")
                    deviceStateManager.saveSnapshot(key, repository)
                }
            }
            handleDeviceChange()
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val isActive = configs?.any { it.isActive } == true
            // Re-apply only on the silent -> playing transition. Re-applying on every playback
            // callback floods the Dolby HAL with parameters and can stall the UI thread.
            if (isActive && !wasPlaying) {
                Log.d(TAG, "Playback became active, applying saved state")
                repository.applySavedState()
            }
            wasPlaying = isActive
        }
    }

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("DolbyEffectService").apply { start() }
        handler = Handler(workerThread.looper)
        handler.post { initialize() }
    }

    private fun initialize() {
        try {
            repository = DolbyRepository(this)
            deviceStateManager = DeviceStateManager(this)

            val currentDevice = getCurrentOutputDevice()
            if (currentDevice != null) {
                previousActiveDevice = currentDevice
                if (isDeviceStateMemoryEnabled) {
                    val key = deviceStateManager.deviceKey(currentDevice)
                    val restored = deviceStateManager.restoreSnapshot(key, repository)
                    if (!restored) repository.applySavedState()
                } else {
                    repository.applySavedState()
                }
            } else {
                repository.applySavedState()
            }

            audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
            audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
            Log.d(TAG, "Dolby effect service created")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Dolby effect service", e)
            stopSelf()
        }
    }

    private fun handleDeviceChange() {
        val newDevice = getCurrentOutputDevice()
        val oldDevice = previousActiveDevice

        if (oldDevice != null) {
            if (isDeviceStateMemoryEnabled) {
                val oldKey = deviceStateManager.deviceKey(oldDevice)
                Log.d(TAG, "Saving snapshot for previous device: $oldKey")
                deviceStateManager.saveSnapshot(oldKey, repository)
            }
        }

        if (newDevice != null) {
            val newKey = deviceStateManager.deviceKey(newDevice)
            if (isDeviceStateMemoryEnabled) {
                Log.d(TAG, "Restoring snapshot for new device: $newKey")
                val restored = deviceStateManager.restoreSnapshot(newKey, repository)
                if (!restored) {
                    Log.d(TAG, "First time device, applying saved state as base")
                    repository.applySavedState()
                }
            } else {
                Log.d(TAG, "Device state memory disabled, applying saved state")
                repository.applySavedState()
            }
            previousActiveDevice = newDevice
        } else {
            repository.updateSpeakerState()
            repository.applySavedState()
            previousActiveDevice = null
        }
    }

    private fun getCurrentOutputDevice(): AudioDeviceInfo? {
        val routedDevice = try {
            audioManager
                .getDevicesForAttributes(ATTRIBUTES_MEDIA)
                .firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get active media route", e)
            null
        } ?: return null

        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val routedAddress = routedDevice.address.orEmpty()

        return outputs.firstOrNull { device ->
            device.isSink &&
                device.type == routedDevice.type &&
                (routedAddress.isEmpty() || device.address == routedAddress)
        } ?: outputs.firstOrNull { device ->
            device.isSink && device.type == routedDevice.type
        }.also { device ->
            if (device == null) {
                Log.w(
                    TAG,
                    "Unable to map active media route: " +
                        "type=${routedDevice.type}, address=${routedDevice.address}"
                )
            }
        }
    }

    private fun AudioDeviceInfo.debugString(): String =
        "name=$productName,type=$type,id=$id,address=$address,isSink=$isSink"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (::handler.isInitialized) {
            handler.post {
                if (::repository.isInitialized) {
                    repository.applySavedState()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::handler.isInitialized) {
            handler.removeCallbacksAndMessages(null)
            handler.post {
                try {
                    audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
                    audioManager.unregisterAudioPlaybackCallback(playbackCallback)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unregister audio callbacks", e)
                }
                if (::repository.isInitialized && isDeviceStateMemoryEnabled) {
                    previousActiveDevice?.let { device ->
                        val key = deviceStateManager.deviceKey(device)
                        deviceStateManager.saveSnapshot(key, repository)
                    }
                }
                workerThread.quitSafely()
            }
        }
        Log.d(TAG, "Dolby effect service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DolbyEffectService"

        private val ATTRIBUTES_MEDIA = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        fun start(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            context.stopService(intent)
        }
    }
}
