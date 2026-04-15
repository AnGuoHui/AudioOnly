package com.angh.audioonly.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
@OptIn(UnstableApi::class)
object SimpleCacheSingleton {
    private var simpleCache: SimpleCache? = null

    // 缓存目录名称
    private const val CACHE_DIR_NAME = "media3_cache"
    // 缓存最大大小 ( 500MB)
    private const val MAX_CACHE_SIZE = 500L * 1024 * 1024

    @Synchronized
    fun getCache(context: Context): SimpleCache {
        if (simpleCache == null) {
            // 获取缓存文件夹
            val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            // 创建数据库提供者 (用于记录缓存索引)
            val databaseProvider = StandaloneDatabaseProvider(context)

            // 创建缓存实例
            // LeastRecentlyUsedCacheEvictor: 当缓存满时，自动删除最久未使用的文件
            simpleCache = SimpleCache(
                cacheDir,
                LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE),
                databaseProvider
            )
        }
        return simpleCache!!
    }

    /**
     * 在应用退出或 Service 销毁时调用，释放文件锁
     */
    fun releaseCache() {
        simpleCache?.release()
        simpleCache = null
    }
}