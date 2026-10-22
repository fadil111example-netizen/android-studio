package com.tom.rv2ide.activities

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.widget.ImageView
import android.text.InputType
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.tom.rv2ide.app.IDEApplication
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * GitHub di dalam aplikasi: clone, commit & push, pull, jalankan GitHub Actions dengan progres
 * langkah-demi-langkah (seperti di browser), ringkasan error, dan unduh APK hasil build.
 */
class GitHubActivity : AppCompatActivity() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val prefs by lazy { getSharedPreferences("github_prefs", MODE_PRIVATE) }

  private var projectDir: File? = null
  private var slug: String? = null
  @Volatile private var currentRunId = 0L

  private lateinit var etUser: EditText
  private lateinit var etEmail: EditText
  private lateinit var etToken: EditText
  private lateinit var projectView: TextView
  private lateinit var stageView: TextView
  private lateinit var detailView: TextView
  private lateinit var progressBar: ProgressBar
  private lateinit var stepsView: TextView
  private lateinit var logView: TextView
  private lateinit var logScroll: ScrollView
  private lateinit var btnCancelRun: MaterialButton
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

  private fun rowOf(vararg buttons: MaterialButton): LinearLayout {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    buttons.forEachIndexed { i, b ->
      b.layoutParams =
          LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            topMargin = dp(8)
            if (i > 0) marginStart = dp(8)
          }
      row.addView(b)
    }
    return row
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
      cardElevation = dp(0).toFloat()
      strokeWidth = dp(1)
      strokeColor = Color.parseColor("#33007A52")
      layoutParams =
          LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
              .apply { topMargin = dp(12) }
      addView(col)
    }
  }

  private fun inputField(hint: String, type: Int, value: String) =
      EditText(this).apply {
        this.hint = hint
        inputType = type
        setText(value)
        maxLines = 1
      }

  private fun buildUi() {
    val content =
        LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(dp(16), dp(20), dp(16), dp(8))
        }
    content.addView(buildHeader())

    etUser = inputField("Username GitHub", InputType.TYPE_CLASS_TEXT, prefs.getString("user", "") ?: "")
    etEmail = inputField("Email (untuk commit)", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, prefs.getString("email", "") ?: "")
    etToken =
        inputField(
            "Token (izin repo + workflow)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            prefs.getString("token", "") ?: "",
        )
    content.addView(
        card(
            "1. Akun",
            etUser,
            etEmail,
            etToken,
            actionButton("Simpan & tes akun", false) { saveAccount() },
        )
    )

    projectView = TextView(this).apply { text = "Belum ada proyek dipilih."; setPadding(0, dp(8), 0, 0) }
    content.addView(
        card(
            "2. Proyek",
            rowOf(
                actionButton("Clone dari GitHub", false) { listRepos() },
                actionButton("Proyek lokal", true) { chooseProject() },
            ),
            projectView,
        )
    )

    content.addView(
        card(
            "3. Kirim & ambil kode",
            rowOf(
                actionButton("Status", true) { guarded { gitStatus() } },
                actionButton("Pull", true) { guarded { pull() } },
            ),
            actionButton("Commit & Push", false) { askCommit() },
        )
    )

    content.addView(
        card(
            "4. Build (GitHub Actions)",
            actionButton("Jalankan build", false) { guarded { dispatchBuild() } },
            rowOf(
                actionButton("Status build", true) { guarded { showRun() } },
                actionButton("Unduh APK", true) { guarded { downloadApk(null) } },
            ),
        )
    )

    val sv = ScrollView(this).apply { addView(content) }
    val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    page.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    page.addView(buildPanel())
    setContentView(page)
  }

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
              TextView(this@GitHubActivity).apply {
                text = "GitHub"
                textSize = 26f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
              }
          )
          addView(
              TextView(this@GitHubActivity).apply {
                text = "Clone · Push · Build · Unduh APK"
                textSize = 13f
                setTextColor(Color.parseColor("#CCFFFFFF"))
              }
          )
        }
    return LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = android.view.Gravity.CENTER_VERTICAL
      setPadding(dp(18), dp(18), dp(18), dp(18))
      background =
          GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#0B2B3A"), Color.parseColor("#0F7A52"))).apply {
            cornerRadius = dp(24).toFloat()
          }
      addView(logo)
      addView(texts)
    }
  }

  /** Panel progres yang selalu terlihat di bagian bawah layar. */
  private fun buildPanel(): View {
    stageView = TextView(this).apply { text = "Siap."; textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
    detailView = TextView(this).apply { textSize = 12f; alpha = 0.75f; maxLines = 1 }
    progressBar =
        ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
          max = 100
          isIndeterminate = false
          progress = 0
          progressTintList = ColorStateList.valueOf(Color.parseColor("#1FB86B"))
          indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#1FB86B"))
        }
    stepsView = TextView(this).apply { typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f }
    logView =
        TextView(this).apply {
          typeface = android.graphics.Typeface.MONOSPACE
          textSize = 11f
          setTextIsSelectable(true)
        }
    logScroll = ScrollView(this).apply { addView(logView) }
    btnCancelRun =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
          text = "Batalkan build"
          visibility = View.GONE
          setOnClickListener { cancelRun() }
        }

    val col =
        LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(dp(16), dp(12), dp(16), dp(12))
          addView(stageView)
          addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
          addView(detailView)
          addView(stepsView)
          addView(btnCancelRun)
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

  private fun steps(msg: CharSequence) = runOnUiThread { stepsView.text = msg }

  private fun symColor(sym: String): Int =
      when (sym) {
        "✓" -> Color.parseColor("#1FB86B")
        "✗" -> Color.parseColor("#E5484D")
        "▶" -> Color.parseColor("#2E7DFF")
        "⊘" -> Color.parseColor("#F5A524")
        else -> Color.parseColor("#8A938C")
      }

  private fun colored(text: String, color: Int): CharSequence {
    val s = SpannableString(text + "\n")
    s.setSpan(ForegroundColorSpan(color), 0, s.length, 0)
    return s
  }

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
      if (!busy) progressBar.isIndeterminate = false
    }
  }

  private fun guarded(block: suspend () -> Unit) {
    saveAccountQuiet()
    setBusy(true)
    progress(null)
    scope.launch {
      try {
        block()
      } catch (t: Throwable) {
        stage("Gagal ✗")
        log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
      } finally {
        setBusy(false)
      }
    }
  }

  // ---------------------------------------------------------------------------------- akun

  private fun saveAccountQuiet() {
    prefs.edit()
        .putString("user", etUser.text.toString().trim())
        .putString("email", etEmail.text.toString().trim())
        .putString("token", etToken.text.toString().trim())
        .apply()
  }

  private fun saveAccount() {
    saveAccountQuiet()
    log("Akun disimpan. Menguji token ...")
    setBusy(true)
    progress(null)
    scope.launch {
      try {
        val (c, t) = api("GET", "/user")
        if (c == 200) {
          stage("Token valid ✓")
          log("Login sebagai ${JSONObject(t).getString("login")}.")
        } else {
          stage("Token ditolak ✗")
          log("GitHub menjawab $c. Periksa token & izin (repo + workflow).")
        }
      } catch (e: Throwable) {
        stage("Gagal ✗")
        log("ERROR: ${e.javaClass.simpleName}: ${e.message}")
      } finally {
        setBusy(false)
      }
    }
  }

  private fun user() = prefs.getString("user", "").orEmpty()

  private fun token() = prefs.getString("token", "").orEmpty()

  private fun needAccount(): Boolean {
    if (user().isEmpty() || token().isEmpty()) {
      log("Isi username dan token dulu, lalu tekan 'Simpan & tes akun'.")
      return false
    }
    return true
  }

  // ------------------------------------------------------------------------------- git & API

  private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

  private fun git(dir: File?, args: String, auth: Boolean = false, live: Boolean = true): Pair<Int, String> {
    val gitBin = File(Environment.BIN_DIR, "git")
    if (!gitBin.exists()) throw IOException("Git belum terpasang. Di Terminal ketik: pkg install -y git")
    val header =
        if (auth) {
          val b64 = Base64.encodeToString("${user()}:${token()}".toByteArray(), Base64.NO_WRAP)
          "-c ${q("http.extraheader=Authorization: Basic $b64")} "
        } else ""
    val cmd = "${q(gitBin.absolutePath)} -c safe.directory='*' $header$args 2>&1"
    val pb = ProcessBuilder(Environment.BASH_SHELL.absolutePath, "-c", cmd)
    pb.environment().putAll(HashMap(TermuxShellEnvironment().getEnvironment(IDEApplication.instance, false)))
    pb.environment()["GIT_TERMINAL_PROMPT"] = "0"
    pb.redirectErrorStream(true)
    pb.directory(dir ?: Environment.HOME)
    val p = pb.start()
    val out = StringBuilder()
    val pct = Regex("(\\d{1,3})%")
    p.inputStream.bufferedReader().use { r ->
      var line = r.readLine()
      while (line != null) {
        val t = line.trim()
        if (t.isNotEmpty()) {
          out.appendLine(t)
          detail(t.takeLast(90))
          pct.find(t)?.let { progress(it.groupValues[1].toInt()) }
          if (live && !t.contains('%')) log(t.take(200))
        }
        line = r.readLine()
      }
    }
    return p.waitFor() to out.toString().trim()
  }

  private fun conn(method: String, url: String): HttpURLConnection {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = method
    c.setRequestProperty("Authorization", "Bearer ${token()}")
    c.setRequestProperty("Accept", "application/vnd.github+json")
    c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
    c.setRequestProperty("User-Agent", "AndroidStudioFork")
    c.connectTimeout = 15000
    c.readTimeout = 30000
    return c
  }

  private fun api(method: String, path: String, body: String? = null): Pair<Int, String> {
    val c = conn(method, "https://api.github.com$path")
    if (body != null) {
      c.doOutput = true
      c.setRequestProperty("Content-Type", "application/json")
      c.outputStream.use { it.write(body.toByteArray()) }
    }
    val code = c.responseCode
    val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
    return code to text
  }

  private fun branch(dir: File): String = git(dir, "rev-parse --abbrev-ref HEAD", live = false).second.ifEmpty { "main" }

  // ----------------------------------------------------------------------------- pilih proyek

  private fun listRepos() {
    saveAccountQuiet()
    if (!needAccount()) return
    setBusy(true)
    progress(null)
    stage("Mengambil daftar repo ...")
    scope.launch {
      try {
        val (code, text) = api("GET", "/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator")
        if (code != 200) throw IOException("GitHub menjawab $code: ${text.take(150)}")
        val arr = JSONArray(text)
        val names = (0 until arr.length()).map { arr.getJSONObject(it).getString("full_name") }
        stage("${names.size} repo ditemukan")
        runOnUiThread { showRepoDialog(names) }
      } catch (t: Throwable) {
        stage("Gagal ✗")
        log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
      } finally {
        setBusy(false)
      }
    }
  }

  private fun showRepoDialog(names: List<String>) {
    MaterialAlertDialogBuilder(this)
        .setTitle("Pilih repo untuk di-clone")
        .setItems(names.toTypedArray()) { _, i -> cloneRepo(names[i]) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private fun cloneRepo(full: String) {
    setBusy(true)
    progress(null)
    scope.launch {
      try {
        val name = full.substringAfter('/')
        val dest = File(Environment.PROJECTS_DIR, name)
        if (dest.exists()) {
          log("Folder $name sudah ada di StudioProjek, dipilih sebagai proyek.")
        } else {
          stage("Clone $full ...")
          val (c, _) = git(Environment.PROJECTS_DIR, "clone --progress https://github.com/$full.git ${q(dest.absolutePath)}", auth = true)
          if (c != 0) throw IOException("Clone gagal (kode $c)")
          log("Clone selesai: ${dest.absolutePath}")
        }
        selectProject(dest)
        stage("Clone selesai ✓")
        progress(100)
      } catch (t: Throwable) {
        stage("Gagal ✗")
        log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
      } finally {
        setBusy(false)
      }
    }
  }

  private fun chooseProject() {
    val dirs = Environment.PROJECTS_DIR.listFiles()?.filter { File(it, ".git").exists() }?.sortedBy { it.name } ?: emptyList()
    if (dirs.isEmpty()) {
      log("Belum ada proyek git di ${Environment.PROJECTS_DIR.absolutePath}. Clone repo dulu.")
      return
    }
    showProjectDialog(dirs)
  }

  private fun showProjectDialog(dirs: List<File>) {
    MaterialAlertDialogBuilder(this)
        .setTitle("Pilih proyek")
        .setItems(dirs.map { it.name }.toTypedArray()) { _, i ->
          setBusy(true)
          scope.launch {
            try {
              selectProject(dirs[i])
              stage("Proyek dipilih ✓")
            } catch (t: Throwable) {
              log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
              setBusy(false)
            }
          }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private fun selectProject(dir: File) {
    projectDir = dir
    val url = git(dir, "remote get-url origin", live = false).second
    val m = Regex("github\\.com[:/]([^/]+)/([^/]+?)(\\.git)?$").find(url.trim())
    slug = m?.let { it.groupValues[1] + "/" + it.groupValues[2] }
    runOnUiThread { projectView.text = "Proyek: ${dir.name}" + (slug?.let { "\n$it" } ?: "\n(remote GitHub belum ada)") }
  }

  private fun needProject(): File? {
    val d = projectDir
    if (d == null) log("Pilih proyek dulu (Clone atau Proyek lokal).")
    return d
  }

  // ----------------------------------------------------------------------------------- git ops

  private fun gitStatus() {
    val d = needProject() ?: return
    stage("Memeriksa perubahan ...")
    val (_, out) = git(d, "status --short", live = false)
    val n = if (out.isEmpty()) 0 else out.lines().size
    log("Cabang: ${branch(d)}")
    log(if (n == 0) "Tidak ada perubahan." else "$n file berubah:\n${out.lines().take(30).joinToString("\n")}")
    stage(if (n == 0) "Bersih ✓" else "$n file berubah")
    progress(100)
  }

  private fun askCommit() {
    if (needProject() == null) return
    showCommitDialog()
  }

  private fun showCommitDialog() {
    val input = EditText(this).apply { setText("Update dari Android Studio"); setPadding(dp(20), dp(12), dp(20), dp(12)) }
    MaterialAlertDialogBuilder(this)
        .setTitle("Pesan commit")
        .setView(input)
        .setPositiveButton("Commit & Push") { _, _ -> guarded { commitAndPush(input.text.toString().ifBlank { "Update" }) } }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private val defaultIgnore =
      "*.iml\n.gradle/\n/local.properties\n/.idea/\n.DS_Store\nbuild/\n/captures\n.externalNativeBuild\n.cxx\n"

  private fun commitAndPush(message: String) {
    val d = needProject() ?: return
    if (!needAccount()) return
    val gi = File(d, ".gitignore")
    if (!gi.exists()) {
      gi.writeText(defaultIgnore)
      log("Membuat .gitignore standar (folder build/.gradle tidak ikut terkirim).")
    }
    stage("1/3 Menyiapkan file ...")
    git(d, "add -A", live = false)
    val changed = git(d, "status --short", live = false).second
    log(if (changed.isEmpty()) "Tidak ada perubahan baru." else "${changed.lines().size} file akan dikirim.")

    stage("2/3 Membuat commit ...")
    val email = prefs.getString("email", "").orEmpty().ifEmpty { "${user()}@users.noreply.github.com" }
    val msgFile = File(cacheDir, "commit_msg.txt").apply { writeText(message) }
    val (cc, co) = git(d, "-c user.name=${q(user())} -c user.email=${q(email)} commit -F ${q(msgFile.absolutePath)}", live = false)
    if (cc != 0 && !co.contains("nothing to commit")) throw IOException("Commit gagal: ${co.take(200)}")

    stage("3/3 Mengirim ke GitHub ...")
    progress(null)
    val br = branch(d)
    var (pc, po) = git(d, "push --progress -u origin HEAD", auth = true)
    if (pc != 0 && (po.contains("fetch first") || po.contains("non-fast-forward") || po.contains("rejected"))) {
      log("Push ditolak karena ada commit baru di GitHub. Mengambil dulu (pull --rebase) ...")
      stage("Mengambil perubahan terbaru ...")
      val (rc, _) = git(d, "pull --rebase origin $br", auth = true)
      if (rc != 0) throw IOException("Pull gagal. Ada konflik file? Selesaikan manual di Terminal.")
      stage("Mengirim ulang ...")
      pc = git(d, "push --progress -u origin HEAD", auth = true).first
    }
    if (pc == 0) {
      stage("Terkirim ke GitHub ✓")
      progress(100)
      log("BERHASIL dikirim ke GitHub.")
    } else {
      throw IOException("Push gagal (cek token/izin repo).")
    }
  }

  private fun pull() {
    val d = needProject() ?: return
    if (!needAccount()) return
    stage("Mengambil perubahan terbaru ...")
    val (c, _) = git(d, "pull --rebase --progress origin ${branch(d)}", auth = true)
    if (c != 0) throw IOException("Pull gagal (kode $c).")
    stage("Pull selesai ✓")
    progress(100)
  }

  // --------------------------------------------------------------------------- GitHub Actions

  private suspend fun dispatchBuild() {
    val d = needProject() ?: return
    val s = slug ?: run { log("Proyek ini belum punya remote GitHub."); return }
    if (!needAccount()) return
    stage("Mencari workflow ...")
    val (code, text) = api("GET", "/repos/$s/actions/workflows")
    if (code != 200) throw IOException("GitHub menjawab $code: ${text.take(150)}")
    val arr = JSONObject(text).getJSONArray("workflows")
    val wfs = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.getString("state") == "active" }
    if (wfs.isEmpty()) throw IOException("Repo ini belum punya workflow aktif (.github/workflows).")
    val wf = wfs.firstOrNull { it.getString("path").contains("build", true) } ?: wfs[0]
    val br = branch(d)
    val t0 = Instant.now()
    stage("Memulai build '${wf.getString("name")}' ...")
    val (dc, dt) = api("POST", "/repos/$s/actions/workflows/${wf.getLong("id")}/dispatches", "{\"ref\":\"$br\"}")
    if (dc != 204) throw IOException("Gagal memulai build ($dc): ${dt.take(200)}. Pastikan workflow punya 'workflow_dispatch' dan perubahan sudah di-push.")
    log("Build dimulai di cabang $br.")

    var runId = 0L
    repeat(20) {
      if (runId != 0L) return@repeat
      val (c, t) = api("GET", "/repos/$s/actions/runs?per_page=5&event=workflow_dispatch")
      if (c == 200) {
        val rs = JSONObject(t).getJSONArray("workflow_runs")
        for (i in 0 until rs.length()) {
          val r = rs.getJSONObject(i)
          if (!Instant.parse(r.getString("created_at")).isBefore(t0.minusSeconds(15))) {
            runId = r.getLong("id")
            break
          }
        }
      }
      if (runId == 0L) delay(2500)
    }
    if (runId == 0L) throw IOException("Build belum terlihat di GitHub. Coba 'Status build' beberapa detik lagi.")
    watchRun(s, runId)
  }

  private fun symbol(status: String, conclusion: String?): String =
      when {
        status == "completed" && conclusion == "success" -> "✓"
        status == "completed" && (conclusion == "failure" || conclusion == "timed_out") -> "✗"
        status == "completed" && conclusion == "cancelled" -> "⊘"
        status == "completed" -> "–"
        status == "in_progress" -> "▶"
        else -> "○"
      }

  private suspend fun watchRun(s: String, runId: Long) {
    currentRunId = runId
    runOnUiThread { btnCancelRun.visibility = View.VISIBLE }
    try {
      var tries = 0
      while (tries++ < 1500) {
        val (rc, rt) = api("GET", "/repos/$s/actions/runs/$runId")
        if (rc != 200) throw IOException("GitHub menjawab $rc")
        val run = JSONObject(rt)
        val (jc, jt) = api("GET", "/repos/$s/actions/runs/$runId/jobs")

        val sb = SpannableStringBuilder()
        var total = 0
        var done = 0
        var failedJob = 0L
        if (jc == 200) {
          val jobs = JSONObject(jt).getJSONArray("jobs")
          for (j in 0 until jobs.length()) {
            val job = jobs.getJSONObject(j)
            val jsym = symbol(job.getString("status"), job.optString("conclusion"))
            sb.append(colored("$jsym ${job.getString("name")}", symColor(jsym)))
            if (job.optString("conclusion") == "failure") failedJob = job.getLong("id")
            val st = job.optJSONArray("steps")
            if (st != null) {
              for (k in 0 until st.length()) {
                val step = st.getJSONObject(k)
                total++
                if (step.getString("status") == "completed") done++
                val ssym = symbol(step.getString("status"), step.optString("conclusion"))
                sb.append(colored("   $ssym ${step.getString("name").take(48)}", symColor(ssym)))
              }
            }
          }
        }
        steps(sb)
        progress(if (total > 0) done * 100 / total else null)

        val started = run.optString("run_started_at", run.getString("created_at"))
        val secs = Duration.between(Instant.parse(started), Instant.now()).seconds.coerceAtLeast(0)
        val elapsed = "%d:%02d".format(secs / 60, secs % 60)
        val status = run.getString("status")
        stage("Build #${run.getInt("run_number")} · $status · $elapsed")
        detail("$done/$total langkah selesai")

        if (status == "completed") {
          val concl = run.optString("conclusion")
          progress(100)
          when (concl) {
            "success" -> {
              stage("Build berhasil ✓ ($elapsed)")
              runOnUiThread { showDownloadDialog(runId) }
            }
            "cancelled" -> stage("Build dibatalkan ⊘")
            else -> {
              stage("Build gagal ✗")
              if (failedJob != 0L) failureSummary(s, failedJob)
              log("Log lengkap: ${run.optString("html_url")}")
            }
          }
          return
        }
        delay(4000)
      }
      log("Build masih berjalan. Tekan 'Status build' untuk melihat lagi.")
    } finally {
      runOnUiThread { btnCancelRun.visibility = View.GONE }
    }
  }

  private fun showDownloadDialog(runId: Long) {
    MaterialAlertDialogBuilder(this)
        .setTitle("Build berhasil")
        .setMessage("Unduh APK hasil build sekarang?")
        .setPositiveButton("Unduh") { _, _ -> guarded { downloadApk(runId) } }
        .setNegativeButton("Nanti", null)
        .show()
  }

  private fun cancelRun() {
    val s = slug ?: return
    val id = currentRunId
    if (id == 0L) return
    scope.launch {
      try {
        val (c, _) = api("POST", "/repos/$s/actions/runs/$id/cancel")
        log(if (c == 202) "Permintaan pembatalan dikirim." else "Pembatalan gagal ($c).")
      } catch (e: Throwable) {
        log("ERROR: ${e.message}")
      }
    }
  }

  /** Mengambil baris error penting dari log job yang gagal supaya tidak perlu membuka browser. */
  private fun failureSummary(s: String, jobId: Long) {
    try {
      val c = conn("GET", "https://api.github.com/repos/$s/actions/jobs/$jobId/logs")
      c.instanceFollowRedirects = false
      val loc = c.getHeaderField("Location") ?: return
      val lines = URL(loc).openStream().bufferedReader().use { it.readLines() }
      val rx = Regex("(?i)(\\be: |error:|FAILURE|exception|failed)")
      val hits = lines.filter { rx.containsMatchIn(it) }.map { it.replace(Regex("^\\S+Z\\s"), "").trim().take(170) }
      if (hits.isNotEmpty()) {
        log("Ringkasan error (baris terakhir):")
        hits.takeLast(12).forEach { log("  $it") }
      }
    } catch (e: Throwable) {
      log("Tidak bisa mengambil log error: ${e.message}")
    }
  }

  private suspend fun showRun() {
    val s = slug ?: run { log("Pilih proyek dulu."); return }
    stage("Mengambil status build ...")
    val (code, text) = api("GET", "/repos/$s/actions/runs?per_page=1")
    if (code != 200) throw IOException("GitHub menjawab $code")
    val arr = JSONObject(text).getJSONArray("workflow_runs")
    if (arr.length() == 0) {
      log("Belum ada build.")
      return
    }
    watchRun(s, arr.getJSONObject(0).getLong("id"))
  }

  private fun downloadApk(runIdIn: Long?) {
    val s = slug ?: run { log("Pilih proyek dulu."); return }
    var runId = runIdIn ?: 0L
    if (runId == 0L) {
      stage("Mencari build sukses terakhir ...")
      val (code, text) = api("GET", "/repos/$s/actions/runs?per_page=10&status=success")
      if (code != 200) throw IOException("GitHub menjawab $code")
      val runs = JSONObject(text).getJSONArray("workflow_runs")
      if (runs.length() == 0) throw IOException("Belum ada build yang sukses.")
      runId = runs.getJSONObject(0).getLong("id")
    }
    val (ac, at) = api("GET", "/repos/$s/actions/runs/$runId/artifacts")
    if (ac != 200) throw IOException("GitHub menjawab $ac")
    val arts = JSONObject(at).getJSONArray("artifacts")
    if (arts.length() == 0) throw IOException("Build ini tidak punya artifact (mungkin sudah kedaluwarsa).")
    val art = arts.getJSONObject(0)
    val name = art.getString("name")
    val size = art.optLong("size_in_bytes", 0L)
    stage("Mengunduh $name ...")
    progress(0)

    val first = conn("GET", art.getString("archive_download_url"))
    first.instanceFollowRedirects = false
    val loc = first.getHeaderField("Location") ?: throw IOException("Tautan unduhan tidak diberikan (kode ${first.responseCode})")
    val c = URL(loc).openConnection() as HttpURLConnection
    c.connectTimeout = 15000
    c.readTimeout = 60000

    val dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
    val zip = File(dl, "$name.zip")
    var total = 0L
    c.inputStream.use { ins ->
      zip.outputStream().use { out ->
        val buf = ByteArray(64 * 1024)
        while (true) {
          val r = ins.read(buf)
          if (r < 0) break
          out.write(buf, 0, r)
          total += r
          if (size > 0) progress((total * 100 / size).toInt())
          detail("${total / 1048576} MB" + (if (size > 0) " / ${size / 1048576} MB" else ""))
        }
      }
    }
    var apks = 0
    ZipInputStream(zip.inputStream().buffered()).use { zin ->
      while (true) {
        val e = zin.nextEntry ?: break
        if (!e.isDirectory && e.name.endsWith(".apk", true)) {
          val f = File(dl, File(e.name).name)
          f.outputStream().use { zin.copyTo(it, 256 * 1024) }
          apks++
          log("APK siap: ${f.absolutePath}")
        }
      }
    }
    progress(100)
    if (apks == 0) {
      log("Tidak ada file .apk di artifact. Zip tersimpan: ${zip.absolutePath}")
    } else {
      zip.delete()
      log("Buka file APK itu dari pengelola file untuk meng-install.")
    }
    stage("Unduhan selesai ✓")
  }
}
