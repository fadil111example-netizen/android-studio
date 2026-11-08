package com.tom.rv2ide.activities

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.web.WebRuntime
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Editor kode web: pewarnaan sintaks, saran otomatis saat mengetik, penutup tag/kurung/kutip,
 * indentasi otomatis, rapikan kode, dan pratinjau ke Chrome (muat ulang otomatis saat disimpan).
 */
class WebEditorActivity : AppCompatActivity() {

  companion object {
    const val EXTRA_DIR = "web.editor.dir"
  }

  private val ui = Handler(Looper.getMainLooper())
  private lateinit var root: File
  private var current: File? = null
  private var ext = "html"

  private lateinit var editor: EditText
  private lateinit var titleView: TextView
  private lateinit var suggestRow: LinearLayout

  private var busy = false
  private var dirty = false
  private var lastStart = 0
  private var lastBefore = 0
  private var lastCount = 0

  private val highlightTask = Runnable { highlight() }
  private val suggestTask = Runnable { updateSuggestions() }

  private val voidTags = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")

  private val dict: Map<String, List<String>> =
      mapOf(
          "html" to listOf(
              "html", "head", "body", "title", "meta", "link", "script", "style", "div", "span", "p", "a", "img", "ul", "ol", "li", "h1", "h2", "h3", "h4", "button",
              "input", "form", "label", "select", "option", "textarea", "table", "tr", "td", "th", "header", "footer", "nav", "main", "section", "article", "aside",
              "br", "hr", "canvas", "video", "audio", "iframe", "class", "id", "href", "src", "alt", "style", "type", "name", "value", "placeholder", "onclick",
              "rel", "content", "charset", "lang", "width", "height", "target", "action", "method", "for", "title", "viewport",
          ),
          "css" to listOf(
              "display", "flex", "grid", "margin", "padding", "border", "background", "color", "font-size", "font-family", "font-weight", "width", "height",
              "position", "absolute", "relative", "fixed", "top", "left", "right", "bottom", "z-index", "overflow", "text-align", "justify-content", "align-items",
              "gap", "border-radius", "box-shadow", "transition", "transform", "opacity", "cursor", "line-height", "max-width", "min-height", "flex-direction",
              "flex-wrap", "animation", "content", "@media", "linear-gradient", "place-items", "grid-template-columns", "none", "center", "space-between",
              "transparent", "inherit", "important", "hover", "before", "after",
          ),
          "js" to listOf(
              "const", "let", "var", "function", "return", "if", "else", "for", "while", "class", "new", "import", "export", "from", "async", "await", "try",
              "catch", "throw", "switch", "case", "break", "continue", "default", "typeof", "this", "null", "undefined", "true", "false", "document", "window",
              "console.log", "querySelector", "querySelectorAll", "getElementById", "addEventListener", "fetch", "JSON.stringify", "JSON.parse", "Math.random",
              "setTimeout", "setInterval", "require", "module.exports", "process.env", "textContent", "innerHTML", "classList", "localStorage", "Promise",
          ),
          "py" to listOf(
              "def", "class", "import", "from", "return", "print", "len", "range", "input", "open", "with", "try", "except", "finally", "for", "while", "if",
              "elif", "else", "lambda", "self", "__init__", "__name__", "dict", "list", "str", "int", "float", "append", "json", "os", "sys", "None", "True",
              "False", "pass", "break", "continue", "yield", "raise", "global", "async", "await", "http.server", "environ",
          ),
          "php" to listOf(
              "echo", "function", "foreach", "isset", "empty", "array", "require", "include", "\$_GET", "\$_POST", "\$_SERVER", "\$_SESSION", "json_encode",
              "json_decode", "header", "session_start", "class", "public", "private", "protected", "static", "return", "if", "else", "elseif", "while", "for",
              "null", "true", "false", "count", "strlen", "str_replace", "explode", "implode", "file_get_contents", "PDO", "namespace", "use",
          ),
          "json" to listOf("true", "false", "null"),
      )

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    root = File(intent.getStringExtra(EXTRA_DIR) ?: "/")
    buildUi()
    val first = listOf("public/index.html", "index.html", "server.js", "app.py", "index.php").map { File(root, it) }.firstOrNull { it.isFile }
        ?: files().firstOrNull()
    if (first != null) openFile(first) else titleView.text = "Belum ada file. Tekan 'File' lalu 'File baru'."
  }

  override fun onPause() {
    super.onPause()
    saveIfDirty()
  }

  // ------------------------------------------------------------------------------------- UI

  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

  private fun small(label: String, onClick: () -> Unit) =
      MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minimumWidth = 0
        minWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginEnd = dp(6) }
        setOnClickListener { onClick() }
      }

  private fun chip(label: String, onClick: () -> Unit) =
      TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(Color.parseColor("#E6EDE8"))
        typeface = android.graphics.Typeface.MONOSPACE
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.parseColor("#26FFFFFF")) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        setOnClickListener { onClick() }
      }

  private fun buildUi() {
    titleView = TextView(this).apply {
      textSize = 14f
      maxLines = 1
      setTextColor(Color.WHITE)
      setTypeface(typeface, android.graphics.Typeface.BOLD)
      layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    val bar = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(12), dp(8), dp(8), dp(8))
      setBackgroundColor(Color.parseColor("#0B2B3A"))
      addView(titleView)
      addView(small("File") { showFiles() })
      addView(small("Simpan") { save(true) })
      addView(small("Rapikan") { format() })
      addView(small("▶ Lihat") { preview() })
    }

    editor = EditText(this).apply {
      typeface = android.graphics.Typeface.MONOSPACE
      textSize = 13f
      setTextColor(Color.parseColor("#E6EDE8"))
      setBackgroundColor(Color.parseColor("#0F1511"))
      setPadding(dp(12), dp(10), dp(12), dp(10))
      gravity = Gravity.TOP or Gravity.START
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
      setHorizontallyScrolling(true)
      isVerticalScrollBarEnabled = true
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
      addTextChangedListener(
          object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}

            override fun onTextChanged(s: CharSequence?, st: Int, before: Int, count: Int) {
              lastStart = st
              lastBefore = before
              lastCount = count
            }

            override fun afterTextChanged(s: Editable?) {
              if (busy || s == null) return
              dirty = true
              if (lastBefore == 0 && lastCount == 1) autoEdit(s, lastStart)
              ui.removeCallbacks(highlightTask)
              ui.postDelayed(highlightTask, 350)
              ui.removeCallbacks(suggestTask)
              ui.postDelayed(suggestTask, 120)
            }
          }
      )
    }

    suggestRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
    val suggestScroll = HorizontalScrollView(this).apply {
      isHorizontalScrollBarEnabled = false
      setBackgroundColor(Color.parseColor("#14201A"))
      addView(suggestRow)
    }

    val symbols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
    listOf("Tab", "<", ">", "/", "{", "}", "(", ")", "[", "]", "\"", "'", ";", ":", "=", "$", "#", "!", ".").forEach { s ->
      symbols.addView(chip(s) { insertSymbol(if (s == "Tab") "  " else s) })
    }
    val symbolScroll = HorizontalScrollView(this).apply {
      isHorizontalScrollBarEnabled = false
      setBackgroundColor(Color.parseColor("#0B2B3A"))
      addView(symbols)
    }

    val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    page.addView(bar)
    page.addView(editor)
    page.addView(suggestScroll)
    page.addView(symbolScroll)
    setContentView(page)
  }

  // ---------------------------------------------------------------------------------- berkas

  private fun files(): List<File> =
      root.walkTopDown()
          .onEnter { it.name != "node_modules" && it.name != ".git" }
          .filter { it.isFile && it.name != ".acsweb.json" && it.length() < 2_000_000 }
          .take(400)
          .toList()
          .sortedBy { it.relativeTo(root).path }

  private fun showFiles() {
    saveIfDirty()
    val list = files()
    val labels = listOf("＋ File baru") + list.map { it.relativeTo(root).path }
    MaterialAlertDialogBuilder(this)
        .setTitle("File proyek")
        .setItems(labels.toTypedArray()) { _, i -> if (i == 0) newFile() else openFile(list[i - 1]) }
        .show()
  }

  private fun newFile() {
    val input = EditText(this).apply { hint = "mis. public/halaman.html"; setPadding(dp(20), dp(12), dp(20), dp(12)) }
    MaterialAlertDialogBuilder(this)
        .setTitle("File baru")
        .setView(input)
        .setPositiveButton("Buat") { _, _ ->
          val rel = input.text.toString().trim().trimStart('/')
          if (rel.isEmpty() || rel.contains("..")) return@setPositiveButton
          val f = File(root, rel)
          f.parentFile?.mkdirs()
          if (!f.exists()) f.writeText("")
          openFile(f)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private fun openFile(f: File) {
    saveIfDirty()
    current = f
    ext = f.extension.lowercase().let { if (it == "htm") "html" else if (it == "mjs" || it == "jsx") "js" else it }
    busy = true
    editor.setText(try { f.readText() } catch (e: Throwable) { "" })
    busy = false
    dirty = false
    titleView.text = f.relativeTo(root).path
    highlight()
    updateSuggestions()
  }

  private fun saveIfDirty() {
    if (dirty) save(false)
  }

  private fun save(show: Boolean) {
    val f = current ?: return
    try {
      f.writeText(editor.text.toString())
      dirty = false
      if (show) {
        titleView.text = "Tersimpan ✓"
        ui.postDelayed({ titleView.text = f.relativeTo(root).path }, 1200)
      }
    } catch (e: Throwable) {
      titleView.text = "Gagal menyimpan: ${e.message}"
    }
  }

  private fun preview() {
    save(false)
    if (!WebRuntime.isRunning()) {
      titleView.text = "Jalankan server dulu di Web Studio"
      return
    }
    val i = Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${WebRuntime.port}"))
    try {
      startActivity(Intent(i).setPackage("com.android.chrome"))
    } catch (e: Throwable) {
      try { startActivity(i) } catch (e2: Throwable) { /* abaikan */ }
    }
  }

  // --------------------------------------------------------------------------- penulisan pintar

  private fun insertSymbol(sym: String) {
    val t = editor.text
    val pos = editor.selectionStart.coerceAtLeast(0)
    t.insert(pos, sym)
  }

  private fun isWeb() = ext == "html" || ext == "php"

  private fun autoEdit(s: Editable, pos: Int) {
    if (pos < 0 || pos >= s.length) return
    val ch = s[pos]
    val close =
        when (ch) {
          '(' -> ")"
          '[' -> "]"
          '{' -> "}"
          '"' -> "\""
          '\'' -> "'"
          else -> null
        }
    if (close != null) {
      if (ch == '"' || ch == '\'') {
        val prev = if (pos > 0) s[pos - 1] else ' '
        val next = if (pos + 1 < s.length) s[pos + 1] else ' '
        if (prev.isLetterOrDigit() || next == ch) return
      }
      busy = true
      s.insert(pos + 1, close)
      editor.setSelection(pos + 1)
      busy = false
      return
    }
    if (ch == '>' && isWeb()) {
      val lt = s.toString().lastIndexOf('<', pos)
      if (lt >= 0 && pos > lt + 1) {
        val inner = s.substring(lt + 1, pos)
        val m = Regex("^[A-Za-z][A-Za-z0-9-]*").find(inner)
        if (m != null && !inner.contains('>') && !inner.endsWith("/") && m.value.lowercase() !in voidTags) {
          busy = true
          s.insert(pos + 1, "</${m.value}>")
          editor.setSelection(pos + 1)
          busy = false
        }
      }
      return
    }
    if (ch == '\n') {
      val text = s.toString()
      val ls = text.lastIndexOf('\n', pos - 1) + 1
      val prevLine = text.substring(ls, pos)
      val indent = prevLine.takeWhile { it == ' ' || it == '\t' }
      val t = prevLine.trimEnd()
      val unit = if (ext == "py") "    " else "  "
      val opens =
          t.endsWith("{") || t.endsWith("(") || t.endsWith("[") || (ext == "py" && t.endsWith(":")) ||
              (isWeb() && Regex("<([A-Za-z][A-Za-z0-9-]*)(\\s[^>]*)?>$").find(t)?.groupValues?.get(1)?.lowercase()?.let { it !in voidTags && !t.endsWith("/>") } == true)
      val extra = if (opens) unit else ""
      val next = if (pos + 1 < text.length) text[pos + 1] else ' '
      busy = true
      if (opens && (next == '}' || next == ']' || next == ')' || next == '<')) {
        s.insert(pos + 1, indent + extra + "\n" + indent)
        editor.setSelection(pos + 1 + indent.length + extra.length)
      } else {
        s.insert(pos + 1, indent + extra)
        editor.setSelection(pos + 1 + indent.length + extra.length)
      }
      busy = false
    }
  }

  // --------------------------------------------------------------------------------- saran

  private fun updateSuggestions() {
    suggestRow.removeAllViews()
    val s = editor.text
    val cur = editor.selectionStart
    if (cur <= 0 || cur > s.length) return
    var i = cur
    while (i > 0 && (s[i - 1].isLetterOrDigit() || s[i - 1] == '_' || s[i - 1] == '-' || s[i - 1] == '$' || s[i - 1] == '.' || s[i - 1] == '@')) i--
    val prefix = s.substring(i, cur)
    val afterLt = i > 0 && s[i - 1] == '<' && isWeb()
    if (prefix.length < (if (afterLt) 1 else 2)) return

    val pool = LinkedHashSet<String>()
    pool.addAll(dict[ext] ?: dict["js"]!!)
    if (ext == "php" || ext == "html") pool.addAll(dict["html"]!!)
    if (ext == "html") { pool.addAll(dict["css"]!!); pool.addAll(dict["js"]!!) }
    if (s.length < 120_000) {
      Regex("[A-Za-z_$][A-Za-z0-9_$-]{2,}").findAll(s).take(500).forEach { pool.add(it.value) }
    }
    val cands = pool.filter { it.startsWith(prefix, true) && !it.equals(prefix, true) }.take(14)
    cands.forEach { c -> suggestRow.addView(chip(c) { applySuggestion(i, cur, c, afterLt) }) }
  }

  private fun applySuggestion(start: Int, end: Int, cand: String, afterLt: Boolean) {
    val t = editor.text
    var ins = cand
    var back = 0
    if (afterLt) {
      if (cand.lowercase() in voidTags) {
        ins = "$cand "
      } else {
        ins = "$cand></$cand>"
        back = cand.length + 3
      }
    }
    busy = true
    t.replace(start, end, ins)
    editor.setSelection(start + ins.length - back)
    busy = false
    dirty = true
    ui.removeCallbacks(highlightTask)
    ui.postDelayed(highlightTask, 200)
    updateSuggestions()
  }

  // ------------------------------------------------------------------------------- pewarnaan

  private class Rule(val regex: Regex, val color: Int)

  private val cKeyword = Color.parseColor("#C586C0")
  private val cString = Color.parseColor("#E9A56E")
  private val cNumber = Color.parseColor("#B5CEA8")
  private val cComment = Color.parseColor("#6A9955")
  private val cTag = Color.parseColor("#4FC1FF")
  private val cAttr = Color.parseColor("#9CDCFE")
  private val cVar = Color.parseColor("#F2D56B")

  private fun rulesFor(e: String): List<Rule> {
    val str = Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'")
    val num = Regex("\\b\\d+(?:\\.\\d+)?\\b")
    return when (e) {
      "html" -> listOf(
          Rule(Regex("</?[A-Za-z][A-Za-z0-9-]*|/?>"), cTag), Rule(Regex("\\s[A-Za-z-:@]+(?==)"), cAttr), Rule(str, cString),
          Rule(Regex("<!--[\\s\\S]*?-->"), cComment), Rule(Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE), cKeyword),
      )
      "css" -> listOf(
          Rule(Regex("[.#][A-Za-z_][\\w-]*"), cVar), Rule(Regex("[A-Za-z-]+(?=\\s*:)"), cAttr), Rule(num, cNumber),
          Rule(Regex("#[0-9a-fA-F]{3,8}\\b"), cNumber), Rule(str, cString), Rule(Regex("/\\*[\\s\\S]*?\\*/"), cComment),
      )
      "js" -> listOf(
          Rule(Regex("\\b(?:const|let|var|function|return|if|else|for|while|class|new|import|export|from|async|await|try|catch|throw|switch|case|break|continue|default|typeof|this|null|undefined|true|false)\\b"), cKeyword),
          Rule(num, cNumber), Rule(str, cString), Rule(Regex("`(?:\\\\.|[^`\\\\])*`"), cString), Rule(Regex("//.*|/\\*[\\s\\S]*?\\*/"), cComment),
      )
      "py" -> listOf(
          Rule(Regex("\\b(?:def|class|return|if|elif|else|for|while|import|from|as|try|except|finally|with|lambda|pass|break|continue|in|is|not|and|or|None|True|False|self|print|raise|yield|global|async|await)\\b"), cKeyword),
          Rule(num, cNumber), Rule(Regex("\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''"), cString), Rule(str, cString), Rule(Regex("#.*"), cComment),
      )
      "php" -> listOf(
          Rule(Regex("<\\?php|\\?>"), cKeyword), Rule(Regex("\\b(?:echo|function|return|if|else|elseif|foreach|for|while|class|new|public|private|protected|static|require|require_once|include|array|null|true|false|namespace|use)\\b"), cKeyword),
          Rule(Regex("\\$[A-Za-z_]\\w*"), cVar), Rule(num, cNumber), Rule(str, cString), Rule(Regex("//.*|#.*|/\\*[\\s\\S]*?\\*/"), cComment),
      )
      "json" -> listOf(Rule(Regex("\\b(?:true|false|null)\\b"), cKeyword), Rule(num, cNumber), Rule(str, cString))
      else -> emptyList()
    }
  }

  private fun highlight() {
    val s = editor.text
    if (s.length > 150_000) return
    busy = true
    try {
      s.getSpans(0, s.length, ForegroundColorSpan::class.java).forEach { s.removeSpan(it) }
      for (rule in rulesFor(ext)) {
        rule.regex.findAll(s).forEach { m -> s.setSpan(ForegroundColorSpan(rule.color), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
      }
    } finally {
      busy = false
    }
  }

  // ----------------------------------------------------------------------------------- rapikan

  private fun format() {
    val src = editor.text.toString()
    val out =
        try {
          when (ext) {
            "json" -> if (src.trimStart().startsWith("[")) JSONArray(src).toString(2) else JSONObject(src).toString(2)
            "html" -> formatHtml(src)
            "css", "js", "php" -> formatBraces(src)
            else -> src.lines().joinToString("\n") { it.trimEnd().replace("\t", "    ") }
          }
        } catch (e: Throwable) {
          titleView.text = "Tidak bisa dirapikan: ${e.message}"
          return
        }
    if (out == src) return
    val sel = editor.selectionStart
    busy = true
    editor.setText(out)
    editor.setSelection(sel.coerceIn(0, out.length))
    busy = false
    dirty = true
    highlight()
    titleView.text = "Dirapikan ✓"
  }

  private fun formatBraces(src: String): String {
    var depth = 0
    val sb = StringBuilder()
    for (raw in src.lines()) {
      val line = raw.trim()
      if (line.isEmpty()) {
        sb.append('\n')
        continue
      }
      val lead = line.takeWhile { it == '}' || it == ')' || it == ']' }.length
      sb.append("  ".repeat((depth - lead).coerceAtLeast(0))).append(line).append('\n')
      val opens = line.count { it == '{' || it == '(' || it == '[' }
      val closes = line.count { it == '}' || it == ')' || it == ']' }
      depth = (depth + opens - closes).coerceAtLeast(0)
    }
    return sb.toString().trimEnd('\n') + "\n"
  }

  private fun formatHtml(src: String): String {
    var depth = 0
    val sb = StringBuilder()
    val open = Regex("<([A-Za-z][A-Za-z0-9-]*)(?:\\s[^>]*)?>")
    for (raw in src.lines()) {
      val line = raw.trim()
      if (line.isEmpty()) {
        sb.append('\n')
        continue
      }
      val opens = open.findAll(line).count { it.groupValues[1].lowercase() !in voidTags && !it.value.endsWith("/>") }
      val closes = Regex("</[A-Za-z]").findAll(line).count()
      val lead = line.startsWith("</")
      if (lead) depth = (depth - 1).coerceAtLeast(0)
      sb.append("  ".repeat(depth)).append(line).append('\n')
      depth = (depth + opens - closes + (if (lead) 1 else 0)).coerceAtLeast(0)
    }
    return sb.toString().trimEnd('\n') + "\n"
  }
}
