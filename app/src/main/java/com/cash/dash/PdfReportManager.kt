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

    fun generateAndSavePremiumReport(context: Context, startMillis: Long, endMillis: Long, isMonthly: Boolean, weekIndex: Int = -1) {
        val cal = Calendar.getInstance().apply { timeInMillis = startMillis }
        val month = cal.get(Calendar.MONTH)
        val year = cal.get(Calendar.YEAR)
        val insights = FinancialInsightsManager.generateReport(context, isMonthly, month, year, weekIndex)
        
        val document = PdfDocument()
        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNum).create()
        var page = document.startPage(pageInfo)
        var canvas = page.canvas

        val bgPaint = Paint().apply { color = Color.parseColor("#0C0C0F") }
        val accentColor = Color.parseColor("#7C5CFC")
        val paint = Paint().apply { isAntiAlias = true; color = Color.WHITE }
        val cardPaint = Paint().apply { color = Color.parseColor("#0F0F14") }

        // --- PAGE 1: EXECUTIVE DASHBOARD ---
        canvas.drawRect(0f, 0f, 595f, 842f, bgPaint)
        
        paint.textSize = 28f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("CASHDASH ADVISORY", 40f, 65f, paint)

        paint.textSize = 12f
        paint.color = Color.parseColor("#7E7D96")
        paint.typeface = Typeface.DEFAULT
        canvas.drawText("EXECUTIVE FINANCIAL SUMMARY: ${insights.periodLabel}", 40f, 88f, paint)

        // Totals Card
        canvas.drawRoundRect(40f, 120f, 555f, 205f, 12f, 12f, cardPaint)
        paint.color = Color.WHITE
        paint.textSize = 10f
        paint.alpha = 140
        canvas.drawText("TOTAL EXPENDITURE", 60f, 153f, paint)
        paint.alpha = 255
        paint.textSize = 32f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("₹${insights.totalSpent.toInt()}", 60f, 185f, paint)
        
        paint.textSize = 12f
        paint.alpha = 180
        val compText = "vs previous: ${if (insights.changePercent >= 0) "+" else ""}${insights.changePercent.toInt()}%"
        canvas.drawText(compText, 440f, 185f, paint)

        // Category breakdown overview
        var yPos = 240f
        paint.alpha = 255
        paint.textSize = 14f
        paint.color = Color.WHITE
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("TOTAL SPENT: ₹${insights.totalSpent.toInt()} | ${insights.periodLabel}", 40f, yPos, paint)
        yPos += 25f

        val colorPalette = intArrayOf(
            Color.parseColor("#7C5CFC"), Color.parseColor("#FCA311"), 
            Color.parseColor("#00F5FF"), Color.parseColor("#FF4D6D"), Color.parseColor("#70E000")
        )

        insights.topCategories.forEachIndexed { index, catSummary ->
            if (yPos > 400) return@forEachIndexed // Simple constraint for page 1
            paint.color = colorPalette[index % colorPalette.size]
            canvas.drawCircle(45f, yPos - 3f, 4f, paint)

            paint.color = Color.parseColor("#7E7D96")
            paint.typeface = Typeface.DEFAULT
            paint.textSize = 10f
            canvas.drawText("${catSummary.category.uppercase()} : ₹${catSummary.amount.toInt()} (${catSummary.percentage.toInt()}%)", 55f, yPos, paint)
            yPos += 16f
        }

        // 3D Chart Embed (Filter for > 0)
        draw3DPieChart(canvas, insights.topCategories.filter { it.amount > 0 }, 420f, 310f, 90f)

        // Patterns & Optimization
        yPos = 440f
        paint.textSize = 14f
        paint.color = accentColor
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("Strategic Optimization", 40f, yPos, paint)
        
        yPos += 30f
        paint.textSize = 11f
        paint.typeface = Typeface.DEFAULT
        
        // Savings Opp Card
        canvas.drawRoundRect(40f, yPos, 555f, yPos + 60f, 8f, 8f, cardPaint)
        paint.color = Color.WHITE
        val oppLines = insights.savingsOpportunity.chunked(70)
        oppLines.forEachIndexed { i, line ->
            canvas.drawText(line, 55f, yPos + 25f + (i * 15f), paint)
        }
        yPos += 80f

        // Pattern Card
        if (isMonthly) {
            val topWeek = insights.topWeeks.firstOrNull()
            canvas.drawRoundRect(40f, yPos, 555f, yPos + 40f, 8f, 8f, cardPaint)
            paint.color = Color.parseColor("#00F5FF")
            canvas.drawText("PEAK WEEK: ${topWeek?.weekLabel ?: "N/A"} - ₹${topWeek?.amount?.toInt() ?: 0}", 55f, yPos + 25f, paint)
        } else {
            val peak = insights.dailyPatterns.find { it.isPeak }
            canvas.drawRoundRect(40f, yPos, 555f, yPos + 40f, 8f, 8f, cardPaint)
            paint.color = Color.parseColor("#00F5FF")
            canvas.drawText("PEAK DAY: ${peak?.dayLabel ?: "N/A"} - ₹${peak?.amount?.toInt() ?: 0}", 55f, yPos + 25f, paint)
        }


        // Footer
        paint.textSize = 9f
        paint.color = Color.parseColor("#4A495E")
        canvas.drawText("Generated by CashDash Executive Reporting Engine | Strategic Summary Only", 40f, 820f, paint)

        document.finishPage(page)

        val monthName = java.text.DateFormatSymbols().months[month]
        val fileName = if (isMonthly) "Cashdash_${monthName}_Report.pdf" else "CashDash_Weekly_Report.pdf"
        savePdfToDownloads(context, document, fileName)
    }

    fun generateStandaloneStatement(context: Context, startMillis: Long, endMillis: Long, categoryFilter: String = "Overall") {
        val breakdown = HistoryDataManager.getCategoryBreakdownForRange(context, startMillis, endMillis, categoryFilter)
        // Ascending sort (Oldest first)
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

        canvas.drawRect(0f, 0f, 595f, 842f, bgPaint)
        
        paint.textSize = 24f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("TRANSACTION STATEMENT", 40f, 60f, paint)
        
        paint.textSize = 10f
        paint.color = Color.parseColor("#7E7D96")
        val sdf = java.text.SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        val periodText = if (categoryFilter == "Overall") {
            "PERIOD: ${sdf.format(Date(startMillis))} - ${sdf.format(Date(endMillis))}"
        } else {
            "ALLOCATION: ${categoryFilter.uppercase()} | ${sdf.format(Date(startMillis))} - ${sdf.format(Date(endMillis))}"
        }
        canvas.drawText(periodText, 40f, 82f, paint)
        
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
        val sdfShort = java.text.SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
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
        val formattedCategory = categoryFilter.replace(" ", "_")
        val fileName = "CashDash_${formattedCategory}_Statement.pdf"
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
                
                val viewIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                val chooserIntent = android.content.Intent.createChooser(viewIntent, "Open PDF with...")
                
                // Construct pending intent for clicking the notification
                val pendingIntent = android.app.PendingIntent.getActivity(
                    context, 0, chooserIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                val notification = androidx.core.app.NotificationCompat.Builder(context, "cashdash_urgent_heads_up_v10")
                    .setSmallIcon(R.mipmap.ic_launcher_round) // Using safe app launcher icon
                    .setContentTitle("Download completed")
                    .setContentText("Tap to open $fileName")
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build()

                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.notify(System.currentTimeMillis().toInt(), notification)
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
