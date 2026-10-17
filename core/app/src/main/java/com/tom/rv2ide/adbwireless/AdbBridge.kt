package com.tom.rv2ide.adbwireless

import android.content.Context
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * Jembatan lokal agar perintah `adb pair`, `adb connect`, `adb devices`, `adb install`,
 * `adb shell` bisa diketik di Terminal aplikasi TANPA binary adb. Skrip `adb` di folder bin
 * terminal hanya meneruskan perintah ke server loopback ini (token acak, hanya 127.0.0.1) dan
 * server memakai implementasi ADB di dalam aplikasi ([AdbWireless]).
 */
object AdbBridge {

  private var started = false

  @Synchronized
  fun start(context: Context) {
    if (started) return
    val app = context.applicationContext
    try {
      val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
      val token = UUID.randomUUID().toString()
      val dir = File(app.filesDir, "adb").apply { mkdirs() }
      val info = File(dir, "bridge")
      info.writeText("${server.localPort} $token\n")
      installScript(info)
      started = true

      Thread(
              {
                while (true) {
                  val socket =
                      try {
                        server.accept()
                      } catch (e: Throwable) {
                        break
                      }
                  Thread({ handle(app, socket, token) }, "adb-bridge-client").start()
                }
              },
              "adb-bridge",
          )
          .apply {
            isDaemon = true
            start()
          }
    } catch (e: Throwable) {
      started = false
    }
  }

  private fun installScript(info: File) {
    try {
      val bash = Environment.BASH_SHELL?.absolutePath ?: "/bin/sh"
      val script =
          """#!$bash
f="${info.absolutePath}"
if [ ! -r "${'$'}f" ]; then
  echo "adb: layanan ADB belum aktif. Buka dulu aplikasi Android Studio." >&2
  exit 1
fi
read -r port token < "${'$'}f"
case "${'$'}1" in
  ""|help|-h|--help)
    echo "Android Debug Bridge (bawaan aplikasi, tanpa binary adb)"
    echo "  adb pair [HOST:]PORT KODE   pairing dengan kode 6 digit (sekali saja, permanen)"
    echo "  adb connect [[HOST:]PORT]   sambung (port dicari otomatis bila dikosongkan)"
    echo "  adb devices                 daftar perangkat"
    echo "  adb install [-r] FILE.apk   install APK ke HP ini"
    echo "  adb shell PERINTAH          jalankan satu perintah shell"
    exit 0
    ;;
esac
exec 3<>"/dev/tcp/127.0.0.1/${'$'}port" || { echo "adb: tidak bisa menghubungi layanan ADB aplikasi" >&2; exit 1; }
printf '%s\n%s\n' "${'$'}token" "${'$'}*" >&3
cat <&3
exec 3>&-
"""
      val file = File(Environment.BIN_DIR, "adb")
      file.writeText(script)
      file.setExecutable(true, false)
      file.setReadable(true, false)
    } catch (e: Throwable) {
      // Terminal belum siap: skrip dibuat lagi pada start berikutnya.
    }
  }

  private fun handle(app: Context, socket: Socket, token: String) {
    socket.use {
      val reader = it.getInputStream().bufferedReader()
      val out = PrintWriter(it.getOutputStream().bufferedWriter(), true)
      if (reader.readLine() != token) {
        out.println("adb: token tidak valid")
        return
      }
      val args =
          (reader.readLine() ?: "").trim().split(Regex("\\s+")).filter { part -> part.isNotEmpty() }
      val text =
          try {
            runBlocking { execute(app, args) }
          } catch (t: Throwable) {
            AdbWireless.saveErrorLog(app, AdbWireless.describe(t))
            "error: " + AdbWireless.describe(t)
          }
      out.println(text)
      out.flush()
    }
  }

  private fun portOf(value: String): Int? = value.substringAfterLast(':').toIntOrNull()

  private suspend fun execute(app: Context, a: List<String>): String {
    if (!AdbWireless.isSupported) return "adb: butuh Android 11 atau lebih baru"

    return when (a.firstOrNull()) {
      "version" -> "Android Debug Bridge (bawaan aplikasi, tanpa binary adb)"

      "devices" ->
          if (AdbWireless.isConnected(app)) "List of devices attached\nlocalhost:wireless\tdevice"
          else "List of devices attached"

      "pair" -> {
        val target = a.getOrNull(1)
        val code = a.getOrNull(2)
        val port = target?.let { portOf(it) }
        if (port == null || code == null) {
          "usage: adb pair [HOST:]PORT KODE-6-DIGIT"
        } else {
          val host = if (target != null && target.contains(':')) target.substringBeforeLast(':') else null
          val result = AdbWireless.pairAndConnect(app, host, port, code)
          if (result.isSuccess) {
            "Successfully paired and connected (permanen: cukup nyalakan Debugging nirkabel lagi)"
          } else {
            "failed: " + result.exceptionOrNull()?.let { AdbWireless.describe(it) }
          }
        }
      }

      "connect" -> {
        val port = a.getOrNull(1)?.let { portOf(it) }
        if (AdbWireless.connect(app, port)) "connected to localhost" else "failed to connect"
      }

      "install" -> {
        val path = a.drop(1).lastOrNull { !it.startsWith("-") }
        val file = path?.let { File(it) }
        if (file == null || !file.isFile) {
          "usage: adb install [-r] FILE.apk"
        } else {
          when (val r = AdbWireless.installApk(app, file)) {
            is AdbInstallResult.Success -> "Success"
            is AdbInstallResult.Failure -> "Failure: ${r.message}"
          }
        }
      }

      "shell" -> {
        val cmd = a.drop(1).joinToString(" ")
        if (cmd.isBlank()) "usage: adb shell PERINTAH" else AdbWireless.shell(app, cmd)
      }

      else -> "adb: perintah tidak dikenal. Ketik: adb help"
    }
  }
}
