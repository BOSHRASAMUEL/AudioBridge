package com.example.audiostreamer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class MainActivity : AppCompatActivity() {

    private var isStreaming = false
    private var isDiscovering = false
    private lateinit var statusText: TextView
    private lateinit var statusIndicator: MaterialCardView
    private lateinit var connectBtn: MaterialButton
    private lateinit var discoverBtn: MaterialButton
    private lateinit var ipInput: EditText
    
    private lateinit var sharedPrefs: SharedPreferences
    private val PREFS_NAME = "AudioStreamerPrefs"
    private val KEY_IP = "last_ip"

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) startServiceWithIp() else statusText.text = "Permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipInput = findViewById(R.id.ipInput)
        connectBtn = findViewById(R.id.connectBtn)
        discoverBtn = findViewById(R.id.discoverBtn)
        statusText = findViewById(R.id.statusText)
        statusIndicator = findViewById(R.id.statusIndicator)

        sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val savedIp = sharedPrefs.getString(KEY_IP, "")
        if (!savedIp.isNullOrEmpty()) {
            ipInput.setText(savedIp)
        }

        AudioService.statusListener = { status ->
            runOnUiThread {
                updateUI(status)
            }
        }

        discoverBtn.setOnClickListener {
            // حماية إضافية: لو إحنا متصلين، متعملش أي حاجة
            if (isStreaming || isDiscovering) return@setOnClickListener
            
            isDiscovering = true
            discoverBtn.isEnabled = false
            discoverBtn.text = "Searching..."
            discoverServer()
        }

        connectBtn.setOnClickListener {
            connectBtn.isEnabled = false
            
            if (!isStreaming) {
                val ip = ipInput.text.toString().trim()
                if (ip.isNotEmpty()) {
                    saveIp(ip)
                    checkNotificationPermissionAndStart(ip)
                } else {
                    statusText.text = "Please enter IP"
                    connectBtn.isEnabled = true
                }
            } else {
                stopStreamService()
            }
            
            CoroutineScope(Dispatchers.Main).launch {
                delay(500)
                connectBtn.isEnabled = true
            }
        }
    }

    private fun saveIp(ip: String) {
        sharedPrefs.edit().putString(KEY_IP, ip).apply()
    }

    private fun updateUI(status: String) {
        statusText.text = status
        
        when {
            // لو متصل: الزرار أحمر، وزرار البحث بيتقفل ويبهت
            status == "Connected" -> {
                statusIndicator.setCardBackgroundColor(Color.parseColor("#388E3C")) // نقطة خضراء
                connectBtn.text = "Disconnect"
                connectBtn.setBackgroundColor(Color.parseColor("#D32F2F")) // زرار أحمر
                connectBtn.setTextColor(Color.WHITE)
                isStreaming = true
                
                // إخفاء/تعطيل زرار البحث
                discoverBtn.isEnabled = false
                discoverBtn.alpha = 0.5f 
            }
            // لو بيتصل أو بيعيد الاتصال: نفس الحكاية
            status.contains("Connecting") || status.contains("Reconnecting") || status == "Starting Service..." -> {
                statusIndicator.setCardBackgroundColor(Color.parseColor("#F57C00")) // نقطة برتقالي
                connectBtn.text = "Cancel"
                connectBtn.setBackgroundColor(Color.parseColor("#F57C00")) // زرار برتقالي
                connectBtn.setTextColor(Color.WHITE)
                isStreaming = true
                
                // إخفاء/تعطيل زرار البحث
                discoverBtn.isEnabled = false
                discoverBtn.alpha = 0.5f
            }
            // لو فصل: الزرار نعناعي، وزرار البحث بيرجع يشتغل
            else -> {
                statusIndicator.setCardBackgroundColor(Color.parseColor("#D32F2F")) // نقطة حمراء
                connectBtn.text = "Connect & Play"
                connectBtn.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_mint)) // زرار نعناعي
                connectBtn.setTextColor(ContextCompat.getColor(this, R.color.bg_main))
                isStreaming = false
                
                // تفعيل زرار البحث
                discoverBtn.isEnabled = true
                discoverBtn.alpha = 1.0f
            }
        }
    }

    private fun discoverServer() {
        CoroutineScope(Dispatchers.IO).launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                val sendData = "DISCOVER_AUDIO_SERVER".toByteArray()
                val sendPacket = DatagramPacket(sendData, sendData.size, InetAddress.getByName("255.255.255.255"), 50506)
                socket.send(sendPacket)

                val receiveData = ByteArray(1024)
                val receivePacket = DatagramPacket(receiveData, receiveData.size)
                socket.soTimeout = 3000
                socket.receive(receivePacket)

                val response = String(receivePacket.data, 0, receivePacket.length)
                if (response == "AUDIO_SERVER_HERE") {
                    val serverIp = receivePacket.address.hostAddress
                    withContext(Dispatchers.Main) {
                        ipInput.setText(serverIp)
                        saveIp(serverIp)
                        updateUI("Found: $serverIp")
                        checkNotificationPermissionAndStart(serverIp)
                    }
                } else {
                    throw Exception("Invalid response")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateUI("Server not found")
                }
            } finally {
                socket?.close()
                withContext(Dispatchers.Main) {
                    isDiscovering = false
                    // لو إحنا مش متصلين، رجع الزرار شغال
                    if (!isStreaming) {
                        discoverBtn.isEnabled = true
                        discoverBtn.alpha = 1.0f
                        discoverBtn.text = "Auto-Discover PC"
                    }
                }
            }
        }
    }

    private fun checkNotificationPermissionAndStart(ip: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                startServiceWithIp()
            } else {
                ipInput.setText(ip)
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startServiceWithIp()
        }
    }

    private fun startServiceWithIp() {
        val ip = ipInput.text.toString().trim()
        updateUI("Starting Service...")

        val intent = Intent(this, AudioService::class.java).apply {
            action = AudioService.ACTION_START
            putExtra(AudioService.EXTRA_IP, ip)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopStreamService() {
        val intent = Intent(this, AudioService::class.java).apply { action = AudioService.ACTION_STOP }
        startService(intent)
        updateUI("Disconnected")
    }

    override fun onDestroy() {
        super.onDestroy()
        AudioService.statusListener = null
    }
}