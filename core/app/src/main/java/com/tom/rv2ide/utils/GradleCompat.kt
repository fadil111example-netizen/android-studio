package com.tom.rv2ide.utils

import java.io.File
import org.slf4j.LoggerFactory

/**
 * Menyamakan versi Gradle wrapper proyek dengan syarat minimum plugin Android (AGP) yang dipakai
 * proyek itu. Jika wrapper terlalu lama, `distributionUrl` dinaikkan otomatis (file asli dicadangkan
 * ke gradle-wrapper.properties.bak). Tidak ada pembatasan versi: proyek dengan Gradle apa pun tetap
 * dibuka, dan bila AGP-nya butuh Gradle lebih baru, wrapper disesuaikan tanpa campur tangan manual.
 */
object GradleCompat {

  private val log = LoggerFactory.getLogger(GradleCompat::class.java)

  /** AGP (mayor.minor) -> Gradle minimum menurut tabel kompatibilitas resmi Android. */
  private val minGradle =
      mapOf(
          "9.0" to "9.1.0",
          "8.13" to "8.13",
          "8.12" to "8.13",
          "8.11" to "8.13",
          "8.10" to "8.11.1",
          "8.9" to "8.11.1",
          "8.8" to "8.10.2",
          "8.7" to "8.9",
          "8.6" to "8.7",
          "8.5" to "8.7",
          "8.4" to "8.6",
          "8.3" to "8.4",
          "8.2" to "8.2",
          "8.1" to "8.0",
          "8.0" to "8.0",
          "7.4" to "7.5",
          "7.3" to "7.4",
          "7.2" to "7.3.3",
          "7.1" to "7.2",
          "7.0" to "7.0",
          "4.2" to "6.7.1",
          "4.1" to "6.5",
          "4.0" to "6.1.1",
          "3.6" to "5.6.4",
          "3.5" to "5.4.1",
      )

  private val agpPatterns =
      listOf(
          Regex("""com\.android\.tools\.build:gradle:([0-9]+\.[0-9]+(?:\.[0-9]+)?[^'"\s)]*)"""),
          Regex("""id\s*\(?\s*["']com\.android\.(?:application|library)["']\s*\)?\s*version\s*\(?\s*["']([^"']+)["']"""),
          Regex("""(?m)^\s*(?:agp|android-gradle-plugin|androidGradlePlugin|androidGradle)\s*=\s*["']([0-9][^"']*)["']"""),
      )

  private fun cmp(a: String, b: String): Int {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
      val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 })
      if (d != 0) return d
    }
    return 0
  }

  private fun findAgp(dir: File): String? {
    val files =
        listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradle/libs.versions.toml", "app/build.gradle", "app/build.gradle.kts")
    for (name in files) {
      val f = File(dir, name)
      if (!f.isFile) continue
      val text = try { f.readText() } catch (e: Throwable) { continue }
      for (p in agpPatterns) p.find(text)?.let { return it.groupValues[1] }
    }
    return null
  }

  /** Mengembalikan pesan jika wrapper diubah, atau null bila tidak ada yang perlu diubah. */
  fun fix(projectDir: File): String? {
    try {
      val wp = File(projectDir, "gradle/wrapper/gradle-wrapper.properties")
      if (!wp.isFile) return null
      val text = wp.readText()
      val m = Regex("""gradle-([0-9][0-9.]*)(-(?:rc|milestone)-[0-9]+)?-(?:bin|all)\.zip""").find(text) ?: return null
      val current = m.groupValues[1]

      val agp = findAgp(projectDir) ?: return null
      val mm = Regex("""^(\d+)\.(\d+)""").find(agp) ?: return null
      val need = minGradle["${mm.groupValues[1]}.${mm.groupValues[2]}"] ?: return null
      if (cmp(current, need) >= 0) return null

      wp.copyTo(File(wp.parentFile, "gradle-wrapper.properties.bak"), overwrite = true)
      val updated =
          text.replace(m.value, "gradle-$need-bin.zip")
              .lines()
              .filterNot { it.trim().startsWith("distributionSha256Sum") }
              .joinToString("\n")
      wp.writeText(updated)
      val msg = "AGP $agp butuh Gradle minimal $need; wrapper dinaikkan otomatis dari $current ke $need (cadangan: gradle-wrapper.properties.bak)"
      log.info(msg)
      return msg
    } catch (e: Throwable) {
      log.warn("GradleCompat gagal", e)
      return null
    }
  }
}
