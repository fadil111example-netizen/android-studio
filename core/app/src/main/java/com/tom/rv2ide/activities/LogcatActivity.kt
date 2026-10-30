package com.tom.rv2ide.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.adbwireless.AdbWireless
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Logcat langsung dari HP ini lewat ADB Wireless: filter level, filter aplikasi, pencarian teks,
 * warna per level, jeda/lanjut, bersihkan, dan salin.
 */
class LogcatActivity : AppCompatActivity() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val ui = Handler(Looper.getMainLooper())

  private val all = ArrayList<String>()
  private val pending = ArrayList<String>()
  private var shown = ArrayList<String>()
  private val maxLines = 6000

  @Volatile private var running = false
  private var closer: (() -> Unit)? = null
  private var minLevel = 0
  private var pkg: String? = null
  private var query = ""
  private var autoScroll = true
  private var refreshScheduled = false

  private lateinit var list: ListView
  private lateinit var statusView: TextView
  private lateinit var btnStart: MaterialButton
  private lateinit var btnLevel: MaterialButton
  private lateinit var btnApp: MaterialButton
  private lateinit var adapter: BaseAdapter

  private val levelNames = listOf("Verbose", "Debug", "Info", "Warning", "Error", "Fatal")

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    buildUi()
  }

  override fun onDestroy() {
    super.onDestroy()
    stop()
    scope.cancel()
  }

  // ------------------------------------------------------------------------------------- UI

  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

  private fun smallButton(label: String, outlined: Boolean, onClick: () -> Unit): MaterialButton {
    val b =
        if (outlined) MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        else MaterialButton(this)
    b.text = label
    b.textSize = 12f
    b.isAllCaps = false
    b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
    b.setOnClickListener { onClick() }
    return b
  }

  private fun levelColor(line: String): Int =
      when (line.firstOrNull()) {
        'E', 'F' -> Color.parseColor("#E5484D")
        'W' -> Color.parseColor("#F5A524")
        'I' -> Color.parseColor("#1FB86B")
        'D' -> Color.parseColor("#2E7DFF")
        else -> Color.parseColor("#8A938C")
      }

  private fun levelOf(line: String): Int =
      when (line.firstOrNull()) {
        'V' -> 0
        'D' -> 1
        'I' -> 2
        'W' -> 3
        'E' -> 4
        'F' -> 5
        else -> -1
      }

  private fun passes(line: String): Boolean {
    val lv = levelOf(line)
    if (lv in 0 until minLevel) return false
    return query.isEmpty() || line.contains(query, true)
  }

  private fun buildUi() {
    val logo =
        ImageView(this).apply {
          setImageResource(com.tom.rv2ide.resources.R.drawable.ic_app_logo)
          layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
    val titles =
        LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(dp(12), 0, 0, 0)
          addView(TextView(this@LogcatActivity).apply { text = "Logcat"; textSize = 22f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) })
          addView(TextView(this@LogcatActivity).apply { text = "Log aplikasi langsung dari HP ini"; textSize = 12f; setTextColor(Color.parseColor("#CCFFFFFF")) })
        }
    val header =
        LinearLayout(this).apply {
          orientation = LinearLayout.HORIZONTAL
          gravity = Gravity.CENTER_VERTICAL
          setPadding(dp(16), dp(14), dp(16), dp(14))
          background =
              GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#0B2B3A"), Color.parseColor("#0F7A52")))
                  .apply { cornerRadius = dp(22).toFloat() }
          addView(logo)
          addView(titles)
        }

    btnStart = smallButton("Mulai", false) { if (running) stop() else start() }
    btnLevel = smallButton("Level: Verbose", true) { pickLevel() }
    btnApp = smallButton("Semua aplikasi", true) { pickApp() }

    val search = EditText(this).apply {
      hint = "Cari teks / tag ..."
      maxLines = 1
      textSize = 14f
      addTextChangedListener(
          object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}

            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}

            override fun afterTextChanged(s: Editable?) {
              query = s?.toString()?.trim().orEmpty()
              refilter()
            }
          }
      )
    }

    statusView = TextView(this).apply { text = "Siap. Tekan Mulai (butuh ADB Wireless tersambung)."; textSize = 12f; alpha = 0.8f; setPadding(0, dp(4), 0, dp(4)) }

    val controls =
        LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(dp(12), dp(10), dp(12), dp(10))
          addView(
              LinearLayout(this@LogcatActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(btnStart)
                addView(smallButton("Bersihkan", true) { clearLog() })
                addView(smallButton("Salin", true) { copyLog() })
              }
          )
          addView(
              LinearLayout(this@LogcatActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
                addView(btnLevel)
                addView(btnApp)
              }
          )
          addView(search)
          addView(statusView)
        }
    val controlsCard =
        MaterialCardView(this).apply {
          radius = dp(20).toFloat()
          cardElevation = 0f
          strokeWidth = dp(1)
          strokeColor = Color.parseColor("#33007A52")
          layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
          addView(controls)
        }

    adapter =
        object : BaseAdapter() {
          override fun getCount() = shown.size

          override fun getItem(position: Int) = shown[position]

          override fun getItemId(position: Int) = position.toLong()

          override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv =
                (convertView as? TextView)
                    ?: TextView(this@LogcatActivity).apply {
                      typeface = android.graphics.Typeface.MONOSPACE
                      textSize = 11f
                      setPadding(dp(10), dp(2), dp(10), dp(2))
                    }
            val line = shown[position]
            tv.text = line
            tv.setTextColor(levelColor(line))
            return tv
          }
        }
    list =
        ListView(this).apply {
          this.adapter = this@LogcatActivity.adapter
          divider = null
          transcriptMode = ListView.TRANSCRIPT_MODE_DISABLED
          setOnScrollListener(
              object : android.widget.AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) {}

                override fun onScroll(view: android.widget.AbsListView?, first: Int, visible: Int, total: Int) {
                  autoScroll = first + visible >= total - 1
                }
              }
          )
        }

    val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(14), dp(12), dp(6)) }
    page.addView(header)
    page.addView(controlsCard)
    page.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
    setContentView(page)
  }

  // -------------------------------------------------------------------------------- filter

  private fun pickLevel() {
    MaterialAlertDialogBuilder(this)
        .setTitle("Level minimum")
        .setItems(levelNames.toTypedArray()) { _, i ->
          minLevel = i
          btnLevel.text = "Level: ${levelNames[i]}"
          refilter()
        }
        .show()
  }

  private fun pickApp() {
    scope.launch {
      val pm = packageManager
      val apps =
          pm.getInstalledApplications(0)
              .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
              .map { pm.getApplicationLabel(it).toString() to it.packageName }
              .sortedBy { it.first.lowercase() }
      runOnUiThread { showAppDialog(apps) }
    }
  }

  private fun showAppDialog(apps: List<Pair<String, String>>) {
    val labels = listOf("Semua aplikasi") + apps.map { "${it.first}\n${it.second}" }
    MaterialAlertDialogBuilder(this)
        .setTitle("Filter aplikasi")
        .setItems(labels.toTypedArray()) { _, i ->
          pkg = if (i == 0) null else apps[i - 1].second
          btnApp.text = if (i == 0) "Semua aplikasi" else apps[i - 1].first.take(18)
          if (running) {
            stop()
            ui.postDelayed({ start() }, 400)
          }
        }
        .show()
  }

  private fun refilter() {
    synchronized(all) {
      shown = ArrayList(all.filter { passes(it) })
    }
    adapter.notifyDataSetChanged()
    if (autoScroll && shown.isNotEmpty()) list.setSelection(shown.size - 1)
  }

  // ------------------------------------------------------------------------------- jalankan

  private fun status(msg: String) = runOnUiThread { statusView.text = msg }

  private fun setRunning(r: Boolean) = runOnUiThread { btnStart.text = if (r) "Berhenti" else "Mulai" }

  private fun start() {
    if (running) return
    if (!AdbWireless.isSupported) {
      status("Butuh Android 11+ dan ADB Wireless.")
      return
    }
    running = true
    setRunning(true)
    status("Menyambung ke ADB ...")
    scope.launch {
      try {
        var cmd = "logcat -v brief -T 300"
        val target = pkg
        if (target != null) {
          val pid = AdbWireless.shell(this@LogcatActivity, "pidof -s $target").trim()
          if (pid.matches(Regex("\\d+"))) {
            cmd += " --pid=$pid"
          } else {
            status("Aplikasi belum berjalan, menampilkan semua log.")
          }
        }
        val (reader, close) = AdbWireless.openShellReader(this@LogcatActivity, cmd)
        closer = close
        status("Berjalan ✓ ${pkg ?: "semua aplikasi"}")
        while (running) {
          val line = reader.readLine() ?: break
          addLine(line)
        }
      } catch (e: Throwable) {
        if (running) status("Berhenti: ${e.message ?: e.javaClass.simpleName}. Pastikan ADB Wireless tersambung (pairing lewat tombol Run).")
      } finally {
        running = false
        closer = null
        setRunning(false)
      }
    }
  }

  private fun stop() {
    running = false
    closer?.invoke()
  }

  private fun addLine(line: String) {
    synchronized(all) {
      all.add(line)
      if (all.size > maxLines) all.subList(0, all.size - maxLines).clear()
      if (passes(line)) pending.add(line)
    }
    scheduleRefresh()
  }

  private fun scheduleRefresh() {
    if (refreshScheduled) return
    refreshScheduled = true
    ui.postDelayed(
        {
          refreshScheduled = false
          synchronized(all) {
            if (pending.isNotEmpty()) {
              shown.addAll(pending)
              pending.clear()
              if (shown.size > maxLines) shown.subList(0, shown.size - maxLines).clear()
            }
          }
          adapter.notifyDataSetChanged()
          if (autoScroll && shown.isNotEmpty()) list.setSelection(shown.size - 1)
        },
        250,
    )
  }

  private fun clearLog() {
    synchronized(all) {
      all.clear()
      pending.clear()
      shown = ArrayList()
    }
    adapter.notifyDataSetChanged()
    scope.launch { try { AdbWireless.shell(this@LogcatActivity, "logcat -c") } catch (e: Throwable) { /* abaikan */ } }
    status("Log dibersihkan.")
  }

  private fun copyLog() {
    val text = synchronized(all) { shown.takeLast(2000).joinToString("\n") }
    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("logcat", text))
    status("${text.lines().size} baris disalin ke clipboard.")
  }
}
