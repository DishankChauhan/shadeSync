package com.shadesync.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter that displays lip shades as circular colour swatches
 * with name and brand underneath. Used in the bottom sheet shade picker.
 */
class ShadeAdapter(
    private var shades: List<LipShade>,
    private val onShadeSelected: (LipShade) -> Unit
) : RecyclerView.Adapter<ShadeAdapter.ShadeVH>() {

    private var selectedPos = 0

    class ShadeVH(view: View) : RecyclerView.ViewHolder(view) {
        val swatch: View = view.findViewById(R.id.swatch)
        val name: TextView = view.findViewById(R.id.shadeTitleItem)
        val brand: TextView = view.findViewById(R.id.shadeBrandItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShadeVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shade, parent, false)
        return ShadeVH(v)
    }

    override fun getItemCount() = shades.size

    override fun onBindViewHolder(holder: ShadeVH, position: Int) {
        val shade = shades[position]

        // Colour circle
        val gd = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(shade.colorInt)
            setStroke(if (position == selectedPos) 4 else 2,
                if (position == selectedPos) Color.WHITE else Color.argb(80, 255, 255, 255))
        }
        holder.swatch.background = gd

        // Scale
        val scale = if (position == selectedPos) 1.2f else 1.0f
        holder.swatch.scaleX = scale
        holder.swatch.scaleY = scale

        holder.name.text = shade.name
        holder.brand.text = shade.brand

        holder.itemView.setOnClickListener {
            val prev = selectedPos
            selectedPos = holder.bindingAdapterPosition
            notifyItemChanged(prev)
            notifyItemChanged(selectedPos)
            onShadeSelected(shade)
        }
    }

    fun updateShades(newShades: List<LipShade>) {
        shades = newShades
        selectedPos = 0
        notifyDataSetChanged()
    }

    fun selectFirst() {
        if (shades.isNotEmpty()) {
            selectedPos = 0
            notifyItemChanged(0)
            onShadeSelected(shades[0])
        }
    }
}
