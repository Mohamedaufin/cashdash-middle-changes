package com.cash.dash

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

object AlertDialogHelper {

    fun createFlatDialogBuilder(context: Context): FlatDialogBuilder {
        return FlatDialogBuilder(context)
    }

    /**
     * Shows a custom-styled list dialog (replaces the system setItems list dialog).
     * Each item in [items] is displayed as a tappable row; [onItemClick] receives the index.
     */
    fun showListDialog(
        context: Context,
        title: String,
        items: Array<String>,
        onItemClick: (Int) -> Unit
    ): AlertDialog {
        val density = context.resources.displayMetrics.density

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_confirm_action, null)
        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvTitle = dialogView.findViewById<TextView>(R.id.tvConfirmTitle)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvConfirmMessage)
        val btnNegative = dialogView.findViewById<Button>(R.id.btnConfirmCancel)
        val btnPositive = dialogView.findViewById<Button>(R.id.btnConfirmAction)

        tvTitle.text = title
        tvMessage.visibility = View.GONE
        btnPositive.visibility = View.GONE
        btnNegative.text = "Cancel"
        btnNegative.setOnClickListener { dialog.dismiss() }

        val rootLayout = dialogView as android.widget.LinearLayout

        // Build item rows and insert them at index 1 (after title, before button row)
        val itemsContainer = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * density).toInt() }
        }

        items.forEachIndexed { index, itemText ->
            val tv = TextView(context).apply {
                text = itemText
                textSize = 15f
                setPadding(
                    (4 * density).toInt(), (14 * density).toInt(),
                    (4 * density).toInt(), (14 * density).toInt()
                )
                setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                gravity = android.view.Gravity.CENTER
                val tv2 = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv2, true)
                setBackgroundResource(tv2.resourceId)
                isClickable = true
                isFocusable = true
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    dialog.dismiss()
                    onItemClick(index)
                }
            }
            itemsContainer.addView(tv)

            if (index < items.size - 1) {
                val divider = View(context).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    val mutedColor = ThemeHelper.resolveColorAttr(context, R.attr.textMutedColor)
                    setBackgroundColor(android.graphics.Color.argb(50,
                        android.graphics.Color.red(mutedColor),
                        android.graphics.Color.green(mutedColor),
                        android.graphics.Color.blue(mutedColor)))
                }
                itemsContainer.addView(divider)
            }
        }
        rootLayout.addView(itemsContainer, 1)

        dialog.show()
        val width = (context.resources.displayMetrics.widthPixels * 0.9).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }

    class FlatDialogBuilder(private val context: Context) {
        private var title: String? = null
        private var message: String? = null
        private var positiveText: String = "OK"
        private var negativeText: String? = null
        private var neutralText: String? = null
        private var positiveListener: (() -> Unit)? = null
        private var negativeListener: (() -> Unit)? = null
        private var neutralListener: (() -> Unit)? = null
        private var cancelable: Boolean = true
        private var titleGravity: Int = android.view.Gravity.CENTER
        private var messageGravity: Int = android.view.Gravity.CENTER
        private var messageTextSizeSp: Float? = null
        private var titleTextSizeSp: Float? = null
        private var titleTypeface: android.graphics.Typeface? = null
        private var positiveTextColor: Int? = null

        private var editTextHint: String? = null
        private var editTextCallback: ((String) -> Unit)? = null

        fun setEditText(hint: String, callback: (String) -> Unit) = apply {
            this.editTextHint = hint
            this.editTextCallback = callback
        }

        fun setTitle(title: String) = apply { this.title = title }
        fun setMessage(message: String) = apply { this.message = message }
        fun setPositiveButton(text: String, listener: (() -> Unit)? = null) = apply {
            this.positiveText = text
            this.positiveListener = listener
        }
        fun setNegativeButton(text: String, listener: (() -> Unit)? = null) = apply {
            this.negativeText = text
            this.negativeListener = listener
        }
        /** Optional third button (e.g. "Discard") rendered between Cancel and Confirm. */
        fun setNeutralButton(text: String, listener: (() -> Unit)? = null) = apply {
            this.neutralText = text
            this.neutralListener = listener
        }
        fun setCancelable(cancelable: Boolean) = apply { this.cancelable = cancelable }
        fun setTitleGravity(gravity: Int) = apply { this.titleGravity = gravity }
        fun setMessageGravity(gravity: Int) = apply { this.messageGravity = gravity }
        fun setMessageTextSize(sp: Float) = apply { this.messageTextSizeSp = sp }
        fun setTitleTextSize(sp: Float) = apply { this.titleTextSizeSp = sp }
        fun setTitleTypeface(typeface: android.graphics.Typeface) = apply { this.titleTypeface = typeface }
        fun setPositiveTextColor(color: Int) = apply { this.positiveTextColor = color }

        fun show(): AlertDialog {
            val density = context.resources.displayMetrics.density
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_confirm_action, null)
            val dialog = AlertDialog.Builder(context)
                .setView(dialogView)
                .setCancelable(cancelable)
                .create()

            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            val tvTitle = dialogView.findViewById<TextView>(R.id.tvConfirmTitle)
            val tvMessage = dialogView.findViewById<TextView>(R.id.tvConfirmMessage)
            val btnPositive = dialogView.findViewById<Button>(R.id.btnConfirmAction)
            val btnNegative = dialogView.findViewById<Button>(R.id.btnConfirmCancel)

            tvTitle.text = title ?: "Notice"
            tvMessage.text = message ?: ""
            btnPositive.text = positiveText

            tvTitle.gravity = titleGravity
            tvMessage.gravity = messageGravity
            titleTextSizeSp?.let { tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, it) }
            messageTextSizeSp?.let { tvMessage.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, it) }
            titleTypeface?.let { tvTitle.typeface = it }
            positiveTextColor?.let { btnPositive.setTextColor(it) }
            
            if (negativeText != null) {
                btnNegative.text = negativeText
                btnNegative.visibility = View.VISIBLE
            } else {
                btnNegative.visibility = View.GONE
            }

            // Neutral button: injected between Cancel and Confirm
            if (neutralText != null) {
                val btnRow = btnNegative.parent as? android.widget.LinearLayout
                val btnNeutral = Button(context).apply {
                    text = neutralText
                    isAllCaps = false
                    textSize = 14f
                    setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                    val tv2 = android.util.TypedValue()
                    context.theme.resolveAttribute(R.attr.cardBackground, tv2, true)
                    setBackgroundResource(tv2.resourceId)
                    stateListAnimator = null
                    elevation = 0f
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, (54 * density).toInt(), 1f).apply {
                        marginStart = (4 * density).toInt()
                        marginEnd = (4 * density).toInt()
                    }
                    setOnClickListener {
                        dialog.dismiss()
                        neutralListener?.invoke()
                    }
                }
                btnRow?.addView(btnNeutral, 1)
                (btnNegative.layoutParams as? android.widget.LinearLayout.LayoutParams)?.apply {
                    marginEnd = (4 * density).toInt()
                }
                (btnPositive.layoutParams as? android.widget.LinearLayout.LayoutParams)?.apply {
                    marginStart = (4 * density).toInt()
                }
            }

            if (editTextHint != null) {
                val et = android.widget.EditText(context).apply {
                    hint = editTextHint
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (16 * density).toInt()
                    }
                    setPadding(
                        (16 * density).toInt(),
                        (12 * density).toInt(),
                        (16 * density).toInt(),
                        (12 * density).toInt()
                    )
                    val tvInputBg = android.util.TypedValue()
                    context.theme.resolveAttribute(R.attr.inputBackground, tvInputBg, true)
                    setBackgroundResource(tvInputBg.resourceId)
                    setTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textPrimaryColor))
                    setHintTextColor(ThemeHelper.resolveColorAttr(context, R.attr.textMutedColor))
                    textSize = 14f
                }
                
                val parent = tvMessage.parent as? android.widget.LinearLayout
                if (parent != null) {
                    val index = parent.indexOfChild(tvMessage)
                    parent.addView(et, index + 1)
                }
                
                btnPositive.setOnClickListener {
                    dialog.dismiss()
                    editTextCallback?.invoke(et.text.toString())
                    positiveListener?.invoke()
                }
            } else {
                btnPositive.setOnClickListener {
                    dialog.dismiss()
                    positiveListener?.invoke()
                }
            }

            btnNegative.setOnClickListener {
                dialog.dismiss()
                negativeListener?.invoke()
            }

            dialog.show()
            
            // Adjust width
            val width = (context.resources.displayMetrics.widthPixels * 0.9).toInt()
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            
            return dialog
        }
    }
}
