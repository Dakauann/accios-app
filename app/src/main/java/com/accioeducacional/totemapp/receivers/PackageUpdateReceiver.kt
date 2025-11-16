package com.accioeducacional.totemapp.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.accioeducacional.totemapp.MainActivity
import com.accioeducacional.totemapp.services.UpdatesService

class PackageUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(launchIntent)
        UpdatesService.start(context)
    }
}
