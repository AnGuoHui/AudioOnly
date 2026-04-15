package com.angh.audioonly.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.angh.audioonly.MainActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class AudioPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    // 通知 ID 和 频道 ID
    companion object {
        const val CHANNEL_ID = "audio_playback_channel"
        const val ACTION_PLAY_URL = "default_.PLAY_URL"
        const val EXTRA_STREAM_URL = "default_stream_url"

        const val EXTRA_STREAM_NAME = "default_stream_name"

        const val EXTRA_STREAM_CACHE = "default_stream_cache"
    }

    private var streamUrl: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializePlayer()
        //这里不再立即 startForeground，等待 onStartCommand 触发
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer() {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
            setDefaultRequestProperties(
                mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to "https://www.bilibili.com/"
                )
            )
        }

        val faultTolerantFactory = FaultTolerantDataSourceFactory(httpDataSourceFactory, this, 3)

        //创建缓存数据源工厂
        // 这个工厂会自动处理：读取缓存、写入缓存、以及未命中时自动回退到网络
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(faultTolerantFactory) // 设置上游为网络工厂
            .setCache(SimpleCacheSingleton.getCache(this)) // 设置缓存目录
            // 必须显式设置写入工厂，否则只读不写
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(SimpleCacheSingleton.getCache(this)))

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(5000, 30000, 2500, 5000)
            .build()

        //使用 DefaultTrackSelector 确保音频输出格式标准
        val trackSelector = DefaultTrackSelector(this)
        // 强制音频属性为音乐类型，这有助于系统正确识别流类型
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val basePlayer = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(audioAttributes, true) // 请求音频焦点
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(cacheDataSourceFactory)
            )
            .setLoadControl(loadControl)
            .build()

        player = basePlayer
        val forwardingPlayer = object : ForwardingPlayer(basePlayer) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(COMMAND_SEEK_TO_NEXT)
                    .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(COMMAND_SEEK_TO_PREVIOUS)
                    .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }
            // 告知系统 始终有“下一曲”和“上一曲”
            override fun hasNextMediaItem(): Boolean = true
            override fun hasPreviousMediaItem(): Boolean = true
            // 拦截所有可能的切歌调用
            override fun seekToNext() { PlaybackStateHolder.tryEmitSkipNext() }
            override fun seekToNextMediaItem() { PlaybackStateHolder.tryEmitSkipNext() }
            override fun seekToPrevious() { PlaybackStateHolder.tryEmitSkipPrev() }
            override fun seekToPreviousMediaItem() { PlaybackStateHolder.tryEmitSkipPrev() }
        }

        // 创建 MediaSession
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        basePlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // 关键：打印详细错误
                Log.e("AudioPlaybackService", "播放器严重错误: $error")

                // 关键：将错误标记为已处理，防止播放器自动释放或 Service 崩溃
                basePlayer.playWhenReady = false

                // 发送一个广播给 UI，提示“播放失败”
                // 不调用 stopSelf()，保持 Service 存活
                PlaybackStateHolder.errorEvent.value = null//强制置空，可以推送重复的报错信息
                val errorMsg = error.message ?: "UnKnown Error"
                PlaybackStateHolder.errorEvent.value = Event("播放失败： $errorMsg")
            }

            //完播推送
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    // 播放结束
                    PlaybackStateHolder.errorEvent.value = null//强制置空，可以推送重复的状态信息
                    val currentMediaItem = basePlayer.currentMediaItem
                    val mediaId = currentMediaItem?.mediaId ?: "unknown"
                    // 发送完播事件
                    PlaybackStateHolder.completionEvent.value = Event(mediaId)
                }
            }
        })
    }

    // 2. 添加 onStartCommand 接收外部 Intent
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_PLAY_URL -> {
                val url = intent.getStringExtra(EXTRA_STREAM_URL)
                val name = intent.getStringExtra(EXTRA_STREAM_NAME) ?: "未知"
                val cacheKey = intent.getStringExtra(EXTRA_STREAM_CACHE) ?: "default_stream_cache"
                if (!url.isNullOrEmpty()) {
                    playMedia(url, name, cacheKey)
                }
            }
        }
        return START_STICKY // 系统杀死服务后尝试重建
    }

    // 将播放逻辑封装为独立方法
    @OptIn(UnstableApi::class)
    fun playMedia(url: String, name: String, cacheKey: String) {
        val player = player ?: return

        // 更新内部状态
        streamUrl = url

        // 创建 MediaItem
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaId("audio_${cacheKey}")
            .setCustomCacheKey(cacheKey)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setArtist(name)
                    .build()
            )
            .build()

        // 停止当前播放（如果有）并加载新资源
//        player.stop()
//        player.clearMediaItems()
//        player.setMediaItem(mediaItem,true)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = player ?: return
        // 如果不在播放且没有待播放项目，则停止服务
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    // --- 通知相关逻辑 ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音频播放控制",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于控制后台音频播放"
                setShowBadge(false)
//                enableLights(false)
//                enableVibration(false)
            }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}

//包装错误信息--Toast
object PlaybackStateHolder {
    val errorEvent = MutableStateFlow<Event<String>?>(null)
    // 用于通知播放完成
    val completionEvent = MutableStateFlow<Event<String>?>(null)

    //按钮控制  切换下一首/上一首的事件  使用 SharedFlow 确保每次 emit 都会被触发，即便值没变
    val skipNextEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val skipPrevEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun tryEmitSkipNext() { skipNextEvent.tryEmit(Unit) }
    fun tryEmitSkipPrev() { skipPrevEvent.tryEmit(Unit) }
}

data class Event<out T>(private val content: T) {
    var hasBeenHandled = false
        private set

    fun getContentIfNotHandled(): T? {
        return if (hasBeenHandled) {
            null
        } else {
            hasBeenHandled = true
            content
        }
    }
}