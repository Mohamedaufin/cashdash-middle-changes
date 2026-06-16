package com.cash.dash

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object FinminderRepository {
    private const val PREFS_NAME = "FinminderPrefs"
    private const val KEY_ITEMS = "finminder_items"

    fun saveItem(context: Context, item: FinminderItem) {
        val items = getItems(context).toMutableList()
        val index = items.indexOfFirst { it.id == item.id }
        if (index != -1) {
            items[index] = item
        } else {
            items.add(0, item) // Add to top
        }
        saveAllItems(context, items)
    }

    fun deleteItem(context: Context, id: String) {
        val items = getItems(context).filter { it.id != id }
        saveAllItems(context, items)
    }

    fun getItems(context: Context): List<FinminderItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
        val list = mutableListOf<FinminderItem>()
        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    FinminderItem(
                        id = obj.getString("id"),
                        type = obj.getString("type"),
                        title = obj.getString("title"),
                        quantity = obj.getString("quantity"),
                        frequency = obj.getString("frequency"),
                        dateInfo = obj.getString("dateInfo"),
                        isChecked = obj.optBoolean("isChecked", false),
                        timestamp = obj.optLong("timestamp", 0L)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun saveAllItems(context: Context, items: List<FinminderItem>) {
        val array = JSONArray()
        items.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("type", item.type)
                put("title", item.title)
                put("quantity", item.quantity)
                put("frequency", item.frequency)
                put("dateInfo", item.dateInfo)
                put("isChecked", item.isChecked)
                put("timestamp", item.timestamp)
            }
            array.put(obj)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }
}
