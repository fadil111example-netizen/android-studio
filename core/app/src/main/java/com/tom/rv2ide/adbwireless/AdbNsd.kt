package com.tom.rv2ide.adbwireless

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Menemukan port Wireless debugging lewat mDNS (NSD), tanpa binary adb. */
object AdbNsd {

  const val TYPE_CONNECT = "_adb-tls-connect._tcp"
  const val TYPE_PAIRING = "_adb-tls-pairing._tcp"

  private val main = Handler(Looper.getMainLooper())

  /**
   * Mengamati service terus-menerus. [onResolved] dipanggil tiap service ditemukan (host, port).
   * Kembalian: fungsi untuk menghentikan pengamatan.
   */
  fun watch(context: Context, type: String, onResolved: (String, Int) -> Unit): () -> Unit {
    val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    var stopped = false

    fun resolve(info: NsdServiceInfo, attempt: Int) {
      if (stopped) return
      @Suppress("DEPRECATION")
      nsd.resolveService(
          info,
          object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
              // FAILURE_ALREADY_ACTIVE (3): coba lagi sebentar lagi
              if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempt < 6) {
                main.postDelayed({ resolve(info, attempt + 1) }, 400L)
              }
            }

            @Suppress("DEPRECATION")
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
              val host = serviceInfo.host?.hostAddress ?: return
              if (!stopped) onResolved(host, serviceInfo.port)
            }
          },
      )
    }

    val listener =
        object : NsdManager.DiscoveryListener {
          override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}

          override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

          override fun onDiscoveryStarted(serviceType: String) {}

          override fun onDiscoveryStopped(serviceType: String) {}

          override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            resolve(serviceInfo, 0)
          }

          override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }

    nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)

    return {
      if (!stopped) {
        stopped = true
        try {
          nsd.stopServiceDiscovery(listener)
        } catch (e: Throwable) {
          // sudah berhenti
        }
      }
    }
  }

  /** Menemukan service sekali saja. Null jika tidak ditemukan sebelum [timeoutMs]. */
  suspend fun discover(context: Context, type: String, timeoutMs: Long): Pair<String, Int>? =
      withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
          var stop: (() -> Unit)? = null
          stop =
              watch(context, type) { host, port ->
                if (cont.isActive) cont.resume(host to port)
                stop?.invoke()
              }
          cont.invokeOnCancellation { stop?.invoke() }
          if (!cont.isActive) stop?.invoke()
        }
      }
}
