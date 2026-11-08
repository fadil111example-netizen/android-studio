package com.tom.rv2ide.web

import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.tom.rv2ide.app.IDEApplication
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import org.json.JSONObject

/** Proyek web: tipe (static/node/python/php), port, dan perintah backend. Disimpan di .acsweb.json. */
class WebProject(val dir: File, var type: String, var port: Int, var command: String) {

  fun save() {
    File(dir, ".acsweb.json").writeText(JSONObject().put("type", type).put("port", port).put("command", command).toString(2))
  }

  companion object {
    fun load(dir: File): WebProject? {
      val f = File(dir, ".acsweb.json")
      if (!f.isFile) return null
      return try {
        val j = JSONObject(f.readText())
        WebProject(dir, j.optString("type", "static"), j.optInt("port", 8080), j.optString("command", ""))
      } catch (e: Throwable) {
        null
      }
    }

    fun root(): File = File(Environment.PROJECTS_DIR, "Web").apply { mkdirs() }
  }
}

/** Template proyek siap jalan untuk tiap jenis. */
object WebTemplates {

  val types = listOf("static" to "HTML / CSS / JavaScript", "node" to "Node.js (backend)", "python" to "Python (backend)", "php" to "PHP (backend)")

  fun defaultCommand(type: String) =
      when (type) {
        "node" -> "node server.js"
        "python" -> "python3 app.py"
        "php" -> "php -S \"\$HOST:\$PORT\" -t ."
        else -> ""
      }

  private const val INDEX =
      """<!DOCTYPE html>
<html lang="id">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Proyek Web</title>
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <main class="card">
    <h1>Halo, Web! 👋</h1>
    <p id="pesan">Proyek ini dibuat dari Android Studio.</p>
    <button id="tombol">Klik saya</button>
  </main>
  <script src="script.js"></script>
</body>
</html>
"""

  private const val CSS =
      """* { box-sizing: border-box; }
body {
  margin: 0;
  min-height: 100vh;
  display: grid;
  place-items: center;
  font-family: system-ui, sans-serif;
  background: linear-gradient(135deg, #0b2b3a, #14b86b);
}
.card {
  background: #fff;
  padding: 32px;
  border-radius: 24px;
  text-align: center;
  box-shadow: 0 20px 40px rgba(0, 0, 0, .25);
}
button {
  padding: 12px 24px;
  border: 0;
  border-radius: 14px;
  background: #0f7a52;
  color: #fff;
  font-size: 16px;
}
"""

  private const val JS =
      """const tombol = document.getElementById('tombol');
const pesan = document.getElementById('pesan');
let n = 0;
tombol.addEventListener('click', () => {
  n++;
  pesan.textContent = 'Tombol diklik ' + n + ' kali';
});
// Jika ada backend, coba panggil API-nya:
fetch('/api/hello').then(r => r.json()).then(d => { pesan.textContent = d.pesan; }).catch(() => {});
"""

  fun create(name: String, type: String): File {
    val dir = File(WebProject.root(), name)
    if (dir.exists()) throw IOException("Proyek '$name' sudah ada")
    dir.mkdirs()
    val pub = if (type == "static") dir else File(dir, "public").apply { mkdirs() }
    File(pub, "index.html").writeText(INDEX)
    File(pub, "style.css").writeText(CSS)
    File(pub, "script.js").writeText(JS)
    when (type) {
      "node" -> {
        File(dir, "package.json").writeText("{\n  \"name\": \"$name\",\n  \"version\": \"1.0.0\",\n  \"main\": \"server.js\"\n}\n")
        File(dir, "server.js").writeText(
            """const http = require('http');
const fs = require('fs');
const path = require('path');

const HOST = process.env.HOST || '0.0.0.0';
const PORT = parseInt(process.env.PORT || '8080', 10);
const types = { '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript', '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg', '.svg': 'image/svg+xml' };

http.createServer((req, res) => {
  if (req.url === '/api/hello') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ pesan: 'Halo dari backend Node.js!' }));
  }
  let p = req.url.split('?')[0];
  if (p === '/') p = '/index.html';
  const file = path.join(__dirname, 'public', path.normalize(p));
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404); return res.end('Tidak ditemukan'); }
    res.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream' });
    res.end(data);
  });
}).listen(PORT, HOST, () => console.log('Server berjalan di http://' + HOST + ':' + PORT));
"""
        )
      }
      "python" -> {
        File(dir, "app.py").writeText(
            """import json
import os
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

HOST = os.environ.get("HOST", "0.0.0.0")
PORT = int(os.environ.get("PORT", "8080"))


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory="public", **kwargs)

    def do_GET(self):
        if self.path == "/api/hello":
            body = json.dumps({"pesan": "Halo dari backend Python!"}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        super().do_GET()


print("Server berjalan di http://%s:%d" % (HOST, PORT), flush=True)
ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
"""
        )
      }
      "php" -> {
        File(dir, "api.php").writeText("<?php\nheader('Content-Type: application/json');\necho json_encode(['pesan' => 'Halo dari backend PHP!']);\n")
        File(dir, "router.php").writeText(
            """<?php
${'$'}uri = parse_url(${'$'}_SERVER['REQUEST_URI'], PHP_URL_PATH);
if (${'$'}uri === '/api/hello') { require __DIR__ . '/api.php'; return true; }
${'$'}file = __DIR__ . '/public' . (${'$'}uri === '/' ? '/index.html' : ${'$'}uri);
if (is_file(${'$'}file)) { return false; }
http_response_code(404); echo 'Tidak ditemukan';
"""
        )
      }
    }
    val cmd = if (type == "php") "php -S \"\$HOST:\$PORT\" -t public router.php" else defaultCommand(type)
    WebProject(dir, type, if (type == "static") 8080 else 8000, cmd).also { it.save() }
    return dir
  }
}

/** Status server yang bertahan walau layar ditutup. */
object WebRuntime {
  private val logs = ArrayList<String>()
  @Volatile var server: StaticServer? = null
  @Volatile var process: Process? = null
  @Volatile var port = 0
  @Volatile var publicAccess = false
  @Volatile var title = ""

  fun log(msg: String) {
    synchronized(logs) {
      logs.add(msg)
      if (logs.size > 400) logs.removeAt(0)
    }
  }

  fun logText(): String = synchronized(logs) { logs.takeLast(120).joinToString("\n") }

  fun clearLog() = synchronized(logs) { logs.clear() }

  fun isRunning(): Boolean = server != null || process?.isAlive == true

  fun lanIp(): String? =
      try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 && it.isSiteLocalAddress }
            ?.hostAddress
      } catch (e: Throwable) {
        null
      }

  fun stop() {
    try { server?.stop() } catch (e: Throwable) { /* abaikan */ }
    server = null
    process?.let {
      try { it.destroy() } catch (e: Throwable) { /* abaikan */ }
    }
    process = null
    log("■ Server dihentikan.")
  }

  fun startStatic(dir: File, p: Int, pub: Boolean) {
    stop()
    val s = StaticServer(dir, p, pub) { log(it) }
    s.start()
    server = s
    port = p
    publicAccess = pub
    log("▶ Server statis berjalan di port $p (${if (pub) "PUBLIK di jaringan yang sama" else "privat, hanya HP ini"}).")
  }

  fun startCommand(dir: File, command: String, p: Int, pub: Boolean) {
    stop()
    val host = if (pub) "0.0.0.0" else "127.0.0.1"
    val cmd = command.replace("{HOST}", host).replace("{PORT}", p.toString())
    val wrapped = if (Regex("[;&|]").containsMatchIn(cmd)) cmd else "exec $cmd"
    val pb = ProcessBuilder(Environment.BASH_SHELL.absolutePath, "-c", wrapped)
    pb.environment().putAll(HashMap(TermuxShellEnvironment().getEnvironment(IDEApplication.instance, false)))
    pb.environment()["HOST"] = host
    pb.environment()["PORT"] = p.toString()
    pb.directory(dir)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    process = proc
    port = p
    publicAccess = pub
    log("▶ $cmd")
    Thread {
          try {
            proc.inputStream.bufferedReader().forEachLine { log(it.take(300)) }
          } catch (e: Throwable) {
            // proses berakhir
          }
          log("■ Proses berakhir (kode ${try { proc.waitFor() } catch (e: Throwable) { -1 }}).")
        }
        .apply { isDaemon = true }
        .start()
  }
}

/** Server HTTP statis kecil dengan muat-ulang otomatis; tanpa pustaka tambahan. */
class StaticServer(private val root: File, private val port: Int, private val publicAccess: Boolean, private val onLog: (String) -> Unit) {

  private var server: ServerSocket? = null
  @Volatile private var running = false

  private val mime =
      mapOf(
          "html" to "text/html; charset=utf-8", "htm" to "text/html; charset=utf-8", "css" to "text/css; charset=utf-8",
          "js" to "text/javascript; charset=utf-8", "json" to "application/json", "png" to "image/png", "jpg" to "image/jpeg",
          "jpeg" to "image/jpeg", "gif" to "image/gif", "svg" to "image/svg+xml", "ico" to "image/x-icon", "txt" to "text/plain; charset=utf-8",
          "woff" to "font/woff", "woff2" to "font/woff2", "mp4" to "video/mp4", "mp3" to "audio/mpeg", "webp" to "image/webp",
      )

  private val reloadScript =
      "<script>(function(){var v;setInterval(function(){fetch('/__acs_version').then(function(r){return r.text()}).then(function(t){if(v&&v!==t)location.reload();v=t}).catch(function(){})},1200)})();</script>"

  fun start() {
    val addr = InetAddress.getByName(if (publicAccess) "0.0.0.0" else "127.0.0.1")
    server = ServerSocket(port, 50, addr).apply { reuseAddress = true }
    running = true
    Thread {
          while (running) {
            val s = try { server!!.accept() } catch (e: IOException) { break }
            Thread { handle(s) }.apply { isDaemon = true }.start()
          }
        }
        .apply { isDaemon = true }
        .start()
  }

  fun stop() {
    running = false
    try { server?.close() } catch (e: Throwable) { /* abaikan */ }
  }

  private fun version(): String {
    var m = 0L
    root.walkTopDown().maxDepth(4).filter { it.isFile }.forEach { if (it.lastModified() > m) m = it.lastModified() }
    return m.toString()
  }

  private fun handle(sock: Socket) {
    sock.use {
      try {
        it.soTimeout = 15000
        val reader = it.getInputStream().bufferedReader()
        val first = reader.readLine() ?: return
        var line = reader.readLine()
        while (!line.isNullOrEmpty()) line = reader.readLine()
        val parts = first.split(' ')
        val method = parts[0]
        val path = URLDecoder.decode((parts.getOrNull(1) ?: "/").substringBefore('?'), "UTF-8")
        val out = it.getOutputStream()

        fun send(code: Int, status: String, type: String, body: ByteArray) {
          val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
          out.write(head.toByteArray())
          if (method != "HEAD") out.write(body)
          out.flush()
        }

        if (path == "/__acs_version") {
          send(200, "OK", "text/plain", version().toByteArray())
          return
        }
        var file = File(root, path).canonicalFile
        if (!file.path.startsWith(root.canonicalPath)) {
          send(403, "Forbidden", "text/plain", "403".toByteArray())
          return
        }
        if (file.isDirectory) file = File(file, "index.html")
        if (!file.isFile) {
          send(404, "Not Found", "text/plain; charset=utf-8", "404 - Tidak ditemukan".toByteArray())
          onLog("404 $path")
          return
        }
        val ext = file.extension.lowercase()
        val type = mime[ext] ?: "application/octet-stream"
        var bytes = file.readBytes()
        if (ext == "html" || ext == "htm") {
          val text = String(bytes, Charsets.UTF_8)
          val i = text.lastIndexOf("</body>", ignoreCase = true)
          bytes = (if (i >= 0) text.substring(0, i) + reloadScript + text.substring(i) else text + reloadScript).toByteArray()
        }
        send(200, "OK", type, bytes)
        onLog("200 $method $path")
      } catch (e: Throwable) {
        // koneksi ditutup klien
      }
    }
  }
}
