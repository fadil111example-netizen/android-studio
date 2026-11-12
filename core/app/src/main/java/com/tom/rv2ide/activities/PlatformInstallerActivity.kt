package com.tom.rv2ide.activities

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.w3c.dom.Element

/**
 * Pemasang Android SDK Platform (android.jar) dan Sources: mengambil daftar paket dari repositori
 * resmi Google (repository2-3.xml), seperti CodeAssist, lalu mengunduh, memverifikasi SHA-1,
 * mengekstrak, dan memasangnya ke android-sdk/platforms/android-XX.
 */
class PlatformInstallerActivity : AppCompatActivity() {

  companion object {
    const val EXTRA_AUTO = "platform.auto.prefixes"
    private const val REPO_BASE = "https://dl.google.com/android/repository/"
    private const val REPO_XML = "repository2-3.xml"
  }

  private class Pkg(val path: String, val name: String, val url: String, val size: Long, val sha1: String?, val api: Double, val license: String?)

  private class Cancelled : RuntimeException("Dibatalkan")

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  @Volatile private var cancelled = false
  private var wakeLock: PowerManager.WakeLock? = null

  private var packages: List<Pkg>? = null
  private val licenses = HashMap<String, String>()
  private var platformPick: Pkg? = null
  private var sourcesPick: Pkg? = null

  private lateinit var platformInfo: TextView
  private lateinit var sourcesInfo: TextView
  private lateinit var stageView: TextView
  private lateinit var detailView: TextView
  private lateinit var progressBar: ProgressBar
  private lateinit var logView: TextView
  private lateinit var logScroll: ScrollView
  private lateinit var btnCancel: MaterialButton
  private val buttons = mutableListOf<MaterialButton>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    buildUi()
    val auto = intent.getStringExtra(EXTRA_AUTO).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (auto.isNotEmpty()) autoInstall(auto)
  }

  override fun onDestroy() {
    super.onDestroy()
    scope.cancel()
  }

  // ------------------------------------------------------------------------------------- UI

  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

  private fun button(label: String, outlined: Boolean, onClick: () -> Unit): MaterialButton {
    val b =
        if (outlined) MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        else MaterialButton(this)
    b.text = label
    b.isAllCaps = false
    b.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    b.setOnClickListener { onClick() }
    buttons.add(b)
    return b
  }

  private fun card(title: String, vararg views: View): MaterialCardView {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
    col.addView(TextView(this).apply { text = title; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
    views.forEach { col.addView(it) }
    return MaterialCardView(this).apply {
      radius = dp(22).toFloat()
      cardElevation = 0f
      strokeWidth = dp(1)
      strokeColor = Color.parseColor("#33007A52")
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
      addView(col)
    }
  }

  private fun info(t: String) = TextView(this).apply { text = t; textSize = 13f; setPadding(0, dp(8), 0, 0) }

  private fun buildUi() {
    val logo = ImageView(this).apply {
      setImageResource(com.tom.rv2ide.resources.R.drawable.ic_app_logo)
      layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
    }
    val texts = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(14), 0, 0, 0)
      addView(TextView(this@PlatformInstallerActivity).apply { text = "Platform SDK"; textSize = 24f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) })
      addView(TextView(this@PlatformInstallerActivity).apply { text = "Android 35 · 36 · 37 dan seterusnya, dari repositori Google"; textSize = 12f; setTextColor(Color.parseColor("#CCFFFFFF")) })
    }
    val header = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(18), dp(18), dp(18), dp(18))
      background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#0B2B3A"), Color.parseColor("#0F7A52"))).apply { cornerRadius = dp(26).toFloat() }
      addView(logo)
      addView(texts)
    }

    platformInfo = info("Belum ada platform dipilih.")
    sourcesInfo = info("Belum ada sources dipilih.")
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(8)) }
    content.addView(header)
    content.addView(
        card(
            "Platform (android.jar)",
            info("Dipakai proyek sesuai compileSdk-nya. Dipasang ke android-sdk/platforms/android-XX."),
            platformInfo,
            button("Pilih platform", true) { withPackages { showPicker("platforms", platformPick) { platformPick = it; refresh() } } },
            button("Unduh & pasang platform", false) { install(platformPick) },
        )
    )
    content.addView(
        card(
            "Sources (kode sumber Android)",
            sourcesInfo,
            button("Pilih sources", true) { withPackages { showPicker("sources", sourcesPick) { sourcesPick = it; refresh() } } },
            button("Unduh & pasang sources", false) { install(sourcesPick) },
        )
    )

    stageView = TextView(this).apply { text = "Siap."; textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
    detailView = TextView(this).apply { textSize = 12f; alpha = 0.8f }
    progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
      max = 100
      progressTintList = ColorStateList.valueOf(Color.parseColor("#1FB86B"))
      indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#1FB86B"))
    }
    logView = TextView(this).apply { typeface = android.graphics.Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
    logScroll = ScrollView(this).apply { addView(logView) }
    btnCancel = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
      text = "Batal"
      visibility = View.GONE
      setOnClickListener { cancelled = true; log("Membatalkan ...") }
    }
    val col = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(12), dp(16), dp(12))
      addView(stageView)
      addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
      addView(detailView)
      addView(btnCancel)
      addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)).apply { topMargin = dp(6) })
    }
    val panel = MaterialCardView(this).apply {
      radius = dp(20).toFloat()
      cardElevation = dp(10).toFloat()
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(8), dp(4), dp(8), dp(8)) }
      addView(col)
    }
    val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    page.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    page.addView(panel)
    setContentView(page)
  }

  private fun log(msg: String) = runOnUiThread { logView.append(msg + "\n"); logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) } }

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

  private fun setBusy(b: Boolean) =
      runOnUiThread {
        buttons.forEach { it.isEnabled = !b }
        btnCancel.visibility = if (b) View.VISIBLE else View.GONE
        if (!b) progressBar.isIndeterminate = false
      }

  private fun refresh() = runOnUiThread {
    platformInfo.text = platformPick?.let { "Dipilih: ${it.name} · ${fmt(it.size)}" + if (isInstalled(it)) "  (sudah terpasang ✓)" else "" } ?: "Belum ada platform dipilih."
    sourcesInfo.text = sourcesPick?.let { "Dipilih: ${it.name} · ${fmt(it.size)}" + if (isInstalled(it)) "  (sudah terpasang ✓)" else "" } ?: "Belum ada sources dipilih."
  }

  private fun fmt(b: Long): String =
      when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        else -> "${b / 1024} KB"
      }

  private fun fmtTime(s: Long) = if (s >= 60) "${s / 60} mnt ${s % 60} dtk" else "$s dtk"

  private fun acquire() {
    try {
      val pm = getSystemService(POWER_SERVICE) as PowerManager
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "acs:platform").apply { setReferenceCounted(false); acquire(3L * 60 * 60 * 1000) }
    } catch (e: Throwable) {
      // abaikan
    }
  }

  private fun release() {
    try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Throwable) { /* abaikan */ }
    wakeLock = null
  }

  private fun dest(p: Pkg) = File(Environment.ANDROID_HOME, p.path.replace(';', '/'))

  private fun isInstalled(p: Pkg): Boolean {
    val d = dest(p)
    return if (p.path.startsWith("platforms")) File(d, "android.jar").isFile else d.isDirectory && (d.listFiles()?.isNotEmpty() == true)
  }

  // ------------------------------------------------------------------------- daftar paket Google

  /** Memuat repository2-3.xml (sekali), lalu menjalankan [then] di UI thread. */
  private fun withPackages(then: () -> Unit) {
    if (packages != null) {
      then()
      return
    }
    setBusy(true)
    progress(null)
    stage("Mengambil daftar paket dari Google ...")
    scope.launch {
      try {
        loadManifest()
        stage("${packages?.size ?: 0} paket ditemukan ✓")
        runOnUiThread { then() }
      } catch (t: Throwable) {
        stage("Gagal ✗")
        log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
      } finally {
        setBusy(false)
      }
    }
  }

  private fun loadManifest() {
    val c = URL(REPO_BASE + REPO_XML).openConnection() as HttpURLConnection
    c.connectTimeout = 20000
    c.readTimeout = 60000
    c.setRequestProperty("User-Agent", "AndroidStudioFork")
    if (c.responseCode != 200) throw IOException("Google menjawab ${c.responseCode}")
    val dbf = DocumentBuilderFactory.newInstance()
    val doc = c.inputStream.use { dbf.newDocumentBuilder().parse(it) }

    val lic = doc.getElementsByTagName("license")
    for (i in 0 until lic.length) {
      val e = lic.item(i) as Element
      licenses[e.getAttribute("id")] = e.textContent.trim()
    }

    val out = ArrayList<Pkg>()
    val rx = Regex("^(platforms|sources);android-(\\d+(?:\\.\\d+)?)$")
    val nodes = doc.getElementsByTagName("remotePackage")
    for (i in 0 until nodes.length) {
      val e = nodes.item(i) as Element
      val path = e.getAttribute("path")
      val m = rx.find(path) ?: continue
      val name = e.getElementsByTagName("display-name").item(0)?.textContent?.trim() ?: path
      val licRef = (e.getElementsByTagName("uses-license").item(0) as? Element)?.getAttribute("ref")
      val archives = e.getElementsByTagName("archive")
      var chosen: Element? = null
      for (k in 0 until archives.length) {
        val a = archives.item(k) as Element
        if (a.getElementsByTagName("host-os").length == 0) { chosen = a; break }
        if (chosen == null) chosen = a
      }
      val complete = chosen?.getElementsByTagName("complete")?.item(0) as? Element ?: continue
      val url = complete.getElementsByTagName("url").item(0)?.textContent?.trim() ?: continue
      val size = complete.getElementsByTagName("size").item(0)?.textContent?.trim()?.toLongOrNull() ?: 0L
      val sha = (complete.getElementsByTagName("checksum").item(0) as? Element)?.textContent?.trim()
      out.add(Pkg(path, name, if (url.startsWith("http")) url else REPO_BASE + url, size, sha, m.groupValues[2].toDouble(), licRef))
    }
    packages = out.sortedByDescending { it.api }
  }

  private fun showPicker(kind: String, current: Pkg?, onPick: (Pkg) -> Unit) {
    val list = (packages ?: emptyList()).filter { it.path.startsWith(kind) }
    if (list.isEmpty()) {
      log("Tidak ada paket $kind di daftar Google.")
      return
    }
    val labels = list.map { (if (isInstalled(it)) "✓ " else "   ") + it.name + " · " + fmt(it.size) }
    MaterialAlertDialogBuilder(this)
        .setTitle("Pilih ${if (kind == "platforms") "platform" else "sources"}")
        .setItems(labels.toTypedArray()) { _, i -> onPick(list[i]) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  // ------------------------------------------------------------------------------ pemasangan

  private fun install(pkg: Pkg?) {
    if (pkg == null) {
      log("Pilih paket dulu.")
      return
    }
    ensureLicense(pkg) { runInstall(listOf(pkg)) }
  }

  /** Lisensi Android SDK wajib disetujui sebelum mengunduh (sama seperti sdkmanager). */
  private fun ensureLicense(pkg: Pkg, then: () -> Unit) {
    val prefs = getSharedPreferences("sdk_prefs", MODE_PRIVATE)
    val ref = pkg.license ?: "android-sdk-license"
    if (prefs.getBoolean("license_$ref", false)) {
      then()
      return
    }
    val text = (licenses[ref] ?: "Lisensi Android SDK. Baca di https://developer.android.com/studio/terms").take(6000)
    MaterialAlertDialogBuilder(this)
        .setTitle("Setujui lisensi Android SDK")
        .setMessage(text)
        .setPositiveButton("Saya setuju") { _, _ -> prefs.edit().putBoolean("license_$ref", true).apply(); then() }
        .setNegativeButton("Batal", null)
        .show()
  }

  private fun runInstall(list: List<Pkg>) {
    cancelled = false
    setBusy(true)
    acquire()
    scope.launch {
      try {
        for (p in list) installOne(p)
        stage("Selesai ✓")
        progress(100)
        refresh()
      } catch (c: Cancelled) {
        stage("Dibatalkan")
        log("Dibatalkan.")
      } catch (t: Throwable) {
        stage("Gagal ✗")
        log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
      } finally {
        release()
        setBusy(false)
      }
    }
  }

  private fun check() {
    if (cancelled) throw Cancelled()
  }

  private fun installOne(p: Pkg) {
    log("== ${p.name} ==")
    val work = File(Environment.HOME, ".acs-tools").apply { mkdirs() }
    val dir = File(work, "downloads").apply { mkdirs() }
    val file = File(p.url.substringAfterLast('/').let { dir.absolutePath + "/" + it })
    download(p, file)
    check()
    verify(p, file)
    val stageDir = File(work, "stage-${System.currentTimeMillis()}").apply { mkdirs() }
    try {
      stage("Mengekstrak ${p.name} ...")
      progress(0)
      extractZip(file, stageDir)
      check()
      stage("Memasang ke lokasi SDK ...")
      progress(null)
      val root = stageDir.listFiles()?.filter { it.isDirectory }?.firstOrNull() ?: stageDir
      val target = dest(p)
      target.deleteRecursively()
      target.parentFile?.mkdirs()
      if (!root.renameTo(target)) {
        root.copyRecursively(target, overwrite = true)
      }
      if (p.path.startsWith("platforms") && !File(target, "android.jar").isFile) throw IOException("android.jar tidak ditemukan setelah ekstrak")
      log("Terpasang di ${target.absolutePath}")
      file.delete()
    } finally {
      stageDir.deleteRecursively()
    }
  }

  private fun download(p: Pkg, done: File) {
    if (done.isFile && (p.size <= 0 || done.length() == p.size)) {
      log("Berkas sudah ada, melewati unduhan.")
      return
    }
    val part = File(done.parentFile, done.name + ".part")
    var attempt = 0
    while (true) {
      try {
        attempt++
        downloadOnce(p, part, done)
        return
      } catch (e: IOException) {
        check()
        if (attempt >= 4) throw e
        log("Koneksi terputus (${e.message}). Melanjutkan ($attempt/3) ...")
        Thread.sleep(2500)
      }
    }
  }

  private fun downloadOnce(p: Pkg, part: File, done: File) {
    val have = if (part.exists()) part.length() else 0L
    val c = URL(p.url).openConnection() as HttpURLConnection
    c.instanceFollowRedirects = true
    c.setRequestProperty("User-Agent", "AndroidStudioFork")
    if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
    c.connectTimeout = 20000
    c.readTimeout = 30000
    val code = c.responseCode
    if (code == 416 && have > 0) {
      part.renameTo(done)
      return
    }
    if (code != 200 && code != 206) throw IOException("Server menjawab $code")
    val append = code == 206
    val start = if (append) have else 0L
    val total = if (c.contentLengthLong > 0) start + c.contentLengthLong else p.size
    if (total > 0 && Environment.HOME.usableSpace < total * 3) throw IOException("Ruang kosong kurang (butuh ±${fmt(total * 3)})")
    stage("Mengunduh ${p.name}")
    var got = start
    val t0 = System.currentTimeMillis()
    var last = 0L
    c.inputStream.use { ins ->
      FileOutputStream(part, append).use { out ->
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
    if (p.size > 0 && part.length() != p.size) throw IOException("Ukuran unduhan tidak cocok (${part.length()} dari ${p.size})")
    done.delete()
    if (!part.renameTo(done)) throw IOException("Gagal menyimpan berkas unduhan")
  }

  private fun verify(p: Pkg, f: File) {
    val sha = p.sha1 ?: return
    stage("Memverifikasi SHA-1 ...")
    progress(null)
    val md = MessageDigest.getInstance("SHA-1")
    f.inputStream().use { ins ->
      val buf = ByteArray(256 * 1024)
      while (true) {
        check()
        val r = ins.read(buf)
        if (r < 0) break
        md.update(buf, 0, r)
      }
    }
    val hex = md.digest().joinToString("") { "%02x".format(it) }
    if (!hex.equals(sha, true)) {
      f.delete()
      throw IOException("SHA-1 tidak cocok (berkas rusak). Coba unduh lagi.")
    }
    log("Verifikasi SHA-1 OK ✓")
  }

  private fun extractZip(zip: File, dest: File) {
    val total = zip.length().coerceAtLeast(1)
    var files = 0L
    var last = 0L
    val counting = object : java.io.FilterInputStream(zip.inputStream().buffered(256 * 1024)) {
      var n = 0L
      override fun read(): Int = super.read().also { if (it >= 0) n++ }
      override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) n += it }
    }
    ZipInputStream(counting).use { zin ->
      while (true) {
        check()
        val e = zin.nextEntry ?: break
        val f = File(dest, e.name).canonicalFile
        if (!f.path.startsWith(dest.canonicalPath)) throw IOException("Path tidak aman: ${e.name}")
        if (e.isDirectory) {
          f.mkdirs()
        } else {
          f.parentFile?.mkdirs()
          f.outputStream().use { zin.copyTo(it, 256 * 1024) }
          files++
        }
        val now = System.currentTimeMillis()
        if (now - last > 300) {
          last = now
          progress((counting.n * 100 / total).toInt())
          detail("Ekstrak ${counting.n * 100 / total}% · $files berkas")
        }
      }
    }
    progress(100)
    log("Ekstrak selesai: $files berkas.")
  }

  // ----------------------------------------------------------------------------- mode otomatis

  /** Dipanggil dari setup awal: pasang versi tertinggi yang cocok dengan tiap awalan (mis. platforms;android-36). */
  private fun autoInstall(prefixes: List<String>) {
    setBusy(true)
    progress(null)
    stage("Menyiapkan pemasangan otomatis ...")
    scope.launch {
      try {
        if (packages == null) loadManifest()
        val picks = prefixes.mapNotNull { pre -> packages?.filter { it.path.startsWith(pre) && !isInstalled(it) }?.maxByOrNull { it.api } }
        if (picks.isEmpty()) {
          stage("Sudah terpasang ✓")
          log("Tidak ada yang perlu dipasang.")
          return@launch
        }
        runOnUiThread { picks.firstOrNull()?.let { ensureLicense(it) { runInstall(picks) } } }
      } catch (t: Throwable) {
        stage("Gagal ✗")
        log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
      } finally {
        setBusy(false)
      }
    }
  }
}
