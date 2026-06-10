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

    class FlatDialogBuilder(private val context: Context) {
        private var title: String? = null
        private var message: String? = null
        private var positiveText: String = "OK"
        private var negativeText: String? = null
        private var positiveListener: (() -> Unit)? = null
        private var negativeListener: (() -> Unit)? = null
        private var cancelable: Boolean = true
        private var titleGravity: Int = android.view.Gravity.CENTER
        private var messageGravity: Int = android.view.Gravity.CENTER
        private var messageTextSizeSp: Float? = null
        private var titleTextSizeSp: Float? = null
        private var titleTypeface: android.graphics.Typeface? = null
        private var positiveTextColor: Int? = null

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
        fun setCancelable(cancelable: Boolean) = apply { this.cancelable = cancelable }
        fun setTitleGravity(gravity: Int) = apply { this.titleGravity = gravity }
        fun setMessageGravity(gravity: Int) = apply { this.messageGravity = gravity }
        fun setMessageTextSize(sp: Float) = apply { this.messageTextSizeSp = sp }
        fun setTitleTextSize(sp: Float) = apply { this.titleTextSizeSp = sp }
        fun setTitleTypeface(typeface: android.graphics.Typeface) = apply { this.titleTypeface = typeface }
        fun setPositiveTextColor(color: Int) = apply { this.positiveTextColor = color }

        fun show(): AlertDialog {
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

            btnPositive.setOnClickListener {
                dialog.dismiss()
                positiveListener?.invoke()
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
