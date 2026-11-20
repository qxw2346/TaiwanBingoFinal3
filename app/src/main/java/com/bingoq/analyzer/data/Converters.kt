package com.bingoq.analyzer.data
import androidx.room.TypeConverter
class Converters {
    @TypeConverter
    fun fromIntList(numbers: List<Int>?): String {
        return numbers?.joinToString(",") ?: ""
    }
    @TypeConverter
    fun toIntList(data: String?): List<Int> {
        if (data.isNullOrEmpty()) return emptyList()
        return try {
            data.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..80 }
        } catch (e: Exception) {
            emptyList()
        }
    }
}