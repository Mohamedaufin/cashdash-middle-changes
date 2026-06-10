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

    suspend fun generateAndSavePremiumReport(context: Context, startMillis: Long, endMillis: Long, isMonthly: Boolean, weekIndex: Int = -1, isCustomMode: Boolean = false) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val cal = Calendar.getInstance().apply { timeInMillis = startMillis }
        val month = cal.get(Calendar.MONTH)
        val year = cal.get(Calendar.YEAR)
        val insights = FinancialInsightsManager.generateReport(context, isMonthly, isCustomMode, startMillis, endMillis, month, year, weekIndex)
        
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

        // Patterns
        yPos = 440f
        paint.textSize = 14f
        paint.color = accentColor
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("Strategic Patterns", 40f, yPos, paint)
        
        yPos += 30f
        paint.textSize = 11f
        paint.typeface = Typeface.DEFAULT

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
        val fileName = if (isCustomMode) "CashDash_Custom_Report.pdf" else if (isMonthly) "Cashdash_${monthName}_Report.pdf" else "CashDash_Weekly_Report.pdf"
        savePdfToDownloads(context, document, fileName)
    }

    suspend fun generateStandaloneStatement(context: Context, startMillis: Long, endMillis: Long, categoryFilter: String = "Overall") = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val breakdown = HistoryDataManager.getCategoryBreakdownForRange(context, startMillis, endMillis, categoryFilter)
        // Ascending order (oldest first, then descending per transaction within that)
        val transactions = breakdown.transactions.sortedBy { entry ->
            val p = entry.rawEntry.split("|")
            if (p.size >= 2) p[1].toLongOrNull() ?: 0L else 0L
        }

        val appPrefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val userName = appPrefs.getString("user_name", "USER") ?: "USER"
        val userEmail = appPrefs.getString("user_email", "email@example.com") ?: "email@example.com"
        val userPhone = appPrefs.getString("user_phone", "N/A") ?: "N/A"

        val document = PdfDocument()
        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNum).create()
        var page = document.startPage(pageInfo)
        var canvas = page.canvas

        val normalFont = Typeface.create("sans-serif", Typeface.NORMAL)
        val boldFont = Typeface.create("sans-serif", Typeface.BOLD)

        val paint = Paint().apply { isAntiAlias = true; color = Color.BLACK; typeface = normalFont }
        val linePaint = Paint().apply { color = Color.parseColor("#CCCCCC"); strokeWidth = 1f; isAntiAlias = true }
        val boxPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.2f; isAntiAlias = true }

        fun drawHeaderAndMid() {
            // Full white background
            canvas.drawColor(Color.WHITE)

            // Silky white header box — no border, just a soft fill
            val headerFill = Paint().apply { color = Color.parseColor("#F7F7F9"); style = Paint.Style.FILL; isAntiAlias = true }
            canvas.drawRect(0f, 0f, 595f, 80f, headerFill)

            // Logo — high resolution for crispness
            try {
                val drawable = androidx.core.content.ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                if (drawable != null) {
                    val hiResSize = 256
                    val bitmap = android.graphics.Bitmap.createBitmap(hiResSize, hiResSize, android.graphics.Bitmap.Config.ARGB_8888)
                    val bmpCanvas = android.graphics.Canvas(bitmap)
                    
                    // Force a perfect circle clip to remove any adaptive icon background square corners
                    val path = android.graphics.Path()
                    path.addCircle(hiResSize / 2f, hiResSize / 2f, hiResSize / 2f, android.graphics.Path.Direction.CW)
                    bmpCanvas.clipPath(path)
                    
                    // Scale the canvas up slightly so the adaptive icon's flat bounds are pushed outside the circle clip
                    bmpCanvas.scale(1.15f, 1.15f, hiResSize / 2f, hiResSize / 2f)
                    
                    drawable.setBounds(0, 0, hiResSize, hiResSize)
                    drawable.draw(bmpCanvas)
                    val logoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                    val destRect = android.graphics.RectF(30f, 18f, 70f, 58f) // 40x40 square for perfect circle
                    canvas.drawBitmap(bitmap, null, destRect, logoPaint)
                }
            } catch (e: Exception) { e.printStackTrace() }

            // CashDash text next to logo
            paint.color = Color.parseColor("#1C1C1E")
            paint.typeface = boldFont
            paint.textSize = 26f
            canvas.drawText("CashDash", 80f, 48f, paint)

            // User details below header box — left aligned, clean
            paint.typeface = normalFont
            paint.color = Color.parseColor("#1C1C1E")
            paint.textSize = 11f
            val displayUserName = userName.split(" ").joinToString(" ") { it.lowercase(Locale.getDefault()).replaceFirstChar { c -> c.uppercase() } }
            canvas.drawText("Name: $displayUserName", 30f, 105f, paint)
            canvas.drawText("Registered Mobile Number: $userPhone", 30f, 122f, paint)
            canvas.drawText("Registered Email Address: ${userEmail.lowercase(Locale.getDefault())}", 30f, 139f, paint)

            // Statement Period — centered and bold
            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            val startStr = sdf.format(Date(startMillis))
            val endStr = sdf.format(Date(endMillis))
            paint.typeface = boldFont
            paint.textSize = 12f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("Statement Period: $startStr - $endStr", 297.5f, 175f, paint)
            paint.textAlign = Paint.Align.LEFT

            val isOverall = categoryFilter == "Overall"
            paint.typeface = normalFont
            paint.color = Color.parseColor("#1C1C1E")
            paint.textSize = 11f
            val displayCatName = if (isOverall) "Overall" else categoryFilter.lowercase(Locale.getDefault()).replaceFirstChar { it.uppercase() }
            canvas.drawText("Allocation choosed: $displayCatName", 30f, 205f, paint)

            // Table Header
            paint.typeface = boldFont
            paint.textSize = 9.5f
            paint.color = Color.parseColor("#3A3A3C")
            
            canvas.drawLine(30f, 230f, 565f, 230f, linePaint)
            
            if (isOverall) {
                canvas.drawText("Date", 38f, 245f, paint)
                canvas.drawText("Description", 128f, 245f, paint)
                canvas.drawText("Amount", 278f, 245f, paint)
                canvas.drawText("Allocation", 358f, 245f, paint)
                canvas.drawText("Entry Type", 448f, 245f, paint)
                canvas.drawLine(30f, 252f, 565f, 252f, linePaint)

                canvas.drawLine(30f, 230f, 30f, 252f, linePaint)
                canvas.drawLine(120f, 230f, 120f, 252f, linePaint)
                canvas.drawLine(270f, 230f, 270f, 252f, linePaint)
                canvas.drawLine(350f, 230f, 350f, 252f, linePaint)
                canvas.drawLine(440f, 230f, 440f, 252f, linePaint)
                canvas.drawLine(565f, 230f, 565f, 252f, linePaint)
            } else {
                canvas.drawText("Date", 38f, 245f, paint)
                canvas.drawText("Description", 128f, 245f, paint)
                canvas.drawText("Amount", 358f, 245f, paint)
                canvas.drawText("Entry Type", 458f, 245f, paint)
                canvas.drawLine(30f, 252f, 565f, 252f, linePaint)

                canvas.drawLine(30f, 230f, 30f, 252f, linePaint)
                canvas.drawLine(120f, 230f, 120f, 252f, linePaint)
                canvas.drawLine(350f, 230f, 350f, 252f, linePaint)
                canvas.drawLine(450f, 230f, 450f, 252f, linePaint)
                canvas.drawLine(565f, 230f, 565f, 252f, linePaint)
            }

            paint.typeface = normalFont
            paint.color = Color.BLACK
        }

        fun startNextPage() {
            document.finishPage(page)
            pageNum++
            pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNum).create()
            page = document.startPage(pageInfo)
            canvas = page.canvas
            drawHeaderAndMid()
        }

        drawHeaderAndMid()
        var yTable = 267f
        val isOverall = categoryFilter == "Overall"

        val sdfShort = java.text.SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        
        transactions.forEach { item ->
            if (yTable > 720) {
                // close table bottom
                canvas.drawLine(30f, yTable - 15f, 565f, yTable - 15f, linePaint)
                startNextPage()
                yTable = 267f
            }
            
            val p = item.rawEntry.split("|")
            val ts = if (p.size >= 2) p[1].toLongOrNull() ?: 0L else 0L
            val metaPrefs = context.getSharedPreferences("ScannerMetadataPrefs", android.content.Context.MODE_PRIVATE)
            val isScanner = metaPrefs.contains("APP_$ts") || metaPrefs.contains("UPI_$ts")
            val typeStr = if (isScanner) "Scanner" else "Rigor tracker"
            
            paint.textSize = 9.5f
            paint.color = Color.BLACK
            val dateStr = sdfShort.format(Date(ts))
            canvas.drawText(dateStr, 38f, yTable, paint)
            
            val cleanTitle = item.title.removePrefix("To: ").removePrefix("to: ").trim()
            
            if (isOverall) {
                val displayTitle = if (cleanTitle.length > 25) cleanTitle.take(22) + "..." else cleanTitle
                canvas.drawText(displayTitle, 128f, yTable, paint)
                
                canvas.drawText("₹${item.amount}", 278f, yTable, paint)
                val displayCat = item.category.replace("(", "").replace(")", "").trim()
                    .lowercase(Locale.getDefault()).replaceFirstChar { it.uppercase() }
                canvas.drawText(displayCat, 358f, yTable, paint)
                canvas.drawText(typeStr, 448f, yTable, paint)
                
                // Draw Vertical Lines for Row
                canvas.drawLine(30f, yTable - 15f, 30f, yTable + 10f, linePaint)
                canvas.drawLine(120f, yTable - 15f, 120f, yTable + 10f, linePaint)
                canvas.drawLine(270f, yTable - 15f, 270f, yTable + 10f, linePaint)
                canvas.drawLine(350f, yTable - 15f, 350f, yTable + 10f, linePaint)
                canvas.drawLine(440f, yTable - 15f, 440f, yTable + 10f, linePaint)
                canvas.drawLine(565f, yTable - 15f, 565f, yTable + 10f, linePaint)
            } else {
                val displayTitle = if (cleanTitle.length > 40) cleanTitle.take(37) + "..." else cleanTitle
                canvas.drawText(displayTitle, 128f, yTable, paint)
                
                canvas.drawText("₹${item.amount}", 358f, yTable, paint)
                canvas.drawText(typeStr, 458f, yTable, paint)
                
                // Draw Vertical Lines for Row
                canvas.drawLine(30f, yTable - 15f, 30f, yTable + 10f, linePaint)
                canvas.drawLine(120f, yTable - 15f, 120f, yTable + 10f, linePaint)
                canvas.drawLine(350f, yTable - 15f, 350f, yTable + 10f, linePaint)
                canvas.drawLine(450f, yTable - 15f, 450f, yTable + 10f, linePaint)
                canvas.drawLine(565f, yTable - 15f, 565f, yTable + 10f, linePaint)
            }
            
            // Draw Horizontal Line for Row Bottom
            canvas.drawLine(30f, yTable + 10f, 565f, yTable + 10f, linePaint)

            yTable += 25f
        }

        // Draw Footer
        yTable += 50f // 2 lines gap
        if (yTable > 740) {
            startNextPage()
            yTable = 260f
        }

        val totalSpent = transactions.sumOf { it.amount.toDouble() }.toInt()
        val totalText = "Total expense : ₹$totalSpent"
        
        paint.textSize = 13f
        paint.typeface = boldFont
        val textWidth = paint.measureText(totalText)
        
        // Center the box on the 595pt wide page
        val boxWidth = textWidth + 60f
        val boxLeft = (595f - boxWidth) / 2f
        val boxTop = yTable - 20f
        val boxRight = boxLeft + boxWidth
        val boxBottom = yTable + 15f

        val fillPaint = Paint().apply { color = Color.parseColor("#F9F9F9"); style = Paint.Style.FILL; isAntiAlias = true }
        canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, fillPaint)
        canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, boxPaint)

        paint.color = Color.BLACK
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(totalText, (boxLeft + boxRight) / 2f, boxBottom - 11f, paint)
        paint.textAlign = Paint.Align.LEFT



        document.finishPage(page)
        val formattedCategory = categoryFilter.replace(" ", "_")
        val fileName = "CashDash_${formattedCategory}_Statement.pdf"
        savePdfToDownloads(context, document, fileName)
    }

    private fun savePdfToDownloads(context: Context, document: PdfDocument, baseFileName: String) {
        // Append a timestamp to the filename to guarantee it is absolutely unique
        // This prevents the MediaStore "Failed to build unique file" crash when too many exist
        val timestampStr = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        val fileName = baseFileName.replace(".pdf", "_$timestampStr.pdf")

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
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, "Report export failed", android.widget.Toast.LENGTH_SHORT).show()
            }
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
