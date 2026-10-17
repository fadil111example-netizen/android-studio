package com.tom.rv2ide.adbwireless

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Menyambung ulang ADB Wireless secara otomatis (seperti Shizuku/Brevent): selama proses aplikasi
 * hidup, setiap kali "Debugging nirkabel" dinyalakan kembali (service mDNS muncul), aplikasi
 * langsung connect memakai key yang sudah pernah dipairing. Tidak perlu pairing ulang.
 */
object AdbAutoConnect {

  private var stop: (() -> Unit)? = null
  private val connecting = AtomicBoolean(false)

  @Synchronized
  fun start(context: Context) {
    val app = context.applicationContext
    if (stop != null) return
    if (!AdbWireless.isSupported || !AdbWireless.isPaired(app)) return

    stop =
        try {
          AdbNsd.watch(app, AdbNsd.TYPE_CONNECT) { host, port ->
            if (!AdbWireless.isConnected(app) && connecting.compareAndSet(false, true)) {
              AdbWireless.launch {
                try {
                  AdbWireless.connectTo(app, host, port)
                } catch (e: Throwable) {
                  // Belum siap / bukan perangkat ini: coba lagi saat service muncul berikutnya.
                } finally {
                  connecting.set(false)
                }
              }
            }
          }
        } catch (e: Throwable) {
          null
        }
  }

  @Synchronized
  fun stop() {
    stop?.invoke()
    stop = null
  }

  fun restart(context: Context) {
    stop()
    start(context)
  }
}
