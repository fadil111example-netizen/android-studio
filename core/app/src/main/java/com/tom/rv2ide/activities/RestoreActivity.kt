package com.tom.rv2ide.activities

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.tom.rv2ide.app.IDEApplication
import com.tom.rv2ide.utils.Environment
import java.nio.file.Files
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.tukaani.xz.XZInputStream

/**
 * Muat (restore) komponen yang sudah pernah diunduh: SDK, JDK, plugin, server LSP, proyek, dsb.
 * Sumber boleh arsip (.zip .tar .tar.gz .tgz .tar.xz) atau folder, banyak sekaligus. Aplikasi
 * mengenali isinya, MENOLAK yang bukan arm64 (aarch64), merapikan struktur, lalu meletakkannya di
 * lokasi yang benar (persis seperti hasil setup otomatis).
 */
class RestoreActivity : AppCompatActivity() {

  private class Source(val uri: Uri, val isFolder: Boolean, val name: String)

  private class Component(val kind: String, val src: File, val dest: File)

  private val sources = mutableListOf<Source>()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private class RestoreCancelled : RuntimeException("Dibatalkan")

  @Volatile private var cancelled = false
  @Volatile private var busyFlag = false
  @Volatile private var totalBytes = 0L
  @Volatile private var phase = ""
  @Volatile private var phaseStart = 0L
  private val doneBytes = AtomicLong(0)
  private var wakeLock: PowerManager.WakeLock? = null

  private lateinit var statusView: TextView
  private lateinit var btnBackup: MaterialButton
  private lateinit var btnCancel: MaterialButton

  private lateinit var listView: TextView
  private lateinit var logView: TextView
  private lateinit var logScroll: ScrollView
  private lateinit var progress: ProgressBar
  private lateinit var btnArchive: MaterialButton
  private lateinit var btnFolder: MaterialButton
  private lateinit var btnStart: MaterialButton
  private lateinit var btnDone: MaterialButton
  private lateinit var btnJdk: MaterialButton

  private val terminalCallback =
      registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Kembali dari Terminal: layar setup akan mengecek ulang JDK dan lanjut bila lengkap.
        setResult(RESULT_OK)
        finish()
      }

  private val pickArchives =
      registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { sources.add(Source(it, false, displayName(it) ?: "arsip")) }
        refreshList()
      }

  private val pickFolder =
      registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
          sources.add(Source(uri, true, treeName(uri) ?: "folder"))
          refreshList()
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Layar tetap menyala selama proses panjang (restore/backup bisa beberapa menit).
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    buildUi()
    refreshList()
  }

  override fun onDestroy() {
    super.onDestroy()
    scope.cancel()
  }

  // ------------------------------------------------------------------------------------- UI

  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

  private fun buildUi() {
    val root =
        LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(dp(20), dp(24), dp(20), dp(16))
        }

    root.addView(
        TextView(this).apply {
          text = "Muat dari file / folder"
          textSize = 24f
          setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    )
    root.addView(
        TextView(this).apply {
          text =
              "Pilih arsip (.zip .tar .tar.gz .tar.xz) atau folder yang sudah kamu punya " +
                  "(android-sdk, acs, plugins, ACSProjects, .gradle, JDK, dll). Bisa banyak sekaligus. " +
                  "Semua komponen yang dikenali dimuat apa adanya; struktur yang berantakan dirapikan otomatis."
          textSize = 14f
          setPadding(0, dp(8), 0, dp(12))
        }
    )

    btnArchive = button("Tambah file arsip") { pickArchives.launch(arrayOf("*/*")) }
    btnFolder = button("Tambah folder") { pickFolder.launch(null) }
    root.addView(btnArchive)
    root.addView(btnFolder)

    listView = TextView(this).apply { setPadding(0, dp(12), 0, dp(8)) }
    root.addView(listView)

    btnStart = button("Mulai muat") { startRestore() }
    root.addView(btnStart)

    btnBackup = button("Buat backup lengkap (1 tombol)") { startBackup() }
    root.addView(btnBackup)

    btnCancel =
        button("Batal") {
          cancelled = true
          log("Membatalkan, mohon tunggu sebentar ...")
        }
    btnCancel.visibility = android.view.View.GONE
    root.addView(btnCancel)

    progress =
        ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
          max = 1000
          isIndeterminate = true
          visibility = android.view.View.GONE
        }
    root.addView(
        progress,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) },
    )
    statusView = TextView(this).apply { textSize = 13f; visibility = android.view.View.GONE }
    root.addView(statusView)

    logView =
        TextView(this).apply {
          typeface = android.graphics.Typeface.MONOSPACE
          textSize = 12f
          setTextIsSelectable(true)
        }
    root.addView(logView)

    btnJdk =
        button("Instal JDK otomatis (hanya JDK, 1 tombol)") {
          terminalCallback.launch(
              android.content.Intent(this, TerminalActivity::class.java)
                  .putExtra(
                      TerminalActivity.EXTRA_RUN_COMMAND,
                      "pkg install -y openjdk-17 git openssh; echo; echo '=== Selesai. Kembali ke aplikasi... ==='; sleep 3",
                  )
          )
        }
    btnJdk.visibility = android.view.View.GONE
    root.addView(btnJdk)

    btnDone =
        button("Selesai") {
          setResult(RESULT_OK)
          finish()
        }
    btnDone.isEnabled = false
    root.addView(btnDone)

    // Seluruh layar bisa di-scroll agar semua tombol & log terlihat di HP kecil.
    logScroll = ScrollView(this).apply {
      isFillViewport = true
      addView(root)
    }
    setContentView(logScroll)
  }

  private fun button(label: String, onClick: () -> Unit): MaterialButton =
      MaterialButton(this).apply {
        text = label
        layoutParams =
            LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                .apply { topMargin = dp(6) }
        setOnClickListener { onClick() }
      }

  private fun refreshList() {
    listView.text =
        if (sources.isEmpty()) "Belum ada yang dipilih."
        else
            sources.joinToString("\n") { (if (it.isFolder) "[folder] " else "[arsip] ") + it.name }
  }

  private fun log(msg: String) {
    runOnUiThread {
      logView.append(msg + "\n")
      logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }
  }

  private fun setBusy(busy: Boolean) {
    runOnUiThread {
      val v = if (busy) android.view.View.VISIBLE else android.view.View.GONE
      progress.visibility = v
      statusView.visibility = v
      btnCancel.visibility = v
      btnArchive.isEnabled = !busy
      btnFolder.isEnabled = !busy
      btnStart.isEnabled = !busy
      btnBackup.isEnabled = !busy
    }
  }

  private fun displayName(uri: Uri): String? =
      try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
          if (it.moveToFirst()) it.getString(0) else null
        }
      } catch (e: Throwable) {
        null
      }

  private fun treeName(tree: Uri): String? =
      try {
        val doc =
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        contentResolver
            .query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
      } catch (e: Throwable) {
        null
      }

  // ------------------------------------------------------------------------------- proses

  private fun startRestore() {
    if (sources.isEmpty()) {
      log("Pilih minimal satu file atau folder dulu.")
      return
    }
    beginWork()
    scope.launch {
      var placed = 0
      try {
        placed = process()
      } catch (c: RestoreCancelled) {
        log("Dibatalkan. Berkas sementara dibersihkan.")
      } catch (t: Throwable) {
        log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
      } finally {
        endWork()
        runOnUiThread { btnDone.isEnabled = placed > 0 }
      }
    }
  }

  private fun beginWork() {
    cancelled = false
    busyFlag = true
    doneBytes.set(0)
    totalBytes = 0
    phase = ""
    phaseStart = System.currentTimeMillis()
    acquireWakeLock()
    setBusy(true)
    startMonitor()
  }

  private fun endWork() {
    busyFlag = false
    releaseWakeLock()
    setBusy(false)
  }

  private fun checkCancel() {
    if (cancelled) throw RestoreCancelled()
  }

  private fun acquireWakeLock() {
    try {
      val pm = getSystemService(POWER_SERVICE) as PowerManager
      wakeLock =
          pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "acs:restore").apply {
            setReferenceCounted(false)
            acquire(3L * 60 * 60 * 1000)
          }
    } catch (e: Throwable) {
      // abaikan
    }
  }

  private fun releaseWakeLock() {
    try {
      wakeLock?.let { if (it.isHeld) it.release() }
    } catch (e: Throwable) {
      // abaikan
    }
    wakeLock = null
  }

  private fun fmt(b: Long): String =
      when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        else -> "${b / 1024} KB"
      }

  private fun fmtTime(sec: Long): String = if (sec >= 60) "${sec / 60} mnt ${sec % 60} dtk" else "$sec dtk"

  /** Memperbarui progres (persen, ukuran, perkiraan sisa waktu) beberapa kali per detik. */
  private fun startMonitor() {
    scope.launch {
      while (busyFlag) {
        delay(400)
        val total = totalBytes
        val done = doneBytes.get()
        val elapsed = (System.currentTimeMillis() - phaseStart).coerceAtLeast(1)
        val text: String
        var pct = -1
        if (total > 0) {
          pct = (done * 1000 / total).toInt().coerceIn(0, 1000)
          val speed = done * 1000.0 / elapsed
          val remain = if (speed > 1) ((total - done) / speed).toLong() else -1L
          text =
              "$phase\n${pct / 10}% · ${fmt(done)} / ${fmt(total)}" +
                  (if (remain >= 0) " · sisa ±${fmtTime(remain)}" else "")
        } else {
          text = "$phase\n$done entri"
        }
        runOnUiThread {
          statusView.text = text
          if (pct >= 0) {
            progress.isIndeterminate = false
            progress.progress = pct
          } else {
            progress.isIndeterminate = true
          }
        }
      }
    }
  }

  private fun querySize(uri: Uri): Long =
      try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
          if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
      } catch (e: Throwable) {
        -1L
      }

  private fun process(): Int {
    val staging = File(Environment.HOME, ".restore_tmp")
    staging.deleteRecursively()
    staging.mkdirs()

    try {
      // Cek ruang kosong sebelum mulai.
      val free = Environment.HOME.usableSpace
      var strict = 0L
      var estimate = 0L
      for (s in sources) {
        if (s.isFolder) continue
        val sz = querySize(s.uri)
        if (sz <= 0) continue
        strict += (sz * 1.05).toLong()
        estimate +=
            when {
              s.name.endsWith(".tar", true) -> (sz * 1.1).toLong()
              s.name.endsWith(".zip", true) -> sz * 2
              else -> sz * 3
            }
      }
      log("Ruang kosong: ${fmt(free)}" + (if (estimate > 0) ", perkiraan kebutuhan: ${fmt(estimate)}" else ""))
      if (strict > 0 && free < strict) {
        log("RUANG TIDAK CUKUP: butuh minimal ${fmt(strict)}, tersedia ${fmt(free)}. Kosongkan ruang lalu coba lagi.")
        return 0
      }
      if (estimate > 0 && free < estimate) log("Peringatan: ruang kosong mungkin kurang untuk arsip terkompresi.")

      sources.forEachIndexed { i, s ->
        checkCancel()
        val dir = File(staging, "src$i-${s.name.replace('/', '_')}")
        dir.mkdirs()
        log("Membaca ${s.name} ...")
        phase = "Membaca ${s.name} (${i + 1}/${sources.size})"
        phaseStart = System.currentTimeMillis()
        doneBytes.set(0)
        totalBytes = if (s.isFolder) 0L else maxOf(0L, querySize(s.uri))
        if (s.isFolder) copyFolder(s.uri, dir) else extractArchive(s.uri, dir)
      }
      phase = "Menempatkan komponen ..."
      phaseStart = System.currentTimeMillis()
      doneBytes.set(0)
      totalBytes = 0

      val found = mutableListOf<Component>()
      scan(staging, 0, found)
      if (found.isEmpty()) {
        log("Tidak ada komponen yang dikenali (SDK, JDK, acs, plugins, ACSProjects, .gradle).")
        return 0
      }

      var ok = 0
      for (c in found) {
        // Tidak ada penolakan: semua komponen yang dikenali selalu dimuat.
        val note = validate(c)
        if (note != null) log("Catatan [${c.kind}]: $note (tetap dimuat)")
        log("Menempatkan [${c.kind}] -> ${c.dest.absolutePath}")
        mergeInto(c.src, c.dest)
        fixExecutables(c.dest)
        if (c.kind == "Kunci ADB") markAdbPaired()
        if (c.kind == "JDK") {
          Environment.JAVA_HOME = c.dest
          Environment.JAVA = File(c.dest, "bin/java")
        }
        ok++
      }
      log(if (ok > 0) "SELESAI: $ok komponen berhasil dimuat." else "Tidak ada yang diterima.")

      // Aplikasi menganggap JDK 'terpasang' hanya jika `java` benar-benar bisa dijalankan.
      var jdkOk = false
      val jdkHome = File(Environment.PREFIX, "lib/jvm").listFiles()?.firstOrNull { File(it, "bin/java").isFile }
      if (jdkHome != null) {
        val problem = probeJdk(jdkHome)
        if (problem == null) {
          jdkOk = true
          Environment.JAVA_HOME = jdkHome
          Environment.JAVA = File(jdkHome, "bin/java")
          log("Cek JDK: OK (java berjalan normal).")
        } else {
          runOnUiThread { btnJdk.visibility = android.view.View.VISIBLE }
          log("Cek JDK: GAGAL. java tidak bisa dijalankan. Pesan dari sistem:\n$problem")
          log(
              "Artinya JDK ini butuh pustaka Termux lain yang tidak ikut di backup. " +
                  "Tekan tombol 'Instal JDK otomatis' di bawah: hanya JDK (dan git/openssh) yang " +
                  "diunduh, SDK/plugin/Gradle hasil restore TIDAK diunduh ulang."
          )
        }
      }
      if (jdkHome == null) {
        runOnUiThread { btnJdk.visibility = android.view.View.VISIBLE }
        log("JDK belum ada. Tekan 'Instal JDK otomatis' di bawah (hanya JDK yang diunduh).")
      }

      // Ringkasan akhir
      val sdkOk = File(Environment.ANDROID_HOME, "platforms").listFiles()?.any { File(it, "android.jar").isFile } == true
      val btOk = File(Environment.ANDROID_HOME, "build-tools").listFiles()?.isNotEmpty() == true
      val lspOk = File(Environment.HOME, "acs/servers/kotlin").isDirectory
      val gradleOk = File(Environment.HOME, ".gradle").isDirectory
      log("— Ringkasan —")
      log((if (sdkOk) "✓" else "✗") + " Android SDK (platforms)")
      log((if (btOk) "✓" else "✗") + " Build-tools")
      log((if (jdkOk) "✓" else "✗") + " JDK (java berjalan)")
      log((if (lspOk) "✓" else "–") + " Kotlin language server")
      log((if (gradleOk) "✓" else "–") + " Gradle")
      if (ok > 0 && sdkOk && btOk && jdkOk) {
        log("Semua siap. Lanjut otomatis dalam beberapa detik ...")
        runOnUiThread {
          Handler(Looper.getMainLooper()).postDelayed(
              {
                if (!isFinishing) {
                  setResult(RESULT_OK)
                  finish()
                }
              },
              2500,
          )
        }
      }
      return ok
    } finally {
      staging.deleteRecursively()
    }
  }

  private fun probeJdk(javaHome: File): String? =
      try {
        val java = File(javaHome, "bin/java")
        if (!java.canExecute()) java.setExecutable(true, false)
        val env = HashMap(TermuxShellEnvironment().getEnvironment(IDEApplication.instance, false))
        val pb =
            ProcessBuilder(
                Environment.BASH_SHELL.absolutePath,
                "-c",
                "${java.absolutePath} -XshowSettings:properties -version",
            )
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        pb.directory(Environment.HOME)
        val out = pb.start().inputStream.bufferedReader().readText()
        if (out.contains("java.home") && out.contains("java.version")) null
        else out.lines().filter { it.isNotBlank() }.take(6).joinToString("\n")
      } catch (t: Throwable) {
        "${t.javaClass.simpleName}: ${t.message}"
      }

  // ----------------------------------------------------------------------------- deteksi

  private fun subdirs(d: File, vararg names: String) = names.count { File(d, it).isDirectory }

  private fun classify(d: File, depth: Int): Component? {
    val n = d.name
    val home = Environment.HOME
    val sdkMarkers = subdirs(d, "platforms", "build-tools", "platform-tools", "cmdline-tools")

    if (sdkMarkers >= 2 || (n == "android-sdk" && sdkMarkers >= 1))
        return Component("Android SDK", d, Environment.ANDROID_HOME)
    if (File(d, "servers").isDirectory && (n == "acs" || subdirs(File(d, "servers"), "kotlin", "c_cpp") > 0))
        return Component("ACS (server bahasa)", d, File(home, "acs"))
    if (n == "servers" && subdirs(d, "kotlin", "c_cpp") > 0)
        return Component("ACS servers", d, File(home, "acs/servers"))
    if (n == "usr" && File(d, "bin").isDirectory && File(d, "lib").isDirectory &&
        (File(d, "bin/bash").exists() || File(d, "lib/jvm").isDirectory))
        return Component("Termux usr (JDK, git, dll)", d, Environment.PREFIX)
    if (File(d, "bin/java").isFile && (File(d, "lib/modules").exists() || File(d, "release").exists())) {
      val major = jdkMajor(d) ?: "17"
      return Component("JDK", d, File(Environment.PREFIX, "lib/jvm/java-$major-openjdk"))
    }
    if (depth <= 2 && n == "adb" && File(d, "adb_private_key.pk8").isFile)
        return Component("Kunci ADB", d, File(home.parentFile, "adb"))
    if (depth <= 2 && n == ".gradle") return Component("Gradle", d, File(home, ".gradle"))
    if (depth <= 2 && n == "plugins") return Component("Plugins", d, File(home, "plugins"))
    if (depth <= 2 && n == "ACSProjects") return Component("Proyek ACS", d, File(home, "ACSProjects"))
    if (n.startsWith("android-") && File(d, "android.jar").isFile)
        return Component("Platform SDK", d, File(Environment.ANDROID_HOME, "platforms/$n"))
    if (File(d, "aapt2").isFile && n.firstOrNull()?.isDigit() == true)
        return Component("Build-tools", d, File(Environment.ANDROID_HOME, "build-tools/$n"))
    if (n == "platform-tools" && (File(d, "adb").exists() || File(d, "fastboot").exists()))
        return Component("Platform-tools", d, File(Environment.ANDROID_HOME, "platform-tools"))
    if (File(d, "bin/sdkmanager").isFile)
        return Component("Cmdline-tools", d, File(Environment.ANDROID_HOME, "cmdline-tools/${if (n == "latest" || n.firstOrNull()?.isDigit() == true) n else "latest"}"))
    return null
  }

  private fun scan(dir: File, depth: Int, out: MutableList<Component>) {
    if (depth > 6) return
    val children = dir.listFiles()?.filter { it.isDirectory } ?: return
    for (c in children) {
      val comp = classify(c, depth)
      if (comp != null) out.add(comp) else scan(c, depth + 1, out)
    }
  }

  private fun jdkMajor(d: File): String? =
      try {
        File(d, "release").readLines().firstOrNull { it.startsWith("JAVA_VERSION=") }
            ?.substringAfter('=')?.trim('"')?.substringBefore('.')?.takeIf { it.all(Char::isDigit) }
      } catch (e: Throwable) {
        null
      }

  /** e_machine ELF: 183 = AArch64. Null bila bukan ELF. */
  private fun elfMachine(f: File): Int? =
      try {
        f.inputStream().use {
          val h = ByteArray(20)
          if (it.read(h) < 20) return null
          if (h[0] != 0x7F.toByte() || h[1] != 'E'.code.toByte() || h[2] != 'L'.code.toByte() || h[3] != 'F'.code.toByte()) return null
          (h[18].toInt() and 0xFF) or ((h[19].toInt() and 0xFF) shl 8)
        }
      } catch (e: Throwable) {
        null
      }

  private fun sampleBinaries(c: Component): List<File> {
    val s = c.src
    val list = mutableListOf<File>()
    when (c.kind) {
      "Android SDK" -> {
        File(s, "build-tools").listFiles()?.forEach { list.add(File(it, "aapt2")) }
        list.add(File(s, "platform-tools/adb"))
      }
      "JDK" -> list.add(File(s, "bin/java"))
      "Build-tools" -> list.add(File(s, "aapt2"))
      "Platform-tools" -> list.add(File(s, "adb"))
      "ACS (server bahasa)" -> list.add(File(s, "servers/c_cpp/server/bin/clangd"))
      "ACS servers" -> list.add(File(s, "c_cpp/server/bin/clangd"))
    }
    return list.filter { it.isFile }
  }

  /** Mengembalikan alasan penolakan, atau null jika aman diterima. */
  private fun validate(c: Component): String? {
    for (f in sampleBinaries(c)) {
      val m = elfMachine(f) ?: continue
      if (m != 183) {
        val arch = when (m) { 62 -> "x86_64"; 3 -> "x86"; 40 -> "arm 32-bit"; else -> "arsitektur $m" }
        return "${f.name} berarsitektur $arch, bukan arm64 (aarch64)"
      }
    }
    return null
  }

  // ----------------------------------------------------------------------------- penempatan

  private fun isLink(f: File): Boolean =
      try {
        Files.isSymbolicLink(f.toPath())
      } catch (e: Throwable) {
        false
      }

  private fun mergeInto(src: File, dest: File) {
    checkCancel()
    if (isLink(src)) {
      try {
        val target = Files.readSymbolicLink(src.toPath()).toString()
        if (isLink(dest) || (dest.exists() && !dest.isDirectory)) dest.delete()
        if (!dest.exists() && !isLink(dest)) {
          dest.parentFile?.mkdirs()
          Os.symlink(target, dest.path)
        }
      } catch (e: Throwable) {
        // abaikan symlink yang gagal dibuat
      }
      return
    }
    if (!dest.exists() && !isLink(dest)) {
      dest.parentFile?.mkdirs()
      if (src.renameTo(dest)) return
    }
    if (src.isDirectory) {
      dest.mkdirs()
      src.listFiles()?.forEach { mergeInto(it, File(dest, it.name)) }
    } else {
      if (!isLink(dest) && dest.exists() && dest.length() == src.length()) return
      if (isLink(dest)) dest.delete()
      dest.parentFile?.mkdirs()
      try {
        src.copyTo(dest, overwrite = true)
      } catch (e: IOException) {
        log("  gagal menimpa ${dest.name}: ${e.message}")
      }
    }
  }

  private fun fixExecutables(root: File) {
    root.walkTopDown().onEnter { !isLink(it) }.forEach { f ->
      if (isLink(f) || !f.isFile || f.length() < 4) return@forEach
      try {
        f.inputStream().use {
          val h = ByteArray(4)
          if (it.read(h) == 4) {
            val elf = h[0] == 0x7F.toByte() && h[1] == 'E'.code.toByte()
            val shebang = h[0] == '#'.code.toByte() && h[1] == '!'.code.toByte()
            if (elf || shebang) f.setExecutable(true, false)
          }
        }
      } catch (e: Throwable) {
        // abaikan
      }
    }
  }

  // ----------------------------------------------------------------------- baca sumber

  private fun copyFolder(tree: Uri, dest: File) {
    copyTree(tree, DocumentsContract.getTreeDocumentId(tree), dest)
  }

  private fun copyTree(tree: Uri, docId: String, dest: File) {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
    val cols =
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
    contentResolver.query(children, cols, null, null, null)?.use { c ->
      while (c.moveToNext()) {
        checkCancel()
        doneBytes.incrementAndGet()
        val id = c.getString(0)
        val name = c.getString(1)
        val mime = c.getString(2)
        val target = File(dest, name)
        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
          target.mkdirs()
          copyTree(tree, id, target)
        } else {
          val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
          contentResolver.openInputStream(uri)?.use { ins ->
            target.outputStream().use { out -> ins.copyTo(out, 256 * 1024) }
          }
        }
      }
    }
  }

  private fun extractArchive(uri: Uri, dest: File) {
    val counting =
        CountingInputStream(
            contentResolver.openInputStream(uri) ?: throw IOException("Tidak bisa membuka file"),
            doneBytes,
        )
    val raw = BufferedInputStream(counting, 256 * 1024)
    raw.use { input ->
      input.mark(8)
      val magic = ByteArray(6)
      val n = input.read(magic)
      input.reset()
      val isZip = n >= 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()
      val isGz = n >= 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte()
      val isXz = n >= 6 && magic[0] == 0xFD.toByte() && magic[1] == '7'.code.toByte() && magic[2] == 'z'.code.toByte()
      when {
        isZip -> extractZip(input, dest)
        isGz -> extractTar(GZIPInputStream(input, 256 * 1024), dest)
        isXz -> extractTar(XZInputStream(input), dest)
        else -> extractTar(input, dest)
      }
    }
  }

  private fun safeFile(dest: File, name: String): File {
    val out = File(dest, name).canonicalFile
    val base = dest.canonicalPath + File.separator
    if (!out.path.startsWith(base) && out.path != dest.canonicalPath) throw IOException("Path tidak aman: $name")
    return out
  }

  private fun extractZip(input: InputStream, dest: File) {
    val zip = ZipInputStream(input)
    var count = 0
    while (true) {
      checkCancel()
      val e = zip.nextEntry ?: break
      val f = safeFile(dest, e.name)
      if (e.isDirectory) {
        f.mkdirs()
      } else {
        f.parentFile?.mkdirs()
        f.outputStream().use { zip.copyTo(it, 256 * 1024) }
      }
      if (++count % 500 == 0) log("  ... $count entri")
    }
  }

  private fun readFully(input: InputStream, buf: ByteArray, len: Int = buf.size): Boolean {
    var off = 0
    while (off < len) {
      val r = input.read(buf, off, len - off)
      if (r < 0) return off > 0 && false
      off += r
    }
    return true
  }

  private fun skipFully(input: InputStream, n: Long) {
    var left = n
    val tmp = ByteArray(8192)
    while (left > 0) {
      val r = input.read(tmp, 0, minOf(left, tmp.size.toLong()).toInt())
      if (r < 0) return
      left -= r
    }
  }

  private fun cstr(b: ByteArray, off: Int, len: Int): String {
    var end = off
    while (end < off + len && b[end] != 0.toByte()) end++
    return String(b, off, end - off, Charsets.UTF_8)
  }

  private fun octal(b: ByteArray, off: Int, len: Int): Long {
    val s = cstr(b, off, len).trim()
    return if (s.isEmpty()) 0 else s.toLongOrNull(8) ?: 0
  }

  private fun extractTar(input: InputStream, dest: File) {
    val header = ByteArray(512)
    var longName: String? = null
    var count = 0
    while (true) {
      checkCancel()
      if (!readFully(input, header)) break
      if (header.all { it == 0.toByte() }) break

      var name = cstr(header, 0, 100)
      val mode = octal(header, 100, 8)
      val size = octal(header, 124, 12)
      val type = header[156].toInt().toChar()
      val link = cstr(header, 157, 100)
      val prefix = cstr(header, 345, 155)
      if (prefix.isNotEmpty() && cstr(header, 257, 5) == "ustar") name = "$prefix/$name"
      val padding = (512 - (size % 512)) % 512

      if (type == 'L' || type == 'x' || type == 'g') {
        val data = ByteArray(size.toInt())
        readFully(input, data)
        skipFully(input, padding)
        if (type == 'L') {
          longName = String(data, Charsets.UTF_8).trimEnd('\u0000')
        } else if (type == 'x') {
          String(data, Charsets.UTF_8).lineSequence().forEach { line ->
            val kv = line.substringAfter(' ', "")
            if (kv.startsWith("path=")) longName = kv.removePrefix("path=")
          }
        }
        continue
      }

      longName?.let {
        name = it
        longName = null
      }
      if (name.isEmpty()) {
        skipFully(input, size + padding)
        continue
      }

      val f = safeFile(dest, name.trimEnd('/'))
      when (type) {
        '5' -> {
          f.mkdirs()
        }
        '2' -> {
          f.parentFile?.mkdirs()
          try {
            Os.symlink(link, f.path)
          } catch (e: Throwable) {
            // symlink gagal: abaikan
          }
        }
        '1' -> {
          val target = File(dest, link)
          if (target.isFile) {
            f.parentFile?.mkdirs()
            target.copyTo(f, overwrite = true)
          }
        }
        '0', '\u0000', '7' -> {
          f.parentFile?.mkdirs()
          f.outputStream().use { out ->
            var left = size
            val buf = ByteArray(64 * 1024)
            while (left > 0) {
              val r = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
              if (r < 0) throw IOException("Arsip terpotong / rusak")
              out.write(buf, 0, r)
              left -= r
            }
          }
          if (mode and 0b001001001L != 0L) f.setExecutable(true, false)
          skipFully(input, padding)
          if (++count % 1000 == 0) log("  ... $count file")
          continue
        }
        else -> {}
      }
      skipFully(input, size + padding)
    }
  }

  // ----------------------------------------------------------------------- ADB (kunci pairing)

  private fun markAdbPaired() {
    try {
      getSharedPreferences("adb_wireless", MODE_PRIVATE).edit().putBoolean("paired", true).apply()
    } catch (e: Throwable) {
      // abaikan
    }
  }

  // ----------------------------------------------------------------------------- backup

  private fun startBackup() {
    beginWork()
    scope.launch {
      try {
        val out = doBackup()
        log("BACKUP SELESAI: ${out.absolutePath} (${fmt(out.length())})")
        log("Salin file ini ke tempat lain (Drive/komputer) agar aman jika HP di-reset.")
      } catch (c: RestoreCancelled) {
        log("Backup dibatalkan. File sementara dihapus.")
      } catch (t: Throwable) {
        log("ERROR backup: ${t.javaClass.simpleName}: ${t.message}")
      } finally {
        endWork()
      }
    }
  }

  private fun backupExcluded(rel: String): Boolean =
      rel == "usr/tmp" ||
          rel.startsWith("usr/tmp/") ||
          rel.startsWith("home/.restore_tmp") ||
          (rel.startsWith("usr/var/cache/apt/archives/") && rel.endsWith(".deb")) ||
          rel == "adb/bridge" ||
          rel == "adb/last_error.txt"

  private fun sizeWalk(f: File, rel: String): Long {
    checkCancel()
    if (backupExcluded(rel) || isLink(f)) return 0
    if (f.isDirectory) {
      var sum = 0L
      f.listFiles()?.forEach { sum += sizeWalk(it, "$rel/${it.name}") }
      return sum
    }
    return if (f.isFile) f.length() else 0
  }

  private fun doBackup(): File {
    val filesDir = Environment.HOME.parentFile ?: throw IOException("Folder data tidak ditemukan")
    val roots =
        listOf("usr", "home", "adb").map { File(filesDir, it) to it }.filter { it.first.isDirectory }

    phase = "Menghitung ukuran ..."
    phaseStart = System.currentTimeMillis()
    doneBytes.set(0)
    totalBytes = 0
    var total = 0L
    roots.forEach { total += sizeWalk(it.first, it.second) }

    val dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
    dl.mkdirs()
    if (dl.usableSpace < total) {
      throw IOException("Ruang kosong kurang: butuh ±${fmt(total)}, tersedia ${fmt(dl.usableSpace)}")
    }

    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    val outFile = File(dl, "acs-full-$stamp.tar")
    val part = File(dl, outFile.name + ".part")

    phase = "Membuat backup → ${outFile.name}"
    phaseStart = System.currentTimeMillis()
    doneBytes.set(0)
    totalBytes = total
    log("Membuat backup (${fmt(total)}) ...")

    try {
      BufferedOutputStream(FileOutputStream(part), 256 * 1024).use { out ->
        roots.forEach { tarWalk(out, it.first, it.second) }
        out.write(ByteArray(1024))
      }
      if (!part.renameTo(outFile)) throw IOException("Gagal memberi nama file backup")
    } catch (t: Throwable) {
      part.delete()
      throw t
    }
    return outFile
  }

  private fun tarWalk(out: OutputStream, f: File, rel: String) {
    checkCancel()
    if (backupExcluded(rel)) return
    val mtime = f.lastModified() / 1000
    when {
      isLink(f) -> {
        val target = Files.readSymbolicLink(f.toPath()).toString()
        tarHeader(out, rel, 0, 511, '2', target, mtime)
      }
      f.isDirectory -> {
        tarHeader(out, "$rel/", 0, 493, '5', "", mtime)
        f.listFiles()?.sortedBy { it.name }?.forEach { tarWalk(out, it, "$rel/${it.name}") }
      }
      f.isFile -> {
        val size = f.length()
        if (size > 8589934591L) {
          log("Dilewati (terlalu besar): $rel")
          return
        }
        tarHeader(out, rel, size, if (f.canExecute()) 493 else 420, '0', "", mtime)
        var written = 0L
        try {
          f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (written < size) {
              val r = ins.read(buf, 0, minOf(buf.size.toLong(), size - written).toInt())
              if (r < 0) break
              out.write(buf, 0, r)
              written += r
              doneBytes.addAndGet(r.toLong())
            }
          }
        } catch (e: IOException) {
          // file berubah / tidak terbaca: sisanya diisi nol agar arsip tetap utuh
        }
        var missing = size - written
        if (missing > 0) {
          val zeros = ByteArray(8192)
          while (missing > 0) {
            val n = minOf(zeros.size.toLong(), missing).toInt()
            out.write(zeros, 0, n)
            missing -= n
          }
        }
        val pad = ((512 - size % 512) % 512).toInt()
        if (pad > 0) out.write(ByteArray(pad))
      }
      else -> {}
    }
  }

  private fun tarHeader(out: OutputStream, name: String, size: Long, mode: Int, type: Char, link: String, mtime: Long) {
    var nameBytes = name.toByteArray(Charsets.UTF_8)
    if (nameBytes.size > 100) {
      // GNU long name: entri 'L' berisi nama lengkap
      val data = nameBytes + byteArrayOf(0)
      tarRaw(out, "././@LongLink".toByteArray(), data.size.toLong(), 0, 'L', ByteArray(0), 0)
      out.write(data)
      val pad = ((512 - data.size % 512) % 512)
      if (pad > 0) out.write(ByteArray(pad))
      nameBytes = nameBytes.copyOf(100)
    }
    val linkBytes = link.toByteArray(Charsets.UTF_8).let { if (it.size > 100) it.copyOf(100) else it }
    tarRaw(out, nameBytes, size, mode, type, linkBytes, mtime)
  }

  private fun tarRaw(out: OutputStream, name: ByteArray, size: Long, mode: Int, type: Char, link: ByteArray, mtime: Long) {
    val h = ByteArray(512)
    System.arraycopy(name, 0, h, 0, minOf(name.size, 100))
    putOctal(h, 100, 8, mode.toLong())
    putOctal(h, 108, 8, 0)
    putOctal(h, 116, 8, 0)
    putOctal(h, 124, 12, size)
    putOctal(h, 136, 12, mtime)
    for (i in 148 until 156) h[i] = ' '.code.toByte()
    h[156] = type.code.toByte()
    System.arraycopy(link, 0, h, 157, minOf(link.size, 100))
    "ustar".toByteArray().copyInto(h, 257)
    h[263] = '0'.code.toByte()
    h[264] = '0'.code.toByte()
    var sum = 0L
    for (b in h) sum += (b.toInt() and 0xFF)
    val chk = String.format(Locale.US, "%06o", sum).toByteArray()
    chk.copyInto(h, 148)
    h[154] = 0
    h[155] = ' '.code.toByte()
    out.write(h)
  }

  private fun putOctal(h: ByteArray, off: Int, len: Int, v: Long) {
    val str = java.lang.Long.toString(v, 8).padStart(len - 1, '0')
    val b = str.toByteArray()
    b.copyInto(h, off, 0, minOf(b.size, len - 1))
    h[off + len - 1] = 0
  }

  /** Menghitung byte mentah yang dibaca dari arsip untuk progres. */
  private class CountingInputStream(input: InputStream, private val counter: AtomicLong) :
      FilterInputStream(input) {
    override fun read(): Int {
      val r = super.read()
      if (r >= 0) counter.incrementAndGet()
      return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      val r = super.read(b, off, len)
      if (r > 0) counter.addAndGet(r.toLong())
      return r
    }
  }
}
