package com.example.wirelessmic.streaming

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discovers and streams audio to DLNA/UPnP compatible devices (Smart TVs, speakers, etc.)
 * 
 * This uses Android's Network Service Discovery (NSD) to find UPnP media renderers
 * and streams raw PCM audio over HTTP.
 */
class DlnaStreamingManager(private val context: Context) {

    companion object {
        private const val TAG = "DlnaStreaming"
        
        // UPnP/DLNA service types
        private const val SERVICE_TYPE_UPNP = "_upnp._tcp."
        private const val SERVICE_TYPE_SSDP = "_ssdp._udp."
        
        // Audio streaming configuration
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        
        // HTTP server port for audio streaming
        private const val STREAM_PORT = 8080
    }

    // Discovered devices
    private val discoveredDevices = ConcurrentHashMap<String, DlnaDevice>()
    
    // NSD Manager for device discovery
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    
    // Streaming server
    private var streamingServer: StreamingServer? = null
    private val isDiscovering = AtomicBoolean(false)
    
    // Callbacks
    private var deviceCallback: ((List<DlnaDevice>) -> Unit)? = null
    
    /**
     * Data class representing a discovered DLNA device
     */
    data class DlnaDevice(
        val name: String,
        val host: String,
        val port: Int,
        val type: DeviceType = DeviceType.UNKNOWN
    ) {
        enum class DeviceType {
            TV,
            SPEAKER,
            MEDIA_RENDERER,
            UNKNOWN
        }
        
        val displayName: String
            get() = "$name (${type.name.replace("_", " ")})"
    }

    /**
     * Start discovering DLNA devices on the network
     */
    fun startDiscovery(onDevicesFound: (List<DlnaDevice>) -> Unit) {
        if (isDiscovering.get()) {
            Log.d(TAG, "Discovery already in progress")
            return
        }
        
        deviceCallback = onDevicesFound
        discoveredDevices.clear()
        
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "DLNA discovery started for $serviceType")
                isDiscovering.set(true)
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                resolveService(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                discoveredDevices.remove(serviceInfo.serviceName)
                notifyDevicesChanged()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped for $serviceType")
                isDiscovering.set(false)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                isDiscovering.set(false)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
            }
        }

        try {
            nsdManager?.discoverServices(SERVICE_TYPE_UPNP, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                Log.i(TAG, "Service resolved: ${resolvedInfo.serviceName} at ${resolvedInfo.host}:${resolvedInfo.port}")
                
                val device = DlnaDevice(
                    name = resolvedInfo.serviceName,
                    host = resolvedInfo.host?.hostAddress ?: "",
                    port = resolvedInfo.port,
                    type = determineDeviceType(resolvedInfo)
                )
                
                if (device.host.isNotEmpty()) {
                    discoveredDevices[resolvedInfo.serviceName] = device
                    notifyDevicesChanged()
                }
            }
        })
    }

    private fun determineDeviceType(serviceInfo: NsdServiceInfo): DlnaDevice.DeviceType {
        val name = serviceInfo.serviceName.lowercase()
        return when {
            name.contains("tv") || name.contains("television") -> DlnaDevice.DeviceType.TV
            name.contains("speaker") || name.contains("soundbar") -> DlnaDevice.DeviceType.SPEAKER
            name.contains("renderer") || name.contains("media") -> DlnaDevice.DeviceType.MEDIA_RENDERER
            else -> DlnaDevice.DeviceType.UNKNOWN
        }
    }

    private fun notifyDevicesChanged() {
        deviceCallback?.invoke(discoveredDevices.values.toList())
    }

    /**
     * Stop discovering devices
     */
    fun stopDiscovery() {
        if (!isDiscovering.get()) return
        
        try {
            discoveryListener?.let { listener ->
                nsdManager?.stopServiceDiscovery(listener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery", e)
        }
        
        discoveryListener = null
        isDiscovering.set(false)
    }

    /**
     * Get currently discovered devices
     */
    fun getDiscoveredDevices(): List<DlnaDevice> {
        return discoveredDevices.values.toList()
    }

    /**
     * Start the HTTP streaming server for audio
     */
    fun startStreamingServer(onReady: (String) -> Unit) {
        streamingServer?.stop()
        streamingServer = StreamingServer(STREAM_PORT).apply {
            start()
            onReady("http://0.0.0.0:$STREAM_PORT/audio.wav")
        }
    }

    /**
     * Write audio data to all connected clients
     */
    fun streamAudioData(audioData: ShortArray, size: Int) {
        streamingServer?.broadcastAudio(audioData, size)
    }

    /**
     * Stop the streaming server
     */
    fun stopStreamingServer() {
        streamingServer?.stop()
        streamingServer = null
    }

    /**
     * Clean up all resources
     */
    fun release() {
        stopDiscovery()
        stopStreamingServer()
        discoveredDevices.clear()
        deviceCallback = null
    }

    /**
     * Internal HTTP server for streaming WAV audio
     */
    private inner class StreamingServer(private val port: Int) {
        private var serverSocket: ServerSocket? = null
        private val clients = ConcurrentHashMap<String, ClientConnection>()
        private var serverThread: Thread? = null
        private val isRunning = AtomicBoolean(false)

        fun start() {
            if (isRunning.get()) return
            
            serverThread = Thread {
                try {
                    serverSocket = ServerSocket(port)
                    isRunning.set(true)
                    Log.i(TAG, "Streaming server started on port $port")
                    
                    while (isRunning.get()) {
                        try {
                            val clientSocket = serverSocket?.accept() ?: continue
                            handleClient(clientSocket)
                        } catch (e: Exception) {
                            if (isRunning.get()) {
                                Log.e(TAG, "Error accepting client", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Server error", e)
                }
            }.apply { start() }
        }

        private fun handleClient(socket: Socket) {
            val clientId = "${socket.inetAddress}:${socket.port}"
            Log.i(TAG, "Client connected: $clientId")
            
            try {
                val outputStream = socket.getOutputStream()
                
                // Send HTTP headers
                val headers = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: audio/wav\r\n")
                    append("Transfer-Encoding: chunked\r\n")
                    append("Connection: keep-alive\r\n")
                    append("Cache-Control: no-cache\r\n")
                    append("\r\n")
                }
                outputStream.write(headers.toByteArray())
                
                // Send WAV header
                outputStream.write(createWavHeader())
                outputStream.flush()
                
                clients[clientId] = ClientConnection(socket, outputStream)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client $clientId", e)
                socket.close()
            }
        }

        fun broadcastAudio(audioData: ShortArray, size: Int) {
            // Convert shorts to bytes (little endian)
            val byteData = ByteArray(size * 2)
            for (i in 0 until size) {
                byteData[i * 2] = (audioData[i].toInt() and 0xFF).toByte()
                byteData[i * 2 + 1] = (audioData[i].toInt() shr 8 and 0xFF).toByte()
            }
            
            val deadClients = mutableListOf<String>()
            
            clients.forEach { (clientId, connection) ->
                try {
                    connection.outputStream.write(byteData)
                    connection.outputStream.flush()
                } catch (e: Exception) {
                    Log.d(TAG, "Client disconnected: $clientId")
                    deadClients.add(clientId)
                }
            }
            
            // Remove dead clients
            deadClients.forEach { clientId ->
                clients.remove(clientId)?.let { conn ->
                    try { conn.socket.close() } catch (_: Exception) {}
                }
            }
        }

        private fun createWavHeader(): ByteArray {
            // Create a WAV header for streaming (infinite length)
            val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
            val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
            
            return ByteArray(44).apply {
                // RIFF header
                "RIFF".toByteArray().copyInto(this, 0)
                // File size - 8 (use max value for streaming)
                writeInt(this, 4, Int.MAX_VALUE)
                "WAVE".toByteArray().copyInto(this, 8)
                
                // fmt subchunk
                "fmt ".toByteArray().copyInto(this, 12)
                writeInt(this, 16, 16) // Subchunk1Size (16 for PCM)
                writeShort(this, 20, 1) // AudioFormat (1 = PCM)
                writeShort(this, 22, CHANNELS.toShort())
                writeInt(this, 24, SAMPLE_RATE)
                writeInt(this, 28, byteRate)
                writeShort(this, 32, blockAlign.toShort())
                writeShort(this, 34, BITS_PER_SAMPLE.toShort())
                
                // data subchunk
                "data".toByteArray().copyInto(this, 36)
                writeInt(this, 40, Int.MAX_VALUE) // Subchunk2Size (infinite for streaming)
            }
        }

        private fun writeInt(buffer: ByteArray, offset: Int, value: Int) {
            buffer[offset] = (value and 0xFF).toByte()
            buffer[offset + 1] = (value shr 8 and 0xFF).toByte()
            buffer[offset + 2] = (value shr 16 and 0xFF).toByte()
            buffer[offset + 3] = (value shr 24 and 0xFF).toByte()
        }

        private fun writeShort(buffer: ByteArray, offset: Int, value: Short) {
            buffer[offset] = (value.toInt() and 0xFF).toByte()
            buffer[offset + 1] = (value.toInt() shr 8 and 0xFF).toByte()
        }

        fun stop() {
            isRunning.set(false)
            
            clients.forEach { (_, connection) ->
                try { connection.socket.close() } catch (_: Exception) {}
            }
            clients.clear()
            
            try { serverSocket?.close() } catch (_: Exception) {}
            serverSocket = null
            
            serverThread?.interrupt()
            serverThread = null
            
            Log.i(TAG, "Streaming server stopped")
        }

        private data class ClientConnection(
            val socket: Socket,
            val outputStream: OutputStream
        )
    }
}
