package com.cash.dash

import android.content.Context

object ThemeHelper {
    private const val PREFS_NAME = "ThemePrefs"
    private const val KEY_THEME = "current_theme"

    fun getSavedTheme(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_THEME, "System") ?: "System"
    }

    fun getCurrentTheme(context: Context): String {
        val savedTheme = getSavedTheme(context)
        if (savedTheme == "System") {
            val systemConfig = android.content.res.Resources.getSystem().configuration
            val currentNightMode = systemConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            return if (currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                "Black"
            } else {
                "White"
            }
        }
        return savedTheme
    }

    fun isWhiteTheme(context: Context): Boolean {
        return getCurrentTheme(context) == "White"
    }

    /**
     * Box tint for check boxes and radio buttons that are tinted at runtime.
     *
     * Mirrors color/selector_checkbox_tint.xml and adds a disabled state, which the XML
     * selector cannot express for controls that are conditionally greyed out.
     *
     * Every caller previously built its own list keyed only on state_enabled, with the
     * "enabled" colour hardcoded to white on the dark themes. A ColorStateList entry of
     * intArrayOf(state_enabled) matches an enabled box whether or not it is checked, so
     * the unchecked box was tinted solid white too and rendered as a filled white square
     * instead of an outline. Some callers used ColorStateList.valueOf(), which is worse:
     * one colour for every state at once.
     *
     * Order matters. ColorStateList returns the FIRST entry whose states are all present,
     * so disabled has to precede checked, and the empty catch-all has to come last.
     */
    fun compoundButtonTint(context: Context): android.content.res.ColorStateList {
        val disabled = android.graphics.Color.parseColor("#44888888")
        val checked = androidx.core.content.ContextCompat.getColor(context, R.color.primary_purple)
        val unchecked = resolveColorAttr(context, R.attr.textMutedColor)
        return android.content.res.ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(disabled, checked, unchecked)
        )
    }

    fun applyTheme(activity: android.app.Activity) {
        val theme = getCurrentTheme(activity)
        val targetMode = if (theme == "White") {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        } else {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        }
        if (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() != targetMode) {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(targetMode)
        }
        // Removed hardcoded override for TaptrackActivity so it follows standard theme logic
        if (activity is SplashActivity) {
            when (theme) {
                "Blue" -> activity.setTheme(R.style.Theme_Cashdash_Blue_Splash)
                "White" -> activity.setTheme(R.style.Theme_Cashdash_White) // Splash white can use base white
                else -> activity.setTheme(R.style.Theme_Cashdash_Splash)
            }
        } else {
            when (theme) {
                "Blue" -> activity.setTheme(R.style.Theme_Cashdash_Blue)
                "White" -> activity.setTheme(R.style.Theme_Cashdash_White)
                else -> activity.setTheme(R.style.Theme_Cashdash)
            }
        }
    }

    fun getDrawable(context: Context, baseResId: Int): Int {
        val currentTheme = getCurrentTheme(context)
        return when (currentTheme) {
            "Blue" -> when (baseResId) {
                R.drawable.bg_glass_3d -> R.drawable.bg_glass_3d_blue
                R.drawable.bg_3d_card -> R.drawable.bg_3d_card_blue
                R.drawable.bg_3d_dropdown -> R.drawable.bg_3d_dropdown_blue
                R.drawable.bg_3d_bottom_nav -> R.drawable.bg_3d_bottom_nav_blue
                R.drawable.bg_card_dark -> R.drawable.bg_card_dark_blue
                R.drawable.bg_glass_3d_keypad -> R.drawable.bg_glass_3d_keypad_blue
                R.drawable.bg_glass_input -> R.drawable.bg_glass_input_blue
                R.drawable.bg_glass_panel -> R.drawable.bg_glass_panel_blue
                R.drawable.bg_transaction -> R.drawable.bg_transaction_blue
                R.drawable.bg_glass_transaction -> R.drawable.bg_glass_transaction_blue
                R.drawable.bg_icon_dark -> R.drawable.bg_icon_dark_blue
                R.drawable.bg_main_gradient -> R.drawable.bg_main_gradient_blue
                else -> baseResId
            }
            "White" -> when (baseResId) {
                R.drawable.bg_glass_3d -> R.drawable.bg_glass_3d_white
                R.drawable.bg_3d_card -> R.drawable.bg_3d_card_white
                R.drawable.bg_3d_dropdown -> R.drawable.bg_3d_dropdown_white
                R.drawable.bg_3d_bottom_nav -> R.drawable.bg_3d_bottom_nav_white
                R.drawable.bg_card_dark -> R.drawable.bg_card_dark_white
                R.drawable.bg_glass_3d_keypad -> R.drawable.bg_glass_3d_keypad_white
                R.drawable.bg_glass_input -> R.drawable.bg_glass_input_white
                R.drawable.bg_glass_panel -> R.drawable.bg_glass_panel_white
                R.drawable.bg_transaction -> R.drawable.bg_transaction_white
                R.drawable.bg_glass_transaction -> R.drawable.bg_glass_transaction_white
                R.drawable.bg_icon_dark -> R.drawable.bg_icon_dark_white
                R.drawable.bg_main_gradient -> R.drawable.bg_main_gradient_white
                R.drawable.bg_glass_3d_round -> R.drawable.bg_glass_3d_round_white
                R.drawable.ic_home -> R.drawable.ic_home_black
                R.drawable.ic_allocator -> R.drawable.ic_allocation_black
                R.drawable.ic_history -> R.drawable.ic_history_black
                R.drawable.ic_scanner -> R.drawable.ic_scanner_black
                R.drawable.ic_rigor_tracker -> R.drawable.ic_rigor_tracker_black
                R.drawable.ic_glass_menu_vector -> R.drawable.ic_glass_menu_white
                R.drawable.ic_profile -> R.drawable.ic_profile_black
                else -> baseResId
            }
            else -> baseResId
        }
    }

    fun resolveColorAttr(context: Context, attrResId: Int): Int {
        val typedValue = android.util.TypedValue()
        if (context.theme.resolveAttribute(attrResId, typedValue, true)) {
            if (typedValue.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                return typedValue.data
            }
            if (typedValue.resourceId != 0) {
                try {
                    return androidx.core.content.ContextCompat.getColor(context, typedValue.resourceId)
                } catch (e: Exception) {
                    // Not a color resource (e.g. it's a drawable)
                }
            }
        }
        // Fallback for specific attributes if resolution fails
        return when (attrResId) {
            R.attr.healthGreen -> android.graphics.Color.parseColor("#26FF26")
            R.attr.healthYellow -> android.graphics.Color.parseColor("#FFC107")
            R.attr.healthRed -> android.graphics.Color.parseColor("#B71C1C")
            R.attr.progressTrackColor -> {
                // If resolving from a wrapper failed, use the current theme preference as a secondary guide
                when (getCurrentTheme(context)) {
                    "Blue" -> android.graphics.Color.parseColor("#08123A")
                    "White" -> android.graphics.Color.parseColor("#E2E8F0")
                    else -> android.graphics.Color.parseColor("#262626")
                }
            }
            else -> android.graphics.Color.WHITE
        }
    }

    fun getDatePickerTheme(context: Context): Int {
        return when (getCurrentTheme(context)) {
            "White" -> R.style.DatePickerThemeWhite
            else -> R.style.DatePickerThemeDark
        }
    }

    fun getSnackbarBackgroundColor(context: Context): Int {
        return when (getCurrentTheme(context)) {
            "White" -> android.graphics.Color.parseColor("#EFEFEF")
            "Blue"  -> android.graphics.Color.parseColor("#101C4A")
            else    -> android.graphics.Color.parseColor("#1C1C1E") // Black theme
        }
    }

    fun getSnackbarTextColor(context: Context): Int {
        return when (getCurrentTheme(context)) {
            "White" -> android.graphics.Color.parseColor("#1A1A1A")
            else    -> android.graphics.Color.parseColor("#FFFFFF")
        }
    }

    fun styleSnackbar(context: Context, snackbar: com.google.android.material.snackbar.Snackbar) {
        snackbar.setBackgroundTint(getSnackbarBackgroundColor(context))
        val textView = snackbar.view.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text)
        textView?.setTextColor(getSnackbarTextColor(context))
        snackbar.setActionTextColor(android.graphics.Color.parseColor("#FF5252"))
    }

    fun getBottomSheetTheme(context: Context): Int {
        return when (getCurrentTheme(context)) {
            "White" -> R.style.BottomSheetDialogThemeWhite
            else -> R.style.BottomSheetDialogTheme
        }
    }

    fun getResIdFromAttr(context: Context, attrResId: Int): Int {
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(attrResId, typedValue, true)
        return typedValue.resourceId
    }

    fun tintDrawableIfWhiteTheme(context: Context, drawable: android.graphics.drawable.Drawable?): android.graphics.drawable.Drawable? {
        if (drawable == null) return null
        if (isWhiteTheme(context)) {
            val wrappedDrawable = androidx.core.graphics.drawable.DrawableCompat.wrap(drawable).mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTint(wrappedDrawable, android.graphics.Color.BLACK)
            return wrappedDrawable
        }
        return drawable
    }
}
