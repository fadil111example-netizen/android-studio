package com.tom.rv2ide.web

import java.io.File
import org.json.JSONObject

/**
 * Mendeteksi jenis proyek web, apakah punya backend, dan perintah untuk menjalankannya dari
 * package.json, README, requirements.txt, composer.json, dan struktur folder.
 */
object ProjectDetector {

  class Cmd(val label: String, val command: String)

  class Result(
      val type: String,
      val backend: Boolean,
      val frameworks: List<String>,
      val commands: List<Cmd>,
      val install: String?,
      val installNeeded: Boolean,
      val port: Int,
      val webRoot: String,
      val notes: List<String>,
  ) {
    fun summary(): String {
      val sb = StringBuilder()
      val typeName = when (type) { "node" -> "Node.js"; "python" -> "Python"; "php" -> "PHP"; else -> "HTML / CSS / JavaScript (statis)" }
      sb.append("Jenis: $typeName\n")
      sb.append("Backend: ").append(if (backend) "ADA" else "tidak ada (cukup server statis)").append('\n')
      if (frameworks.isNotEmpty()) sb.append("Kerangka: ").append(frameworks.joinToString(", ")).append('\n')
      sb.append("Port yang disarankan: $port\n")
      if (install != null && installNeeded) sb.append("\nLangkah 1 — instal dependensi:\n  $install\n")
      if (commands.isNotEmpty()) {
        sb.append(if (install != null && installNeeded) "Langkah 2 — jalankan:\n" else "\nCara menjalankan:\n")
        commands.take(4).forEach { sb.append("  ${it.command}   (${it.label})\n") }
      } else {
        sb.append("\nCara menjalankan: tekan Jalankan (server statis).\n")
      }
      notes.forEach { sb.append("• ").append(it).append('\n') }
      return sb.toString().trim()
    }
  }

  private val runnableStart = Regex("^(?:npm|yarn|pnpm|npx|node|nodemon|python3?|pip3?|php|flask|uvicorn|gunicorn|composer|bun|deno)\\s")
  private val backendDeps = setOf("express", "koa", "fastify", "@nestjs/core", "hapi", "@hapi/hapi", "socket.io", "json-server", "restify", "sails", "adonis", "apollo-server")

  private fun isGradleProject(dir: File) = File(dir, "settings.gradle").exists() || File(dir, "settings.gradle.kts").exists()

  fun looksLikeWeb(dir: File): Boolean {
    if (!dir.isDirectory || isGradleProject(dir)) return false
    if (File(dir, ".acsweb.json").isFile) return true
    val names = listOf("index.html", "package.json", "requirements.txt", "composer.json", "index.php", "app.py", "manage.py", "server.js", "public/index.html", "dist/index.html", "docs/index.html")
    return names.any { File(dir, it).exists() }
  }

  private fun readmeHints(dir: File): List<String> {
    val f = dir.listFiles()?.firstOrNull { it.isFile && it.name.lowercase().startsWith("readme") } ?: return emptyList()
    val text = try { f.readText().take(60_000) } catch (e: Throwable) { return emptyList() }
    val out = LinkedHashSet<String>()
    for (raw in text.lines()) {
      val line = raw.trim().removePrefix("$").removePrefix(">").trim()
      if (line.length in 6..100 && runnableStart.containsMatchIn(line + " ") && !line.contains("http")) out.add(line.trimEnd('`'))
    }
    Regex("`((?:npm|yarn|pnpm|npx|node|nodemon|python3?|php|flask|uvicorn|gunicorn|bun|deno)\\s[^`\\n]{2,80})`").findAll(text).forEach { out.add(it.groupValues[1].trim()) }
    return out.take(10)
  }

  private fun portFrom(text: String): Int? =
      Regex("(?:--port[= ]|-p |PORT=|localhost:|listen\\()(\\d{4,5})").find(text)?.groupValues?.get(1)?.toIntOrNull()

  fun detect(dir: File): Result {
    val notes = mutableListOf<String>()
    val fw = LinkedHashSet<String>()
    val cmds = mutableListOf<Cmd>()
    var type = "static"
    var backend = false
    var install: String? = null
    var installNeeded = false
    var port = 8080
    var webRoot = ""
    val hints = readmeHints(dir)

    val pkg = File(dir, "package.json")
    when {
      pkg.isFile -> {
        type = "node"
        port = 3000
        try {
          val j = JSONObject(pkg.readText())
          val scripts = j.optJSONObject("scripts")
          val deps = HashSet<String>()
          for (k in listOf("dependencies", "devDependencies")) j.optJSONObject(k)?.keys()?.forEach { deps.add(it) }
          val names = mapOf(
              "vite" to "Vite", "next" to "Next.js", "react-scripts" to "Create React App", "express" to "Express", "koa" to "Koa",
              "fastify" to "Fastify", "@nestjs/core" to "NestJS", "nuxt" to "Nuxt", "react" to "React", "vue" to "Vue", "svelte" to "Svelte",
              "@angular/core" to "Angular", "socket.io" to "Socket.IO", "mongoose" to "MongoDB (Mongoose)", "sequelize" to "Sequelize",
              "prisma" to "Prisma", "json-server" to "json-server", "webpack-dev-server" to "Webpack Dev Server",
          )
          names.forEach { (k, v) -> if (k in deps) fw.add(v) }
          backend = deps.any { it in backendDeps }
          if (scripts != null) {
            for (name in listOf("dev", "start", "serve", "server", "develop", "preview", "watch")) {
              if (!scripts.has(name)) continue
              val body = scripts.optString(name)
              if (name == "server" || body.contains("node ") || body.contains("nodemon")) backend = true
              portFrom(body)?.let { port = it }
              var c = if (name == "start") "npm start" else "npm run $name"
              c += when {
                body.contains("vite") -> " -- --host {HOST} --port {PORT}"
                body.contains("next") -> " -- -H {HOST} -p {PORT}"
                body.contains("ng serve") -> " -- --host {HOST} --port {PORT}"
                else -> ""
              }
              cmds.add(Cmd("script '$name': ${body.take(48)}", c))
            }
          }
          if ("vite" in deps) port = 5173
        } catch (e: Throwable) {
          notes.add("package.json tidak bisa dibaca: ${e.message}")
        }
        if (cmds.isEmpty()) {
          for (f in listOf("server.js", "app.js", "index.js", "main.js")) {
            if (File(dir, f).isFile) {
              cmds.add(Cmd("berkas $f", "node $f"))
              backend = true
              break
            }
          }
        }
        install = "npm install"
        installNeeded = !File(dir, "node_modules").isDirectory
        for (f in listOf("server.js", "app.js", "index.js")) {
          val file = File(dir, f)
          if (file.isFile) portFrom(file.readText().take(40_000))?.let { port = it }
        }
        if (installNeeded) notes.add("Folder node_modules belum ada: jalankan instal dependensi dulu (butuh Node.js dan internet).")
      }
      File(dir, "requirements.txt").isFile || File(dir, "manage.py").isFile || File(dir, "app.py").isFile || File(dir, "main.py").isFile -> {
        type = "python"
        backend = true
        port = 8000
        val req = File(dir, "requirements.txt").takeIf { it.isFile }?.readText()?.lowercase().orEmpty()
        if ("flask" in req) fw.add("Flask")
        if ("django" in req || File(dir, "manage.py").isFile) fw.add("Django")
        if ("fastapi" in req) fw.add("FastAPI")
        if ("uvicorn" in req) fw.add("Uvicorn")
        if ("gunicorn" in req) fw.add("Gunicorn")
        if (File(dir, "manage.py").isFile) cmds.add(Cmd("Django", "python3 manage.py runserver {HOST}:{PORT}"))
        if ("fastapi" in req && File(dir, "main.py").isFile) cmds.add(Cmd("FastAPI", "uvicorn main:app --host {HOST} --port {PORT}"))
        if ("flask" in req && File(dir, "app.py").isFile) cmds.add(Cmd("Flask", "FLASK_APP=app.py flask run --host {HOST} --port {PORT}"))
        for (f in listOf("app.py", "main.py", "server.py", "run.py")) {
          if (File(dir, f).isFile) {
            cmds.add(Cmd("berkas $f", "python3 $f"))
            portFrom(File(dir, f).readText().take(40_000))?.let { port = it }
            break
          }
        }
        if (req.isNotEmpty()) {
          install = "pip install -r requirements.txt"
          installNeeded = true
          notes.add("Instal dependensi Python dengan: pip install -r requirements.txt")
        }
      }
      File(dir, "artisan").isFile || File(dir, "composer.json").isFile || dir.listFiles()?.any { it.name.endsWith(".php") } == true || File(dir, "public/index.php").isFile -> {
        type = "php"
        backend = true
        port = 8000
        if (File(dir, "artisan").isFile) {
          fw.add("Laravel")
          cmds.add(Cmd("Laravel", "php artisan serve --host={HOST} --port={PORT}"))
        }
        val root = if (File(dir, "public/index.php").isFile || File(dir, "public/index.html").isFile) "public" else "."
        cmds.add(Cmd("server bawaan PHP", "php -S {HOST}:{PORT} -t $root"))
        if (File(dir, "composer.json").isFile) {
          install = "composer install"
          installNeeded = !File(dir, "vendor").isDirectory
          if (installNeeded) notes.add("Folder vendor belum ada: jalankan 'composer install' (butuh Composer).")
        }
      }
      else -> {
        type = "static"
        webRoot = listOf("", "public", "dist", "build", "docs", "src").firstOrNull { File(dir, if (it.isEmpty()) "index.html" else "$it/index.html").isFile } ?: ""
        if (webRoot.isNotEmpty()) notes.add("Halaman utama ada di folder '$webRoot', itu yang disajikan.")
      }
    }

    // Perintah dari README sebagai tambahan (ditandai), tanpa duplikat.
    hints.filter { h -> cmds.none { it.command.equals(h, true) } && Regex("^(npm run|npm start|npm run|yarn|pnpm|node|nodemon|python|python3|php|flask|uvicorn|gunicorn|bun|deno)\\b").containsMatchIn(h) }
        .forEach { cmds.add(Cmd("dari README", it)) }
    if (hints.isNotEmpty()) notes.add("README menyebut: ${hints.take(3).joinToString(" | ")}")
    if (type != "static" && !backend) notes.add("Proyek ini tampak seperti frontend dengan server pengembangan.")

    return Result(type, backend, fw.toList(), cmds, install, installNeeded, port, webRoot, notes)
  }
}
