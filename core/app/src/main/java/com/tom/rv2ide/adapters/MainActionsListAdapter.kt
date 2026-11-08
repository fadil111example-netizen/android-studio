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

package com.tom.rv2ide.adapters

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tom.rv2ide.databinding.LayoutMainActionBigBinding
import com.tom.rv2ide.databinding.LayoutMainActionRowBinding
import com.tom.rv2ide.databinding.LayoutMainActionToolBinding
import com.tom.rv2ide.databinding.LayoutMainSectionHeaderBinding
import com.tom.rv2ide.models.MainScreenAction
import com.tom.rv2ide.utils.Environment
import java.io.File

/**
 * Adapter menu layar utama dengan hierarki: kartu besar (aksi utama), ikon alat cepat, dan daftar
 * sistem. Layout grid 4 kolom diatur oleh adapter ini sendiri.
 *
 * @author Akash Yadav (diubah untuk desain baru)
 */
class MainActionsListAdapter
@JvmOverloads
constructor(val actions: List<MainScreenAction> = emptyList()) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

  private sealed class Row {
    class Header(val text: String) : Row()

    class Item(val action: MainScreenAction, val kind: Int) : Row()
  }

  private companion object {
    const val T_HEADER = 0
    const val T_BIG = 1
    const val T_TOOL = 2
    const val T_ROW = 3

    val bigIds = listOf(MainScreenAction.ACTION_CREATE_PROJECT, MainScreenAction.ACTION_OPEN_PROJECT)
    val toolIds = listOf(12, 2, 3, 8, 9, 10, 11)
    val rowIds = listOf(4, 5, 7, 6)

    val subtitles =
        mapOf(
            0 to "Mulai dari template",
            1 to "Lanjutkan pekerjaanmu",
            4 to "Tema, editor, dan build",
            5 to "NDK, CMake, dan sistem",
            6 to "Panduan penggunaan",
            7 to "Cadangkan & pulihkan data",
        )

    val accents =
        mapOf(
            2 to 0xFFF59E0B.toInt(), 3 to 0xFFEF4444.toInt(), 8 to 0xFF8B5CF6.toInt(),
            9 to 0xFF14B8A6.toInt(), 10 to 0xFFEC4899.toInt(), 11 to 0xFF3B82F6.toInt(), 12 to 0xFFF97316.toInt(),
            4 to 0xFF64748B.toInt(), 5 to 0xFF14B8A6.toInt(), 6 to 0xFF22C55E.toInt(), 7 to 0xFFF97316.toInt(),
        )

    val gradients =
        mapOf(
            0 to intArrayOf(0xFF0F7A52.toInt(), 0xFF22C55E.toInt()),
            1 to intArrayOf(0xFF1E3A8A.toInt(), 0xFF3B82F6.toInt()),
        )
  }

  private val rows: List<Row> = buildRows()

  private fun buildRows(): List<Row> {
    val out = mutableListOf<Row>()
    val big = bigIds.mapNotNull { id -> actions.firstOrNull { it.id == id } }
    val tools = toolIds.mapNotNull { id -> actions.firstOrNull { it.id == id } }
    val known = (bigIds + toolIds).toSet()
    val sysFirst = rowIds.mapNotNull { id -> actions.firstOrNull { it.id == id } }
    val system = sysFirst + actions.filter { it.id !in known && it.id !in rowIds }

    big.forEach { out.add(Row.Item(it, T_BIG)) }
    if (tools.isNotEmpty()) {
      out.add(Row.Header("ALAT CEPAT"))
      tools.forEach { out.add(Row.Item(it, T_TOOL)) }
    }
    if (system.isNotEmpty()) {
      out.add(Row.Header("SISTEM"))
      system.forEach { out.add(Row.Item(it, T_ROW)) }
    }
    return out
  }

  fun getAction(index: Int) = actions[index]

  override fun getItemCount(): Int = rows.size

  override fun getItemViewType(position: Int): Int =
      when (val r = rows[position]) {
        is Row.Header -> T_HEADER
        is Row.Item -> r.kind
      }

  override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
    super.onAttachedToRecyclerView(recyclerView)
    val lm = GridLayoutManager(recyclerView.context, 4)
    lm.spanSizeLookup =
        object : GridLayoutManager.SpanSizeLookup() {
          override fun getSpanSize(position: Int): Int =
              when (val r = rows[position]) {
                is Row.Header -> 4
                is Row.Item -> if (r.kind == T_BIG) 2 else if (r.kind == T_TOOL) 1 else 4
              }
        }
    recyclerView.layoutManager = lm
    updateStatus(recyclerView)
  }

  private fun updateStatus(rv: RecyclerView) {
    try {
      val tv = rv.rootView.findViewById<TextView>(com.tom.rv2ide.R.id.home_status) ?: return
      val sdk = File(Environment.ANDROID_HOME, "platforms").listFiles()?.any { File(it, "android.jar").isFile } == true
      val jdk = File(Environment.PREFIX, "lib/jvm").listFiles()?.any { File(it, "bin/java").isFile } == true
      val ndk = File(Environment.ANDROID_HOME, "ndk").listFiles()?.count { it.isDirectory } ?: 0
      val git = File(Environment.BIN_DIR, "git").exists()
      fun m(ok: Boolean) = if (ok) "✓" else "✗"
      tv.text = "${m(jdk)} JDK   ${m(sdk)} SDK   ${if (ndk > 0) "✓ NDK×$ndk" else "– NDK"}   ${m(git)} Git"
    } catch (e: Throwable) {
      // abaikan
    }
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
    val inf = LayoutInflater.from(parent.context)
    return when (viewType) {
      T_HEADER -> HeaderVH(LayoutMainSectionHeaderBinding.inflate(inf, parent, false))
      T_BIG -> BigVH(LayoutMainActionBigBinding.inflate(inf, parent, false))
      T_TOOL -> ToolVH(LayoutMainActionToolBinding.inflate(inf, parent, false))
      else -> RowVH(LayoutMainActionRowBinding.inflate(inf, parent, false))
    }
  }

  private class HeaderVH(val b: LayoutMainSectionHeaderBinding) : RecyclerView.ViewHolder(b.root)

  private class BigVH(val b: LayoutMainActionBigBinding) : RecyclerView.ViewHolder(b.root)

  private class ToolVH(val b: LayoutMainActionToolBinding) : RecyclerView.ViewHolder(b.root)

  private class RowVH(val b: LayoutMainActionRowBinding) : RecyclerView.ViewHolder(b.root)

  private fun wire(root: View, action: MainScreenAction) {
    root.setOnClickListener { action.onClick?.invoke(action, it) }
    action.onLongClick?.let { l -> root.setOnLongClickListener { l(action, it) } }
  }

  private fun tonal(color: Int, radiusDp: Float, view: View) {
    val d = view.resources.displayMetrics.density
    view.background =
        GradientDrawable().apply {
          cornerRadius = radiusDp * d
          setColor(ColorUtils.setAlphaComponent(color, 40))
        }
  }

  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    when (val r = rows[position]) {
      is Row.Header -> (holder as HeaderVH).b.sectionTitle.text = r.text
      is Row.Item -> {
        val a = r.action
        when (holder) {
          is BigVH -> {
            val b = holder.b
            val d = b.root.resources.displayMetrics.density
            val g = gradients[a.id] ?: intArrayOf(0xFF0F7A52.toInt(), 0xFF22C55E.toInt())
            b.container.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, g).apply { cornerRadius = 28 * d }
            b.icon.setImageResource(a.icon)
            b.icon.imageTintList = ColorStateList.valueOf(Color.WHITE)
            b.title.setText(a.text)
            b.subtitle.text = subtitles[a.id] ?: ""
            wire(b.root, a)
          }
          is ToolVH -> {
            val b = holder.b
            val c = accents[a.id] ?: 0xFF64748B.toInt()
            tonal(c, 20f, b.holder)
            b.icon.setImageResource(a.icon)
            b.icon.imageTintList = ColorStateList.valueOf(c)
            b.title.setText(a.text)
            wire(b.root, a)
          }
          is RowVH -> {
            val b = holder.b
            val c = accents[a.id] ?: 0xFF64748B.toInt()
            tonal(c, 14f, b.holder)
            b.icon.setImageResource(a.icon)
            b.icon.imageTintList = ColorStateList.valueOf(c)
            b.title.setText(a.text)
            b.subtitle.text = subtitles[a.id] ?: ""
            wire(b.root, a)
          }
          else -> {}
        }
      }
    }
  }
}
