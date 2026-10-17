/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.build.config

import org.gradle.api.Project

/** @author Akash Yadav */
object ProjectConfig {

  // Fork: alamat repo diambil dari repo GitHub milik sendiri saat build di GitHub Actions
  // (GITHUB_REPOSITORY = "pemilik/nama-repo"). Build lokal memakai nilai cadangan.
  private val githubRepo: List<String> =
    (System.getenv("GITHUB_REPOSITORY") ?: "local/android-studio").split("/", limit = 2)

  const val REPO_HOST = "github.com"
  val REPO_OWNER: String = githubRepo.getOrElse(0) { "local" }
  val REPO_NAME: String = githubRepo.getOrElse(1) { "android-studio" }

  val REPO_URL: String = "https://$REPO_HOST/$REPO_OWNER/$REPO_NAME"
  val SCM_GIT: String = "scm:git:git://$REPO_HOST/$REPO_OWNER/$REPO_NAME.git"
  val SCM_SSH: String = "scm:git:ssh://git@$REPO_HOST/$REPO_OWNER/$REPO_NAME.git"

  // const val PROJECT_SITE = "https://m.androidide.com"
  val PROJECT_SITE: String = REPO_URL
}

private var shouldPrintNotAGitRepoWarning = true
private var shouldPrintVersionName = true

val Project.simpleVersionName: String
  get() {
    val version = rootProject.version.toString()
    val simpleVersion = version

    if (shouldPrintVersionName) {
       logger.warn("Version name is '$simpleVersion'")
       shouldPrintVersionName = false
    }
    
    return simpleVersion
  }

private var shouldPrintVersionCode = true
val Project.projectVersionCode: Int
  get() {

    // I don't like this being hardcoded here, so change it if you want.
    val baseVersionCode = System.getenv("PROJECT_CONFIG_KT_BASE_VERSION_CODE")?.toIntOrNull() ?: 1024
    // Default value (1020) is used if not specified. The middle digit (e.g., 10<2>0) represents the revision version, such as 1.0.0+gh.r0<2>.
    
    val versionCode = baseVersionCode
    
    if (shouldPrintVersionCode) {
      logger.warn("Version code is '$versionCode'")
      shouldPrintVersionCode = false
    }
    
    return versionCode
  }
/**
 * The version name which is used to download the artifacts at runtime.
 *
 * The value varies based on the following cases :
 * - For CI and F-Droid builds: same as [publishingVersion].
 * - For local builds: `latest.integration` to make sure that Gradle downloads the latest snapshots.
 */
val Project.downloadVersion: String
  get() {
      // sometimes, when working locally, Gradle fails to download the latest snapshot version
      // this may cause issues while initializing the project in AndroidIDE
      return VersionUtils.getLatestSnapshotVersion("gradle-plugin")
  }
  