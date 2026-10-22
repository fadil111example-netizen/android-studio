package com.tom.rv2ide.utils

import java.io.File
import org.slf4j.LoggerFactory

/**
 * Menyamakan versi Gradle wrapper proyek dengan syarat plugin Android (AGP). Tidak ada batas versi
 * dari aplikasi: bila AGP butuh Gradle lebih baru, wrapper dinaikkan otomatis (file asli dicadangkan
 * ke gradle-wrapper.properties.bak). Ada dua lapis:
 *  1. Proaktif: membaca versi AGP dari file Gradle proyek sebelum sync.
 *  2. Reaktif: membaca pesan "Minimum supported Gradle version is X" dari hasil build lalu memperbaiki
 *     wrapper ke X dan meminta sync ulang otomatis.
 */
object GradleCompat {

  private val log = LoggerFactory.getLogger(GradleCompat::class.java)

  @Volatile private var retryPending = false

  /** True sekali bila wrapper baru saja diperbaiki dan sync perlu diulang. */
  fun consumeRetry(): Boolean {
    val r = retryPending
    retryPending = false
    return r
  }

  private val minGradle =
      mapOf(
          "9.0" to "9.1.0", "8.13" to "8.13", "8.12" to "8.13", "8.11" to "8.13", "8.10" to "8.11.1",
          "8.9" to "8.11.1", "8.8" to "8.10.2", "8.7" to "8.9", "8.6" to "8.7", "8.5" to "8.7",
          "8.4" to "8.6", "8.3" to "8.4", "8.2" to "8.2", "8.1" to "8.0", "8.0" to "8.0",
          "7.4" to "7.5", "7.3" to "7.4", "7.2" to "7.3.3", "7.1" to "7.2", "7.0" to "7.0",
          "4.2" to "6.7.1", "4.1" to "6.5", "4.0" to "6.1.1", "3.6" to "5.6.4", "3.5" to "5.4.1",
      )

  private val agpPatterns =
      listOf(
          Regex("""com\.android\.tools\.build:gradle:([0-9]+\.[0-9]+(?:\.[0-9]+)?)"""),
          Regex("""com\.android\.(?:application|library)["']\s*\)?\s*version\s*\(?\s*["']([0-9][^"']*)["']"""),
          Regex("""(?m)^\s*(?:agp|android-gradle-plugin|androidGradlePlugin|androidGradle|androidPlugin)\s*=\s*["']([0-9][^"']*)["']"""),
      )

  private fun cmp(a: String, b: String): Int {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
      val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
      if (d != 0) return d
    }
    return 0
  }

  /** Versi AGP tertinggi yang disebut di file Gradle/toml proyek (maks. kedalaman 3 folder). */
  private fun findAgp(dir: File): String? {
    var best: String? = null
    dir.walkTopDown()
        .maxDepth(3)
        .onEnter { !it.name.equals("build", true) && !it.name.startsWith(".git") && !it.name.equals("node_modules", true) }
        .filter { it.isFile && it.length() < 512 * 1024 && (it.name.endsWith(".gradle") || it.name.endsWith(".gradle.kts") || it.name.endsWith(".toml")) }
        .forEach { f ->
          val text = try { f.readText() } catch (e: Throwable) { return@forEach }
          for (p in agpPatterns) {
            for (m in p.findAll(text)) {
              val v = m.groupValues[1]
              if (best == null || cmp(v, best!!) > 0) best = v
            }
          }
        }
    return best
  }

  /** Menulis wrapper ke versi [need] bila saat ini lebih rendah. Mengembalikan pesan atau null. */
  private fun rewriteWrapper(projectDir: File, need: String, reason: String): String? {
    val wp = File(projectDir, "gradle/wrapper/gradle-wrapper.properties")
    if (!wp.isFile) return null
    val text = wp.readText()
    val m = Regex("""gradle-([0-9][0-9.]*)(-(?:rc|milestone)-[0-9]+)?-(?:bin|all)\.zip""").find(text) ?: return null
    val current = m.groupValues[1]
    if (cmp(current, need) >= 0) return null

    wp.copyTo(File(wp.parentFile, "gradle-wrapper.properties.bak"), overwrite = true)
    val updated =
        text.replace(m.value, "gradle-$need-bin.zip")
            .lines()
            .filterNot { it.trim().startsWith("distributionSha256Sum") }
            .joinToString("\n")
    wp.writeText(updated)
    val msg = "$reason; wrapper Gradle dinaikkan otomatis dari $current ke $need (cadangan: gradle-wrapper.properties.bak)"
    log.info(msg)
    return msg
  }

  /** Lapis 1: sebelum sync. */
  fun fix(projectDir: File): String? =
      try {
        val agp = findAgp(projectDir)
        val mm = agp?.let { Regex("""^(\d+)\.(\d+)""").find(it) }
        val need = mm?.let { minGradle["${it.groupValues[1]}.${it.groupValues[2]}"] }
        if (agp == null || need == null) null else rewriteWrapper(projectDir, need, "AGP $agp butuh Gradle minimal $need")
      } catch (e: Throwable) {
        log.warn("GradleCompat.fix gagal", e)
        null
      }

  /** Lapis 2: dipanggil untuk tiap baris output build. */
  fun onBuildOutput(projectDir: File?, line: String) {
    if (projectDir == null || !line.contains("Minimum supported Gradle version")) return
    try {
      val m = Regex("""Minimum supported Gradle version is ([0-9][0-9.]*)""").find(line) ?: return
      val need = m.groupValues[1].trimEnd('.')
      if (rewriteWrapper(projectDir, need, "Plugin Android meminta Gradle $need") != null) retryPending = true
    } catch (e: Throwable) {
      log.warn("GradleCompat.onBuildOutput gagal", e)
    }
  }
}
