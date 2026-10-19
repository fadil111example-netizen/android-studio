package com.tom.rv2ide.activities

import android.app.ActivityManager
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.os.PowerManager
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.tom.rv2ide.app.IDEApplication
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * APK -> proyek Gradle berisi source Java + resource (memakai jadx yang dijalankan di JDK
 * aplikasi). Hasilnya HASIL DEKOMPILASI (pendekatan), bukan source asli: nama variabel bisa
 * hilang, Kotlin menjadi Java, dan sebagian method bisa gagal didekompilasi.
 */
class ApkToSourceActivity : AppCompatActivity() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @Volatile private var cancelled = false
  @Volatile private var process: Process? = null
  private var wakeLock: PowerManager.WakeLock? = null

  private var apkFile: File? = null
  private var apkLabel: String = ""

  private lateinit var selectedView: TextView
  private lateinit var statusView: TextView
  private lateinit var logView: TextView
  private lateinit var scroll: ScrollView
  private lateinit var progress: ProgressBar
  private lateinit var chkDeobf: CheckBox
  private lateinit var btnPick: MaterialButton
  private lateinit var btnInstalled: MaterialButton
  private lateinit var btnStart: MaterialButton
  private lateinit var btnCancel: MaterialButton

  private val pickApk =
      registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadPicked(uri)
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    buildUi()
  }

  override fun onDestroy() {
    super.onDestroy()
    process?.destroy()
    scope.cancel()
  }

  // ------------------------------------------------------------------------------------- UI

  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

  private fun button(label: String, onClick: () -> Unit) =
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

  private fun buildUi() {
    val root =
        LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(dp(20), dp(24), dp(20), dp(16))
        }

    root.addView(
        TextView(this).apply {
          text = "APK ke Source Code"
          textSize = 24f
          setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    )
    root.addView(
        TextView(this).apply {
          text =
              "Ubah APK (file atau aplikasi terpasang) menjadi proyek Gradle berisi source Java dan " +
                  "resource yang bisa dibuka & di-build ulang. Ini hasil dekompilasi (pendekatan), bukan " +
                  "source asli: Kotlin menjadi Java, komentar dan sebagian nama hilang, dan beberapa " +
                  "method bisa gagal didekompilasi. Gunakan hanya untuk aplikasi milikmu atau yang " +
                  "boleh kamu bongkar. Butuh JDK terpasang dan internet sekali (unduh jadx ±40 MB)."
          textSize = 14f
          setPadding(0, dp(8), 0, dp(12))
        }
    )

    btnPick = button("Pilih file APK") { pickApk.launch(arrayOf("*/*")) }
    btnInstalled = button("Pilih dari aplikasi terpasang") { showInstalledApps() }
    root.addView(btnPick)
    root.addView(btnInstalled)

    selectedView = TextView(this).apply {
      text = "Belum ada APK dipilih."
      setPadding(0, dp(12), 0, dp(4))
    }
    root.addView(selectedView)

    chkDeobf = CheckBox(this).apply { text = "Deobfuscation nama (untuk APK yang di-obfuscate)" }
    root.addView(chkDeobf)

    btnStart = button("Mulai konversi") { startConvert() }
    root.addView(btnStart)
    btnCancel =
        button("Batal") {
          cancelled = true
          process?.destroy()
          log("Membatalkan ...")
        }
    btnCancel.visibility = android.view.View.GONE
    root.addView(btnCancel)

    progress =
        ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
          isIndeterminate = true
          visibility = android.view.View.GONE
        }
    root.addView(
        progress,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) },
    )
    statusView = TextView(this).apply { textSize = 12f; visibility = android.view.View.GONE }
    root.addView(statusView)

    logView =
        TextView(this).apply {
          typeface = android.graphics.Typeface.MONOSPACE
          textSize = 12f
          setTextIsSelectable(true)
        }
    root.addView(logView)

    scroll = ScrollView(this).apply {
      isFillViewport = true
      addView(root)
    }
    setContentView(scroll)
  }

  private fun log(msg: String) {
    runOnUiThread {
      logView.append(msg + "\n")
      scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }
  }

  private fun status(msg: String) {
    runOnUiThread { statusView.text = msg }
  }

  private fun setBusy(busy: Boolean) {
    runOnUiThread {
      val v = if (busy) android.view.View.VISIBLE else android.view.View.GONE
      progress.visibility = v
      statusView.visibility = v
      btnCancel.visibility = v
      btnPick.isEnabled = !busy
      btnInstalled.isEnabled = !busy
      btnStart.isEnabled = !busy
    }
  }

  private fun acquireWakeLock() {
    try {
      val pm = getSystemService(POWER_SERVICE) as PowerManager
      wakeLock =
          pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "acs:apk2src").apply {
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

  // ---------------------------------------------------------------------------- pilih input

  private fun inputDir(): File = File(cacheDir, "apk2src").apply { mkdirs() }

  private fun displayName(uri: Uri): String? =
      try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
          if (it.moveToFirst()) it.getString(0) else null
        }
      } catch (e: Throwable) {
        null
      }

  private fun safeName(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "app" }

  private fun loadPicked(uri: Uri) {
    val name = displayName(uri) ?: "app.apk"
    setBusy(true)
    scope.launch {
      try {
        status("Menyalin $name ...")
        val dest = File(inputDir(), safeName(name))
        contentResolver.openInputStream(uri)?.use { ins ->
          dest.outputStream().use { out -> ins.copyTo(out, 256 * 1024) }
        } ?: throw IOException("Tidak bisa membuka file")
        apkFile = dest
        apkLabel = safeName(name.substringBeforeLast('.'))
        runOnUiThread { selectedView.text = "Dipilih: $name (${dest.length() / 1024 / 1024} MB)" }
      } catch (t: Throwable) {
        log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
      } finally {
        setBusy(false)
      }
    }
  }

  private fun showInstalledApps() {
    setBusy(true)
    scope.launch {
      try {
        val pm = packageManager
        val apps =
            pm.getInstalledApplications(0)
                .filter {
                  (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                      (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                }
                .map { AppItem(pm.getApplicationLabel(it).toString(), it.packageName, it.sourceDir, pm.getApplicationIcon(it)) }
                .sortedBy { it.label.lowercase() }
        runOnUiThread {
          setBusy(false)
          showAppPicker(apps)
        }
      } catch (t: Throwable) {
        setBusy(false)
        log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
      }
    }
  }

  private class AppItem(val label: String, val pkg: String, val src: String, val icon: Drawable)

  private fun showAppPicker(all: List<AppItem>) {
    var shown = all
    val adapter =
        object : BaseAdapter() {
          override fun getCount() = shown.size

          override fun getItem(position: Int) = shown[position]

          override fun getItemId(position: Int) = position.toLong()

          override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row =
                (convertView as? LinearLayout)
                    ?: LinearLayout(this@ApkToSourceActivity).apply {
                      orientation = LinearLayout.HORIZONTAL
                      gravity = Gravity.CENTER_VERTICAL
                      setPadding(dp(16), dp(8), dp(16), dp(8))
                      addView(
                          ImageView(context).apply {
                            id = 1
                            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                          }
                      )
                      addView(
                          LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(14), 0, 0, 0)
                            addView(
                                TextView(context).apply {
                                  id = 2
                                  textSize = 16f
                                  maxLines = 1
                                  ellipsize = TextUtils.TruncateAt.END
                                }
                            )
                            addView(
                                TextView(context).apply {
                                  id = 3
                                  textSize = 12f
                                  maxLines = 1
                                  alpha = 0.7f
                                  ellipsize = TextUtils.TruncateAt.END
                                }
                            )
                          }
                      )
                    }
            val item = shown[position]
            row.findViewById<ImageView>(1).setImageDrawable(item.icon)
            row.findViewById<TextView>(2).text = item.label
            row.findViewById<TextView>(3).text = item.pkg
            return row
          }
        }

    val list = ListView(this)
    list.adapter = adapter
    val search = EditText(this).apply { hint = "Cari aplikasi ..."; maxLines = 1 }
    val box =
        LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(dp(16), dp(8), dp(16), 0)
          addView(search)
          addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)))
        }
    val dialog =
        MaterialAlertDialogBuilder(this)
            .setTitle("Pilih aplikasi")
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

    search.addTextChangedListener(
        object : TextWatcher {
          override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

          override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

          override fun afterTextChanged(s: Editable?) {
            val q = s?.toString()?.trim().orEmpty()
            shown =
                if (q.isEmpty()) all
                else all.filter { it.label.contains(q, true) || it.pkg.contains(q, true) }
            adapter.notifyDataSetChanged()
          }
        }
    )
    list.setOnItemClickListener { _, _, pos, _ ->
      dialog.dismiss()
      selectInstalled(shown[pos])
    }
    dialog.show()
  }

  private fun selectInstalled(app: AppItem) {
    setBusy(true)
    scope.launch {
      try {
        status("Menyalin APK ${app.label} ...")
        val dest = File(inputDir(), safeName(app.pkg) + ".apk")
        File(app.src).inputStream().use { ins ->
          dest.outputStream().use { out -> ins.copyTo(out, 256 * 1024) }
        }
        apkFile = dest
        apkLabel = safeName(app.label)
        runOnUiThread {
          selectedView.text = "Dipilih: ${app.label} (${app.pkg}), ${dest.length() / 1024 / 1024} MB"
        }
      } catch (t: Throwable) {
        log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
      } finally {
        setBusy(false)
      }
    }
  }

  // ------------------------------------------------------------------------------ konversi

  private fun startConvert() {
    val apk = apkFile
    if (apk == null || !apk.isFile) {
      log("Pilih APK dulu.")
      return
    }
    cancelled = false
    val deobfChecked = chkDeobf.isChecked
    setBusy(true)
    acquireWakeLock()
    scope.launch {
      try {
        convert(apk, deobfChecked)
      } catch (t: Throwable) {
        log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
      } finally {
        process = null
        releaseWakeLock()
        setBusy(false)
      }
    }
  }

  private fun findJava(): File? =
      File(Environment.PREFIX, "lib/jvm").listFiles()?.map { File(it, "bin/java") }?.firstOrNull { it.isFile }

  private fun jadxLib() = File(Environment.HOME, ".acs-tools/jadx/lib")

  private fun hasJadx(): Boolean =
      jadxLib().listFiles()?.any { it.name.startsWith("jadx") && it.name.endsWith(".jar") } == true

  private fun convert(apk: File, deobfChecked: Boolean) {
    val java = findJava() ?: throw IOException("JDK belum terpasang. Selesaikan setup JDK dulu.")

    if (!hasJadx()) ensureJadx()
    if (cancelled) return

    val projects = Environment.PROJECTS_DIR.apply { mkdirs() }
    var out = File(projects, "$apkLabel-source")
    var n = 2
    while (out.exists()) out = File(projects, "$apkLabel-source-${n++}")

    val mi = ActivityManager.MemoryInfo()
    (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
    val xmx = ((mi.totalMem / 1048576) * 0.45).toInt().coerceIn(512, 3072)

    val deobf = if (deobfChecked) "--deobf" else ""
    val cmd =
        "'${java.absolutePath}' -Xmx${xmx}m -cp '${jadxLib().absolutePath}/*' jadx.cli.JadxCLI " +
            "-e -j 2 --show-bad-code $deobf -d '${out.absolutePath}' '${apk.absolutePath}' 2>&1"

    log("Memulai dekompilasi (memori maks ${xmx} MB). Bisa beberapa menit untuk APK besar ...")
    val pb = ProcessBuilder(Environment.BASH_SHELL.absolutePath, "-c", cmd)
    pb.environment().putAll(HashMap(TermuxShellEnvironment().getEnvironment(IDEApplication.instance, false)))
    pb.redirectErrorStream(true)
    pb.directory(Environment.HOME)

    val p = pb.start()
    process = p
    var errors = 0
    var shown = 0
    p.inputStream.bufferedReader().use { r ->
      var line = r.readLine()
      while (line != null) {
        if (cancelled) break
        val clean = line.trim()
        if (clean.isNotEmpty()) {
          status(clean.takeLast(120))
          val m = Regex("count:\\s*(\\d+)").find(clean)
          if (clean.contains("finished with errors") && m != null) {
            errors = m.groupValues[1].toInt()
          } else if (clean.contains("ERROR")) {
            errors++
          }
          if (clean.contains("ERROR")) {
            if (shown < 25) {
              shown++
              log(clean.take(200))
            }
          }
        }
        line = r.readLine()
      }
    }
    if (cancelled) {
      p.destroy()
      log("Dibatalkan.")
      return
    }
    val code = p.waitFor()

    val javaFiles = out.walkTopDown().count { it.isFile && it.extension == "java" }
    when {
      javaFiles > 0 -> {
        log("SELESAI: $javaFiles file Java diekspor. Bagian yang tidak bisa didekompilasi sempurna: $errors (tetap diekspor sebagai kode sebagian + komentar peringatan).")
        log("Lokasi: ${out.absolutePath}")
        log("Buka lewat Buka proyek → StudioProjek → ${out.name}, lalu Run seperti biasa.")
        log("Catatan jujur: kode hasil dekompilasi sering masih punya error kompilasi kecil & versi Gradle/AGP perlu disesuaikan sebelum bisa di-build.")
      }
      else -> log("Gagal (kode keluar $code): tidak ada source yang dihasilkan. Coba aktifkan Deobfuscation atau APK lain.")
    }
  }

  // ------------------------------------------------------------------------------- jadx

  private fun ensureJadx() {
    val base = File(Environment.HOME, ".acs-tools/jadx").apply { mkdirs() }
    val dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
    val local =
        dl.listFiles()
            ?.filter { it.name.startsWith("jadx", true) && it.name.endsWith(".zip", true) }
            ?.maxByOrNull { it.lastModified() }

    val zip =
        if (local != null) {
          log("Memakai ${local.name} dari folder Download.")
          local
        } else {
          downloadJadx(base)
        }
    if (cancelled) return

    status("Mengekstrak jadx ...")
    val lib = jadxLib().apply { mkdirs() }
    ZipInputStream(zip.inputStream().buffered()).use { zin ->
      while (true) {
        val e = zin.nextEntry ?: break
        if (e.isDirectory) continue
        val i = e.name.indexOf("lib/")
        if (i < 0 || !e.name.endsWith(".jar")) continue
        val target = File(lib, File(e.name).name)
        target.outputStream().use { zin.copyTo(it, 256 * 1024) }
      }
    }
    if (!hasJadx()) throw IOException("Isi zip jadx tidak sesuai (folder lib/*.jar tidak ditemukan)")
    if (local == null) zip.delete()
    log("jadx siap.")
  }

  private fun downloadJadx(base: File): File {
    status("Mencari rilis jadx terbaru ...")
    val api = URL("https://api.github.com/repos/skylot/jadx/releases/latest").openConnection() as HttpURLConnection
    api.setRequestProperty("User-Agent", "AndroidStudioFork")
    api.setRequestProperty("Accept", "application/vnd.github+json")
    api.connectTimeout = 15000
    api.readTimeout = 30000
    val json = JSONObject(api.inputStream.bufferedReader().use { it.readText() })
    val assets = json.getJSONArray("assets")
    var url: String? = null
    val pattern = Regex("^jadx-[0-9][0-9.]*\\.zip$")
    for (i in 0 until assets.length()) {
      val a = assets.getJSONObject(i)
      if (pattern.matches(a.getString("name"))) {
        url = a.getString("browser_download_url")
        break
      }
    }
    if (url == null) throw IOException("File rilis jadx tidak ditemukan. Unduh jadx-*.zip manual ke folder Download lalu coba lagi.")

    log("Mengunduh jadx ...")
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.instanceFollowRedirects = true
    conn.setRequestProperty("User-Agent", "AndroidStudioFork")
    conn.connectTimeout = 15000
    conn.readTimeout = 30000
    val total = conn.contentLengthLong
    val part = File(base, "jadx.zip.part")
    var read = 0L
    var lastUi = 0L
    conn.inputStream.use { ins ->
      part.outputStream().use { out ->
        val buf = ByteArray(64 * 1024)
        while (true) {
          if (cancelled) throw IOException("Dibatalkan")
          val r = ins.read(buf)
          if (r < 0) break
          out.write(buf, 0, r)
          read += r
          val now = System.currentTimeMillis()
          if (now - lastUi > 400) {
            lastUi = now
            status("Mengunduh jadx: ${read / 1048576} MB" + (if (total > 0) " / ${total / 1048576} MB" else ""))
          }
        }
      }
    }
    val zip = File(base, "jadx.zip")
    zip.delete()
    if (!part.renameTo(zip)) throw IOException("Gagal menyimpan file unduhan")
    return zip
  }
}
