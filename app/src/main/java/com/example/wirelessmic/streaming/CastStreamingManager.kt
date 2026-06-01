package com.example.wirelessmic.streaming

import android.content.Context
import android.util.Log
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages Google Cast (Chromecast) device discovery and audio streaming.
 * 
 * Supports:
 * - Chromecast devices
 * - Android TV with Chromecast built-in
 * - Chromecast Audio
 * - Smart speakers with Chromecast
 */
class CastStreamingManager(private val context: Context) {

    companion object {
        private const val TAG = "CastStreaming"
        
        // Default receiver app ID for media streaming
        // Use CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID for default receiver
        private const val CAST_APP_ID = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
    }

    /**
     * Data class representing a Cast device
     */
    data class CastDevice(
        val id: String,
        val name: String,
        val description: String?,
        val isConnected: Boolean = false
    ) {
        val displayName: String
            get() = if (description.isNullOrEmpty()) name else "$name ($description)"
    }

    // Cast context and session manager
    private var castContext: CastContext? = null
    private var sessionManager: SessionManager? = null
    private var mediaRouter: MediaRouter? = null
    private var mediaRouterCallback: MediaRouterCallback? = null
    
    // Discovered devices
    private val discoveredDevices = CopyOnWriteArrayList<CastDevice>()
    
    // Callbacks
    private var deviceCallback: ((List<CastDevice>) -> Unit)? = null
    private var connectionCallback: ((Boolean, String?) -> Unit)? = null

    /**
     * Initialize Cast SDK. Call this in onCreate.
     */
    fun initialize(): Boolean {
        return try {
            castContext = CastContext.getSharedInstance(context)
            sessionManager = castContext?.sessionManager
            mediaRouter = MediaRouter.getInstance(context)
            
            Log.i(TAG, "Cast SDK initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Cast SDK", e)
            false
        }
    }

    /**
     * Start discovering Cast devices
     */
    fun startDiscovery(onDevicesFound: (List<CastDevice>) -> Unit) {
        deviceCallback = onDevicesFound
        discoveredDevices.clear()
        
        val router = mediaRouter ?: run {
            Log.e(TAG, "MediaRouter not initialized")
            return
        }
        
        // Build selector for remote playback routes
        val selector = MediaRouteSelector.Builder()
            .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
            .addControlCategory(CastMediaControlIntent.categoryForCast(CAST_APP_ID))
            .build()
        
        mediaRouterCallback = MediaRouterCallback()
        router.addCallback(selector, mediaRouterCallback!!, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        
        // Scan existing routes
        scanExistingRoutes(router)
        
        Log.i(TAG, "Cast discovery started")
    }

    private fun scanExistingRoutes(router: MediaRouter) {
        val routes = router.routes
        for (route in routes) {
            if (route.isEnabled && !route.isDefault) {
                addDeviceFromRoute(route)
            }
        }
        notifyDevicesChanged()
    }

    private fun addDeviceFromRoute(route: MediaRouter.RouteInfo) {
        val device = CastDevice(
            id = route.id,
            name = route.name,
            description = route.description,
            isConnected = route.isSelected
        )
        
        // Only add if not already present
        if (discoveredDevices.none { it.id == device.id }) {
            discoveredDevices.add(device)
            Log.d(TAG, "Cast device found: ${device.displayName}")
        }
    }

    private fun removeDeviceById(routeId: String) {
        discoveredDevices.removeAll { it.id == routeId }
    }

    private fun notifyDevicesChanged() {
        deviceCallback?.invoke(discoveredDevices.toList())
    }

    /**
     * Stop discovering devices
     */
    fun stopDiscovery() {
        mediaRouterCallback?.let { callback ->
            mediaRouter?.removeCallback(callback)
        }
        mediaRouterCallback = null
        Log.i(TAG, "Cast discovery stopped")
    }

    /**
     * Get currently discovered devices
     */
    fun getDiscoveredDevices(): List<CastDevice> {
        return discoveredDevices.toList()
    }

    /**
     * Connect to a specific Cast device
     */
    fun connectToDevice(device: CastDevice, onResult: (Boolean, String?) -> Unit) {
        connectionCallback = onResult
        
        val router = mediaRouter ?: run {
            onResult(false, "MediaRouter not initialized")
            return
        }
        
        val route = router.routes.find { it.id == device.id }
        if (route == null) {
            onResult(false, "Device not found")
            return
        }
        
        try {
            router.selectRoute(route)
            Log.i(TAG, "Connecting to ${device.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to ${device.name}", e)
            onResult(false, e.message)
        }
    }

    /**
     * Disconnect from current Cast device
     */
    fun disconnect() {
        try {
            sessionManager?.currentCastSession?.let { session ->
                sessionManager?.endCurrentSession(true)
                Log.i(TAG, "Disconnected from Cast device")
            }
            
            // Also select the default route
            mediaRouter?.selectRoute(mediaRouter?.defaultRoute ?: return)
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }

    /**
     * Check if currently connected to a Cast device
     */
    fun isConnected(): Boolean {
        return sessionManager?.currentCastSession?.isConnected == true
    }

    /**
     * Get the currently connected device name
     */
    fun getConnectedDeviceName(): String? {
        return sessionManager?.currentCastSession?.castDevice?.friendlyName
    }

    /**
     * Get the streaming URL to use for Cast
     * This should be called to get the URL that Cast devices will connect to
     */
    fun getStreamUrl(localServerPort: Int): String {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val ipAddress = wifiManager.connectionInfo.ipAddress
        val formattedIp = String.format(
            "%d.%d.%d.%d",
            ipAddress and 0xff,
            ipAddress shr 8 and 0xff,
            ipAddress shr 16 and 0xff,
            ipAddress shr 24 and 0xff
        )
        return "http://$formattedIp:$localServerPort/audio.wav"
    }

    /**
     * Load and play audio stream on the Cast device
     */
    fun playAudioStream(streamUrl: String) {
        val session = sessionManager?.currentCastSession
        if (session == null || !session.isConnected) {
            Log.w(TAG, "No active Cast session")
            return
        }
        
        val remoteMediaClient = session.remoteMediaClient
        if (remoteMediaClient == null) {
            Log.w(TAG, "RemoteMediaClient not available")
            return
        }
        
        // Build media info
        val mediaInfo = com.google.android.gms.cast.MediaInfo.Builder(streamUrl)
            .setStreamType(com.google.android.gms.cast.MediaInfo.STREAM_TYPE_LIVE)
            .setContentType("audio/wav")
            .build()
        
        // Load media
        val mediaLoadOptions = com.google.android.gms.cast.MediaLoadOptions.Builder()
            .setAutoplay(true)
            .build()
        
        remoteMediaClient.load(mediaInfo, mediaLoadOptions)
            .setResultCallback { result ->
                if (result.status.isSuccess) {
                    Log.i(TAG, "Audio stream loaded successfully")
                } else {
                    Log.e(TAG, "Failed to load audio stream: ${result.status}")
                }
            }
    }

    /**
     * Stop playback on Cast device
     */
    fun stopPlayback() {
        sessionManager?.currentCastSession?.remoteMediaClient?.stop()
    }

    /**
     * Set volume on Cast device (0.0 to 1.0)
     */
    fun setVolume(volume: Float) {
        try {
            sessionManager?.currentCastSession?.setVolume(volume.toDouble())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume", e)
        }
    }

    /**
     * Mute/unmute Cast device
     */
    fun setMute(muted: Boolean) {
        try {
            sessionManager?.currentCastSession?.setMute(muted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set mute", e)
        }
    }

    /**
     * Clean up resources
     */
    fun release() {
        stopDiscovery()
        disconnect()
        discoveredDevices.clear()
        deviceCallback = null
        connectionCallback = null
    }

    /**
     * MediaRouter callback for Cast device discovery
     */
    private inner class MediaRouterCallback : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
            if (route.isEnabled && !route.isDefault) {
                addDeviceFromRoute(route)
                notifyDevicesChanged()
            }
        }

        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
            removeDeviceById(route.id)
            notifyDevicesChanged()
        }

        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
            // Update device state
            val index = discoveredDevices.indexOfFirst { it.id == route.id }
            if (index >= 0) {
                discoveredDevices[index] = CastDevice(
                    id = route.id,
                    name = route.name,
                    description = route.description,
                    isConnected = route.isSelected
                )
                notifyDevicesChanged()
            }
        }

        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) {
            Log.i(TAG, "Route selected: ${route.name}")
            
            // Mark as connected
            val index = discoveredDevices.indexOfFirst { it.id == route.id }
            if (index >= 0) {
                discoveredDevices[index] = discoveredDevices[index].copy(isConnected = true)
                notifyDevicesChanged()
            }
            
            connectionCallback?.invoke(true, null)
        }

        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) {
            Log.i(TAG, "Route unselected: ${route.name}")
            
            // Mark as disconnected
            val index = discoveredDevices.indexOfFirst { it.id == route.id }
            if (index >= 0) {
                discoveredDevices[index] = discoveredDevices[index].copy(isConnected = false)
                notifyDevicesChanged()
            }
        }
    }
}
