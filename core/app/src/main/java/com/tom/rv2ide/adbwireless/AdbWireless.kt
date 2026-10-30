package com.tom.rv2ide.adbwireless

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 *
 * Pairing bersifat permanen: key RSA disimpan di filesDir, status "sudah pairing" disimpan di
 * SharedPreferences, dan [AdbAutoConnect] menyambung ulang otomatis setiap Debugging nirkabel
 * dinyalakan kembali.
 */
object AdbWireless {

  private val log = LoggerFactory.getLogger(AdbWireless::class.java)

  private const val PREFS = "adb_wireless"
  private const val KEY_PAIRED = "paired"

  /** Scope proses-wide supaya pairing dari notifikasi tetap jalan saat app di background. */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @Volatile private var manager: AcsAdbConnectionManager? = null

  private var stopWatch: (() -> Unit)? = null

  /** Dipanggil setelah pairing + connect sukses lewat notifikasi. */
  @Volatile var onPairedAndConnected: (() -> Unit)? = null

  /** Wireless debugging (pairing) baru ada di Android 11 (API 30) ke atas. */
  val isSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

  fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

  // ---------------------------------------------------------------- status & diagnostik

  fun isPaired(context: Context): Boolean =
      context.applicationContext
          .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
          .getBoolean(KEY_PAIRED, false)

  private fun markPaired(context: Context) {
    context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_PAIRED, true)
        .apply()
  }

  /** Rantai exception lengkap (nama class + pesan + beberapa baris stacktrace). */
  fun describe(t: Throwable): String {
    val sb = StringBuilder()
    var cause: Throwable? = t
    var depth = 0
    while (cause != null && depth < 5) {
      sb.append(cause.javaClass.name)
      cause.message?.takeIf { it.isNotBlank() }?.let { sb.append(": ").append(it) }
      sb.append('\n')
      if (depth == 0) {
        cause.stackTrace.take(8).forEach { sb.append("    at ").append(it).append('\n') }
      }
      cause = cause.cause
      depth++
    }
    return sb.toString().trim()
  }

  /** Ringkasan satu baris untuk toast/notifikasi (tidak pernah kosong). */
  fun brief(t: Throwable): String {
    var root: Throwable = t
    while (root.cause != null && root.cause !== root) root = root.cause!!
    val msg = root.message?.takeIf { it.isNotBlank() } ?: t.message?.takeIf { it.isNotBlank() }
    return (root.javaClass.simpleName + (if (msg != null) ": $msg" else "")).take(160)
  }

  fun saveErrorLog(context: Context, text: String) {
    try {
      val dir = File(context.applicationContext.filesDir, "adb").apply { mkdirs() }
      File(dir, "last_error.txt").writeText(text)
    } catch (e: Throwable) {
      // abaikan
    }
  }

  // ---------------------------------------------------------------- manager

  private fun manager(context: Context): AcsAdbConnectionManager {
    manager?.let {
      return it
    }
    return synchronized(this) {
      manager ?: AcsAdbConnectionManager.create(context).also { manager = it }
    }
  }

  /** Manager baru (key yang sama diambil dari disk) agar tidak ada state pairing yang basi. */
  private fun freshManager(context: Context): AcsAdbConnectionManager =
      synchronized(this) { AcsAdbConnectionManager.create(context).also { manager = it } }

  fun isConnected(context: Context): Boolean =
      try {
        manager(context).isConnected()
      } catch (e: Throwable) {
        false
      }

  // ---------------------------------------------------------------- connect

  /** Connect ke host:port. Loopback dicoba lebih dulu (paling andal di HP yang sama). */
  suspend fun connectTo(context: Context, host: String?, port: Int): Boolean =
      withContext(Dispatchers.IO) {
        val m = manager(context)
        if (m.isConnected()) return@withContext true
        var last: Throwable? = null
        for (h in listOfNotNull("127.0.0.1", host).distinct()) {
          try {
            log.info("Connecting to adb at {}:{}", h, port)
            if (m.connect(h, port)) return@withContext true
          } catch (t: Throwable) {
            log.warn("connect $h:$port failed", t)
            last = t
          }
        }
        throw last ?: IOException("adbd menolak koneksi di port $port")
      }

  /**
   * Menyambung ke adbd di HP ini. Port dicari otomatis lewat mDNS, atau [manualPort] jika diisi.
   * Melempar exception jika gagal (mis. belum pairing / Debugging nirkabel mati).
   */
  suspend fun connect(context: Context, manualPort: Int? = null): Boolean =
      withContext(Dispatchers.IO) {
        if (manager(context).isConnected()) return@withContext true
        if (manualPort != null) return@withContext connectTo(context, null, manualPort)

        val target =
            AdbNsd.discover(context, AdbNsd.TYPE_CONNECT, 8000L)
                ?: throw IOException("Debugging nirkabel tidak ditemukan. Aktifkan dulu (Wi-Fi harus tersambung).")
        connectTo(context, target.first, target.second)
      }

  // ---------------------------------------------------------------- pairing

  /** Pairing dengan kode 6 digit dari Developer options → Wireless debugging → Pair. */
  suspend fun pair(context: Context, host: String?, port: Int, code: String): Boolean =
      withContext(Dispatchers.IO) {
        var last: Throwable? = null
        for (h in listOfNotNull("127.0.0.1", host).distinct()) {
          val m = freshManager(context)
          try {
            log.info("Pairing with adb at {}:{}", h, port)
            if (m.pair(h, port, code)) return@withContext true
            last = IOException("adbd menolak pairing di $h:$port (kode salah atau kedaluwarsa?)")
          } catch (t: Throwable) {
            log.warn("pair $h:$port failed", t)
            last = t
          }
        }
        throw last ?: IOException("Pairing gagal")
      }

  /** Pairing lalu langsung connect. Dipakai dialog dan notifikasi. */
  suspend fun pairAndConnect(
      context: Context,
      host: String?,
      port: Int,
      code: String,
  ): Result<Unit> {
    val result = runCatching {
      pair(context, host, port, code)
      markPaired(context)

      // Setelah pairing, port connect berbeda dari port pairing & butuh waktu muncul di mDNS.
      var lastError: Throwable? = null
      repeat(6) { attempt ->
        try {
          if (connect(context)) {
            AdbAutoConnect.restart(context)
            return@runCatching
          }
        } catch (e: Throwable) {
          lastError = e
        }
        delay(1500L * (attempt + 1) / 2)
      }
      throw lastError ?: IOException("Pairing berhasil, tetapi tidak bisa terhubung")
    }
    result.exceptionOrNull()?.let { saveErrorLog(context, describe(it)) }
    return result
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
  }

  // ---------------------------------------------------------------- install

  /**
   * Menginstal APK lewat ADB (setara `adb install -r`). APK dikirim lewat stdin:
   * `cmd package install -S <ukuran>`, tanpa perlu menyalin file ke /data/local/tmp.
   */
  suspend fun installApk(context: Context, apk: File, packageName: String? = null): AdbInstallResult =
      withContext(Dispatchers.IO) {
        try {
          val m = manager(context)
          if (!m.isConnected()) connect(context)
          val stream = m.openStream("exec:cmd package install -r -t -S ${apk.length()}")
          val text = StringBuilder()
          try {
            // adbd boleh menutup stream begitu semua byte diterima, jadi "Stream closed" saat
            // menulis/membaca BUKAN berarti install gagal. Hasil akhirnya diverifikasi di bawah.
            try {
              val out = stream.openOutputStream()
              apk.inputStream().use { it.copyTo(out, 64 * 1024) }
              out.flush()
            } catch (e: IOException) {
              log.info("write ended early: {}", e.message)
            }
            try {
              stream.openInputStream().bufferedReader().forEachLine { text.appendLine(it) }
            } catch (e: IOException) {
              log.info("read ended: {}", e.message)
            }
          } finally {
            try {
              stream.close()
            } catch (e: Throwable) {
              // abaikan
            }
          }

          val output = text.toString().trim()
          log.info("adb install output: {}", output)
          when {
            output.contains("Success", ignoreCase = true) -> AdbInstallResult.Success
            output.contains("Failure", ignoreCase = true) || output.contains("Error", ignoreCase = true) ->
                AdbInstallResult.Failure(output)
            // Output kosong: cek langsung apakah paketnya sudah terpasang.
            packageName != null && isPackageInstalled(context, packageName) -> AdbInstallResult.Success
            packageName == null && output.isEmpty() -> AdbInstallResult.Success
            else -> AdbInstallResult.Failure(output.ifEmpty { "Install tidak terkonfirmasi" })
          }
        } catch (e: Throwable) {
          log.error("ADB install failed", e)
          saveErrorLog(context, describe(e))
          AdbInstallResult.Failure(brief(e))
        }
      }

  private suspend fun isPackageInstalled(context: Context, packageName: String): Boolean =
      try {
        shell(context, "pm path $packageName").contains("package:")
      } catch (e: Throwable) {
        false
      }

  /** Membuka aliran `adb shell <command>` (mis. logcat). Mengembalikan pembaca baris dan fungsi penutup. */
  suspend fun openShellReader(context: Context, command: String): Pair<java.io.BufferedReader, () -> Unit> =
      withContext(Dispatchers.IO) {
        val m = manager(context)
        if (!m.isConnected()) connect(context)
        val stream = m.openStream("shell:$command")
        val reader = stream.openInputStream().bufferedReader()
        val closer: () -> Unit = {
          try {
            stream.close()
          } catch (e: Throwable) {
            // abaikan
          }
        }
        reader to closer
      }

  /** Menjalankan satu perintah `adb shell <command>` dan mengembalikan outputnya. */
  suspend fun shell(context: Context, command: String): String =
      withContext(Dispatchers.IO) {
        val m = manager(context)
        if (!m.isConnected()) connect(context)
        val stream = m.openStream("shell:$command")
        try {
          val sb = StringBuilder()
          try {
            stream.openInputStream().bufferedReader().forEachLine { sb.appendLine(it) }
          } catch (e: IOException) {
            // stream ditutup oleh adbd setelah perintah selesai
          }
          sb.toString()
        } finally {
          try {
            stream.close()
          } catch (e: Throwable) {
            // abaikan
          }
        }
      }
}
