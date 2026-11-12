package com.tom.rv2ide.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.web.ProjectDetector
import com.tom.rv2ide.web.WebProject
import com.tom.rv2ide.web.WebRuntime
import com.tom.rv2ide.web.WebTemplates
import java.io.File
import kotlin.concurrent.thread

/**
 * Web Studio: buat dan jalankan proyek HTML/CSS/JS, Node.js, Python, dan PHP. Server statis bawaan
 * (tanpa instal apa pun) atau backend lewat runtime yang terpasang. Mode Publik membuat situs bisa
 * dibuka perangkat lain di jaringan Wi-Fi/hotspot yang sama; Privat hanya untuk HP ini.
 */
class WebStudioActivity : AppCompatActivity() {

  private val ui = Handler(Looper.getMainLooper())
  private var project: WebProject? = null

  private lateinit var projectView: TextView
  private lateinit var urlView: TextView
  private lateinit var logView: TextView
  private lateinit var logScroll: ScrollView
  private lateinit var swPublic: SwitchCompat
  private lateinit var etPort: EditText
  private lateinit var etCmd: EditText
  private lateinit var btnRun: MaterialButton
  private lateinit var detectView: TextView
  private lateinit var tunnelView: TextView
  private lateinit var etToken: EditText
  private var detection: ProjectDetector.Result? = null

  private val ticker =
      object : Runnable {
        override fun run() {
          refreshLog()
          ui.postDelayed(this, 700)
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    buildUi()
  }

  override fun onResume() {
    super.onResume()
    ui.post(ticker)
    refreshState()
  }

  override fun onPause() {
    super.onPause()
    ui.removeCallbacks(ticker)
    saveFields()
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
    return b
  }

  private fun row(vararg bs: MaterialButton): LinearLayout {
    val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    bs.forEachIndexed { i, b ->
      b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        topMargin = dp(8)
        if (i > 0) marginStart = dp(8)
      }
      r.addView(b)
    }
    return r
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

  private fun buildHeader(): View {
    val logo = ImageView(this).apply {
      setImageResource(com.tom.rv2ide.resources.R.drawable.ic_app_logo)
      layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
    }
    val texts = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(14), 0, 0, 0)
      addView(TextView(this@WebStudioActivity).apply { text = "Web Studio"; textSize = 24f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) })
      addView(TextView(this@WebStudioActivity).apply { text = "HTML · CSS · JS · Node · Python · PHP"; textSize = 12f; setTextColor(Color.parseColor("#CCFFFFFF")) })
    }
    return LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(18), dp(18), dp(18), dp(18))
      background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#0B2B3A"), Color.parseColor("#14B86B"))).apply { cornerRadius = dp(26).toFloat() }
      addView(logo)
      addView(texts)
    }
  }

  private fun buildUi() {
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(24)) }
    content.addView(buildHeader())

    projectView = TextView(this).apply { text = "Belum ada proyek dipilih."; textSize = 13f; setPadding(0, dp(8), 0, 0) }
    content.addView(
        card(
            "1. Proyek",
            row(button("Proyek baru", false) { newProject() }, button("Pilih proyek", true) { chooseProject() }),
            projectView,
        )
    )

    detectView = TextView(this).apply { text = "Pilih proyek untuk mendeteksi backend dan cara menjalankannya."; textSize = 13f; setTextIsSelectable(true); setPadding(0, dp(8), 0, 0) }
    content.addView(
        card(
            "2. Hasil deteksi",
            detectView,
            row(button("Pilih perintah", true) { pickDetected() }, button("Instal dependensi", true) { installDeps() }),
            button("Deteksi ulang", true) { project?.let { selectDir(it.dir) } },
        )
    )

    swPublic = SwitchCompat(this).apply {
      text = "Publik (bisa dibuka perangkat lain di Wi-Fi yang sama)"
      textSize = 13f
      setPadding(0, dp(10), 0, 0)
      setOnCheckedChangeListener { _, _ -> if (WebRuntime.isRunning()) startServer() }
    }
    etPort = EditText(this).apply { hint = "Port"; inputType = InputType.TYPE_CLASS_NUMBER; setText("8080"); maxLines = 1 }
    etCmd = EditText(this).apply { hint = "Perintah backend (kosong = server statis)"; maxLines = 2; textSize = 13f }
    btnRun = button("▶  Jalankan", false) { if (WebRuntime.isRunning()) stopServer() else startServer() }
    urlView = TextView(this).apply { textSize = 13f; setTextIsSelectable(true); setPadding(0, dp(8), 0, 0) }
    content.addView(
        card(
            "3. Jalankan",
            swPublic,
            etPort,
            etCmd,
            btnRun,
            urlView,
            row(button("Buka di Chrome", true) { openChrome() }, button("Salin tautan", true) { copyLink() }),
        )
    )

    val prefs = getSharedPreferences("web_prefs", MODE_PRIVATE)
    etToken = EditText(this).apply {
      hint = "Token ngrok (untuk metode ngrok)"
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
      maxLines = 1
      setText(prefs.getString("ngrok_token", ""))
    }
    tunnelView = TextView(this).apply { textSize = 13f; setTextIsSelectable(true); setPadding(0, dp(8), 0, 0) }
    content.addView(
        card(
            "4. Akses publik dari internet",
            TextView(this).apply { text = "Jalankan server dulu, lalu aktifkan tunnel. Siapa pun di internet bisa membuka tautan yang dibuat. Metode: ngrok (butuh token), SSH atau Cloudflare (tanpa akun)."; textSize = 12f; alpha = 0.8f },
            etToken,
            row(button("Aktifkan", false) { chooseTunnel() }, button("Matikan", true) { stopTunnel() }),
            tunnelView,
            button("Salin tautan publik", true) { copyTunnel() },
        )
    )

    content.addView(
        card(
            "5. Kode & runtime",
            button("Edit kode", false) { editCode() },
            row(button("Instal Node.js", true) { installRuntime("nodejs") }, button("Instal Python", true) { installRuntime("python") }),
            button("Instal PHP", true) { installRuntime("php") },
        )
    )

    logView = TextView(this).apply { typeface = android.graphics.Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
    logScroll = ScrollView(this).apply { addView(logView) }
    content.addView(card("Log server", LinearLayout(this).apply { addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170))) }))

    setContentView(ScrollView(this).apply { addView(content) })
  }

  // ---------------------------------------------------------------------------------- proyek

  private fun newProject() {
    val labels = WebTemplates.types.map { it.second }.toTypedArray()
    MaterialAlertDialogBuilder(this)
        .setTitle("Jenis proyek")
        .setItems(labels) { _, i -> askName(WebTemplates.types[i].first) }
        .show()
  }

  private fun askName(type: String) {
    val input = EditText(this).apply { hint = "Nama proyek (tanpa spasi)"; setPadding(dp(20), dp(12), dp(20), dp(12)) }
    MaterialAlertDialogBuilder(this)
        .setTitle("Nama proyek")
        .setView(input)
        .setPositiveButton("Buat") { _, _ ->
          val name = input.text.toString().trim().replace(Regex("[^A-Za-z0-9._-]"), "-")
          if (name.isEmpty()) return@setPositiveButton
          try {
            val dir = WebTemplates.create(name, type)
            selectDir(dir)
            WebRuntime.log("Proyek '$name' dibuat di ${dir.absolutePath}")
          } catch (e: Throwable) {
            WebRuntime.log("ERROR: ${e.message}")
          }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private fun candidates(): List<File> {
    val dirs = ArrayList<File>()
    Environment.PROJECTS_DIR.listFiles()?.let { dirs.addAll(it) }
    WebProject.root().listFiles()?.let { dirs.addAll(it) }
    return dirs.filter { it.isDirectory && it.name != "Web" && ProjectDetector.looksLikeWeb(it) }.distinct().sortedBy { it.name }
  }

  private fun chooseProject() {
    val dirs = candidates()
    if (dirs.isEmpty()) {
      WebRuntime.log("Tidak ada proyek web di ${Environment.PROJECTS_DIR.absolutePath}. Taruh/clone proyek di sana, atau tekan 'Proyek baru'.")
      return
    }
    MaterialAlertDialogBuilder(this)
        .setTitle("Pilih proyek web")
        .setItems(dirs.map { it.name }.toTypedArray()) { _, i -> selectDir(dirs[i]) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  /** Memilih folder, mendeteksi jenis/backend/perintahnya, lalu mengisi pengaturan otomatis. */
  private fun selectDir(dir: File) {
    thread {
      val det = try { ProjectDetector.detect(dir) } catch (e: Throwable) { null }
      var p = WebProject.load(dir)
      if (p == null && det != null) {
        p = WebProject(dir, det.type, det.port, if (det.type == "static") "" else det.commands.firstOrNull()?.command.orEmpty(), det.webRoot)
        try { p.save() } catch (e: Throwable) { /* abaikan */ }
      } else if (p != null && det != null && p.command.isBlank() && det.type != "static") {
        p.command = det.commands.firstOrNull()?.command.orEmpty()
        if (det.webRoot.isNotEmpty()) p.root = det.webRoot
      }
      project = p
      detection = det
      ui.post {
        applyProjectToFields()
        detectView.text = det?.summary() ?: "Deteksi gagal."
      }
    }
  }

  private fun pickDetected() {
    val det = detection
    if (det == null || det.commands.isEmpty()) {
      WebRuntime.log("Tidak ada perintah terdeteksi. Isi perintah backend manual, atau biarkan kosong untuk server statis.")
      return
    }
    val items = (listOf("(server statis, tanpa perintah)") + det.commands.map { "${it.command}\n   ${it.label}" }).toTypedArray()
    MaterialAlertDialogBuilder(this)
        .setTitle("Perintah menjalankan")
        .setItems(items) { _, i -> etCmd.setText(if (i == 0) "" else det.commands[i - 1].command); saveFields() }
        .show()
  }

  private fun installDeps() {
    val p = project
    val cmd = detection?.install
    if (p == null || cmd == null) {
      WebRuntime.log("Tidak ada langkah instal dependensi yang terdeteksi.")
      return
    }
    WebRuntime.runOnce(p.dir, cmd) { }
  }

  // ------------------------------------------------------------------------------- tunnel

  private fun chooseTunnel() {
    if (!WebRuntime.isRunning()) {
      WebRuntime.log("Jalankan server dulu, baru aktifkan tunnel.")
      return
    }
    val items = arrayOf("ngrok (butuh token)", "SSH · localhost.run (tanpa akun)", "Cloudflare (tanpa akun)")
    val kinds = arrayOf("ngrok", "ssh", "cloudflare")
    MaterialAlertDialogBuilder(this)
        .setTitle("Metode akses internet")
        .setItems(items) { _, i ->
          val token = etToken.text.toString().trim()
          getSharedPreferences("web_prefs", MODE_PRIVATE).edit().putString("ngrok_token", token).apply()
          val port = WebRuntime.port
          thread {
            try {
              WebRuntime.startTunnel(kinds[i], port, token)
            } catch (e: Throwable) {
              WebRuntime.log("ERROR: ${e.message}")
            }
          }
        }
        .show()
  }

  private fun stopTunnel() {
    thread { WebRuntime.stopTunnel() }
  }

  private fun copyTunnel() {
    val u = WebRuntime.tunnelUrl
    if (u == null) {
      WebRuntime.log("Belum ada tautan publik.")
      return
    }
    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", u))
    WebRuntime.log("Tautan publik disalin: $u")
  }

  private fun applyProjectToFields() {
    val p = project ?: return
    projectView.text = "${p.dir.name}  ·  ${WebTemplates.types.firstOrNull { it.first == p.type }?.second ?: p.type}\n${p.dir.absolutePath}"
    etPort.setText(p.port.toString())
    etCmd.setText(p.command)
  }

  private fun saveFields() {
    val p = project ?: return
    p.port = etPort.text.toString().toIntOrNull() ?: p.port
    p.command = etCmd.text.toString().trim()
    try { p.save() } catch (e: Throwable) { /* abaikan */ }
  }

  // ------------------------------------------------------------------------------------ server

  private fun staticRoot(p: WebProject): File =
      if (p.type == "static") (if (p.root.isNotEmpty()) File(p.dir, p.root) else p.dir) else (File(p.dir, "public").takeIf { it.isDirectory } ?: p.dir)

  private fun startServer() {
    val p = project
    if (p == null) {
      WebRuntime.log("Pilih atau buat proyek dulu.")
      return
    }
    saveFields()
    val pub = swPublic.isChecked
    thread {
      try {
        if (p.command.isBlank()) {
          WebRuntime.startStatic(staticRoot(p), p.port, pub)
        } else {
          WebRuntime.startCommand(p.dir, p.command, p.port, pub)
        }
      } catch (e: Throwable) {
        WebRuntime.log("ERROR: ${e.javaClass.simpleName}: ${e.message}. Port mungkin dipakai, atau runtime belum terpasang (lihat 'Instal ...').")
      }
      ui.post { refreshState() }
    }
  }

  private fun stopServer() {
    thread {
      WebRuntime.stop()
      ui.post { refreshState() }
    }
  }

  private fun refreshState() {
    val running = WebRuntime.isRunning()
    btnRun.text = if (running) "■  Hentikan" else "▶  Jalankan"
    if (!running) {
      urlView.text = ""
      return
    }
    val port = WebRuntime.port
    val lines = StringBuilder("Lokal: http://127.0.0.1:$port")
    if (WebRuntime.publicAccess) {
      val ip = WebRuntime.lanIp()
      lines.append("\nPublik (Wi-Fi yang sama): ").append(if (ip != null) "http://$ip:$port" else "IP tidak terdeteksi, pastikan Wi-Fi/hotspot aktif")
    } else {
      lines.append("\nMode: PRIVAT (hanya HP ini)")
    }
    urlView.text = lines.toString()
  }

  private fun refreshLog() {
    val t = WebRuntime.logText()
    if (logView.text.toString() != t) {
      logView.text = t
      logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }
    val tu = WebRuntime.tunnelUrl
    tunnelView.text = when {
      tu != null -> "🌐 Publik: $tu"
      WebRuntime.tunnelRunning() -> "Tunnel dibuat, menunggu tautan ..."
      else -> "Tunnel mati."
    }
    val running = WebRuntime.isRunning()
    if (btnRun.text.startsWith("■") != running) refreshState()
  }

  private fun currentUrl(): String {
    val port = WebRuntime.port.takeIf { it > 0 } ?: (etPort.text.toString().toIntOrNull() ?: 8080)
    return "http://127.0.0.1:$port"
  }

  private fun openChrome() {
    if (!WebRuntime.isRunning()) {
      WebRuntime.log("Jalankan server dulu.")
      return
    }
    val i = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl()))
    try {
      startActivity(Intent(i).setPackage("com.android.chrome"))
    } catch (e: Throwable) {
      try { startActivity(i) } catch (e2: Throwable) { WebRuntime.log("Tidak ada browser untuk membuka tautan.") }
    }
  }

  private fun copyLink() {
    val link = if (WebRuntime.publicAccess) (WebRuntime.lanIp()?.let { "http://$it:${WebRuntime.port}" } ?: currentUrl()) else currentUrl()
    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", link))
    WebRuntime.log("Tautan disalin: $link")
  }

  private fun editCode() {
    val p = project
    if (p == null) {
      WebRuntime.log("Pilih proyek dulu.")
      return
    }
    startActivity(Intent(this, WebEditorActivity::class.java).putExtra(WebEditorActivity.EXTRA_DIR, p.dir.absolutePath))
  }

  private fun installRuntime(pkg: String) {
    startActivity(
        Intent(this, TerminalActivity::class.java)
            .putExtra(TerminalActivity.EXTRA_RUN_COMMAND, "pkg install -y $pkg; echo; echo '=== Selesai ==='; sleep 3")
    )
  }
}
