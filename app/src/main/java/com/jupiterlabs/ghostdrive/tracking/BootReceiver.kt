package com.jupiterlabs.ghostdrive.tracking

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.jupiterlabs.ghostdrive.GhostApp

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val repo = (context.applicationContext as? GhostApp)?.repository ?: return
        val s = repo.settings.value
        if (!s.autoStartOnBoot || !s.wasArmed) return
        val hasPermissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        ).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (!hasPermissions) {
            repo.log("WARN", "Boot", "Auto-start skipped: missing Location or Nearby Devices permission.")
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DriveService::class.java).setAction(DriveService.START)
            )
            repo.log("INFO", "Boot", "Ghost Drive monitoring automatically re-armed after system boot/update.")
        }.onFailure {
            repo.log("WARN", "Boot", "Android blocked background service start on boot (${it.javaClass.simpleName}). Open app to re-arm.")
        }
    }
}
