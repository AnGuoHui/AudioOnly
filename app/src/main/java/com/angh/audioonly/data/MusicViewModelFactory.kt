package com.angh.audioonly.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.angh.audioonly.viewmodel.MusicViewModel

class MusicViewModelFactory(private val musicDao: MusicDao) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MusicViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MusicViewModel(musicDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}