package com.tom.rv2ide.adbwireless

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.resources.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dialog pilihan Run + dialog pairing ADB. Dibuat lewat kode agar tidak butuh layout XML baru. */
object AdbRunDialogs {

  private fun dp(activity: Activity, value: Int): Int =
      (value * activity.resources.displayMetrics.density).toInt()

  private fun copyToClipboard(activity: Activity, text: String) {
    try {
      val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
      cm.setPrimaryClip(android.content.ClipData.newPlainText("adb error", text))
    } catch (e: Throwable) {
      // abaikan
    }
  }

  /** Dialog yang muncul saat tombol Run (play) ditekan. */
  fun showMethodChooser(activity: Activity, onChosen: (InstallMethod) -> Unit) {
    val methods = InstallMethod.values()
    val labels =
        arrayOf(
            activity.getString(R.string.adbw_method_system),
            activity.getString(R.string.adbw_method_shizuku),
            activity.getString(R.string.adbw_method_adb),
        )

    MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.adbw_run_title)
        .setItems(labels) { dialog, which ->
          dialog.dismiss()
          onChosen(methods[which])
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  /**
   * Dialog pairing. Port pairing terisi otomatis begitu dialog "Pair device with pairing code" di
   * Settings terbuka. Kode juga bisa diketik lewat notifikasi.
   */
  fun showPairingDialog(
      activity: Activity,
      reason: String?,
      onConnected: () -> Unit,
      onCancelled: () -> Unit,
  ) {
    val pad = dp(activity, 20)

    val message =
        TextView(activity).apply {
          text =
              buildString {
                append(activity.getString(R.string.adbw_pair_message))
                if (!reason.isNullOrBlank()) {
                  append("\n\n")
                  append(reason)
                }
              }
          textSize = 14f
        }

    val edtPort =
        EditText(activity).apply {
          hint = activity.getString(R.string.adbw_pair_port_hint)
          inputType = InputType.TYPE_CLASS_NUMBER
          maxLines = 1
        }
    val edtCode =
        EditText(activity).apply {
          hint = activity.getString(R.string.adbw_pair_code_hint)
          inputType = InputType.TYPE_CLASS_NUMBER
          maxLines = 1
        }
    val status = TextView(activity).apply { textSize = 13f }

    val root =
        LinearLayout(activity).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(pad, pad / 2, pad, 0)
          layoutParams =
              ViewGroup.LayoutParams(
                  ViewGroup.LayoutParams.MATCH_PARENT,
                  ViewGroup.LayoutParams.WRAP_CONTENT,
              )
          addView(message)
          addView(edtPort)
          addView(edtCode)
          addView(status)
        }

    var pairingHost: String? = null
    var finished = false

    val dialog =
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.adbw_pair_title)
            .setView(root)
            .setPositiveButton(R.string.adbw_pair_action, null)
            .setNeutralButton(R.string.adbw_open_dev_options, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

    fun finish(connected: Boolean) {
      if (finished) return
      finished = true
      AdbWireless.stopPairingWatch()
      AdbWireless.onPairedAndConnected = null
      AdbPairingNotification.cancel(activity)
      if (dialog.isShowing) dialog.dismiss()
      if (connected) onConnected() else onCancelled()
    }

    dialog.setOnShowListener {
      // Tombol tidak menutup dialog otomatis; kita kontrol sendiri.
      dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
        val port = edtPort.text.toString().trim().toIntOrNull()
        val code = edtCode.text.toString().trim()
        if (port == null || code.length < 6) {
          status.text = activity.getString(R.string.adbw_pair_need_input)
          return@setOnClickListener
        }
        status.text = activity.getString(R.string.adbw_pairing)
        AdbWireless.launch {
          val result = AdbWireless.pairAndConnect(activity, pairingHost, port, code)
          withContext(Dispatchers.Main) {
            if (result.isSuccess) {
              finish(true)
            } else {
              val error = result.exceptionOrNull()
              val full = if (error != null) AdbWireless.describe(error) else ""
              copyToClipboard(activity, full)
              status.text =
                  activity.getString(R.string.adbw_pair_failed, error?.let { AdbWireless.brief(it) } ?: "") +
                      "\n" + activity.getString(R.string.adbw_detail_copied)
            }
          }
        }
      }

      dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
        try {
          activity.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: Throwable) {
          activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
      }

      dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener { finish(false) }
    }

    dialog.setOnCancelListener { finish(false) }

    // Pairing via notifikasi sukses => tutup dialog & lanjutkan install.
    AdbWireless.onPairedAndConnected = { activity.runOnUiThread { finish(true) } }

    // Port pairing terisi otomatis + tampilkan notifikasi input kode.
    AdbWireless.startPairingWatch(activity) { host, port ->
      pairingHost = host
      activity.runOnUiThread {
        edtPort.setText(port.toString())
        status.text = activity.getString(R.string.adbw_pair_found, port)
      }
      AdbPairingNotification.show(activity, host, port)
    }

    dialog.show()
  }
}
