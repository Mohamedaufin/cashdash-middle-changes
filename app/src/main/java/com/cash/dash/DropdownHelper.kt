package com.cash.dash

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat

object DropdownHelper {

    fun showBlinkingDropdown(
        context: Context,
        anchor: View,
        items: List<String>,
        fixedWidthDp: Int? = null,
        onItemSelected: (Int, String) -> Unit
    ) {
        val listPopupWindow = android.widget.ListPopupWindow(context)
        val adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                v.setBackgroundColor(Color.TRANSPARENT)
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                
                val dp12 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, context.resources.displayMetrics).toInt()
                val dp16 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics).toInt()
                v.setPadding(dp16, dp12, dp16, dp12)
                
                v.setOnTouchListener { view, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            view.animate().cancel()
                            view.alpha = 0.4f
                            true // Absorb the down touch so we continue tracking MOVE and UP
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val inside = event.x >= 0 && event.x <= view.width && event.y >= 0 && event.y <= view.height
                            if (!inside) {
                                view.animate().alpha(1.0f).setDuration(150).start()
                            } else if (view.alpha > 0.9f) {
                                view.animate().cancel()
                                view.alpha = 0.4f
                            }
                            false
                        }
                        MotionEvent.ACTION_UP -> {
                            view.animate().alpha(1.0f).setDuration(150).start()
                            val inside = event.x >= 0 && event.x <= view.width && event.y >= 0 && event.y <= view.height
                            if (inside) {
                                // Explicitly trigger the click if they released inside the item!
                                listPopupWindow.listView?.performItemClick(view, position, getItemId(position))
                            }
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            view.animate().alpha(1.0f).setDuration(150).start()
                            true
                        }
                        else -> false
                    }
                }
                return v
            }
        }
        
        listPopupWindow.setAdapter(adapter)
        listPopupWindow.anchorView = anchor
        listPopupWindow.setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_3d_dropdown))
        
        val density = context.resources.displayMetrics.density
        
        listPopupWindow.isModal = true
        
        // Leverage native WRAP_CONTENT to perfectly expand the width for longer options like Monthly,
        // unless a specialized fixed width is requested.
        if (fixedWidthDp != null) {
            listPopupWindow.width = (fixedWidthDp * density).toInt()
        } else {
            listPopupWindow.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        }
        
        listPopupWindow.verticalOffset = (8 * density).toInt()
        
        // If there's many items, limit height so it doesn't take the full screen
        if (items.size > 5) {
            listPopupWindow.height = (250 * density).toInt()   
        }

        listPopupWindow.setOnItemClickListener { _, _, position, _ ->
            onItemSelected(position, items[position])
            listPopupWindow.dismiss()
        }

        listPopupWindow.show()

        listPopupWindow.listView?.let { lv ->
            lv.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            lv.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
    }
}
