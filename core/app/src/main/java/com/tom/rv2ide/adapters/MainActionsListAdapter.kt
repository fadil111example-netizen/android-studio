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
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.tom.rv2ide.databinding.LayoutMainActionItemBinding
import com.tom.rv2ide.models.MainScreenAction

/**
 * Adapter for the actions available on the main screen.
 *
 * @author Akash Yadav
 */
class MainActionsListAdapter
@JvmOverloads
constructor(val actions: List<MainScreenAction> = emptyList()) :
    RecyclerView.Adapter<MainActionsListAdapter.VH>() {
  class VH(val binding: LayoutMainActionItemBinding) : RecyclerView.ViewHolder(binding.root)

  /** Warna aksen berbeda untuk tiap ubin agar menu mudah dikenali. */
  private val palette =
      intArrayOf(
          0xFF1FB86B.toInt(), 0xFF2E7DFF.toInt(), 0xFFF5A524.toInt(), 0xFFE5484D.toInt(),
          0xFF8E4EC6.toInt(), 0xFF12A594.toInt(), 0xFFE93D82.toInt(), 0xFF3E63DD.toInt(),
      )

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
      VH(LayoutMainActionItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

  override fun getItemCount(): Int = actions.size

  fun getAction(index: Int) = actions[index]

  override fun onBindViewHolder(holder: VH, position: Int) {
    val action = getAction(index = position)
    val binding = holder.binding

    val accent = palette[position % palette.size]
    binding.root.setCardBackgroundColor(ColorUtils.setAlphaComponent(accent, 34))
    binding.actionButton.iconTint = ColorStateList.valueOf(accent)

    binding.actionButton.apply {
      setText(action.text)
      setIconResource(action.icon)
      setOnClickListener { action.onClick?.invoke(action, it) }
      action.onLongClick?.let { onLongClick -> setOnLongClickListener { onLongClick(action, it) } }
    }
  }
}
