package com.justme.xtls_core_proxy.config

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Utility for formatting JSON with indentation
 */
object JsonFormatter {

    /**
     * Format a JSON string with 2-space indentation
     * @param jsonString the JSON string to format
     * @return formatted JSON string with 2-space indentation
     * @throws IllegalArgumentException if the input is not valid JSON
     */
    fun formatJson(jsonString: String): String {
        if (jsonString.isBlank()) {
            return jsonString
        }

        return try {
            val trimmed = jsonString.trim()
            val jsonTokener = JSONTokener(trimmed)
            val nextValue = jsonTokener.nextValue()
            
            when (nextValue) {
                is JSONObject -> nextValue.toString(2)
                is JSONArray -> nextValue.toString(2)
                else -> jsonString // Return as-is for primitive values
            }
        } catch (_: Exception) {
            // If it's not valid JSON, return the original string
            jsonString
        }
    }

    /**
     * Check if a string is valid JSON
     * @param jsonString the string to check
     * @return true if the string is valid JSON, false otherwise
     */
    fun isValidJson(jsonString: String): Boolean {
        if (jsonString.isBlank()) {
            return false
        }

        return try {
            val trimmed = jsonString.trim()
            val jsonTokener = JSONTokener(trimmed)
            jsonTokener.nextValue()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Format JSON if it's valid, otherwise return the original string
     * @param jsonString the string to format
     * @return formatted JSON or original string if not valid JSON
     */
    fun formatJsonIfValid(jsonString: String): String {
        return if (isValidJson(jsonString)) {
            formatJson(jsonString)
        } else {
            jsonString
        }
    }
}
