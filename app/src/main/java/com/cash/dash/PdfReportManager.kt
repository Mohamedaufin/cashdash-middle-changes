package com.cash.dash

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.OutputStream
import java.util.Calendar
import java.util.Date
import java.util.Locale

object PdfReportManager {

    fun generateAndSavePremiumReport(context: Context, startMillis: Long, endMillis: Long) {
        val cal = Calendar.getInstance().apply { timeInMillis = startMillis }
        val month = cal.get(Calendar.MONTH)
        val year = cal.get(Calendar.YEAR)
        val insights = FinancialInsightsManager.generateReport(context, true, month, year)
        val breakdown = HistoryDataManager.getCategoryBreakdownForRange(context, startMillis, endMillis)
        
        val transactions = breakdown.transactions.sortedBy { entry ->
            val p = entry.rawEntry.split("|")
            if (p.size >= 2) p[1].toLongOrNull() ?: 0L else 0L
        }

        val document = PdfDocument()
        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNum).create()
        var page = document.startPage(pageInfo)
        var canvas = page.canvas

        val bgPaint = Paint().apply { color = Color.parseColor("#0C0C0F") }
        val accentColor = Color.parseColor("#7C5CFC")
        val paint = Paint().apply { isAntiAlias = true; color = Color.WHITE }
        val cardPaint = Paint().apply { color = Color.parseColor("#0F0F14") }

        fun startNextPage() {
            document.finishPage(page)
            pageNum++
            pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNum).create()
            page = document.startPage(pageInfo)
            canvas = page.canvas
            canvas.drawRect(0f, 0f, 595f, 842f, bgPaint)
        }

        // --- PAGE 1: EXECUTIVE DASHBOARD ---
        canvas.drawRect(0f, 0f, 595f, 842f, bgPaint)
        
        paint.textSize = 28f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("CASHDASH ADVISORY", 40f, 65f, paint)

        val sdfYear = java.text.SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        paint.textSize = 12f
        paint.color = Color.parseColor("#7E7D96")
        paint.typeface = Typeface.DEFAULT
        canvas.drawText("EXECUTIVE FINANCIAL SUMMARY: ${insights.periodLabel}", 40f, 88f, paint)

        // Totals Card
        canvas.drawRoundRect(40f, 120f, 280f, 205f, 12f, 12f, cardPaint)
        paint.color = Color.WHITE
        paint.textSize = 10f
        paint.alpha = 140
        canvas.drawText("TOTAL EXPENDITURE", 60f, 150f, paint)
        paint.alpha = 255
        paint.textSize = 32f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("₹${insights.totalSpent.toInt()}", 60f, 185f, paint)

        // Health Score Card
        canvas.drawRoundRect(300f, 120f, 550f, 205f, 12f, 12f, cardPaint)
        paint.color = accentColor
        paint.textSize = 10f
        canvas.drawText("FINANCIAL HEALTH", 320f, 150f, paint)
        paint.color = Color.WHITE
        paint.textSize = 24f
        canvas.drawText("${insights.score}/100", 320f, 185f, paint)

        // Category breakdown overview
        var yCat = 240f
        paint.textSize = 14f
        paint.color = Color.WHITE
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("Top Capital Allocations", 40f, yCat, paint)
        yCat += 25f
        insights.topCategories.take(5).forEach { catSummary ->
            paint.color = Color.parseColor("#7E7D96")
            paint.typeface = Typeface.DEFAULT
            paint.textSize = 11f
            canvas.drawText("${catSummary.category.uppercase()} : ₹${catSummary.amount.toInt()} (${catSummary.percentage.toInt()}%)", 45f, yCat, paint)
            yCat += 18f
        }

        // 3D Chart Embed
        draw3DPieChart(canvas, insights.topCategories, 420f, 310f, 90f)

        // Behavioral Highlights
        var yInsights = 440f
        paint.textSize = 14f
        paint.color = accentColor
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("Behavioral Pattern Analysis", 40f, yInsights, paint)
        
        yInsights += 30f
        paint.textSize = 11f
        paint.typeface = Typeface.DEFAULT
        insights.habitInsights.take(3).forEach { habitInsight ->
            canvas.drawRoundRect(40f, yInsights, 555f, yInsights + 35f, 8f, 8f, cardPaint)
            paint.color = Color.WHITE
            canvas.drawText("• ${habitInsight.message}", 55f, yInsights + 22f, paint)
            yInsights += 42f
        }

        // Strategic Suggestions (Savings Opportunity & Alerts)
        yInsights += 10f
        paint.textSize = 14f
        paint.color = accentColor
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("Integrity Alerts & Opportunities", 40f, yInsights, paint)
        
        yInsights += 30f
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 11f
        
        // Savings Opp
        canvas.drawRoundRect(40f, yInsights, 555f, yInsights + 35f, 8f, 8f, cardPaint)
        paint.color = Color.parseColor("#00F5FF")
        canvas.drawText("SAVINGS: ${insights.savingsOpportunity}", 55f, yInsights + 22f, paint)
        yInsights += 42f
        
        // Alerts
        insights.alerts.take(2).forEach { alert ->
            canvas.drawRoundRect(40f, yInsights, 555f, yInsights + 35f, 8f, 8f, cardPaint)
            paint.color = if (alert.severity == 2) Color.RED else Color.YELLOW
            canvas.drawText("ALERT: ${alert.title} - ${alert.message}", 55f, yInsights + 22f, paint)
            yInsights += 42f
        }

        // Footer on last page
        paint.textSize = 9f
        paint.color = Color.parseColor("#4A495E")
        canvas.drawText("Generated by CashDash Executive Reporting Engine | Strategic Summary Only", 40f, 820f, paint)

        document.finishPage(page)

        val fileName = "CashDash_Strategy_Summary_${System.currentTimeMillis()}.pdf"
        savePdfToDownloads(context, document, fileName)
    }

    fun generateStandaloneStatement(context: Context, startMillis: Long, endMillis: Long) {
        val breakdown = HistoryDataManager.getCategoryBreakdownForRange(context, startMillis, endMillis)
        val transactions = breakdown.transactions.sortedByDescending { entry ->
            val p = entry.rawEntry.split("|")
            if (p.size >= 2) p[1].toLongOrNull() ?: 0L else 0L
        }

        val document = PdfDocument()
        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNum).create()
        var page = document.startPage(pageInfo)
        var canvas = page.canvas

        val bgPaint = Paint().apply { color = Color.parseColor("#0C0C0F") }
        val accentColor = Color.parseColor("#7C5CFC")
        val paint = Paint().apply { isAntiAlias = true; color = Color.WHITE }
        val cardPaint = Paint().apply { color = Color.parseColor("#0F0F14") }

        fun startNextPage() {
            document.finishPage(page)
            pageNum++
            pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNum).create()
            page = document.startPage(pageInfo)
            canvas = page.canvas
            canvas.drawRect(0f, 0f, 595f, 842f, bgPaint)
        }

        canvas.drawRect(0f, 0f, 595f, 842f, bgPaint)
        
        paint.textSize = 24f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("TRANSACTION STATEMENT", 40f, 60f, paint)
        
        paint.textSize = 10f
        paint.color = Color.parseColor("#7E7D96")
        val sdf = java.text.SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        canvas.drawText("PERIOD: ${sdf.format(Date(startMillis))} - ${sdf.format(Date(endMillis))}", 40f, 82f, paint)
        
        var yTable = 120f
        paint.textSize = 10f
        paint.color = Color.parseColor("#7E7D96")
        canvas.drawText("DATE", 40f, yTable, paint)
        canvas.drawText("DESCRIPTION", 110f, yTable, paint)
        canvas.drawText("CATEGORY", 360f, yTable, paint)
        canvas.drawText("AMOUNT", 490f, yTable, paint)
        
        yTable += 8f
        paint.color = Color.parseColor("#1F1F26")
        canvas.drawLine(40f, yTable, 555f, yTable, paint)
        
        yTable += 22f
        val sdfShort = java.text.SimpleDateFormat("dd MMM", Locale.getDefault())
        paint.color = Color.WHITE
        paint.typeface = Typeface.DEFAULT
        
        transactions.forEach { item ->
            if (yTable > 780) {
                startNextPage()
                yTable = 60f
                paint.color = Color.parseColor("#7E7D96")
                paint.textSize = 8f
                canvas.drawText("...Statement Continued", 40f, 40f, paint)
                yTable = 70f
            }
            
            val p = item.rawEntry.split("|")
            val ts = if (p.size >= 2) p[1].toLongOrNull() ?: 0L else 0L
            
            paint.textSize = 10f
            paint.color = Color.WHITE
            canvas.drawText(sdfShort.format(Date(ts)), 40f, yTable, paint)
            
            val displayTitle = if (item.title.length > 28) item.title.take(25) + "..." else item.title
            canvas.drawText(displayTitle, 110f, yTable, paint)
            
            val displayCat = item.category.uppercase()
            paint.color = Color.parseColor("#7E7D96")
            canvas.drawText(displayCat, 360f, yTable, paint)
            
            paint.color = Color.WHITE
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("₹${item.amount}", 490f, yTable, paint)
            paint.typeface = Typeface.DEFAULT

            yTable += 22f
        }

        yTable += 20f
        if (yTable > 750) startNextPage()
        paint.color = Color.parseColor("#1F1F26")
        canvas.drawRect(40f, yTable, 555f, yTable + 40f, paint)
        paint.color = Color.WHITE
        paint.textSize = 14f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("CUMULATIVE EXPENDITURE", 60f, yTable + 26f, paint)
        canvas.drawText("₹${transactions.sumOf { it.amount.toDouble() }.toInt()}", 430f, yTable + 26f, paint)

        document.finishPage(page)
        val fileName = "CashDash_Statement_${System.currentTimeMillis()}.pdf"
        savePdfToDownloads(context, document, fileName)
    }

    private fun savePdfToDownloads(context: Context, document: PdfDocument, fileName: String) {
        try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
            }
            
            val externalUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Files.getContentUri("external")
            }

            val uri = resolver.insert(externalUri, contentValues)
            uri?.let {
                resolver.openOutputStream(it)?.use { os -> document.writeTo(os) }
                Toast.makeText(context, "Report saved to Downloads", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Report export failed", Toast.LENGTH_SHORT).show()
        } finally {
            document.close()
        }
    }

    private fun draw3DPieChart(canvas: Canvas, data: List<HistoryReportGenerator.CategorySummary>, cx: Float, cy: Float, radius: Float) {
        if (data.isEmpty()) return
        
        val colorPalette = intArrayOf(
            Color.parseColor("#7C5CFC"), Color.parseColor("#FCA311"), 
            Color.parseColor("#00F5FF"), Color.parseColor("#FF4D6D"), Color.parseColor("#70E000")
        )
        
        val thickness = 30f
        val tilt = 0.6f
        val rect = RectF(cx - radius, cy - radius * tilt, cx + radius, cy + radius * tilt)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { 
            textSize = 18f
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // Shadow under chart
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            alpha = 100
            maskFilter = android.graphics.BlurMaskFilter(15f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawOval(cx - radius, cy + thickness + radius * 0.2f, cx + radius, cy + thickness + radius * 0.5f, shadowPaint)

        var startAngle = 0f
        // Sides
        data.forEachIndexed { i, s ->
            val sweep = (s.percentage / 100f) * 360f
            val hsv = FloatArray(3)
            Color.colorToHSV(colorPalette[i % colorPalette.size], hsv)
            hsv[2] *= 0.55f
            paint.color = Color.HSVToColor(hsv)
            
            for (off in 1..thickness.toInt() step 2) {
                val sideRect = RectF(rect.left, rect.top + off, rect.right, rect.bottom + off)
                canvas.drawArc(sideRect, startAngle, sweep, true, paint)
            }
            startAngle += sweep
        }

        // Top Faces
        startAngle = 0f
        data.forEachIndexed { i, s ->
            val sweep = (s.percentage / 100f) * 360f
            paint.color = colorPalette[i % colorPalette.size]
            canvas.drawArc(rect, startAngle, sweep, true, paint)
            
            if (s.percentage > 5) {
                val rad = Math.toRadians((startAngle + sweep / 2).toDouble())
                val lx = cx + (radius * 0.7f) * Math.cos(rad).toFloat()
                val ly = cy + (radius * tilt * 0.7f) * Math.sin(rad).toFloat()
                canvas.drawText("${s.percentage.toInt()}%", lx, ly, textPaint)
            }
            startAngle += sweep
        }
    }
}
