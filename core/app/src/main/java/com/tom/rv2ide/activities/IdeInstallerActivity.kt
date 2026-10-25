package com.tom.rv2ide.activities

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.PowerManager
import android.system.Os
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.fragments.onboarding.JdkVersion
import com.tom.rv2ide.fragments.onboarding.SdkVersion
import com.tom.rv2ide.models.IdeSetupArgument
import com.tom.rv2ide.utils.Environment
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.tukaani.xz.XZInputStream

/**
 * Pemasang komponen: SDK/JDK/Git (lewat Terminal seperti setup awal) dan NDK/CMake (unduh langsung
 * dengan progres persen, lanjut otomatis bila putus, ekstrak berprogres, lalu dipasang ke lokasi yang
 * dibutuhkan aplikasi: android-sdk/ndk/<revisi> dan android-sdk/cmake/<versi>).
 */
class IdeInstallerActivity : AppCompatActivity() {

  private class Pkg(val label: String, val url: String, val fileName: String, val size: Long)

  private class Cancelled : RuntimeException("Dibatalkan")

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  @Volatile private var cancelled = false
  private var wakeLock: PowerManager.WakeLock? = null

  private var sdkChoice = SdkVersion.entries.last().displayName
  private var jdkChoice = JdkVersion.entries.first().displayName
  private var releases: List<Pkg>? = null
  private var ndkPick: Pkg? = null
  private var cmakePick: Pkg? = null

  private lateinit var sdkInfo: TextView
  private lateinit var ndkInfo: TextView
  private lateinit var cmakeInfo: TextView
  private lateinit var chkGit: CheckBox
  private lateinit var chkSsh: CheckBox
  private lateinit var stageView: TextView
  private lateinit var detailView: TextView
  private lateinit var progressBar: ProgressBar
  private lateinit var logView: TextView
  private lateinit var logScroll: ScrollView
  private lateinit var btnCancel: MaterialButton
  private val actionButtons = mutableListOf<MaterialButton>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    buildUi()
  }

  override fun onDestroy() {
    super.onDestroy()
    scope.cancel()
  }

  // ------------------------------------------------------------------------------------- UI

  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

  private fun actionButton(label: String, outlined: Boolean, onClick: () -> Unit): MaterialButton {
    val b =
        if (outlined) MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        else MaterialButton(this)
    b.text = label
    b.layoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
    b.setOnClickListener { onClick() }
    actionButtons.add(b)
    return b
  }

  private fun card(title: String, vararg views: View): MaterialCardView {
    val col =
        LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(dp(16), dp(14), dp(16), dp(14))
        }
    col.addView(
        TextView(this).apply {
          text = title
          textSize = 16f
          setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    )
    views.forEach { col.addView(it) }
    return MaterialCardView(this).apply {
      radius = dp(20).toFloat()
      cardElevation = 0f
      strokeWidth = dp(1)
      strokeColor = Color.parseColor("#33007A52")
      layoutParams =
          LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
              .apply { topMargin = dp(12) }
      addView(col)
    }
  }

  private fun info(text: String) = TextView(this).apply { this.text = text; textSize = 13f; setPadding(0, dp(8), 0, 0) }

  private fun buildHeader(): View {
    val logo =
        ImageView(this).apply {
          setImageResource(com.tom.rv2ide.resources.R.drawable.ic_app_logo)
          layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        }
    val texts =
        LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(dp(14), 0, 0, 0)
          addView(
              TextView(this@IdeInstallerActivity).apply {
                text = "Instal Komponen"
                textSize = 24f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
              }
          )
          addView(
              TextView(this@IdeInstallerActivity).apply {
                text = "SDK · JDK · NDK · CMake"
                textSize = 13f
                setTextColor(Color.parseColor("#CCFFFFFF"))
              }
          )
        }
    return LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(18), dp(18), dp(18), dp(18))
      background =
          GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#0B2B3A"), Color.parseColor("#0F7A52")))
              .apply { cornerRadius = dp(24).toFloat() }
      addView(logo)
      addView(texts)
    }
  }

  private fun buildUi() {
    val content =
        LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(dp(16), dp(20), dp(16), dp(8))
        }
    content.addView(buildHeader())

    sdkInfo = info("")
    chkGit = CheckBox(this).apply { text = "Instal Git"; isChecked = true }
    chkSsh = CheckBox(this).apply { text = "Instal OpenSSH"; isChecked = true }
    refreshSdkInfo()
    content.addView(
        card(
            "SDK, JDK & alat (lewat Terminal)",
            sdkInfo,
            actionButton("Pilih versi SDK", true) { pickSdk() },
            actionButton("Pilih versi JDK", true) { pickJdk() },
            chkGit,
            chkSsh,
            actionButton("Instal SDK / JDK", false) { runIdeSetup() },
        )
    )

    ndkInfo = info("Belum ada versi dipilih.")
    content.addView(
        card(
            "NDK",
            ndkInfo,
            actionButton("Pilih versi NDK", true) { loadReleases { showPicker("NDK", ndkList()) { ndkPick = it; refreshNdk() } } },
            actionButton("Unduh & pasang NDK", false) { installPick(ndkPick, "NDK") },
        )
    )

    cmakeInfo = info("Belum ada versi dipilih.")
    content.addView(
        card(
            "CMake",
            cmakeInfo,
            actionButton("Pilih versi CMake", true) { loadReleases { showPicker("CMake", cmakeList()) { cmakePick = it; refreshCmake() } } },
            actionButton("Unduh & pasang CMake", false) { installPick(cmakePick, "CMake") },
        )
    )

    val sv = ScrollView(this).apply { addView(content) }
    val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    page.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    page.addView(buildPanel())
    setContentView(page)
  }

  private fun buildPanel(): View {
    stageView = TextView(this).apply { text = "Siap."; textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
    detailView = TextView(this).apply { textSize = 12f; alpha = 0.8f }
    progressBar =
        ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
          max = 100
          progressTintList = ColorStateList.valueOf(Color.parseColor("#1FB86B"))
          indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#1FB86B"))
        }
    logView = TextView(this).apply { typeface = android.graphics.Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
    logScroll = ScrollView(this).apply { addView(logView) }
    btnCancel =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
          text = "Batal"
          visibility = View.GONE
          setOnClickListener { cancelled = true; log("Membatalkan ...") }
        }
    val col =
        LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(dp(16), dp(12), dp(16), dp(12))
          addView(stageView)
          addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
          addView(detailView)
          addView(btnCancel)
          addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)).apply { topMargin = dp(6) })
        }
    return MaterialCardView(this).apply {
      radius = dp(20).toFloat()
      cardElevation = dp(10).toFloat()
      layoutParams =
          LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
              .apply { setMargins(dp(8), dp(4), dp(8), dp(8)) }
      addView(col)
    }
  }

  private fun log(msg: String) {
    runOnUiThread {
      logView.append(msg + "\n")
      logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }
  }

  private fun stage(msg: String) = runOnUiThread { stageView.text = msg }

  private fun detail(msg: String) = runOnUiThread { detailView.text = msg }

  private fun progress(p: Int?) =
      runOnUiThread {
        if (p == null) {
          progressBar.isIndeterminate = true
        } else {
          progressBar.isIndeterminate = false
          progressBar.progress = p.coerceIn(0, 100)
        }
      }

  private fun setBusy(busy: Boolean) {
    runOnUiThread {
      actionButtons.forEach { it.isEnabled = !busy }
      btnCancel.visibility = if (busy) View.VISIBLE else View.GONE
      if (!busy) progressBar.isIndeterminate = false
    }
  }

  private fun refreshSdkInfo() {
    sdkInfo.text = "Dipilih: $sdkChoice · $jdkChoice"
  }

  private fun refreshNdk() = runOnUiThread { ndkInfo.text = ndkPick?.let { "Dipilih: ${it.label}" } ?: "Belum ada versi dipilih." }

  private fun refreshCmake() = runOnUiThread { cmakeInfo.text = cmakePick?.let { "Dipilih: ${it.label}" } ?: "Belum ada versi dipilih." }

  private fun acquireWakeLock() {
    try {
      val pm = getSystemService(POWER_SERVICE) as PowerManager
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "acs:installer").apply {
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

  private fun fmtTime(s: Long): String = if (s >= 60) "${s / 60} mnt ${s % 60} dtk" else "$s dtk"

  // ------------------------------------------------------------------------ SDK / JDK (Terminal)

  private fun pickSdk() {
    val items = SdkVersion.entries.map { it.displayName }.reversed()
    MaterialAlertDialogBuilder(this)
        .setTitle("Versi SDK")
        .setItems(items.toTypedArray()) { _, i -> sdkChoice = items[i]; refreshSdkInfo() }
        .show()
  }

  private fun pickJdk() {
    val items = JdkVersion.entries.map { it.displayName }
    MaterialAlertDialogBuilder(this)
        .setTitle("Versi JDK")
        .setItems(items.toTypedArray()) { _, i -> jdkChoice = items[i]; refreshSdkInfo() }
        .show()
  }

  private fun runIdeSetup() {
    val args = mutableListOf<String>()
    fun add(a: IdeSetupArgument, value: String = "") {
      args.add(a.argumentName)
      args.add(value)
    }
    add(IdeSetupArgument.INSTALL_DIR, Environment.HOME.absolutePath)
    add(IdeSetupArgument.SDK_VERSION, SdkVersion.fromDisplayName(sdkChoice).version)
    add(IdeSetupArgument.JDK_VERSION, JdkVersion.fromDisplayName(jdkChoice).version)
    add(IdeSetupArgument.NDK_VERSION, "0")
    add(IdeSetupArgument.ASSUME_YES)
    if (chkGit.isChecked) add(IdeSetupArgument.WITH_GIT)
    if (chkSsh.isChecked) add(IdeSetupArgument.WITH_OPENSSH)
    startActivity(
        Intent(this, TerminalActivity::class.java)
            .putExtra(TerminalActivity.EXTRA_ONBOARDING_RUN_IDESETUP, true)
            .putExtra(TerminalActivity.EXTRA_ONBOARDING_RUN_IDESETUP_ARGS, args.toTypedArray())
    )
  }

  // ------------------------------------------------------------------------------ daftar rilis

  private val ndkFallback =
      Pkg(
          "NDK r29 (29.0.14033849)",
          "https://github.com/AndroidCSOfficial/acs-build-system/releases/download/v29.0.14033849/android-ndk-r29-aarch64-linux-android.tar.xz",
          "android-ndk-r29-aarch64-linux-android.tar.xz",
          0L,
      )

  private fun isArchive(n: String) = n.endsWith(".tar.xz") || n.endsWith(".tar.gz") || n.endsWith(".tgz") || n.endsWith(".zip")

  private fun ndkList(): List<Pkg> {
    val l = (releases ?: emptyList()).filter { it.fileName.contains("ndk", true) && it.fileName.contains("aarch64", true) && isArchive(it.fileName) }
    return if (l.none { it.fileName == ndkFallback.fileName }) l + ndkFallback else l
  }

  private fun cmakeList(): List<Pkg> =
      (releases ?: emptyList()).filter { it.fileName.contains("cmake", true) && it.fileName.contains("aarch64", true) && isArchive(it.fileName) }

  /** Mengambil daftar rilis dari repo build-system lalu menjalankan [then] di UI thread. */
  private fun loadReleases(then: () -> Unit) {
    if (releases != null) {
      then()
      return
    }
    setBusy(true)
    progress(null)
    stage("Mengambil daftar versi ...")
    scope.launch {
      try {
        val c = URL("https://api.github.com/repos/AndroidCSOfficial/acs-build-system/releases?per_page=100").openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "AndroidStudioFork")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        getSharedPreferences("github_prefs", MODE_PRIVATE).getString("token", "")?.takeIf { it.isNotEmpty() }?.let {
          c.setRequestProperty("Authorization", "Bearer $it")
        }
        c.connectTimeout = 15000
        c.readTimeout = 30000
        val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        val out = mutableListOf<Pkg>()
        for (i in 0 until arr.length()) {
          val assets = arr.getJSONObject(i).optJSONArray("assets") ?: continue
          for (j in 0 until assets.length()) {
            val a = assets.getJSONObject(j)
            val n = a.getString("name")
            out.add(Pkg(n.removeSuffix(".tar.xz").removeSuffix(".tar.gz").removeSuffix(".zip") + " · " + fmt(a.optLong("size")), a.getString("browser_download_url"), n, a.optLong("size")))
          }
        }
        releases = out
        stage("Daftar versi siap ✓")
        runOnUiThread { then() }
      } catch (t: Throwable) {
        // Offline / dibatasi GitHub: tetap beri pilihan NDK r29 bawaan.
        releases = emptyList()
        log("Tidak bisa mengambil daftar rilis (${t.javaClass.simpleName}: ${t.message}). Memakai daftar bawaan.")
        stage("Daftar bawaan dipakai")
        runOnUiThread { then() }
      } finally {
        setBusy(false)
      }
    }
  }

  private fun showPicker(kind: String, list: List<Pkg>, onPick: (Pkg) -> Unit) {
    if (list.isEmpty()) {
      log("Tidak ada paket $kind (aarch64) di daftar rilis.")
      return
    }
    MaterialAlertDialogBuilder(this)
        .setTitle("Pilih versi $kind")
        .setItems(list.map { it.label }.toTypedArray()) { _, i -> onPick(list[i]) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  // ------------------------------------------------------------------------------ pemasangan

  private fun installPick(pkg: Pkg?, kind: String) {
    if (pkg == null) {
      log("Pilih versi $kind dulu.")
      return
    }
    cancelled = false
    setBusy(true)
    acquireWakeLock()
    scope.launch {
      try {
        installPackage(pkg, kind)
      } catch (c: Cancelled) {
        stage("Dibatalkan")
        log("Dibatalkan oleh pengguna.")
      } catch (t: Throwable) {
        stage("Gagal ✗")
        log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
      } finally {
        releaseWakeLock()
        setBusy(false)
      }
    }
  }

  private fun check() {
    if (cancelled) throw Cancelled()
  }

  private fun installPackage(pkg: Pkg, kind: String) {
    val work = File(Environment.HOME, ".acs-tools").apply { mkdirs() }
    val archive = download(pkg, File(work, "downloads").apply { mkdirs() })
    check()

    val stageDir = File(work, "stage-${System.currentTimeMillis()}").apply { mkdirs() }
    try {
      stage("Mengekstrak ${pkg.fileName} ...")
      extract(archive, stageDir)
      check()

      stage("Memasang $kind ke lokasi yang dibutuhkan ...")
      progress(null)
      val dest = if (kind == "NDK") integrateNdk(stageDir) else integrateCmake(stageDir, pkg)
      stage("$kind terpasang ✓")
      progress(100)
      log("$kind siap di: ${dest.absolutePath}")
      archive.delete()
    } finally {
      stageDir.deleteRecursively()
    }
  }

  // ---- unduh (lanjut otomatis bila putus)

  private fun download(pkg: Pkg, dir: File): File {
    val done = File(dir, pkg.fileName)
    if (done.isFile && (pkg.size <= 0 || done.length() == pkg.size)) {
      log("Berkas sudah ada, melewati unduhan.")
      return done
    }
    var attempt = 0
    while (true) {
      try {
        attempt++
        return downloadOnce(pkg, File(dir, pkg.fileName + ".part"), done)
      } catch (e: IOException) {
        check()
        if (attempt >= 4) throw e
        log("Koneksi terputus (${e.message}). Melanjutkan unduhan ($attempt/3) ...")
        Thread.sleep(2500)
      }
    }
  }

  private fun downloadOnce(pkg: Pkg, part: File, done: File): File {
    val have = if (part.exists()) part.length() else 0L
    val c = URL(pkg.url).openConnection() as HttpURLConnection
    c.instanceFollowRedirects = true
    c.setRequestProperty("User-Agent", "AndroidStudioFork")
    if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
    c.connectTimeout = 20000
    c.readTimeout = 30000
    val code = c.responseCode
    if (code == 416 && have > 0) {
      part.renameTo(done)
      return done
    }
    if (code != 200 && code != 206) throw IOException("Server menjawab $code untuk ${pkg.url}")
    val append = code == 206
    val start = if (append) have else 0L
    val total = if (c.contentLengthLong > 0) start + c.contentLengthLong else pkg.size

    if (total > 0 && Environment.HOME.usableSpace < total * 3) {
      throw IOException("Ruang kosong kurang (butuh ±${fmt(total * 3)}, tersedia ${fmt(Environment.HOME.usableSpace)})")
    }

    stage("Mengunduh ${pkg.fileName}")
    var got = start
    val t0 = System.currentTimeMillis()
    var last = 0L
    c.inputStream.use { ins ->
      java.io.FileOutputStream(part, append).use { out ->
        val buf = ByteArray(128 * 1024)
        while (true) {
          check()
          val r = ins.read(buf)
          if (r < 0) break
          out.write(buf, 0, r)
          got += r
          val now = System.currentTimeMillis()
          if (now - last > 300) {
            last = now
            val speed = (got - start) * 1000.0 / (now - t0).coerceAtLeast(1)
            if (total > 0) {
              progress((got * 100 / total).toInt())
              val eta = if (speed > 1) ((total - got) / speed).toLong() else -1L
              detail("${got * 100 / total}% · ${fmt(got)} / ${fmt(total)} · ${fmt(speed.toLong())}/dtk" + (if (eta >= 0) " · sisa ${fmtTime(eta)}" else ""))
            } else {
              progress(null)
              detail("${fmt(got)} · ${fmt(speed.toLong())}/dtk")
            }
          }
        }
      }
    }
    if (total > 0 && part.length() != total) throw IOException("Ukuran unduhan tidak cocok (${part.length()} dari $total)")
    done.delete()
    if (!part.renameTo(done)) throw IOException("Gagal menyimpan berkas unduhan")
    log("Unduhan selesai: ${fmt(done.length())}")
    return done
  }

  // ---- ekstrak (progres dari byte terbaca / ukuran berkas)

  private class CountingInputStream(input: InputStream, private val counter: AtomicLong) : FilterInputStream(input) {
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

  private fun extract(archive: File, dest: File) {
    val counter = AtomicLong(0)
    val total = archive.length()
    val t0 = System.currentTimeMillis()
    var last = 0L
    var files = 0L
    val tick = {
      val now = System.currentTimeMillis()
      if (now - last > 300) {
        last = now
        val done = counter.get()
        progress((done * 100 / total).toInt())
        detail("Ekstrak ${done * 100 / total}% · $files berkas · ${fmtTime((now - t0) / 1000)}")
      }
    }
    val raw = BufferedInputStream(CountingInputStream(archive.inputStream(), counter), 256 * 1024)
    val name = archive.name
    raw.use { input ->
      when {
        name.endsWith(".zip") -> extractZip(input, dest) { files++; tick() }
        name.endsWith(".tar.xz") -> extractTar(XZInputStream(input), dest) { files++; tick() }
        name.endsWith(".tar.gz") || name.endsWith(".tgz") -> extractTar(GZIPInputStream(input, 256 * 1024), dest) { files++; tick() }
        else -> extractTar(input, dest) { files++; tick() }
      }
    }
    progress(100)
    log("Ekstrak selesai: $files berkas.")
  }

  private fun safeFile(dest: File, name: String): File {
    val out = File(dest, name).canonicalFile
    if (!out.path.startsWith(dest.canonicalPath + File.separator) && out.path != dest.canonicalPath) throw IOException("Path tidak aman: $name")
    return out
  }

  private fun extractZip(input: InputStream, dest: File, onFile: () -> Unit) {
    val zip = ZipInputStream(input)
    while (true) {
      check()
      val e = zip.nextEntry ?: break
      val f = safeFile(dest, e.name)
      if (e.isDirectory) {
        f.mkdirs()
      } else {
        f.parentFile?.mkdirs()
        f.outputStream().use { zip.copyTo(it, 256 * 1024) }
        onFile()
      }
    }
  }

  private fun readFully(input: InputStream, buf: ByteArray, len: Int = buf.size): Boolean {
    var off = 0
    while (off < len) {
      val r = input.read(buf, off, len - off)
      if (r < 0) return false
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

  private fun extractTar(input: InputStream, dest: File, onFile: () -> Unit) {
    val header = ByteArray(512)
    var longName: String? = null
    while (true) {
      check()
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
      longName?.let { name = it; longName = null }
      if (name.isEmpty()) {
        skipFully(input, size + padding)
        continue
      }
      val f = safeFile(dest, name.trimEnd('/'))
      when (type) {
        '5' -> f.mkdirs()
        '2' -> {
          f.parentFile?.mkdirs()
          try { Os.symlink(link, f.path) } catch (e: Throwable) { /* abaikan */ }
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
          onFile()
          continue
        }
        else -> {}
      }
      skipFully(input, size + padding)
    }
  }

  // ---- integrasi ke lokasi yang dibutuhkan aplikasi

  private fun isLink(f: File) = try { Files.isSymbolicLink(f.toPath()) } catch (e: Throwable) { false }

  private fun findDir(root: File, depth: Int, test: (File) -> Boolean): File? {
    if (test(root)) return root
    if (depth <= 0) return null
    for (c in root.listFiles()?.filter { it.isDirectory && !isLink(it) } ?: emptyList()) findDir(c, depth - 1, test)?.let { return it }
    return null
  }

  private fun moveDir(src: File, dest: File) {
    dest.deleteRecursively()
    dest.parentFile?.mkdirs()
    if (!src.renameTo(dest)) {
      src.copyRecursively(dest, overwrite = true)
      src.deleteRecursively()
    }
  }

  private fun fixExecutables(root: File) {
    root.walkTopDown().onEnter { !isLink(it) }.forEach { f ->
      if (isLink(f) || !f.isFile || f.length() < 4) return@forEach
      try {
        f.inputStream().use {
          val h = ByteArray(4)
          if (it.read(h) == 4 && ((h[0] == 0x7F.toByte() && h[1] == 'E'.code.toByte()) || (h[0] == '#'.code.toByte() && h[1] == '!'.code.toByte()))) f.setExecutable(true, false)
        }
      } catch (e: Throwable) {
        // abaikan
      }
    }
  }

  private fun integrateNdk(stageDir: File): File {
    val root = findDir(stageDir, 3) { File(it, "source.properties").isFile && (File(it, "ndk-build").exists() || File(it, "toolchains").isDirectory) }
        ?: throw IOException("Isi paket bukan NDK yang valid (source.properties tidak ditemukan)")
    val rev = File(root, "source.properties").readLines().firstOrNull { it.startsWith("Pkg.Revision") }?.substringAfter('=')?.trim()
        ?: throw IOException("Revisi NDK tidak terbaca")
    val dest = File(Environment.ANDROID_HOME, "ndk/$rev")
    moveDir(root, dest)
    fixExecutables(dest)
    if (!File(dest, "ndk-build").exists()) log("Peringatan: ndk-build tidak ditemukan di $dest")
    log("NDK revisi $rev terpasang.")
    return dest
  }

  private fun integrateCmake(stageDir: File, pkg: Pkg): File {
    val root = findDir(stageDir, 3) { File(it, "bin/cmake").exists() }
        ?: throw IOException("Isi paket bukan CMake yang valid (bin/cmake tidak ditemukan)")
    val ver = Regex("cmake-(\\d+\\.\\d+(?:\\.\\d+)?)").find(pkg.fileName)?.groupValues?.get(1)
        ?: Regex("cmake-(\\d+\\.\\d+)").find(root.name)?.groupValues?.get(1)
        ?: root.name
    val dest = File(Environment.ANDROID_HOME, "cmake/$ver")
    moveDir(root, dest)
    fixExecutables(dest)
    val sp = File(dest, "source.properties")
    if (!sp.exists()) sp.writeText("Pkg.Desc=CMake $ver\nPkg.Revision=$ver\n")
    log("CMake $ver terpasang.")
    return dest
  }
}
