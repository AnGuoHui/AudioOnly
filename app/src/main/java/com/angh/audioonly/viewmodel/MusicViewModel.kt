package com.angh.audioonly.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.angh.audioonly.data.MusicDao
import com.angh.audioonly.data.MusicEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MusicViewModel(
    private val musicDao: MusicDao
) : ViewModel() {

    // 存储数据的方法
    fun addMusic(musicEntity: MusicEntity,updateCurrentPlayingId: Boolean = true) {
        viewModelScope.launch {
            //新增时赋值_currentPlayingId.value，添加歌曲即播放即加入播放列表
            if(updateCurrentPlayingId) {
                _currentPlayingId.value = musicDao.insert(musicEntity).toInt()
            }
            else{
                musicDao.insert(musicEntity)
            }
        }
    }

    // 获取所有音乐，这是一个 Flow
    val allMusic = musicDao.getAllMusicOlderByAddTime()

    //清空列表并重新计算自增长起始id-0
    suspend fun clearMusicList(){
        musicDao.clearAllMusicEntity()
        musicDao.resetPrimaryKeySequence()
    }

    //移除一首歌
    suspend fun removeMusic(music: MusicEntity){
        musicDao.delete(music)
    }

    //记录当前播放数据id，便于切歌逻辑
    private val _currentPlayingId = MutableStateFlow<Int?>(0)
    val currentPlayingId: StateFlow<Int?> = _currentPlayingId.asStateFlow()
    fun playSong(musicId: Int) {
        _currentPlayingId.value = musicId
    }

}