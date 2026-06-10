package com.cash.dash

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

object TutorialManager {

    private const val PREFS_NAME = "CashDashTutorials_v2"

    fun showTutorialIfNeeded(activity: Activity, tutorialKey: String, title: String, message: String) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        if (prefs.getBoolean(tutorialKey, false)) {
            // Already seen
            return
        }

        val density = activity.resources.displayMetrics.density
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (28 * density).toInt()
            setPadding(p, p, p, (24 * density).toInt())
            setBackgroundResource(ThemeHelper.getDrawable(context, R.drawable.bg_transaction))
        }

        val scrollBox = android.widget.ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val innerBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        val titleView = TextView(activity).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            setTextColor(ThemeHelper.resolveColorAttr(activity, R.attr.textPrimaryColor))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (20 * density).toInt())
        }
        innerBox.addView(titleView)

        val introView = TextView(activity).apply {
            text = message
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(ThemeHelper.resolveColorAttr(activity, R.attr.textPrimaryColor))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (32 * density).toInt())
            setLineSpacing(8f, 1f)
        }
        innerBox.addView(introView)
        scrollBox.addView(innerBox)
        box.addView(scrollBox)

        val dialog = AlertDialog.Builder(activity).setView(box).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(false) // Force them to acknowledge

        val btnUnderstood = Button(activity).apply {
            text = "Understood"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(ThemeHelper.resolveColorAttr(activity, R.attr.textPrimaryColor))
            val tv = TypedValue()
            activity.theme.resolveAttribute(R.attr.cardBackground, tv, true)
            background = ContextCompat.getDrawable(context, tv.resourceId)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            minHeight = 150
            setPadding(30, 30, 30, 30)
            setOnClickListener {
                prefs.edit().putBoolean(tutorialKey, true).apply()
                dialog.dismiss()
            }
        }

        box.addView(btnUnderstood)
        dialog.show()
    }
}
