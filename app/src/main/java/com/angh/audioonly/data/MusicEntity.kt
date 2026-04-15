package com.angh.audioonly.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.ZonedDateTime

@Entity(tableName = "music")
data class MusicEntity (
    @PrimaryKey(autoGenerate = true) val id: Int? = null,
    var name: String,
    var searchString: String,
    var playUrl: String,
    var playCache: String,//本地缓存地址
    var hasPlay: Boolean=false,
    var addTime: ZonedDateTime
)