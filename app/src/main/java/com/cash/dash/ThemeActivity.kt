@file:Suppress("DEPRECATION")
package com.cash.dash

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.view.ContextThemeWrapper
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class ThemeActivity : ThemedActivity() {

    private lateinit var rootLayout: View
    private lateinit var btnBack: ImageButton
    private lateinit var tvTitle: TextView
    private lateinit var header: View
    
    private lateinit var themeBlack: View
    private lateinit var themeBlue: View
    private lateinit var themeWhite: View
    
    private lateinit var btnModeGradient: TextView
    private lateinit var btnModeSingle: TextView
    private lateinit var layoutGradientOptions: View
    private lateinit var layoutSingleOptions: View
    private lateinit var previewProgress: GradientCircularProgressView
    private lateinit var previewContainer: View
    private lateinit var btnApply: TextView
    
    private lateinit var btnGradient1: TextView
    private lateinit var btnGradient2: TextView
    
    private var initialAppTheme = "Black"
    private var initialBBMode = "gradient"
    private var initialBBType = "gradient1"

    private var selectedAppTheme = "Black"
    private var selectedBBMode = "gradient"
    private var selectedBBType = "gradient1"
    private var activeTabMode = "gradient"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme)

        rootLayout = findViewById(R.id.rootLayout)
        btnBack = findViewById(R.id.btnBack)
        tvTitle = findViewById(R.id.tvTitle)
        header = findViewById(R.id.header)

        btnBack.setOnClickListener { handleBackAction() }

        // Initialize App Theme Views
        themeBlack = findViewById(R.id.themeBlack)
        themeBlue = findViewById(R.id.themeBlue)
        themeWhite = findViewById(R.id.themeWhite)

        // Initialize Balance Bar Views
        btnModeGradient = findViewById(R.id.btnModeGradient)
        btnModeSingle = findViewById(R.id.btnModeSingle)
        layoutGradientOptions = findViewById(R.id.layoutGradientOptions)
        layoutSingleOptions = findViewById(R.id.layoutSingleOptions)
        previewProgress = findViewById(R.id.previewProgress)
        previewContainer = (previewProgress.parent as? View) ?: rootLayout
        
        btnGradient1 = findViewById(R.id.btnGradient1)
        btnGradient2 = findViewById(R.id.btnGradient2)
        btnApply = findViewById(R.id.btnApply)

        // Handle System Insets
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val extraPadding = (resources.displayMetrics.heightPixels * 0.03).toInt() 
            v.setPadding(0, systemBars.top + extraPadding, 0, systemBars.bottom)
            insets
        }

        // Load existing settings
        val themePrefs = getSharedPreferences("ThemePrefs", Context.MODE_PRIVATE)
        initialAppTheme = themePrefs.getString("current_theme", "Black") ?: "Black"
        selectedAppTheme = initialAppTheme

        val bbPrefs = getSharedPreferences("WalletPrefs", Context.MODE_PRIVATE)
        initialBBMode = bbPrefs.getString("balance_bar_mode", "gradient") ?: "gradient"
        initialBBType = bbPrefs.getString("balance_bar_type", "gradient1") ?: "gradient1"
        selectedBBMode = initialBBMode
        selectedBBType = initialBBType
        activeTabMode = initialBBMode

        initClickListeners()
        updateUI(this) 
        previewProgress.setProgressCompat(75, false)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackAction()
            }
        })
    }

    private fun initClickListeners() {
        themeBlack.setOnClickListener { selectedAppTheme = "Black"; applyThemePreview("Black") }
        themeBlue.setOnClickListener { selectedAppTheme = "Blue"; applyThemePreview("Blue") }
        themeWhite.setOnClickListener { selectedAppTheme = "White"; applyThemePreview("White") }

        btnModeGradient.setOnClickListener {
            activeTabMode = "gradient"
            updatePreviewUI(animate = false)
        }

        btnModeSingle.setOnClickListener {
            activeTabMode = "single"
            updatePreviewUI(animate = false)
        }

        btnGradient1.setOnClickListener { selectedBBMode = "gradient"; selectedBBType = "gradient1"; updatePreviewUI() }
        btnGradient2.setOnClickListener { selectedBBMode = "gradient"; selectedBBType = "gradient2"; updatePreviewUI() }

        findViewById<View>(R.id.colorPurple).setOnClickListener { selectedBBMode = "single"; selectedBBType = "purple"; updatePreviewUI() }
        findViewById<View>(R.id.colorYellow).setOnClickListener { selectedBBMode = "single"; selectedBBType = "yellow"; updatePreviewUI() }
        findViewById<View>(R.id.colorRed).setOnClickListener { selectedBBMode = "single"; selectedBBType = "red"; updatePreviewUI() }
        findViewById<View>(R.id.colorGreen).setOnClickListener { selectedBBMode = "single"; selectedBBType = "green"; updatePreviewUI() }
        findViewById<View>(R.id.colorBlack).setOnClickListener { selectedBBMode = "single"; selectedBBType = "black"; updatePreviewUI() }
        findViewById<View>(R.id.colorWhite).setOnClickListener { selectedBBMode = "single"; selectedBBType = "white"; updatePreviewUI() }

        btnApply.setOnClickListener {
            handleApplyAction()
        }
    }

    private fun updatePreviewUI(animate: Boolean = true) {
        val themeRes = getThemeResId(selectedAppTheme)
        val wrapper = ContextThemeWrapper(this, themeRes)
        updateUI(wrapper)
        if (animate) {
            previewProgress.startLoadingAnimation()
        }
    }

    private fun updateUI(ctx: Context) {
        val activeText = ThemeHelper.resolveColorAttr(ctx, R.attr.textPrimaryColor)
        val mutedText = ThemeHelper.resolveColorAttr(ctx, R.attr.textMutedColor)

        updateCardSelection(ctx, themeBlack, selectedAppTheme == "Black", activeText, mutedText)
        updateCardSelection(ctx, themeBlue, selectedAppTheme == "Blue", activeText, mutedText)
        updateCardSelection(ctx, themeWhite, selectedAppTheme == "White", activeText, mutedText)

        updateModeButton(ctx, btnModeGradient, activeTabMode == "gradient", activeText, mutedText)
        updateModeButton(ctx, btnModeSingle, activeTabMode == "single", activeText, mutedText)

        if (activeTabMode == "gradient") {
            layoutGradientOptions.visibility = View.VISIBLE
            layoutSingleOptions.visibility = View.GONE
            updateModeButton(ctx, btnGradient1, selectedBBType == "gradient1", activeText, mutedText)
            updateModeButton(ctx, btnGradient2, selectedBBType == "gradient2", activeText, mutedText)
        } else {
            layoutGradientOptions.visibility = View.GONE
            layoutSingleOptions.visibility = View.VISIBLE
            updateSingleColors(selectedBBType)
        }

        previewProgress.setColorConfig(selectedBBMode, selectedBBType, selectedAppTheme)
    }

    private fun updateCardSelection(ctx: Context, card: View, isSelected: Boolean, activeText: Int, mutedText: Int) {
        val resId = getResIdFromAttr(ctx, R.attr.cardBackground)
        card.setBackgroundResource(resId)
        val text = (card as LinearLayout).getChildAt(1) as TextView
        text.setTextColor(if (isSelected) activeText else mutedText)
        text.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        card.animate().scaleX(if (isSelected) 1.08f else 1.0f).scaleY(if (isSelected) 1.08f else 1.0f).setDuration(200).start()
    }

    private fun updateModeButton(ctx: Context, btn: TextView, isSelected: Boolean, activeText: Int, mutedText: Int) {
        val resId = getResIdFromAttr(ctx, R.attr.cardBackground)
        btn.setBackgroundResource(resId)
        btn.setTextColor(if (isSelected) activeText else mutedText)
        btn.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        btn.animate().scaleX(if (isSelected) 1.08f else 1.0f).scaleY(if (isSelected) 1.08f else 1.0f).setDuration(200).start()
    }

    private fun updateSingleColors(type: String) {
        val colorIds = listOf(R.id.colorPurple, R.id.colorYellow, R.id.colorRed, R.id.colorGreen, R.id.colorBlack, R.id.colorWhite)
        colorIds.forEach { id ->
            val view = findViewById<View>(id)
            val isSelected = when(id) {
                R.id.colorPurple -> type == "purple"
                R.id.colorYellow -> type == "yellow"
                R.id.colorRed -> type == "red"
                R.id.colorGreen -> type == "green"
                R.id.colorBlack -> type == "black"
                R.id.colorWhite -> type == "white"
                else -> false
            }
            view.animate().scaleX(if (isSelected) 1.25f else 1.0f).scaleY(if (isSelected) 1.25f else 1.0f).setDuration(200).start()
        }
    }

    private fun applyThemePreview(themeName: String) {
        val themeRes = getThemeResId(themeName)
        val wrapper = ContextThemeWrapper(this, themeRes)
        
        rootLayout.setBackgroundResource(getResIdFromAttr(wrapper, R.attr.mainBackground))
        btnBack.setBackgroundResource(getResIdFromAttr(wrapper, R.attr.roundBackground))
        previewContainer.setBackgroundResource(getResIdFromAttr(wrapper, R.attr.panelBackground))
        
        val primaryCol = ThemeHelper.resolveColorAttr(wrapper, R.attr.textPrimaryColor)
        val mutedCol = ThemeHelper.resolveColorAttr(wrapper, R.attr.textMutedColor)
        
        btnBack.setColorFilter(primaryCol)
        tvTitle.setTextColor(primaryCol)
        btnApply.setTextColor(primaryCol)
        btnApply.setBackgroundResource(getResIdFromAttr(wrapper, R.attr.cardBackground))
        
        updateGlobalTextColors(rootLayout, primaryCol, mutedCol)
        updateUI(wrapper)
    }

    private fun updateGlobalTextColors(root: View, primary: Int, muted: Int) {
        if (root is TextView && root != btnApply && root != btnModeGradient && root != btnModeSingle && root != btnGradient1 && root != btnGradient2) {
            if (root.typeface?.isBold == true || root.id == R.id.tvTitle) root.setTextColor(primary)
            else root.setTextColor(muted)
        } else if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) updateGlobalTextColors(root.getChildAt(i), primary, muted)
        }
    }

    private fun getThemeResId(name: String): Int = when(name) {
        "Blue" -> R.style.Theme_Cashdash_Blue
        "White" -> R.style.Theme_Cashdash_White
        else -> R.style.Theme_Cashdash
    }

    private fun getResIdFromAttr(ctx: Context, attr: Int): Int {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(attr, tv, true)
        return tv.resourceId
    }

    private fun handleApplyAction() {
        val themeChanged = selectedAppTheme != initialAppTheme
        val bbChanged = selectedBBMode != initialBBMode || selectedBBType != initialBBType
        
        if (themeChanged) {
            val themeRes = getThemeResId(selectedAppTheme)
            val themedContext = ContextThemeWrapper(this, themeRes)
            
            AlertDialogHelper.createFlatDialogBuilder(themedContext)
                .setTitle("Apply Changes")
                .setMessage("Application will restart to apply the new theme. Proceed?")
                .setPositiveButton("Proceed") {
                    saveSettings()
                    val toastText = if (bbChanged) "Appearance Updated ✓" else "Theme Updated ✓"
                    ToastHelper.showCustomToast(this, toastText, 800L)
                    navigateToHome()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else if (bbChanged) {
            saveSettings()
            ToastHelper.showCustomToast(this, "Balance Bar Updated ✓", 800L)
            navigateToHome()
        } else {
            finish()
        }
    }

    private fun handleBackAction() {
        if (selectedAppTheme != initialAppTheme || selectedBBMode != initialBBMode || selectedBBType != initialBBType) {
            val themeRes = getThemeResId(selectedAppTheme) // Use current preview theme for dialog
            val themedContext = ContextThemeWrapper(this, themeRes)
            
            AlertDialogHelper.createFlatDialogBuilder(themedContext)
                .setTitle("Discard Changes?")
                .setMessage("You have unsaved changes. Exit without applying?")
                .setPositiveButton("Exit") { finish() }
                .setNegativeButton("Cancel", null)
                .show()
        } else finish()
    }

    private fun saveSettings() {
        getSharedPreferences("ThemePrefs", Context.MODE_PRIVATE).edit().putString("current_theme", selectedAppTheme).apply()
        getSharedPreferences("WalletPrefs", Context.MODE_PRIVATE).edit()
            .putString("balance_bar_mode", selectedBBMode)
            .putString("balance_bar_type", selectedBBType)
            .apply()
        FirestoreSyncManager.pushAllDataToCloud(this)
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(FirestoreSyncManager.ACTION_SYNC_UPDATE))
    }

    private fun navigateToHome() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
