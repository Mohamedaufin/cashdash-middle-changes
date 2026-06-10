import re

with open('app/src/main/java/com/cash/dash/PdfReportManager.kt', 'r', encoding='utf-8') as f:
    content = f.read()

start_pattern = "suspend fun generateStandaloneStatement"
end_pattern = "private fun savePdfToDownloads"

start_idx = content.find(start_pattern)
end_idx = content.find(end_pattern)

new_func = '''suspend fun generateStandaloneStatement(context: Context, startMillis: Long, endMillis: Long, categoryFilter: String = "Overall") = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val breakdown = HistoryDataManager.getCategoryBreakdownForRange(context, startMillis, endMillis, categoryFilter)
        // Ascending to descending order (newest first)
        val transactions = breakdown.transactions.sortedByDescending { entry ->
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
        val linePaint = Paint().apply { color = Color.parseColor("#CCCCCC"); strokeWidth = 1f }
        val boxPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.2f; isAntiAlias = true }

        fun drawHeaderAndMid() {
            // White background for whole page
            canvas.drawColor(Color.WHITE)
            
            // Silky White Header Box
            paint.color = Color.parseColor("#F5F5F7")
            paint.style = Paint.Style.FILL
            canvas.drawRect(0f, 0f, 595f, 75f, paint)

            // Logo
            try {
                val drawable = androidx.core.content.ContextCompat.getDrawable(context, R.mipmap.ic_launcher_round)
                if (drawable != null) {
                    val size = 256
                    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                    val cvs = android.graphics.Canvas(bitmap)
                    drawable.setBounds(0, 0, cvs.width, cvs.height)
                    drawable.draw(cvs)
                    
                    val destRect = android.graphics.RectF(40f, 18f, 78f, 56f)
                    canvas.drawBitmap(bitmap, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            paint.color = Color.parseColor("#1C1C1E")
            paint.typeface = boldFont
            paint.textSize = 28f
            canvas.drawText("CashDash", 90f, 50f, paint)
            
            // Straight Line below header
            canvas.drawLine(0f, 75f, 595f, 75f, linePaint)

            paint.typeface = normalFont
            paint.color = Color.parseColor("#2C2C2E")
            paint.textSize = 11f
            canvas.drawText("Name: \", 40f, 105f, paint)
            canvas.drawText("Registered email address: \", 40f, 122f, paint)

            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText("Mobile No: \", 555f, 105f, paint)
            paint.textAlign = Paint.Align.LEFT

            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            val startStr = sdf.format(Date(startMillis))
            val endStr = sdf.format(Date(endMillis))

            paint.typeface = boldFont
            paint.color = Color.BLACK
            canvas.drawText("Statement from \ to \", 40f, 160f, paint)
            
            paint.typeface = normalFont
            canvas.drawText("Allocation choosed: \", 40f, 178f, paint)
            
            // Table Header
            canvas.drawLine(40f, 205f, 555f, 205f, linePaint)
            paint.typeface = boldFont
            paint.textSize = 9.5f
            paint.color = Color.parseColor("#3A3A3C")
            
            canvas.drawText("Transaction", 48f, 220f, paint)
            canvas.drawText("Date", 48f, 233f, paint)
            
            canvas.drawText("Description", 132f, 233f, paint)
            canvas.drawText("Amount", 282f, 233f, paint)
            canvas.drawText("Allocation", 362f, 233f, paint)
            canvas.drawText("Type of Entry", 452f, 233f, paint)
            
            canvas.drawLine(40f, 240f, 555f, 240f, linePaint)
            
            // Draw Vertical Lines for Header
            canvas.drawLine(40f, 205f, 40f, 240f, linePaint)
            canvas.drawLine(125f, 205f, 125f, 240f, linePaint)
            canvas.drawLine(275f, 205f, 275f, 240f, linePaint)
            canvas.drawLine(355f, 205f, 355f, 240f, linePaint)
            canvas.drawLine(445f, 205f, 445f, 240f, linePaint)
            canvas.drawLine(555f, 205f, 555f, 240f, linePaint)
            
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
        var yTable = 255f

        val sdfShort = java.text.SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        
        transactions.forEach { item ->
            if (yTable > 720) {
                // close table bottom
                canvas.drawLine(40f, yTable - 15f, 555f, yTable - 15f, linePaint)
                startNextPage()
                yTable = 255f
            }
            
            val p = item.rawEntry.split("|")
            val typeStr = if (p.isNotEmpty() && p[0] == "SCAN") "Scanner" else "Rigor tracker"
            val ts = if (p.size >= 2) p[1].toLongOrNull() ?: 0L else 0L
            
            paint.textSize = 9.5f
            val dateStr = sdfShort.format(Date(ts))
            canvas.drawText(dateStr, 48f, yTable, paint)
            
            val displayTitle = if (item.title.length > 25) item.title.take(22) + "..." else item.title
            canvas.drawText(displayTitle, 132f, yTable, paint)
            
            canvas.drawText("?\", 282f, yTable, paint)
            canvas.drawText(item.category.uppercase(), 362f, yTable, paint)
            canvas.drawText(typeStr, 452f, yTable, paint)
            
            // Draw Vertical Lines for Row
            canvas.drawLine(40f, yTable - 15f, 40f, yTable + 10f, linePaint)
            canvas.drawLine(125f, yTable - 15f, 125f, yTable + 10f, linePaint)
            canvas.drawLine(275f, yTable - 15f, 275f, yTable + 10f, linePaint)
            canvas.drawLine(355f, yTable - 15f, 355f, yTable + 10f, linePaint)
            canvas.drawLine(445f, yTable - 15f, 445f, yTable + 10f, linePaint)
            canvas.drawLine(555f, yTable - 15f, 555f, yTable + 10f, linePaint)
            
            // Draw Horizontal Line for Row Bottom
            canvas.drawLine(40f, yTable + 10f, 555f, yTable + 10f, linePaint)

            yTable += 25f
        }

        // Draw Footer
        yTable += 20f
        if (yTable > 740) {
            startNextPage()
            yTable = 260f
        }

        val totalSpent = transactions.sumOf { it.amount.toDouble() }.toInt()
        val totalText = "Total expense : ?\"
        
        paint.textSize = 13f
        paint.typeface = boldFont
        val textWidth = paint.measureText(totalText)
        
        // Draw Box for total
        val boxLeft = 555f - textWidth - 40f
        val boxTop = yTable - 20f
        val boxRight = 555f
        val boxBottom = yTable + 15f
        
        paint.color = Color.parseColor("#F9F9F9")
        paint.style = Paint.Style.FILL
        canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, paint)
        
        paint.color = Color.BLACK
        paint.style = Paint.Style.FILL
        canvas.drawText(totalText, boxLeft + 20f, boxBottom - 11f, paint)
        
        canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, boxPaint)

        yTable += 60f
        paint.typeface = normalFont
        paint.textSize = 11f
        canvas.drawText("Thank you for using CashDash!", boxLeft, yTable, paint)

        document.finishPage(page)
        val formattedCategory = categoryFilter.replace(" ", "_")
        val fileName = "CashDash_\_Statement.pdf"
        savePdfToDownloads(context, document, fileName)
    }
'''

new_content = content[:start_idx] + new_func + content[end_idx:]

with open('app/src/main/java/com/cash/dash/PdfReportManager.kt', 'w', encoding='utf-8') as f:
    f.write(new_content)

print("done")
