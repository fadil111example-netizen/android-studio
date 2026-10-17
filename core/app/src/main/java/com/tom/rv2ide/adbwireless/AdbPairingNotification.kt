package com.tom.rv2ide.adbwireless

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.tom.rv2ide.resources.R

/**
 * Notifikasi dengan kolom input, supaya kode pairing bisa diketik tanpa meninggalkan layar
 * Settings (dialog "Pair device" di Settings tertutup bila Settings kehilangan fokus).
 */
object AdbPairingNotification {

  private const val CHANNEL_ID = "adb_wireless_pairing"
  private const val NOTIFICATION_ID = 4711
  const val KEY_CODE = "adb_pairing_code"
  const val EXTRA_HOST = "adb_host"
  const val EXTRA_PORT = "adb_port"
  const val ACTION_PAIR = "com.tom.rv2ide.adbwireless.ACTION_PAIR"

  private fun manager(context: Context) =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
          NotificationChannel(
              CHANNEL_ID,
              context.getString(R.string.adbw_notif_channel),
              NotificationManager.IMPORTANCE_HIGH,
          )
      manager(context).createNotificationChannel(channel)
    }
  }

  fun show(context: Context, host: String, port: Int) {
    ensureChannel(context)

    val remoteInput =
        RemoteInput.Builder(KEY_CODE).setLabel(context.getString(R.string.adbw_pair_code_hint)).build()

    val intent =
        Intent(context, AdbPairingReceiver::class.java).apply {
          action = ACTION_PAIR
          putExtra(EXTRA_HOST, host)
          putExtra(EXTRA_PORT, port)
        }

    var flags = PendingIntent.FLAG_UPDATE_CURRENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      flags = flags or PendingIntent.FLAG_MUTABLE
    }
    val pending = PendingIntent.getBroadcast(context, 0, intent, flags)

    val action =
        NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_edit,
                context.getString(R.string.adbw_notif_action),
                pending,
            )
            .addRemoteInput(remoteInput)
            .build()

    val notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.adbw_notif_title))
            .setContentText(context.getString(R.string.adbw_notif_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .addAction(action)
            .build()

    manager(context).notify(NOTIFICATION_ID, notification)
  }

  fun showResult(context: Context, success: Boolean, detail: String? = null) {
    ensureChannel(context)
    val text =
        if (success) context.getString(R.string.adbw_pair_success)
        else context.getString(R.string.adbw_pair_failed, detail ?: "")
    val notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.adbw_notif_title))
            .setContentText(text)
            .setTimeoutAfter(5000)
            .build()
    manager(context).notify(NOTIFICATION_ID, notification)
  }

  fun cancel(context: Context) {
    manager(context).cancel(NOTIFICATION_ID)
  }
}

/** Menerima kode pairing dari kolom input notifikasi lalu menjalankan pairing + connect. */
class AdbPairingReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != AdbPairingNotification.ACTION_PAIR) return

    val code =
        RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(AdbPairingNotification.KEY_CODE)
            ?.toString()
            ?.trim()
            .orEmpty()
    val host = intent.getStringExtra(AdbPairingNotification.EXTRA_HOST)
    val port = intent.getIntExtra(AdbPairingNotification.EXTRA_PORT, -1)
    if (code.isEmpty() || port <= 0) return

    val app = context.applicationContext
    val pending = goAsync()
    AdbWireless.launch {
      try {
        val result = AdbWireless.pairAndConnect(app, host, port, code)
        AdbPairingNotification.showResult(app, result.isSuccess, result.exceptionOrNull()?.message)
        if (result.isSuccess) {
          AdbWireless.onPairedAndConnected?.invoke()
        }
      } finally {
        pending.finish()
      }
    }
  }
}
