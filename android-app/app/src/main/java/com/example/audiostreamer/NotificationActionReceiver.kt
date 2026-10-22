package com.example.audiostreamer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "ACTION_STOP_FROM_NOTIFICATION") {
            val serviceIntent = Intent(context, AudioService::class.java).apply {
                action = AudioService.ACTION_STOP
            }
            context.startService(serviceIntent)
        }
    }
}