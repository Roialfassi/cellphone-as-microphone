package com.example.wirelessmic.streaming

import android.content.Context
import android.util.Log

/**
 * Unified manager for all network streaming methods.
 * Coordinates DLNA and Google Cast streaming.
 */
class NetworkStreamingManager(private val context: Context) {

    companion object {
        private const val TAG = "NetworkStreaming"
        private const val STREAM_PORT = 8080
    }

    /**
     * Represents any streamable network device
     */
    sealed class NetworkDevice {
        abstract val id: String
        abstract val name: String
        abstract val displayName: String
        abstract val isConnected: Boolean
        
        data class DlnaDevice(
            override val id: String,
            override val name: String,
            override val displayName: String,
            override val isConnected: Boolean,
            val host: String,
            val port: Int
        ) : NetworkDevice()
        
        data class CastDevice(
            override val id: String,
            override val name: String,
            override val displayName: String,
            override val isConnected: Boolean
        ) : NetworkDevice()
    }

    /**
     * Streaming state
     */
    enum class StreamingState {
        IDLE,
        DISCOVERING,
        CONNECTING,
        STREAMING,
        ERROR
    }

    // Managers
    private val dlnaManager = DlnaStreamingManager(context)
    private var castManager: CastStreamingManager? = null
    
    // State
    private var currentState = StreamingState.IDLE
    private var connectedDevice: NetworkDevice? = null
    
    // Callbacks
    private var devicesCallback: ((List<NetworkDevice>) -> Unit)? = null
    private var stateCallback: ((StreamingState, String?) -> Unit)? = null

    /**
     * Initialize the streaming manager
     */
    fun initialize() {
        try {
            castManager = CastStreamingManager(context).apply {
                initialize()
            }
            Log.i(TAG, "NetworkStreamingManager initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Cast not available, using DLNA only", e)
            castManager = null
        }
    }

    /**
     * Start discovering all available network devices
     */
    fun startDiscovery(
        onDevicesFound: (List<NetworkDevice>) -> Unit,
        onStateChanged: (StreamingState, String?) -> Unit
    ) {
        devicesCallback = onDevicesFound
        stateCallback = onStateChanged
        
        updateState(StreamingState.DISCOVERING)
        
        val allDevices = mutableListOf<NetworkDevice>()
        
        // Start DLNA discovery
        dlnaManager.startDiscovery { dlnaDevices ->
            synchronized(allDevices) {
                allDevices.removeAll { it is NetworkDevice.DlnaDevice }
                allDevices.addAll(dlnaDevices.map { device ->
                    NetworkDevice.DlnaDevice(
                        id = "dlna_${device.host}:${device.port}",
                        name = device.name,
                        displayName = "📺 ${device.name} (DLNA)",
                        isConnected = false,
                        host = device.host,
                        port = device.port
                    )
                })
                notifyDevicesChanged(allDevices)
            }
        }
        
        // Start Cast discovery
        castManager?.startDiscovery { castDevices ->
            synchronized(allDevices) {
                allDevices.removeAll { it is NetworkDevice.CastDevice }
                allDevices.addAll(castDevices.map { device ->
                    NetworkDevice.CastDevice(
                        id = "cast_${device.id}",
                        name = device.name,
                        displayName = "📡 ${device.name} (Chromecast)",
                        isConnected = device.isConnected
                    )
                })
                notifyDevicesChanged(allDevices)
            }
        }
    }

    private fun notifyDevicesChanged(devices: List<NetworkDevice>) {
        devicesCallback?.invoke(devices.sortedBy { it.name })
    }

    private fun updateState(state: StreamingState, message: String? = null) {
        currentState = state
        stateCallback?.invoke(state, message)
    }

    /**
     * Stop device discovery
     */
    fun stopDiscovery() {
        dlnaManager.stopDiscovery()
        castManager?.stopDiscovery()
        
        if (currentState == StreamingState.DISCOVERING) {
            updateState(StreamingState.IDLE)
        }
    }

    /**
     * Connect to a network device
     */
    fun connectToDevice(device: NetworkDevice, onResult: (Boolean, String?) -> Unit) {
        updateState(StreamingState.CONNECTING)
        
        when (device) {
            is NetworkDevice.DlnaDevice -> {
                // For DLNA, we start the streaming server
                dlnaManager.startStreamingServer { streamUrl ->
                    Log.i(TAG, "DLNA streaming server ready at $streamUrl")
                    connectedDevice = device
                    updateState(StreamingState.STREAMING)
                    onResult(true, "Streaming to ${device.name}\nOpen $streamUrl on your TV/device")
                }
            }
            is NetworkDevice.CastDevice -> {
                val originalId = device.id.removePrefix("cast_")
                val castDevice = castManager?.getDiscoveredDevices()?.find { it.id == originalId }
                    ?: run {
                        onResult(false, "Device not found")
                        updateState(StreamingState.ERROR)
                        return
                    }
                
                castManager?.connectToDevice(castDevice) { success, error ->
                    if (success) {
                        // Start local server for Cast to connect to
                        dlnaManager.startStreamingServer { _ ->
                            val streamUrl = castManager?.getStreamUrl(STREAM_PORT) ?: return@startStreamingServer
                            castManager?.playAudioStream(streamUrl)
                            connectedDevice = device
                            updateState(StreamingState.STREAMING)
                            onResult(true, null)
                        }
                    } else {
                        updateState(StreamingState.ERROR, error)
                        onResult(false, error)
                    }
                }
            }
        }
    }

    /**
     * Disconnect from current device
     */
    fun disconnect() {
        castManager?.let {
            it.stopPlayback()
            it.disconnect()
        }
        dlnaManager.stopStreamingServer()
        
        connectedDevice = null
        updateState(StreamingState.IDLE)
    }

    /**
     * Check if currently connected to any device
     */
    fun isConnected(): Boolean {
        return connectedDevice != null && currentState == StreamingState.STREAMING
    }

    /**
     * Get the currently connected device
     */
    fun getConnectedDevice(): NetworkDevice? = connectedDevice

    /**
     * Stream audio data to connected devices
     */
    fun streamAudioData(audioData: ShortArray, size: Int) {
        if (!isConnected()) return
        dlnaManager.streamAudioData(audioData, size)
    }

    /**
     * Set volume on Cast device (0.0 to 1.0)
     */
    fun setRemoteVolume(volume: Float) {
        if (connectedDevice is NetworkDevice.CastDevice) {
            castManager?.setVolume(volume)
        }
    }

    /**
     * Mute/unmute remote device
     */
    fun setRemoteMute(muted: Boolean) {
        if (connectedDevice is NetworkDevice.CastDevice) {
            castManager?.setMute(muted)
        }
    }

    /**
     * Get current streaming state
     */
    fun getCurrentState(): StreamingState = currentState

    /**
     * Clean up all resources
     */
    fun release() {
        disconnect()
        dlnaManager.release()
        castManager?.release()
        devicesCallback = null
        stateCallback = null
    }
}
