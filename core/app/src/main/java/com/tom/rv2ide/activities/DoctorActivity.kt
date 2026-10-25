package com.tom.rv2ide.activities

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.tom.rv2ide.adbwireless.AdbWireless
import com.tom.rv2ide.app.IDEApplication
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Dokter Proyek: memeriksa kesehatan sistem (JDK, SDK, NDK, Git, ADB, ruang) dan membantu
 * memperbaiki proyek: menyesuaikan Gradle wrapper dengan plugin Android, memilih versi Gradle manual
 * dengan satu ketukan, membersihkan cache build, dan membuat local.properties.
 */
class DoctorActivity : AppCompatActivity() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var project: File? = null

  private lateinit var healthView: TextView
  private lateinit var projectView: TextView
  private lateinit var resultView: TextView

  /** AGP (mayor.minor) -> Gradle minimum menurut tabel kompatibilitas resmi Android. */
  private val minGradle =
      mapOf(
          "9.0" to "9.1.0", "8.13" to "8.13", "8.12" to "8.13", "8.11" to "8.13", "8.10" to "8.11.1",
          "8.9" to "8.11.1", "8.8" to "8.10.2", "8.7" to "8.9", "8.6" to "8.7", "8.5" to "8.7",
          "8.4" to "8.6", "8.3" to "8.4", "8.2" to "8.2", "8.1" to "8.0", "8.0" to "8.0",
          "7.4" to "7.5", "7.3" to "7.4", "7.2" to "7.3.3", "7.1" to "7.2", "7.0" to "7.0",
          "4.2" to "6.7.1", "4.1" to "6.5", "4.0" to "6.1.1", "3.6" to "5.6.4", "3.5" to "5.4.1",
      )

  private val gradleChoices =
      listOf("9.1.0", "8.13", "8.11.1", "8.10.2", "8.9", "8.7", "8.6", "8.4", "8.2", "8.0", "7.6.4", "7.5", "7.4.2", "7.3.3", "7.2", "6.9.4", "6.7.1", "6.5")

  private val agpPatterns =
      listOf(
          Regex("""com\.android\.tools\.build:gradle:([0-9]+\.[0-9]+(?:\.[0-9]+)?)"""),
          Regex("""com\.android\.(?:application|library)["']\s*\)?\s*version\s*\(?\s*["']([0-9][^"']*)["']"""),
          Regex("""(?m)^\s*(?:agp|android-gradle-plugin|androidGradlePlugin|androidGradle)\s*=\s*["']([0-9][^"']*)["']"""),
      )

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    buildUi()
    runHealth()
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
    return b
  }

  private fun card(title: String, vararg views: View): MaterialCardView {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
    col.addView(TextView(this).apply { text = title; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
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

  private fun textBlock() = TextView(this).apply { textSize = 13f; setPadding(0, dp(8), 0, 0) }

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
          addView(TextView(this@DoctorActivity).apply { text = "Dokter Proyek"; textSize = 24f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) })
          addView(TextView(this@DoctorActivity).apply { text = "Periksa sistem · perbaiki Gradle · bersihkan"; textSize = 13f; setTextColor(Color.parseColor("#CCFFFFFF")) })
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
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(16)) }
    content.addView(buildHeader())

    healthView = textBlock()
    content.addView(card("Kesehatan sistem", healthView, actionButton("Periksa ulang", true) { runHealth() }))

    projectView = textBlock().apply { text = "Belum ada proyek dipilih." }
    resultView = textBlock()
    content.addView(
        card(
            "Proyek",
            actionButton("Pilih proyek", false) { chooseProject() },
            projectView,
            actionButton("Sesuaikan Gradle otomatis", false) { autoFixGradle() },
            actionButton("Pilih versi Gradle manual", true) { pickGradle() },
            actionButton("Kembalikan wrapper dari cadangan", true) { restoreWrapper() },
            actionButton("Buat local.properties (sdk.dir)", true) { makeLocalProps() },
            actionButton("Bersihkan cache build", true) { confirmClean() },
            resultView,
        )
    )
    setContentView(ScrollView(this).apply { addView(content) })
  }

  private fun colored(text: String, color: Int): CharSequence {
    val s = SpannableString(text + "\n")
    s.setSpan(ForegroundColorSpan(color), 0, s.length, 0)
    return s
  }

  private fun fmt(b: Long): String =
      if (b >= 1L shl 30) String.format(Locale.US, "%.1f GB", b / 1073741824.0) else String.format(Locale.US, "%d MB", b / 1048576)

  private fun result(msg: String) = runOnUiThread { resultView.text = msg }

  // ---------------------------------------------------------------------------- kesehatan

  private fun runHealth() {
    healthView.text = "Memeriksa ..."
    scope.launch {
      val sb = SpannableStringBuilder()
      fun line(ok: Boolean?, text: String) {
        val (sym, col) =
            when (ok) {
              true -> "✓" to Color.parseColor("#1FB86B")
              false -> "✗" to Color.parseColor("#E5484D")
              null -> "–" to Color.parseColor("#8A938C")
            }
        sb.append(colored("$sym $text", col))
      }

      // JDK
      val java = File(Environment.PREFIX, "lib/jvm").listFiles()?.map { File(it, "bin/java") }?.firstOrNull { it.isFile }
      if (java == null) {
        line(false, "JDK belum terpasang (buka Instal Komponen)")
      } else {
        val out =
            try {
              val pb = ProcessBuilder(Environment.BASH_SHELL.absolutePath, "-c", "'${java.absolutePath}' -version 2>&1")
              pb.environment().putAll(HashMap(TermuxShellEnvironment().getEnvironment(IDEApplication.instance, false)))
              pb.redirectErrorStream(true)
              val p = pb.start()
              val text = p.inputStream.bufferedReader().readLine().orEmpty()
              p.waitFor(20, TimeUnit.SECONDS)
              text
            } catch (e: Throwable) {
              ""
            }
        line(out.contains("version"), if (out.isEmpty()) "JDK ada tetapi java tidak bisa dijalankan" else "JDK: $out")
      }

      // SDK
      val platforms = File(Environment.ANDROID_HOME, "platforms").listFiles()?.filter { File(it, "android.jar").isFile }?.map { it.name } ?: emptyList()
      line(platforms.isNotEmpty(), if (platforms.isNotEmpty()) "SDK platform: ${platforms.sorted().joinToString()}" else "SDK platform belum ada")
      val bt = File(Environment.ANDROID_HOME, "build-tools").listFiles()?.map { it.name } ?: emptyList()
      line(bt.isNotEmpty(), if (bt.isNotEmpty()) "Build-tools: ${bt.sorted().joinToString()}" else "Build-tools belum ada")

      // NDK / CMake (opsional)
      val ndk = File(Environment.ANDROID_HOME, "ndk").listFiles()?.map { it.name } ?: emptyList()
      line(if (ndk.isEmpty()) null else true, if (ndk.isEmpty()) "NDK: belum terpasang (opsional)" else "NDK: ${ndk.sorted().joinToString()}")
      val cm = File(Environment.ANDROID_HOME, "cmake").listFiles()?.map { it.name } ?: emptyList()
      line(if (cm.isEmpty()) null else true, if (cm.isEmpty()) "CMake: belum terpasang (opsional)" else "CMake: ${cm.sorted().joinToString()}")

      // Git, GitHub, ADB
      line(File(Environment.BIN_DIR, "git").exists(), if (File(Environment.BIN_DIR, "git").exists()) "Git terpasang" else "Git belum terpasang")
      val token = getSharedPreferences("github_prefs", MODE_PRIVATE).getString("token", "").orEmpty()
      line(if (token.isEmpty()) null else true, if (token.isEmpty()) "GitHub: akun belum diisi (opsional)" else "GitHub: token tersimpan")
      if (AdbWireless.isSupported) {
        val paired = AdbWireless.isPaired(this@DoctorActivity)
        line(if (paired) true else null, if (paired) "ADB Wireless: sudah pairing" else "ADB Wireless: belum pairing (opsional)")
      }

      // Ruang
      val free = Environment.HOME.usableSpace
      line(free > 2L * 1073741824, "Ruang kosong: ${fmt(free)}")
      runOnUiThread { healthView.text = sb }
    }
  }

  // ----------------------------------------------------------------------------------- proyek

  private fun chooseProject() {
    val dirs =
        Environment.PROJECTS_DIR.listFiles()
            ?.filter { it.isDirectory && (File(it, "settings.gradle").exists() || File(it, "settings.gradle.kts").exists() || File(it, "build.gradle").exists() || File(it, "build.gradle.kts").exists()) }
            ?.sortedBy { it.name } ?: emptyList()
    if (dirs.isEmpty()) {
      result("Tidak ada proyek Gradle di ${Environment.PROJECTS_DIR.absolutePath}.")
      return
    }
    MaterialAlertDialogBuilder(this)
        .setTitle("Pilih proyek")
        .setItems(dirs.map { it.name }.toTypedArray()) { _, i ->
          project = dirs[i]
          showProjectInfo()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private fun cmp(a: String, b: String): Int {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
      val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
      if (d != 0) return d
    }
    return 0
  }

  private fun findAgp(dir: File): String? {
    var best: String? = null
    dir.walkTopDown()
        .maxDepth(3)
        .onEnter { !it.name.equals("build", true) && !it.name.startsWith(".git") }
        .filter { it.isFile && it.length() < 512 * 1024 && (it.name.endsWith(".gradle") || it.name.endsWith(".gradle.kts") || it.name.endsWith(".toml")) }
        .forEach { f ->
          val text = try { f.readText() } catch (e: Throwable) { return@forEach }
          for (p in agpPatterns) for (m in p.findAll(text)) {
            val v = m.groupValues[1]
            val cur = best
            if (cur == null || cmp(v, cur) > 0) best = v
          }
        }
    return best
  }

  private fun wrapperFile(dir: File) = File(dir, "gradle/wrapper/gradle-wrapper.properties")

  private fun wrapperVersion(dir: File): String? {
    val f = wrapperFile(dir)
    if (!f.isFile) return null
    return Regex("""gradle-([0-9][0-9.]*)(?:-(?:rc|milestone)-[0-9]+)?-(?:bin|all)\.zip""").find(f.readText())?.groupValues?.get(1)
  }

  private fun recommended(agp: String?): String? {
    val mm = agp?.let { Regex("""^(\d+)\.(\d+)""").find(it) } ?: return null
    return minGradle["${mm.groupValues[1]}.${mm.groupValues[2]}"]
  }

  private fun showProjectInfo() {
    val d = project ?: return
    scope.launch {
      val agp = findAgp(d)
      val cur = wrapperVersion(d)
      val rec = recommended(agp)
      val ndkWanted =
          d.walkTopDown().maxDepth(3).filter { it.isFile && (it.name == "build.gradle" || it.name == "build.gradle.kts") }
              .mapNotNull { Regex("""ndkVersion\s*=?\s*["']([^"']+)["']""").find(it.readText())?.groupValues?.get(1) }.firstOrNull()
      val ndkHave = ndkWanted?.let { File(Environment.ANDROID_HOME, "ndk/$it").isDirectory }
      val sb = StringBuilder()
      sb.appendLine(d.name)
      sb.appendLine("Plugin Android (AGP): ${agp ?: "tidak terdeteksi"}")
      sb.appendLine("Gradle wrapper: ${cur ?: "tidak ada"}")
      if (rec != null) sb.appendLine("Gradle minimal untuk AGP ini: $rec")
      if (cur != null && rec != null) sb.appendLine(if (cmp(cur, rec) >= 0) "Status: cocok ✓" else "Status: Gradle terlalu lama ✗ (tekan 'Sesuaikan Gradle otomatis')")
      if (ndkWanted != null) sb.appendLine("NDK diminta: $ndkWanted " + if (ndkHave == true) "(terpasang ✓)" else "(belum terpasang ✗, pasang via Instal Komponen)")
      runOnUiThread { projectView.text = sb.toString().trim() }
    }
  }

  private fun needProject(): File? {
    val d = project
    if (d == null) result("Pilih proyek dulu.")
    return d
  }

  private fun writeWrapper(d: File, version: String): String {
    val wp = wrapperFile(d)
    if (!wp.isFile) return "Proyek ini tidak punya gradle-wrapper.properties."
    val text = wp.readText()
    val m = Regex("""gradle-[0-9][0-9.]*(?:-(?:rc|milestone)-[0-9]+)?-(?:bin|all)\.zip""").find(text) ?: return "distributionUrl tidak ditemukan di wrapper."
    wp.copyTo(File(wp.parentFile, "gradle-wrapper.properties.bak"), overwrite = true)
    val updated =
        text.replace(m.value, "gradle-$version-bin.zip").lines().filterNot { it.trim().startsWith("distributionSha256Sum") }.joinToString("\n")
    wp.writeText(updated)
    return "Gradle wrapper diubah ke $version (cadangan: gradle-wrapper.properties.bak). Sync ulang proyek di editor."
  }

  private fun autoFixGradle() {
    val d = needProject() ?: return
    scope.launch {
      val rec = recommended(findAgp(d))
      val cur = wrapperVersion(d)
      if (rec == null) {
        result("Versi AGP proyek tidak terdeteksi. Pakai 'Pilih versi Gradle manual'.")
        return@launch
      }
      if (cur != null && cmp(cur, rec) >= 0) {
        result("Gradle $cur sudah cukup untuk AGP ini (minimal $rec). Tidak ada yang diubah.")
      } else {
        result(writeWrapper(d, rec))
      }
      showProjectInfo()
    }
  }

  private fun pickGradle() {
    val d = needProject() ?: return
    MaterialAlertDialogBuilder(this)
        .setTitle("Pilih versi Gradle")
        .setItems(gradleChoices.toTypedArray()) { _, i ->
          scope.launch {
            result(writeWrapper(d, gradleChoices[i]))
            showProjectInfo()
          }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private fun restoreWrapper() {
    val d = needProject() ?: return
    val bak = File(wrapperFile(d).parentFile, "gradle-wrapper.properties.bak")
    if (!bak.isFile) {
      result("Tidak ada cadangan wrapper.")
      return
    }
    bak.copyTo(wrapperFile(d), overwrite = true)
    result("Wrapper dikembalikan dari cadangan.")
    showProjectInfo()
  }

  private fun makeLocalProps() {
    val d = needProject() ?: return
    val f = File(d, "local.properties")
    if (f.exists() && f.readText().contains("sdk.dir")) {
      result("local.properties sudah berisi sdk.dir.")
      return
    }
    f.appendText("sdk.dir=${Environment.ANDROID_HOME.absolutePath}\n")
    result("local.properties dibuat: sdk.dir=${Environment.ANDROID_HOME.absolutePath}")
  }

  private fun confirmClean() {
    val d = needProject() ?: return
    MaterialAlertDialogBuilder(this)
        .setTitle("Bersihkan cache build?")
        .setMessage("Folder build/ dan .gradle/ di proyek '${d.name}' akan dihapus. Kode sumber tidak disentuh. Build berikutnya akan lebih lama.")
        .setPositiveButton("Bersihkan") { _, _ ->
          scope.launch {
            var freed = 0L
            for (name in listOf("build", "app/build", ".gradle")) {
              val t = File(d, name)
              if (t.exists()) {
                freed += t.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                t.deleteRecursively()
              }
            }
            result("Selesai. Ruang dibebaskan: ${fmt(freed)}.")
          }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }
}
