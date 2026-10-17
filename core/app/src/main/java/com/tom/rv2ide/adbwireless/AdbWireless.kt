package com.tom.rv2ide.adbwireless

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/** Cara menginstal hasil build saat tombol Run ditekan. */
enum class InstallMethod {
  SYSTEM,
  SHIZUKU,
  ADB_WIRELESS,
}

sealed class AdbInstallResult {
  object Success : AdbInstallResult()

  data class Failure(val message: String) : AdbInstallResult()
}

/**
 * Pintu utama fitur "Run lewat ADB Wireless". Semuanya pure Kotlin/Java (protokol ADB +
 * pairing SPAKE2/TLS dari libadb-android), jadi tidak perlu `pkg install android-tools`.
 */
object AdbWireless {

  private val log = LoggerFactory.getLogger(AdbWireless::class.java)

  /** Scope proses-wide supaya pairing dari notifikasi tetap jalan saat app di background. */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @Volatile private var manager: AcsAdbConnectionManager? = null

  private var stopWatch: (() -> Unit)? = null
  private var watchJob: Job? = null

  /** Dipanggil setelah pairing + connect sukses lewat notifikasi. */
  @Volatile var onPairedAndConnected: (() -> Unit)? = null

  /** Wireless debugging (pairing) baru ada di Android 11 (API 30) ke atas. */
  val isSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

  fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

  private fun manager(context: Context): AcsAdbConnectionManager {
    manager?.let {
      return it
    }
    return synchronized(this) {
      manager ?: AcsAdbConnectionManager.create(context).also { manager = it }
    }
  }

  fun isConnected(context: Context): Boolean =
      try {
        manager(context).isConnected()
      } catch (e: Throwable) {
        false
      }

  /**
   * Menyambung ke adbd di HP ini. Port dicari otomatis lewat mDNS, atau [manualPort] jika diisi.
   * Melempar exception jika gagal (mis. belum pairing / Wireless debugging mati).
   */
  suspend fun connect(context: Context, manualPort: Int? = null): Boolean =
      withContext(Dispatchers.IO) {
        val m = manager(context)
        if (m.isConnected()) return@withContext true

        val target: Pair<String, Int> =
            (if (manualPort != null) "127.0.0.1" to manualPort
            else AdbNsd.discover(context, AdbNsd.TYPE_CONNECT, 6000L))
                ?: throw IOException("Wireless debugging service not found")

        log.info("Connecting to adb at {}:{}", target.first, target.second)
        m.connect(target.first, target.second)
      }

  /** Pairing dengan kode 6 digit dari Developer options → Wireless debugging → Pair. */
  suspend fun pair(context: Context, host: String?, port: Int, code: String): Boolean =
      withContext(Dispatchers.IO) {
        val m = manager(context)
        log.info("Pairing with adb at {}:{}", host ?: "127.0.0.1", port)
        m.pair(host ?: "127.0.0.1", port, code)
      }

  /** Pairing lalu langsung connect. Dipakai dialog dan notifikasi. */
  suspend fun pairAndConnect(
      context: Context,
      host: String?,
      port: Int,
      code: String,
  ): Result<Unit> =
      runCatching {
        if (!pair(context, host, port, code)) {
          throw IOException("Pairing rejected (wrong code?)")
        }
        // Setelah pairing, port connect biasanya berbeda dari port pairing.
        var lastError: Throwable? = null
        repeat(3) {
          try {
            if (connect(context)) return@runCatching
          } catch (e: Throwable) {
            lastError = e
          }
        }
        throw lastError ?: IOException("Paired, but could not connect")
      }

  /** Mulai mengamati service pairing (muncul saat dialog "Pair device" di Settings terbuka). */
  fun startPairingWatch(context: Context, onFound: (host: String, port: Int) -> Unit) {
    stopPairingWatch()
    val app = context.applicationContext
    stopWatch = AdbNsd.watch(app, AdbNsd.TYPE_PAIRING) { host, port -> onFound(host, port) }
  }

  fun stopPairingWatch() {
    stopWatch?.invoke()
    stopWatch = null
    watchJob?.cancel()
    watchJob = null
  }

  /**
   * Menginstal APK lewat ADB (setara `adb install -r`). APK dikirim lewat stdin:
   * `cmd package install -S <ukuran>`, tanpa perlu menyalin file ke /data/local/tmp.
   */
  suspend fun installApk(context: Context, apk: File): AdbInstallResult =
      withContext(Dispatchers.IO) {
        try {
          val m = manager(context)
          val stream = m.openStream("exec:cmd package install -r -t -S ${apk.length()}")
          try {
            val out = stream.openOutputStream()
            apk.inputStream().use { it.copyTo(out, 64 * 1024) }
            out.flush()
            val text = stream.openInputStream().bufferedReader().readText().trim()
            log.info("adb install output: {}", text)
            if (text.contains("Success", ignoreCase = true)) {
              AdbInstallResult.Success
            } else {
              AdbInstallResult.Failure(text.ifEmpty { "Unknown install failure" })
            }
          } finally {
            try {
              stream.close()
            } catch (e: Throwable) {
              // abaikan
            }
          }
        } catch (e: Throwable) {
          log.error("ADB install failed", e)
          AdbInstallResult.Failure(e.message ?: e.javaClass.simpleName)
        }
      }
}
