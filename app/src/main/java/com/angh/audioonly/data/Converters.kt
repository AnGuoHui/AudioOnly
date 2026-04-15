package com.angh.audioonly.data

import androidx.room.TypeConverter
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class Converters {
    @TypeConverter
    fun fromZonedDateTime(date: ZonedDateTime?): Long? {
        return date?.toInstant()?.toEpochMilli()
    }

    @TypeConverter
    fun toZonedDateTime(timestamp: Long?): ZonedDateTime? {
        return timestamp?.let { ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()) }
    }
}