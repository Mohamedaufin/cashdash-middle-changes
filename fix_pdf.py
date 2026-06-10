import re

with open('app/src/main/java/com/cash/dash/PdfReportManager.kt', 'r', encoding='utf-8') as f:
    content = f.read()

start_pattern = r"suspend fun generateStandaloneStatement"
end_pattern = r"private fun savePdfToDownloads"

start_idx = content.find("suspend fun generateStandaloneStatement")
end_idx = content.find("private fun savePdfToDownloads")

new_func = '''suspend fun generateStandaloneStatement(context: Context, startMillis: Long, endMillis: Long, categoryFilter: String = "Overall") = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val breakdown = HistoryDataManager.getCategoryBreakdownForRange(context, startMillis, endMillis, categoryFilter)
        // Descending order as per user requirements
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

        val paint = Paint().apply { isAntiAlias = true; color = Color.BLACK }
        val linePaint = Paint().apply { color = Color.parseColor("#CCCCCC"); strokeWidth = 1f }
        val boxPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.5f }

        fun drawHeaderAndMid() {
            // White background
            canvas.drawColor(Color.WHITE)
            
            // Logo
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = 28f
            canvas.drawText("CashDash", 40f, 60f, paint)

            paint.typeface = Typeface.DEFAULT
            paint.textSize = 12f
            canvas.drawText("Name: \", 40f, 90f, paint)
            canvas.drawText("Registered email address: \", 40f, 110f, paint)

            canvas.drawText("Mobile No: \", 350f, 90f, paint)

            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            val startStr = sdf.format(Date(startMillis))
            val endStr = sdf.format(Date(endMillis))

            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("Statement from \ to \", 40f, 150f, paint)
            canvas.drawText("Allocation choosed: \", 40f, 170f, paint)
            paint.typeface = Typeface.DEFAULT
            
            // Table Header
            canvas.drawLine(40f, 190f, 555f, 190f, linePaint)
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = 10f
            canvas.drawText("Transaction", 45f, 205f, paint)
            canvas.drawText("Date", 45f, 218f, paint)
            
            canvas.drawText("Description", 130f, 218f, paint)
            canvas.drawText("Amount", 280f, 218f, paint)
            canvas.drawText("Allocation", 360f, 218f, paint)
            canvas.drawText("Type of Entry", 450f, 218f, paint)
            
            canvas.drawLine(40f, 225f, 555f, 225f, linePaint)
            
            // Draw Vertical Lines for Header
            canvas.drawLine(40f, 190f, 40f, 225f, linePaint)
            canvas.drawLine(125f, 190f, 125f, 225f, linePaint)
            canvas.drawLine(275f, 190f, 275f, 225f, linePaint)
            canvas.drawLine(355f, 190f, 355f, 225f, linePaint)
            canvas.drawLine(445f, 190f, 445f, 225f, linePaint)
            canvas.drawLine(555f, 190f, 555f, 225f, linePaint)
            
            paint.typeface = Typeface.DEFAULT
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
        var yTable = 240f

        val sdfShort = java.text.SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        
        transactions.forEach { item ->
            if (yTable > 720) {
                startNextPage()
                yTable = 240f
            }
            
            val p = item.rawEntry.split("|")
            val typeStr = if (p.isNotEmpty() && p[0] == "SCAN") "Scanner" else "Piggybank"
            val ts = if (p.size >= 2) p[1].toLongOrNull() ?: 0L else 0L
            
            paint.textSize = 10f
            val dateStr = sdfShort.format(Date(ts))
            canvas.drawText(dateStr, 45f, yTable, paint)
            
            val displayTitle = if (item.title.length > 25) item.title.take(22) + "..." else item.title
            canvas.drawText(displayTitle, 130f, yTable, paint)
            
            canvas.drawText("?\", 280f, yTable, paint)
            canvas.drawText(item.category.uppercase(), 360f, yTable, paint)
            canvas.drawText(typeStr, 450f, yTable, paint)
            
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
        
        paint.textSize = 14f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val textWidth = paint.measureText(totalText)
        
        // Draw Box for total
        val boxLeft = 555f - textWidth - 60f
        val boxTop = yTable
        val boxRight = 555f
        val boxBottom = yTable + 30f
        canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, boxPaint)
        canvas.drawText(totalText, boxLeft + 30f, boxBottom - 10f, paint)

        yTable += 60f
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 12f
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
