package com.example.wirelessmic

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvOutputDevice: TextView
    private lateinit var tvLatency: TextView
    private lateinit var btnToggleStream: Button
    private lateinit var btnConnectDevice: Button
    private lateinit var seekBarVolume: SeekBar
    private lateinit var cbMute: CheckBox

    private var isStreaming = false
    private var audioThread: Thread? = null
    private val shouldContinue = AtomicBoolean(false)
    private var isMuted = false

    private val handler = Handler(Looper.getMainLooper())
    private val updateDeviceRunnable = object : Runnable {
        override fun run() {
            updateOutputDeviceLabel()
            handler.postDelayed(this, 3000)
        }
    }

    // Audio Configuration
    private val sampleRate = 44100
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private lateinit var audioManager: AudioManager

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
        private const val TAG = "WirelessMic"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        initViews()
        setupListeners()
        checkPermissions()

        handler.post(updateDeviceRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        handler.removeCallbacks(updateDeviceRunnable)
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvOutputDevice = findViewById(R.id.tvOutputDevice)
        tvLatency = findViewById(R.id.tvLatency)
        btnToggleStream = findViewById(R.id.btnToggleStream)
        btnConnectDevice = findViewById(R.id.btnConnectDevice)
        seekBarVolume = findViewById(R.id.seekBarVolume)
        cbMute = findViewById(R.id.cbMute)

        // Init volume seekbar
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        seekBarVolume.max = maxVol
        seekBarVolume.progress = currentVol
    }

    private fun setupListeners() {
        btnConnectDevice.setOnClickListener {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot open Bluetooth settings", Toast.LENGTH_SHORT).show()
                // Fallback for some devices
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (e2: Exception) {
                    // Ignore
                }
            }
        }

        btnToggleStream.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                if (checkPermissions()) {
                    startStreaming()
                }
            }
        }

        seekBarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        cbMute.setOnCheckedChangeListener { _, isChecked ->
            isMuted = isChecked
        }
    }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions Granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permissions Required for functionality", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startStreaming() {
        if (isStreaming) return

        isStreaming = true
        shouldContinue.set(true)
        btnToggleStream.text = getString(R.string.btn_stop_stream)
        tvStatus.text = getString(R.string.status_connected)

        audioThread = Thread {
            streamAudio()
        }
        audioThread?.start()
    }

    private fun stopStreaming() {
        if (!isStreaming) return

        shouldContinue.set(false)
        try {
            audioThread?.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        isStreaming = false
        runOnUiThread {
            btnToggleStream.text = getString(R.string.btn_start_stream)
            tvStatus.text = getString(R.string.status_disconnected)
            tvLatency.text = "Latency: -- ms"
        }
    }

    private fun streamAudio() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

        val minBufferSizeIn = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
        val minBufferSizeOut = AudioTrack.getMinBufferSize(sampleRate, channelConfigOut, audioFormat)

        // Optimize buffer size for latency. Too small might cause underruns.
        val bufferSize = maxOf(minBufferSizeIn, minBufferSizeOut)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
             return
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfigIn,
            audioFormat,
            bufferSize
        )

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfigOut)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val buffer = ShortArray(bufferSize / 2) // Short is 2 bytes

        try {
            recorder.startRecording()
            track.play()

            val startTime = System.nanoTime()
            var loops = 0

            while (shouldContinue.get()) {
                val readSize = recorder.read(buffer, 0, buffer.size)

                if (readSize > 0) {
                    if (isMuted) {
                        buffer.fill(0, 0, readSize)
                    }
                    track.write(buffer, 0, readSize)
                }

                // Simple latency estimation (very rough)
                loops++
                if (loops % 100 == 0) {
                    // Logic to estimate latency could go here, for now strictly UI update
                    // Real latency calculation involves roundtrip timestamping which is hard in loopback
                     runOnUiThread {
                        // Just showing buffer duration as a proxy for minimum latency introduced by buffering
                        val latencyMs = (bufferSize.toFloat() / sampleRate / 2 * 1000).toInt() // bytes / (bytes/sample) / samples/sec
                        tvLatency.text = "Buffer Latency: ~$latencyMs ms"
                     }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in audio stream", e)
            runOnUiThread {
                Toast.makeText(this, "Error streaming audio: ${e.message}", Toast.LENGTH_LONG).show()
                stopStreaming()
            }
        } finally {
            try {
                recorder.stop()
                recorder.release()
                track.stop()
                track.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing resources", e)
            }
        }
    }

    private fun updateOutputDeviceLabel() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var deviceName = "Internal Speaker"
        for (device in devices) {
            if (device.type == AudioAttributes.USAGE_MEDIA || device.type == AudioFormat.CHANNEL_OUT_DEFAULT) {
               // heuristics
            }
            if (device.type == 8) { // BLUETOOTH_A2DP
                 deviceName = "Bluetooth A2DP"
                 if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                     deviceName += " (${device.address})"
                 }
                 break
            } else if (device.type == 3 || device.type == 4) { // WIRED_HEADSET or HEADPHONES
                deviceName = "Wired Headset"
                break
            } else if (device.type == 22) { // HEARING_AID
                 deviceName = "Hearing Aid"
                 break
            }
        }
        tvOutputDevice.text = getString(R.string.output_device_label, deviceName)
    }
}
