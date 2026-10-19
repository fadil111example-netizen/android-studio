package com.tom.rv2ide.activities

import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * GitHub di dalam aplikasi: clone, commit & push, pull, jalankan GitHub Actions, pantau build, dan
 * unduh APK hasilnya. Token disimpan di folder pribadi aplikasi.
 */
class GitHubActivity : AppCompatActivity() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val prefs by lazy { getSharedPreferences("github_prefs", MODE_PRIVATE) }

  private var projectDir: File? = null
  private var slug: String? = null

  private lateinit var etUser: EditText
  private lateinit var etEmail: EditText
  private lateinit var etToken: EditText
  private lateinit var projectView: TextView
  private lateinit var statusView: TextView
  private lateinit var logView: TextView
  private lateinit var scroll: ScrollView
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
        actionButtons.add(this)
      }

  private fun inputField(hint: String, type: Int, value: String) =
      EditText(this).apply {
        this.hint = hint
        inputType = type
        setText(value)
        maxLines = 1
      }

  private fun buildUi() {
    val root =
        LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(dp(20), dp(24), dp(20), dp(16))
        }
    root.addView(
        TextView(this).apply {
          text = "GitHub"
          textSize = 24f
          setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    )
    root.addView(
        TextView(this).apply {
          text =
              "Isi sekali saja. Token (izin 'repo' dan 'workflow') dibuat di github.com → Settings → " +
                  "Developer settings → Personal access tokens."
          textSize = 13f
          setPadding(0, dp(6), 0, dp(8))
        }
    )

    etUser = inputField("Username GitHub", InputType.TYPE_CLASS_TEXT, prefs.getString("user", "") ?: "")
    etEmail =
        inputField("Email (untuk commit)", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, prefs.getString("email", "") ?: "")
    etToken =
        inputField(
            "Token",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            prefs.getString("token", "") ?: "",
        )
    root.addView(etUser)
    root.addView(etEmail)
    root.addView(etToken)
    root.addView(button("Simpan akun") { saveAccount() })

    root.addView(button("Daftar repo GitHub-ku (clone)") { listRepos() })
    root.addView(button("Pilih proyek lokal") { chooseProject() })
    projectView = TextView(this).apply { text = "Proyek: (belum dipilih)"; setPadding(0, dp(10), 0, dp(4)) }
    root.addView(projectView)

    root.addView(button("Status perubahan") { guarded { gitStatus() } })
    root.addView(button("Commit & Push") { askCommit() })
    root.addView(button("Pull (ambil terbaru)") { guarded { pull() } })
    root.addView(button("Jalankan build (GitHub Actions)") { guarded { dispatchBuild() } })
    root.addView(button("Status build terakhir") { guarded { showRun() } })
    root.addView(button("Unduh APK hasil build") { guarded { downloadApk() } })

    statusView = TextView(this).apply { textSize = 12f; setPadding(0, dp(10), 0, 0) }
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
      actionButtons.forEach { it.isEnabled = !busy }
      if (!busy) statusView.text = ""
    }
  }

  /** Menjalankan pekerjaan di background dengan tombol dinonaktifkan sementara. */
  private fun guarded(block: suspend () -> Unit) {
    saveAccount(silent = true)
    setBusy(true)
    scope.launch {
      try {
        block()
      } catch (t: Throwable) {
        log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
      } finally {
        setBusy(false)
      }
    }
  }

  private fun saveAccount(silent: Boolean = false) {
    prefs.edit()
        .putString("user", etUser.text.toString().trim())
        .putString("email", etEmail.text.toString().trim())
        .putString("token", etToken.text.toString().trim())
        .apply()
    if (!silent) log("Akun disimpan.")
  }

  private fun user() = prefs.getString("user", "").orEmpty()

  private fun token() = prefs.getString("token", "").orEmpty()

  private fun needAccount(): Boolean {
    if (user().isEmpty() || token().isEmpty()) {
      log("Isi username dan token dulu, lalu tekan 'Simpan akun'.")
      return false
    }
    return true
  }

  // ------------------------------------------------------------------------------- git & API

  private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

  /** Menjalankan git. Kembalian: (kode keluar, output). Token dikirim lewat header, tidak disimpan di repo. */
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
    p.inputStream.bufferedReader().use { r ->
      var line = r.readLine()
      while (line != null) {
        val t = line.trim()
        if (t.isNotEmpty()) {
          out.appendLine(t)
          status(t.takeLast(100))
          if (live && !t.contains('%')) log(t.take(200))
        }
        line = r.readLine()
      }
    }
    return p.waitFor() to out.toString().trim()
  }

  private fun api(method: String, path: String, body: String? = null): Pair<Int, String> {
    val c = URL("https://api.github.com$path").openConnection() as HttpURLConnection
    c.requestMethod = method
    c.setRequestProperty("Authorization", "Bearer ${token()}")
    c.setRequestProperty("Accept", "application/vnd.github+json")
    c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
    c.setRequestProperty("User-Agent", "AndroidStudioFork")
    c.connectTimeout = 15000
    c.readTimeout = 30000
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
    saveAccount(silent = true)
    if (!needAccount()) return
    setBusy(true)
    scope.launch {
      try {
        val (code, text) = api("GET", "/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator")
        if (code != 200) throw IOException("GitHub menjawab $code: ${text.take(150)}")
        val arr = JSONArray(text)
        val names = (0 until arr.length()).map { arr.getJSONObject(it).getString("full_name") }
        runOnUiThread {
          MaterialAlertDialogBuilder(this@GitHubActivity)
              .setTitle("Pilih repo untuk di-clone")
              .setItems(names.toTypedArray()) { _, i -> cloneRepo(names[i]) }
              .setNegativeButton(android.R.string.cancel, null)
              .show()
        }
      } catch (t: Throwable) {
        log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
      } finally {
        setBusy(false)
      }
    }
  }

  private fun cloneRepo(full: String) {
    setBusy(true)
    scope.launch {
      try {
        val name = full.substringAfter('/')
        val dest = File(Environment.PROJECTS_DIR, name)
        if (dest.exists()) {
          log("Folder $name sudah ada di StudioProjek, dipilih sebagai proyek.")
        } else {
          log("Clone $full ...")
          val (c, _) = git(Environment.PROJECTS_DIR, "clone https://github.com/$full.git ${q(dest.absolutePath)}", auth = true)
          if (c != 0) throw IOException("Clone gagal (kode $c)")
          log("Clone selesai: ${dest.absolutePath}")
        }
        selectProject(dest)
      } catch (t: Throwable) {
        log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
      } finally {
        setBusy(false)
      }
    }
  }

  private fun chooseProject() {
    val dirs = Environment.PROJECTS_DIR.listFiles()?.filter { File(it, ".git").exists() }?.sortedBy { it.name } ?: emptyList()
    if (dirs.isEmpty()) {
      log("Belum ada proyek git di ${Environment.PROJECTS_DIR.absolutePath}. Clone repo dulu, atau jalankan 'git init' di proyekmu.")
      return
    }
    MaterialAlertDialogBuilder(this)
        .setTitle("Pilih proyek")
        .setItems(dirs.map { it.name }.toTypedArray()) { _, i ->
          setBusy(true)
          scope.launch {
            try {
              selectProject(dirs[i])
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
    runOnUiThread { projectView.text = "Proyek: ${dir.name}" + (slug?.let { "  ($it)" } ?: "  (remote GitHub belum ada)") }
  }

  private fun needProject(): File? {
    val d = projectDir
    if (d == null) log("Pilih proyek lokal dulu.")
    return d
  }

  // ----------------------------------------------------------------------------------- git ops

  private fun gitStatus() {
    val d = needProject() ?: return
    val (_, out) = git(d, "status --short", live = false)
    log("Cabang: ${branch(d)}")
    log(if (out.isEmpty()) "Tidak ada perubahan." else out)
  }

  private fun askCommit() {
    if (needProject() == null) return
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
    val email = prefs.getString("email", "").orEmpty().ifEmpty { "${user()}@users.noreply.github.com" }
    git(d, "add -A")
    val msgFile = File(cacheDir, "commit_msg.txt").apply { writeText(message) }
    val (cc, co) = git(d, "-c user.name=${q(user())} -c user.email=${q(email)} commit -F ${q(msgFile.absolutePath)}")
    if (cc != 0 && !co.contains("nothing to commit")) throw IOException("Commit gagal")
    val br = branch(d)
    var (pc, po) = git(d, "push -u origin HEAD", auth = true)
    if (pc != 0 && (po.contains("fetch first") || po.contains("non-fast-forward") || po.contains("rejected"))) {
      log("Push ditolak karena ada commit baru di GitHub. Mengambil dulu (pull --rebase) ...")
      val (rc, _) = git(d, "pull --rebase origin $br", auth = true)
      if (rc != 0) throw IOException("Pull gagal. Ada konflik file? Selesaikan manual di Terminal.")
      val r = git(d, "push -u origin HEAD", auth = true)
      pc = r.first
    }
    log(if (pc == 0) "BERHASIL dikirim ke GitHub." else "Push gagal (cek token/izin repo).")
  }

  private fun pull() {
    val d = needProject() ?: return
    if (!needAccount()) return
    val (c, _) = git(d, "pull --rebase origin ${branch(d)}", auth = true)
    log(if (c == 0) "Pull selesai." else "Pull gagal (kode $c).")
  }

  // --------------------------------------------------------------------------- GitHub Actions

  private suspend fun dispatchBuild() {
    val d = needProject() ?: return
    val s = slug ?: run { log("Proyek ini belum punya remote GitHub."); return }
    if (!needAccount()) return
    val (code, text) = api("GET", "/repos/$s/actions/workflows")
    if (code != 200) throw IOException("GitHub menjawab $code: ${text.take(150)}")
    val arr = JSONObject(text).getJSONArray("workflows")
    val wfs = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.getString("state") == "active" }
    if (wfs.isEmpty()) throw IOException("Repo ini belum punya workflow aktif (.github/workflows).")
    val wf = wfs.firstOrNull { it.getString("path").contains("build", true) } ?: wfs[0]
    val br = branch(d)
    val (dc, dt) = api("POST", "/repos/$s/actions/workflows/${wf.getLong("id")}/dispatches", "{\"ref\":\"$br\"}")
    if (dc != 204) throw IOException("Gagal memulai build ($dc): ${dt.take(200)}. Pastikan workflow punya 'workflow_dispatch' dan perubahan sudah di-push.")
    log("Build '${wf.getString("name")}' dimulai di cabang $br. Memantau ...")
    delay(5000)
    watchRun(s)
  }

  private fun latestRun(s: String): JSONObject? {
    val (code, text) = api("GET", "/repos/$s/actions/runs?per_page=1")
    if (code != 200) throw IOException("GitHub menjawab $code")
    val arr = JSONObject(text).getJSONArray("workflow_runs")
    return if (arr.length() > 0) arr.getJSONObject(0) else null
  }

  private suspend fun watchRun(s: String) {
    repeat(80) {
      val lr = latestRun(s) ?: return
      val st = lr.getString("status")
      status("Build: $st")
      if (st == "completed") {
        val concl = lr.optString("conclusion")
        log("Build selesai: $concl")
        if (concl == "success") log("Tekan 'Unduh APK hasil build'.")
        return
      }
      delay(15000)
    }
    log("Masih berjalan. Cek lagi lewat 'Status build terakhir'.")
  }

  private fun showRun() {
    val s = slug ?: run { log("Pilih proyek dulu."); return }
    val lr = latestRun(s) ?: run { log("Belum ada build."); return }
    log("Build #${lr.getInt("run_number")}: ${lr.getString("status")} / ${lr.optString("conclusion", "-")} (${lr.getString("head_branch")})")
  }

  private fun downloadApk() {
    val s = slug ?: run { log("Pilih proyek dulu."); return }
    val (code, text) = api("GET", "/repos/$s/actions/runs?per_page=10&status=success")
    if (code != 200) throw IOException("GitHub menjawab $code")
    val runs = JSONObject(text).getJSONArray("workflow_runs")
    if (runs.length() == 0) throw IOException("Belum ada build yang sukses.")
    val runId = runs.getJSONObject(0).getLong("id")
    val (ac, at) = api("GET", "/repos/$s/actions/runs/$runId/artifacts")
    if (ac != 200) throw IOException("GitHub menjawab $ac")
    val arts = JSONObject(at).getJSONArray("artifacts")
    if (arts.length() == 0) throw IOException("Build ini tidak punya artifact (mungkin sudah kedaluwarsa).")
    val art = arts.getJSONObject(0)
    val name = art.getString("name")
    log("Mengunduh artifact $name ...")

    val first = URL(art.getString("archive_download_url")).openConnection() as HttpURLConnection
    first.instanceFollowRedirects = false
    first.setRequestProperty("Authorization", "Bearer ${token()}")
    first.setRequestProperty("User-Agent", "AndroidStudioFork")
    val loc = first.getHeaderField("Location") ?: throw IOException("Tautan unduhan tidak diberikan (kode ${first.responseCode})")
    val conn = URL(loc).openConnection() as HttpURLConnection
    conn.connectTimeout = 15000
    conn.readTimeout = 60000

    val dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
    val zip = File(dl, "$name.zip")
    var total = 0L
    conn.inputStream.use { ins ->
      zip.outputStream().use { out ->
        val buf = ByteArray(64 * 1024)
        while (true) {
          val r = ins.read(buf)
          if (r < 0) break
          out.write(buf, 0, r)
          total += r
          status("Unduh: ${total / 1048576} MB")
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
    if (apks == 0) log("Tidak ada file .apk di artifact. Zip tersimpan: ${zip.absolutePath}") else zip.delete()
    log("Buka file APK itu dari pengelola file untuk meng-install.")
  }
}
