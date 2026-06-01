package com.example.wirelessmic

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.wirelessmic.streaming.NetworkStreamingManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MainActivity - Wireless Microphone Application
 *
 * Transforms an Android smartphone into a wireless microphone by capturing
 * real-time audio from the device's microphone and streaming it to:
 * - Local audio output (Bluetooth speaker, wired headphones)
 * - Network devices (Smart TV via DLNA, Chromecast)
 */
class MainActivity : AppCompatActivity() {

    // region UI Components
    private lateinit var tvStatus: TextView
    private lateinit var tvOutputDevice: TextView
    private lateinit var tvLatency: TextView
    private lateinit var btnToggleStream: Button
    private lateinit var btnConnectDevice: Button
    private lateinit var btnStreamToTV: Button
    private lateinit var seekBarVolume: SeekBar
    private lateinit var cbMute: CheckBox
    private lateinit var tvNetworkStatus: TextView
    private lateinit var networkStatusContainer: LinearLayout
    // endregion

    // region State Variables
    @Volatile
    private var isStreaming = false

    @Volatile
    private var isMuted = false
    
    @Volatile
    private var isNetworkStreaming = false

    private var audioThread: Thread? = null
    private val shouldContinue = AtomicBoolean(false)
    // endregion

    // region Managers
    private lateinit var audioManager: AudioManager
    private lateinit var networkStreamingManager: NetworkStreamingManager
    private val handler = Handler(Looper.getMainLooper())
    // endregion

    // region Audio Configuration Constants
    companion object {
        private const val TAG = "WirelessMic"
        private const val PERMISSION_REQUEST_CODE = 101

        // Audio format configuration
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Polling interval for device detection (milliseconds)
        private const val DEVICE_POLL_INTERVAL_MS = 3000L

        // Latency update interval (number of audio loop iterations)
        private const val LATENCY_UPDATE_INTERVAL = 100

        // Bytes per sample for 16-bit PCM mono
        private const val BYTES_PER_SAMPLE = 2
    }
    // endregion

    // region Device Update Runnable
    private val updateDeviceRunnable = object : Runnable {
        override fun run() {
            updateOutputDeviceLabel()
            handler.postDelayed(this, DEVICE_POLL_INTERVAL_MS)
        }
    }
    // endregion

    // region Lifecycle Methods
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        networkStreamingManager = NetworkStreamingManager(this)
        networkStreamingManager.initialize()

        initViews()
        setupListeners()
        checkPermissions()

        // Start polling for connected audio devices
        handler.post(updateDeviceRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        networkStreamingManager.release()
        handler.removeCallbacks(updateDeviceRunnable)
    }

    override fun onPause() {
        super.onPause()
        // Note: Audio streaming continues in background.
    }
    // endregion

    // region UI Initialization
    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvOutputDevice = findViewById(R.id.tvOutputDevice)
        tvLatency = findViewById(R.id.tvLatency)
        btnToggleStream = findViewById(R.id.btnToggleStream)
        btnConnectDevice = findViewById(R.id.btnConnectDevice)
        btnStreamToTV = findViewById(R.id.btnStreamToTV)
        seekBarVolume = findViewById(R.id.seekBarVolume)
        cbMute = findViewById(R.id.cbMute)
        tvNetworkStatus = findViewById(R.id.tvNetworkStatus)
        networkStatusContainer = findViewById(R.id.networkStatusContainer)

        // Initialize volume seekbar with system values
        initializeVolumeSeekBar()
        
        // Initially hide network status
        networkStatusContainer.visibility = View.GONE
    }

    private fun initializeVolumeSeekBar() {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        seekBarVolume.max = maxVolume
        seekBarVolume.progress = currentVolume
    }

    private fun setupListeners() {
        btnConnectDevice.setOnClickListener {
            openBluetoothSettings()
        }

        btnToggleStream.setOnClickListener {
            toggleStreaming()
        }
        
        btnStreamToTV.setOnClickListener {
            showNetworkDeviceDialog()
        }

        seekBarVolume.setOnSeekBarChangeListener(createVolumeChangeListener())

        cbMute.setOnCheckedChangeListener { _, isChecked ->
            isMuted = isChecked
            // Also mute network stream if connected
            if (isNetworkStreaming) {
                networkStreamingManager.setRemoteMute(isChecked)
            }
            Log.d(TAG, "Mute toggled: $isMuted")
        }
    }

    private fun createVolumeChangeListener() = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                // Also update network stream volume if connected
                if (isNetworkStreaming) {
                    val normalizedVolume = progress.toFloat() / (seekBar?.max ?: 100)
                    networkStreamingManager.setRemoteVolume(normalizedVolume)
                }
            }
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
    // endregion

    // region Network Streaming
    private fun showNetworkDeviceDialog() {
        // Show loading dialog while discovering
        val loadingDialog = AlertDialog.Builder(this)
            .setTitle("Searching for Devices")
            .setMessage("Looking for TVs and Chromecast devices on your network...")
            .setView(ProgressBar(this).apply {
                isIndeterminate = true
                setPadding(50, 50, 50, 50)
            })
            .setNegativeButton("Cancel") { dialog, _ ->
                networkStreamingManager.stopDiscovery()
                dialog.dismiss()
            }
            .setCancelable(true)
            .create()
        
        loadingDialog.show()
        
        val discoveredDevices = mutableListOf<NetworkStreamingManager.NetworkDevice>()
        
        networkStreamingManager.startDiscovery(
            onDevicesFound = { devices ->
                discoveredDevices.clear()
                discoveredDevices.addAll(devices)
                
                // Update dialog if we found devices
                if (devices.isNotEmpty()) {
                    runOnUiThread {
                        loadingDialog.dismiss()
                        showDeviceSelectionDialog(devices)
                    }
                }
            },
            onStateChanged = { state, message ->
                Log.d(TAG, "Network state: $state, message: $message")
            }
        )
        
        // Auto-dismiss after 10 seconds if no devices found
        handler.postDelayed({
            if (loadingDialog.isShowing) {
                loadingDialog.dismiss()
                networkStreamingManager.stopDiscovery()
                if (discoveredDevices.isEmpty()) {
                    showNoDevicesFoundDialog()
                } else {
                    showDeviceSelectionDialog(discoveredDevices)
                }
            }
        }, 10000)
    }
    
    private fun showDeviceSelectionDialog(devices: List<NetworkStreamingManager.NetworkDevice>) {
        val deviceNames = devices.map { it.displayName }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("Select Device")
            .setItems(deviceNames) { _, which ->
                val selectedDevice = devices[which]
                connectToNetworkDevice(selectedDevice)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Refresh") { _, _ ->
                showNetworkDeviceDialog()
            }
            .show()
    }
    
    private fun showNoDevicesFoundDialog() {
        AlertDialog.Builder(this)
            .setTitle("No Devices Found")
            .setMessage("""
                No Smart TVs or Chromecast devices found on your network.
                
                Make sure:
                • Your phone and TV are on the same WiFi network
                • Your TV is turned on
                • DLNA/Miracast/Chromecast is enabled on your TV
                
                For manual streaming, you can open this URL in your TV's browser:
                http://[your-phone-ip]:8080/audio.wav
            """.trimIndent())
            .setPositiveButton("Try Again") { _, _ ->
                showNetworkDeviceDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun connectToNetworkDevice(device: NetworkStreamingManager.NetworkDevice) {
        Toast.makeText(this, "Connecting to ${device.name}...", Toast.LENGTH_SHORT).show()
        
        networkStreamingManager.connectToDevice(device) { success, message ->
            runOnUiThread {
                if (success) {
                    isNetworkStreaming = true
                    updateNetworkStatusUI(device)
                    Toast.makeText(this, "Connected to ${device.name}", Toast.LENGTH_SHORT).show()
                    
                    // If not already streaming, start streaming
                    if (!isStreaming && checkPermissions()) {
                        startStreaming()
                    }
                } else {
                    Toast.makeText(this, "Connection failed: $message", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun updateNetworkStatusUI(device: NetworkStreamingManager.NetworkDevice?) {
        if (device != null) {
            networkStatusContainer.visibility = View.VISIBLE
            tvNetworkStatus.text = "📡 Streaming to: ${device.name}"
            btnStreamToTV.text = getString(R.string.btn_disconnect_tv)
            btnStreamToTV.setOnClickListener {
                disconnectFromNetwork()
            }
        } else {
            networkStatusContainer.visibility = View.GONE
            btnStreamToTV.text = getString(R.string.btn_stream_to_tv)
            btnStreamToTV.setOnClickListener {
                showNetworkDeviceDialog()
            }
        }
    }
    
    private fun disconnectFromNetwork() {
        networkStreamingManager.disconnect()
        isNetworkStreaming = false
        updateNetworkStatusUI(null)
        Toast.makeText(this, "Disconnected from TV", Toast.LENGTH_SHORT).show()
    }
    // endregion

    // region Navigation
    private fun openBluetoothSettings() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (e: Exception) {
            Log.w(TAG, "Could not open Bluetooth settings directly", e)
            Toast.makeText(this, "Cannot open Bluetooth settings", Toast.LENGTH_SHORT).show()
            openGeneralSettings()
        }
    }

    private fun openGeneralSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (e: Exception) {
            Log.e(TAG, "Could not open any settings", e)
        }
    }
    // endregion

    // region Permissions
    private fun checkPermissions(): Boolean {
        val requiredPermissions = buildRequiredPermissionsList()
        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        return if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
            false
        } else {
            true
        }
    }

    private fun buildRequiredPermissionsList(): List<String> {
        return buildList {
            add(Manifest.permission.RECORD_AUDIO)
            // Bluetooth permission required for Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != PERMISSION_REQUEST_CODE) return

        val allGranted = grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }

        if (allGranted) {
            Toast.makeText(this, "Permissions Granted", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "All required permissions granted")
        } else {
            Toast.makeText(
                this,
                "Permissions required for microphone functionality",
                Toast.LENGTH_LONG
            ).show()
            Log.w(TAG, "Some permissions were denied")
        }
    }
    // endregion

    // region Streaming Control
    private fun toggleStreaming() {
        if (isStreaming) {
            stopStreaming()
        } else {
            if (checkPermissions()) {
                startStreaming()
            }
        }
    }

    private fun startStreaming() {
        if (isStreaming) {
            Log.d(TAG, "Already streaming, ignoring start request")
            return
        }

        Log.i(TAG, "Starting audio stream")
        isStreaming = true
        shouldContinue.set(true)

        updateUIForStreaming(true)

        audioThread = Thread({
            runAudioStreamLoop()
        }, "AudioStreamThread").apply {
            start()
        }
    }

    private fun stopStreaming() {
        if (!isStreaming) {
            Log.d(TAG, "Not streaming, ignoring stop request")
            return
        }

        Log.i(TAG, "Stopping audio stream")
        shouldContinue.set(false)

        // Wait for audio thread to finish
        try {
            audioThread?.join(1000)
            if (audioThread?.isAlive == true) {
                Log.w(TAG, "Audio thread did not stop gracefully")
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while waiting for audio thread", e)
            Thread.currentThread().interrupt()
        }

        audioThread = null
        isStreaming = false

        runOnUiThread {
            updateUIForStreaming(false)
        }
    }

    private fun updateUIForStreaming(streaming: Boolean) {
        if (streaming) {
            btnToggleStream.text = getString(R.string.btn_stop_stream)
            tvStatus.text = getString(R.string.status_connected)
        } else {
            btnToggleStream.text = getString(R.string.btn_start_stream)
            tvStatus.text = getString(R.string.status_disconnected)
            tvLatency.text = "Latency: -- ms"
        }
    }
    // endregion

    // region Audio Processing
    private fun runAudioStreamLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

        val bufferSize = calculateOptimalBufferSize()
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            handleAudioError("Failed to calculate buffer size")
            return
        }

        if (!hasRecordAudioPermission()) {
            handleAudioError("Missing RECORD_AUDIO permission")
            return
        }

        var recorder: AudioRecord? = null
        var track: AudioTrack? = null
        var noiseSuppressor: NoiseSuppressor? = null
        var echoCanceler: AcousticEchoCanceler? = null

        try {
            recorder = createAudioRecorder(bufferSize)
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                handleAudioError("Failed to initialize AudioRecord")
                return
            }

            track = createAudioTrack(bufferSize)
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                handleAudioError("Failed to initialize AudioTrack")
                return
            }

            noiseSuppressor = tryEnableNoiseSuppressor(recorder.audioSessionId)
            echoCanceler = tryEnableEchoCanceler(recorder.audioSessionId)

            processAudioStream(recorder, track, bufferSize)

        } catch (e: Exception) {
            Log.e(TAG, "Error in audio stream", e)
            handleAudioError("Audio streaming error: ${e.message}")
        } finally {
            releaseAudioResources(recorder, track, noiseSuppressor, echoCanceler)
        }
    }

    private fun calculateOptimalBufferSize(): Int {
        val minBufferSizeIn = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG_IN,
            AUDIO_FORMAT
        )
        val minBufferSizeOut = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG_OUT,
            AUDIO_FORMAT
        )
        return maxOf(minBufferSizeIn, minBufferSizeOut)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("MissingPermission")
    private fun createAudioRecorder(bufferSize: Int): AudioRecord {
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG_IN,
            AUDIO_FORMAT,
            bufferSize
        )
    }

    private fun createAudioTrack(bufferSize: Int): AudioTrack {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG_OUT)
            .build()

        return AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun tryEnableNoiseSuppressor(audioSessionId: Int): NoiseSuppressor? {
        return if (NoiseSuppressor.isAvailable()) {
            try {
                NoiseSuppressor.create(audioSessionId)?.also {
                    it.enabled = true
                    Log.i(TAG, "Noise suppressor enabled")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable noise suppressor", e)
                null
            }
        } else {
            Log.d(TAG, "Noise suppressor not available on this device")
            null
        }
    }

    private fun tryEnableEchoCanceler(audioSessionId: Int): AcousticEchoCanceler? {
        return if (AcousticEchoCanceler.isAvailable()) {
            try {
                AcousticEchoCanceler.create(audioSessionId)?.also {
                    it.enabled = true
                    Log.i(TAG, "Echo canceler enabled")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable echo canceler", e)
                null
            }
        } else {
            Log.d(TAG, "Echo canceler not available on this device")
            null
        }
    }

    private fun processAudioStream(recorder: AudioRecord, track: AudioTrack, bufferSize: Int) {
        val buffer = ShortArray(bufferSize / BYTES_PER_SAMPLE)
        var loopCount = 0

        recorder.startRecording()
        track.play()

        Log.i(TAG, "Audio stream started with buffer size: $bufferSize bytes")

        while (shouldContinue.get()) {
            val samplesRead = recorder.read(buffer, 0, buffer.size)

            when {
                samplesRead > 0 -> {
                    if (isMuted) {
                        buffer.fill(0, 0, samplesRead)
                    }
                    
                    // Play locally
                    track.write(buffer, 0, samplesRead)
                    
                    // Stream to network if connected
                    if (isNetworkStreaming && !isMuted) {
                        networkStreamingManager.streamAudioData(buffer, samplesRead)
                    }
                }
                samplesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                    Log.e(TAG, "AudioRecord: Invalid operation")
                    break
                }
                samplesRead == AudioRecord.ERROR_BAD_VALUE -> {
                    Log.e(TAG, "AudioRecord: Bad value")
                    break
                }
                samplesRead == AudioRecord.ERROR_DEAD_OBJECT -> {
                    Log.e(TAG, "AudioRecord: Dead object")
                    break
                }
            }

            loopCount++
            if (loopCount % LATENCY_UPDATE_INTERVAL == 0) {
                updateLatencyDisplay(bufferSize)
            }
        }

        Log.i(TAG, "Audio stream loop ended")
    }

    private fun updateLatencyDisplay(bufferSize: Int) {
        val bufferSamples = bufferSize / BYTES_PER_SAMPLE
        val latencyMs = (bufferSamples.toFloat() / SAMPLE_RATE * 1000).toInt()

        runOnUiThread {
            val networkExtra = if (isNetworkStreaming) " (+network)" else ""
            tvLatency.text = "Buffer Latency: ~${latencyMs} ms$networkExtra"
        }
    }

    private fun handleAudioError(message: String) {
        Log.e(TAG, message)
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            stopStreaming()
        }
    }

    private fun releaseAudioResources(
        recorder: AudioRecord?,
        track: AudioTrack?,
        noiseSuppressor: NoiseSuppressor?,
        echoCanceler: AcousticEchoCanceler?
    ) {
        try {
            noiseSuppressor?.release()
            echoCanceler?.release()

            recorder?.let {
                if (it.state == AudioRecord.STATE_INITIALIZED) {
                    it.stop()
                }
                it.release()
            }

            track?.let {
                if (it.state == AudioTrack.STATE_INITIALIZED) {
                    it.stop()
                }
                it.release()
            }

            Log.d(TAG, "Audio resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio resources", e)
        }
    }
    // endregion

    // region Device Detection
    private fun updateOutputDeviceLabel() {
        val deviceInfo = detectActiveOutputDevice()
        tvOutputDevice.text = getString(R.string.output_device_label, deviceInfo)
    }

    private fun detectActiveOutputDevice(): String {
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        for (device in outputDevices) {
            val detectedDevice = mapDeviceTypeToName(device)
            if (detectedDevice != null) {
                return detectedDevice
            }
        }

        return "Internal Speaker"
    }

    private fun mapDeviceTypeToName(device: AudioDeviceInfo): String? {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                buildBluetoothDeviceName(device, "Bluetooth A2DP")
            }
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                buildBluetoothDeviceName(device, "Bluetooth SCO")
            }
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio Device"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Accessory"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
            AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC"
            AudioDeviceInfo.TYPE_DOCK -> "Dock"
            AudioDeviceInfo.TYPE_DOCK_ANALOG -> "Dock (Analog)"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line Out (Analog)"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line Out (Digital)"
            AudioDeviceInfo.TYPE_AUX_LINE -> "AUX Line"
            AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing Aid"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Headset"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE Speaker"
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE Broadcast"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_TELEPHONY,
            AudioDeviceInfo.TYPE_UNKNOWN -> null
            else -> null
        }
    }

    private fun buildBluetoothDeviceName(device: AudioDeviceInfo, baseName: String): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && device.address.isNotEmpty()) {
            "$baseName (${device.address})"
        } else {
            baseName
        }
    }
    // endregion
}
