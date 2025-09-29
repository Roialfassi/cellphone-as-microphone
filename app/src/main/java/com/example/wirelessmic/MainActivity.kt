package com.example.wirelessmic

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioAttributes
import android.media.AudioRecord
import android.media.AudioTrack
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.wirelessmic.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var bluetoothAdapter: BluetoothAdapter? = null

    private var isStreaming = false
    private var isMuted = false
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    private val bluetoothPermissionRequestCode = 1
    // SCO compatible settings will be used in the next step
    private val audioSampleRate = 8000
    private val audioChannelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(audioSampleRate, audioChannelConfig, audioFormat)

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
    }

    private lateinit var audioManager: AudioManager

    private val scoStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    Toast.makeText(context, "SCO Connected", Toast.LENGTH_SHORT).show()
                    startRecording()
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    Toast.makeText(context, "SCO Disconnected", Toast.LENGTH_SHORT).show()
                    // If streaming was active, stop it
                    if(isStreaming) stopStreaming()
                }
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    Toast.makeText(context, "SCO Error", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        binding.volumeControl.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        binding.volumeControl.progress = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)

        registerReceiver(scoStateReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))

        binding.volumeControl.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, progress, 0)
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.startStopButton.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                startStreaming()
            }
        }

        binding.muteButton.setOnClickListener {
            toggleMute()
        }

        if (checkAndRequestPermissions()) {
            initializeBluetooth()
        }
    }

    private fun checkAndRequestPermissions(): Boolean {
        val permissionsToRequest = ArrayList<String>()
        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }

        return if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), bluetoothPermissionRequestCode)
            false
        } else {
            true
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            Toast.makeText(this, "Bluetooth must be enabled to use the app", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initializeBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
    }

    private fun startStreaming() {
        if (!isStreaming) {
            audioManager.startBluetoothSco()
            binding.statusText.text = "Status: Connecting SCO..."
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, audioSampleRate, audioChannelConfig, audioFormat, bufferSize)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(audioSampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            Toast.makeText(this, "Failed to initialize audio components", Toast.LENGTH_SHORT).show()
            return
        }

        audioRecord?.startRecording()
        audioTrack?.play()
        isStreaming = true

        coroutineScope.launch {
            val audioBuffer = ByteArray(bufferSize)
            while (isStreaming) {
                val readSize = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (readSize > 0) {
                     audioTrack?.write(audioBuffer, 0, readSize)
                }
            }
        }

        binding.startStopButton.text = "Stop Streaming"
        binding.statusText.text = "Status: Streaming"
        binding.latencyText.text = "Latency: ~100ms (SCO)"
    }

    private fun stopStreaming() {
        if (isStreaming) {
            isStreaming = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        }
        audioManager.stopBluetoothSco()
        binding.startStopButton.text = "Start Streaming"
        binding.statusText.text = "Status: Disconnected"
        binding.latencyText.text = "Latency: N/A"
    }

    private fun toggleMute() {
        isMuted = !isMuted
        audioManager.isMicrophoneMute = isMuted
        binding.muteButton.text = if (isMuted) "Unmute" else "Mute"
        Toast.makeText(this, if (isMuted) "Muted" else "Unmuted", Toast.LENGTH_SHORT).show()
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == bluetoothPermissionRequestCode) {
            var allPermissionsGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false
                    break
                }
            }

            if (allPermissionsGranted) {
                initializeBluetooth()
            } else {
                Toast.makeText(this, "Permissions are required to use the app", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(scoStateReceiver)
        if (isStreaming) {
            stopStreaming()
        }
    }
}