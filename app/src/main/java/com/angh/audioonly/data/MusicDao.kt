package com.angh.audioonly.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface  MusicDao {

    @Insert
    suspend fun insert(music: MusicEntity) : Long

    @Delete
    suspend fun delete(music: MusicEntity)

    @Query("SELECT * FROM music order by addTime")
    fun getAllMusicOlderByAddTime(): Flow<List<MusicEntity>>

    @Query("SELECT * FROM music order by addTime")
    fun getAllMusicListOlderByAddTime(): List<MusicEntity>

    // 1. 清空表数据
    @Query("DELETE FROM music")
    suspend fun clearAllMusicEntity()

    // 2. 重置自增主键的序列
    @Query("UPDATE sqlite_sequence SET seq = 0 WHERE name = 'music'")
    suspend fun resetPrimaryKeySequence()

    @Query("SELECT * FROM music where playUrl = :playUrl")
    fun getOneMusicByPlayUrl(playUrl : String): MusicEntity
}