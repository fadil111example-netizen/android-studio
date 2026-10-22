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

package com.tom.rv2ide.plugins.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/** Generates the Gradle init script for AndroidIDE. */
abstract class GenerateInitScriptTask : DefaultTask() {

  @get:Input abstract val downloadVersion: Property<String>

  @get:Input abstract val mavenGroupId: Property<String>

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun generate() {

    val outFile =
        this.outputDir.file("data/common/androidide.init.gradle").also {
          it.get().asFile.parentFile.mkdirs()
        }

    outFile.get().asFile.bufferedWriter().use {
      it.write(
          """
            initscript {
                repositories {
                    mavenCentral()
                    google()
                }

                dependencies {
                    classpath('io.github.mohammed-baqer-null:rv2ide-gradle-plugin:1.0.0') {
                        setChanging(false)
                    }
                }
            }
            
            apply plugin: com.tom.rv2ide.gradle.AndroidIDEInitScriptPlugin

            // Jangan biarkan dependensi plugin IDE menaikkan versi AGP milik proyek
            // (penyebab error "Minimum supported Gradle version" pada proyek lama).
            gradle.rootProject { rp ->
                def declaresAgp = false
                ['build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts', 'gradle/libs.versions.toml'].each { n ->
                    def f = new File(rp.projectDir, n)
                    if (f.exists()) {
                        def txt = f.text
                        if (txt.contains('com.android.tools.build:gradle') || txt =~ /com\.android\.(application|library)['"]\s*\)?\s*version/ || txt =~ /(?m)^\s*(agp|androidGradlePlugin)\s*=/) {
                            declaresAgp = true
                        }
                    }
                }
                if (declaresAgp) {
                    rp.buildscript.configurations.getByName('classpath').dependencies.all { dep ->
                        if (dep instanceof org.gradle.api.artifacts.ModuleDependency && dep.group == 'io.github.mohammed-baqer-null') {
                            dep.exclude(group: 'com.android.tools.build')
                        }
                    }
                }
            }
          """
              .trimIndent()
      )
    }
  }
}
