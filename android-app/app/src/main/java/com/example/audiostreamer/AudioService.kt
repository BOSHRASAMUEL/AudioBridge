package com.example.audiostreamer

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue

class AudioService : Service() {

    private var isStreaming = false
    private var streamJob: Job? = null
    private var ipAddress = ""
    
    private val packetQueue = LinkedBlockingQueue<ByteArray>()

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        throwable.printStackTrace()
        updateStatus("Reconnecting...")
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_IP = "EXTRA_IP"
        var statusListener: ((String) -> Unit)? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ipAddress = intent.getStringExtra(EXTRA_IP) ?: ""
                startForegroundService()
            }
            ACTION_STOP -> {
                stopStreaming()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundService() {
        val channelId = "audio_stream_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Audio Streaming", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, NotificationActionReceiver::class.java).apply { action = "ACTION_STOP_FROM_NOTIFICATION" }
        val stopPendingIntent = PendingIntent.getBroadcast(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Audio Streamer")
            .setContentText("Streaming audio from $ipAddress")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
        startStreaming()
    }

    @Synchronized
    private fun startStreaming() {
        if (isStreaming) return
        isStreaming = true
        packetQueue.clear()

        streamJob = serviceScope.launch {
            while (isActive && isStreaming) {
                var socket: DatagramSocket? = null
                var localAudioTrack: AudioTrack? = null

                try {
                    updateStatus("Connecting...")
                    
                    socket = DatagramSocket()
                    val serverAddress = InetAddress.getByName(ipAddress)
                    
                    val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                    val startPacket = "START:$deviceName".toByteArray()
                    socket.send(DatagramPacket(startPacket, startPacket.size, serverAddress, 50505))
                    
                    updateStatus("Connected")

                    val sampleRate = 48000
                    val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
                    val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)

                    val audioTrackBuilder = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(channelConfig).build())
                        .setBufferSizeInBytes(minBufferSize * 4)
                        .setTransferMode(AudioTrack.MODE_STREAM)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        audioTrackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    }

                    localAudioTrack = audioTrackBuilder.build()
                    
                    if (localAudioTrack.state != AudioTrack.STATE_INITIALIZED) {
                        throw Exception("AudioTrack failed to initialize")
                    }
                    
                    localAudioTrack.play()

                    val receiveJob = launch {
                        val receiveBuffer = ByteArray(8192)
                        val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                        socket.soTimeout = 5000
                        var lastPacketTime = System.currentTimeMillis()
                        
                        while (isActive && isStreaming) {
                            try {
                                socket.receive(receivePacket)
                                lastPacketTime = System.currentTimeMillis() // جات نبضة أو صوت
                                
                                val data = receivePacket.data.copyOf(receivePacket.length)
                                
                                // لو الحزمة كبيرة (فيها صوت فعلاً) ضيفها للطابور
                                if (data.size > 4) { 
                                    if (packetQueue.size < 100) { 
                                        packetQueue.put(data)
                                    }
                                }
                            } catch (e: SocketTimeoutException) {
                                // لو عدت 7 ثواني من غير أي حزمة (ولا حتى نبضة)، يبقى السيرفر قفل
                                if (System.currentTimeMillis() - lastPacketTime > 7000) {
                                    throw Exception("Server disconnected (Timeout)")
                                }
                            } catch (e: Exception) {
                                if (isActive && isStreaming) throw e
                            }
                        }
                    }

                    var isBuffering = true
                    var dynamicTargetSize = 10 
                    val minTargetSize = 3       
                    val maxTargetSize = 30      

                    while (isActive && isStreaming) {
                        if (isBuffering && packetQueue.size >= dynamicTargetSize) {
                            isBuffering = false
                        }

                        if (!isBuffering) {
                            val data = packetQueue.poll()
                            
                            if (data != null) {
                                try {
                                    if (localAudioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                        localAudioTrack.write(data, 0, data.size)
                                    }
                                } catch (e: Exception) { }
                            } else {
                                if (dynamicTargetSize < maxTargetSize) {
                                    dynamicTargetSize += 2
                                    isBuffering = true
                                }
                                delay(10) 
                            }

                            if (packetQueue.size > dynamicTargetSize + 5) {
                                if (dynamicTargetSize > minTargetSize) {
                                    dynamicTargetSize -= 1
                                }
                            }
                        } else {
                            delay(20)
                        }
                    }
                    
                    receiveJob.cancel()

                } catch (e: Exception) {
                    e.printStackTrace()
                    updateStatus("Disconnected. Reconnecting...")
                } finally {
                    try { localAudioTrack?.stop() } catch (e: Exception) {}
                    try { localAudioTrack?.release() } catch (e: Exception) {}
                    try { socket?.close() } catch (e: Exception) {}
                    packetQueue.clear()
                }

                if (isStreaming) delay(2000)
            }
        }
    }

    @Synchronized
    private fun stopStreaming() {
        isStreaming = false
        streamJob?.cancel()
        streamJob = null
        packetQueue.clear()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = DatagramSocket()
                val serverAddress = InetAddress.getByName(ipAddress)
                val stopPacket = "STOP".toByteArray()
                socket.send(DatagramPacket(stopPacket, stopPacket.size, serverAddress, 50505))
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        updateStatus("Disconnected")
    }

    private fun updateStatus(status: String) {
        CoroutineScope(Dispatchers.Main).launch {
            statusListener?.invoke(status)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        serviceScope.cancel()
    }
}