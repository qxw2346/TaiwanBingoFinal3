package com.bingoq.analyzer.model
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName = "bingo_draws")
data class BingoDraw(
    @PrimaryKey
    val drawNo: String,
    val drawTime: String,
    val numbers: List<Int>,
    val superNumber: Int? = null
)