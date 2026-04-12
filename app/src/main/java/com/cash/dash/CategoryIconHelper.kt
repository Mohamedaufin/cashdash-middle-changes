package com.cash.dash

import android.content.Context
import java.util.Locale

object CategoryIconHelper {

    private val foodKeywords = setOf("food", "foodie", "hotel", "soru", "lunch", "dinner", "breakfast", "restaurant", "swiggy", "zomato", "eat", "meal", "snack")
    private val shoppingKeywords = setOf("shop", "shopping", "cart", "buy", "mall", "clothes", "amazon", "flipkart", "myntra", "grocery", "groceries")
    private val fuelKeywords = setOf("petrol", "diesel", "fuel", "gas", "cng", "pump")
    private val transportKeywords = setOf("travel", "bus", "car", "train", "flight", "taxi", "cab", "uber", "ola", "rapido", "auto", "metro", "transport")

    /**
     * Attempts to heuristically detect the best icon for a given category name based on keyword matching.
     * Predictable accuracy is high due to root-word containment checking.
     * Returns the pencil icon (ic_edit) as a fallback if no confidence match is met.
     */
    fun getIconForCategory(context: Context, categoryName: String): Int {
        val prefs = context.getSharedPreferences("CategoryPrefs", Context.MODE_PRIVATE)
        val customIcon = prefs.getInt("ICON_$categoryName", 0)
        if (customIcon != 0) {
            return customIcon
        }

        val normalized = categoryName.lowercase(Locale.getDefault()).trim()
        
        // Exact or partial containment matching
        for (word in foodKeywords) {
            if (normalized.contains(word)) return R.drawable.ic_category_food
        }
        for (word in shoppingKeywords) {
            if (normalized.contains(word)) return R.drawable.ic_category_shopping
        }
        for (word in fuelKeywords) {
            if (normalized.contains(word)) return R.drawable.ic_category_fuel
        }
        for (word in transportKeywords) {
            if (normalized.contains(word)) return R.drawable.ic_category_transport
        }
        
        // Fallback
        return R.drawable.ic_edit
    }
}
