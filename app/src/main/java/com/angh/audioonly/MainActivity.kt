package com.angh.audioonly

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.angh.audioonly.data.MusicEntity
import com.angh.audioonly.data.MusicViewModelFactory
import com.angh.audioonly.data.PlaylistAdapter
import com.angh.audioonly.data.database
import com.angh.audioonly.service.AudioPlaybackService
import com.angh.audioonly.service.PlaybackStateHolder
import com.angh.audioonly.utill.HttpClientProvider
import com.angh.audioonly.utill.WebUtils
import com.angh.audioonly.viewmodel.MusicViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private var mediaController: MediaController? = null

    // UI 组件
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnRewind: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var seekbarProgress: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var seekbarVolume: SeekBar
    private lateinit var tvVolumePercent: TextView

    //列表部分
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PlaylistAdapter

    private lateinit var musicViewModel: MusicViewModel

    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false

    // 更新进度条的 Runnable
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            val controller = mediaController ?: return
            if (!isUserSeeking) {
                val currentPos = controller.currentPosition.toInt()
                val duration = if (controller.duration == -1L) 0 else controller.duration.toInt()

                seekbarProgress.max = duration
                seekbarProgress.progress = currentPos

                tvCurrentTime.text = formatTime(currentPos)
                tvTotalTime.text = formatTime(duration)
            }
            // 每 1 秒更新一次
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        connectToService()

        // 初始化系统音量
        updateVolumeUI()

        //初始化musicViewmodel
        val musicDao = this.database.musicDao()
        val factory = MusicViewModelFactory(musicDao)
        musicViewModel = ViewModelProvider(this, factory)[MusicViewModel::class.java]

        // 初始化 获取直链 按钮
        val btnGetLink = findViewById<Button>(R.id.btn_get_link)
        btnGetLink.setOnClickListener {
            showGetLinkDialog()
        }

        // 初始化 获取单曲 按钮
        val btnGetSingle = findViewById<Button>(R.id.btn_get_single)
        btnGetSingle.setOnClickListener {
            showGetSingleDialog()
        }

        // 初始化 获取合集 按钮
        val btnGetCollection = findViewById<Button>(R.id.btn_get_collection)
        btnGetCollection.setOnClickListener {
            showGetCollectionDialog()
        }

        // 初始化 清空 按钮
        val btnGetClean = findViewById<Button>(R.id.btn_get_clean)
        btnGetClean.setOnClickListener {
            showGetCleanDialog()
        }



        //-----列表注册
        // 1. 找到 RecyclerView
        recyclerView = findViewById(R.id.recycler_playlist)
        adapter = PlaylistAdapter(object : PlaylistAdapter.OnItemClickListener{
            override fun onItemClick(
                song: MusicEntity,
                position: Int
            ) {
                // 更新列表currentPlayingId & 滚动列表
                adapter.submitPlayingId(song.id)
                if (song.id != null) {
                    scrollToPlayingPosition(song.id)
                }
                //传输播放信息
                sendPlayInfoToForegroundService(this@MainActivity, song.playUrl, song.name, song.playCache)
            }

        }, { position, music,isPlaying ->
            showLongClickDialog(position, music, isPlaying)
        })

        // 2. 设置布局管理器
        recyclerView.layoutManager = LinearLayoutManager(this@MainActivity)
        // 3. 设置适配器
        recyclerView.adapter = adapter
        //----注册列表结束

        //使用 lifecycleScope.launch 来收集 Flow
        lifecycleScope.launch {
            // 使用 combine 操作符，同时监听列表和 ID 的变化
            // 只要其中一个变了，就会触发回调
            combine(
                musicViewModel.allMusic,
                musicViewModel.currentPlayingId
            ) { list, currentId ->
                Pair(list, currentId)
            }.collect { (list, currentId) ->
                //更新 Adapter 数据和高亮
                adapter.submitList(list, currentId)

                // 触发滚动
                if (currentId != null) {
                    scrollToPlayingPosition(currentId)
                }
            }
        }

        //        --监听播放异常
        lifecycleScope.launch {
            PlaybackStateHolder.errorEvent.filterNotNull().collect { errorMessage ->
                val msg = errorMessage.getContentIfNotHandled()
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }

        //        --监听完播推送
        lifecycleScope.launch {
            PlaybackStateHolder.completionEvent.filterNotNull().collect { mediaId ->
                val completeMediaId = mediaId.getContentIfNotHandled()
                val playThisSong = adapter.getNextMusicEntityInMusicListByMediaId(completeMediaId)
                // 更新列表currentPlayingId & 滚动列表
                adapter.submitPlayingId(playThisSong.id)
                if (playThisSong.id != null) {
                    scrollToPlayingPosition(playThisSong.id)
                }
                //传输播放信息
                sendPlayInfoToForegroundService(this@MainActivity, playThisSong.playUrl, playThisSong.name, playThisSong.playCache)
            }
        }

        //切歌监听
        lifecycleScope.launch {
            PlaybackStateHolder.skipNextEvent.collect {
                btnNext.performClick()
            }
        }

        lifecycleScope.launch {
            PlaybackStateHolder.skipPrevEvent.collect {
                btnPrev.performClick()
            }
        }
    }

    private fun initViews() {
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        btnRewind = findViewById(R.id.btn_rewind)
        btnForward = findViewById(R.id.btn_forward)
        seekbarProgress = findViewById(R.id.seekbar_progress)
        tvCurrentTime = findViewById(R.id.tv_current_time)
        tvTotalTime = findViewById(R.id.tv_total_time)
        seekbarVolume = findViewById(R.id.seekbar_volume)
        tvVolumePercent = findViewById(R.id.tv_volume_percent)
    }

    private fun setupListeners() {
        // 1. 播放/暂停
        btnPlayPause.setOnClickListener {
            mediaController?.run {
                if (isPlaying) pause() else play()
            }
        }

        // 2. 上一曲/下一曲
        btnPrev.setOnClickListener {
            lifecycleScope.launch {
                val currentList = musicViewModel.allMusic.first()
                if (currentList.isEmpty()) return@launch

                // 找到当前播放歌曲的索引
                val currentId = musicViewModel.currentPlayingId.value
                var currentIndex = currentList.indexOfFirst { it.id == currentId }

                // 如果当前没播放或者找不到，默认从第一首开始
                if (currentIndex == -1) currentIndex = 0

                // 计算上一首索引 (循环列表)
                val prevIndex = (currentIndex - 1 + currentList.size) % currentList.size
                val prevMusic = currentList[prevIndex]

                // 更新 ViewModel 中的当前播放 ID
                musicViewModel.playSong(prevMusic.id?:0)

                //传输播放信息
                sendPlayInfoToForegroundService(this@MainActivity, prevMusic.playUrl, prevMusic.name, prevMusic.playCache)
            }
        }
        btnNext.setOnClickListener {
            lifecycleScope.launch {
                val currentList = musicViewModel.allMusic.first()
                if (currentList.isEmpty()) return@launch

                // 找到当前播放歌曲的索引
                val currentId = musicViewModel.currentPlayingId.value
                var currentIndex = currentList.indexOfFirst { it.id == currentId }

                // 如果当前没播放或者找不到，默认从第一首开始
                if (currentIndex == -1) currentIndex = 0

                // 计算下一首索引 (循环列表)
                val nextIndex = (currentIndex + 1) % currentList.size
                val nextMusic = currentList[nextIndex]

                // 更新 ViewModel 中的当前播放 ID
                musicViewModel.playSong(nextMusic.id?:0)

                //传输播放信息
                sendPlayInfoToForegroundService(this@MainActivity, nextMusic.playUrl, nextMusic.name, nextMusic.playCache)
            }
        }

        // 3. 快退/快进 (±10 秒)
        btnRewind.setOnClickListener {
            mediaController?.run {
                val newPos = (currentPosition - 10000).coerceAtLeast(0)
                seekTo(newPos)
            }
        }
        btnForward.setOnClickListener {
            mediaController?.run {
                val duration = if (this.duration == -1L) 0 else this.duration
                val newPos = (currentPosition + 10000).coerceAtMost(duration)
                seekTo(newPos)
            }
        }

        // 4. 进度条拖动
        seekbarProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvCurrentTime.text = formatTime(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isUserSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                mediaController?.seekTo(seekBar!!.progress.toLong())
            }
        })

        // 5. App 内音量控制
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        seekbarVolume.max = maxVol

        seekbarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                    updateVolumeUI()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun connectToService() {
        lifecycleScope.launchWhenStarted {
            try {
                val sessionToken = SessionToken(this@MainActivity, ComponentName(this@MainActivity, AudioPlaybackService::class.java))
                val controllerFuture = MediaController.Builder(this@MainActivity, sessionToken).buildAsync()

                // 等待控制器连接完成
                mediaController = controllerFuture.await()

                // 连接成功后，同步状态
                syncPlayerState(mediaController!!)

                // 开始更新进度条
                handler.post(updateProgressRunnable)

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "连接服务失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun syncPlayerState(player: Player) {
        // 监听播放状态变化以更新按钮图标
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                runOnUiThread {
                    btnPlayPause.setImageResource(
                        if (isPlaying) android.R.drawable.ic_media_pause
                        else android.R.drawable.ic_media_play
                    )
                }
            }

            override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                runOnUiThread {
                    metadata.artist?.let { findViewById<TextView>(R.id.tv_artist).text = it }
                }
            }
        })

        // 初始化一次按钮状态
        btnPlayPause.setImageResource(
            if (player.isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun updateVolumeUI() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        seekbarVolume.progress = currentVol
        val percent = if (maxVol > 0) (currentVol * 100 / maxVol) else 0
        tvVolumePercent.text = "$percent%"
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateProgressRunnable)
    }

    // 恢复音量显示（当用户从其他应用切回来时）
    override fun onResume() {
        super.onResume()
        updateVolumeUI()
    }

    //显示长按 删除单曲 弹窗的函数
    private fun showLongClickDialog(position: Int, music: MusicEntity, isPlaying: Boolean) {
        AlertDialog.Builder(this)
            .setTitle("删除当前歌曲")
            .setPositiveButton("确定") { dialog, which ->
                removeMusic(position,music,isPlaying)
            }
            .setNegativeButton("取消") { dialog, which ->
                dialog.cancel()
            }
            .show()
    }

    //显示获取直链弹窗的函数
    private fun showGetLinkDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 设置左右边距，让输入框不要紧贴屏幕边缘 (16dp 转像素)
            val padding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                16f,
                resources.displayMetrics
            ).toInt()
            setPadding(padding, padding * 2, padding, padding) // 上下左右留白
        }

        // 创建输入框
        val inputLinkUrl = EditText(this).apply {
            hint = "http://vjs.zencdn.net/v/oceans.mp4"
            // 可选：设置输入类型为文本
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val inputLinkName = EditText(this).apply {
            hint = "音频名称"
            // 可选：设置输入类型为文本
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        // 给输入框之间加点间距
        params.topMargin = 30

        container.addView(inputLinkUrl, params)
        container.addView(inputLinkName, params)

        // 构建 AlertDialog
        AlertDialog.Builder(this)
            .setTitle("获取直链")
            .setMessage("请输入音视频直链地址：")
            .setView(container) // 将输入框放入弹窗
            .setPositiveButton("确定") { dialog, which ->
                // 捕获输入字符串
                val linkUrlInput = inputLinkUrl.text.toString().trim()
                var linkNameInput = inputLinkName.text.toString().trim()

                if (linkUrlInput.isEmpty()) {
                    // 如果为空，可以提示用户
                    Toast.makeText(this, "输入不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (linkNameInput.isEmpty()) {
                    linkNameInput = linkUrlInput
                }

                handleUrlInput(linkUrlInput,linkNameInput)
            }
            .setNegativeButton("取消") { dialog, which ->
                dialog.cancel()
            }
            .show()
    }

    //显示删除列表弹窗的函数
    private fun showGetCleanDialog() {
        AlertDialog.Builder(this)
            .setTitle("清空播放列表")
            .setPositiveButton("确定") { dialog, which ->
                clearMusicList()
            }
            .setNegativeButton("取消") { dialog, which ->
                dialog.cancel()
            }
            .show()
    }

    //显示获取合集弹窗的函数
    private fun showGetCollectionDialog() {
        // 1. 创建输入框
        val input = EditText(this).apply {
            hint = "BV1GdJQzvEyh"
            // 可选：设置输入类型为文本
            inputType = InputType.TYPE_CLASS_TEXT
        }

        // 2. 构建 AlertDialog
        AlertDialog.Builder(this)
            .setTitle("获取合集")
            .setMessage("请输入哔哩哔哩视频的 BV 号：")
            .setView(input) // 将输入框放入弹窗
            .setPositiveButton("确定") { dialog, which ->
                // 3. 捕获输入字符串
                val bvInput = input.text.toString().trim()

                if (bvInput.isEmpty()) {
                    // 如果为空，可以提示用户
                    Toast.makeText(this, "输入不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                //bvInput 包含了用户输入的字符串
                handleBvInputCollection(bvInput)
            }
            .setNegativeButton("取消") { dialog, which ->
                dialog.cancel()
            }
            .show()
    }

    //显示获取单曲弹窗的函数
    private fun showGetSingleDialog() {
        // 1. 创建输入框
        val input = EditText(this).apply {
            hint = "BV1GdJQzvEyh或BV1GdJQzvEyh?p=2"
            // 可选：设置输入类型为文本
            inputType = InputType.TYPE_CLASS_TEXT
        }

        // 2. 构建 AlertDialog
        AlertDialog.Builder(this)
            .setTitle("获取单曲")
            .setMessage("请输入哔哩哔哩视频的 BV 号：")
            .setView(input) // 将输入框放入弹窗
            .setPositiveButton("确定") { dialog, which ->
                // 3. 捕获输入字符串
                val bvInput = input.text.toString().trim()

                if (bvInput.isEmpty()) {
                    // 如果为空，可以提示用户
                    Toast.makeText(this, "输入不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                //bvInput 包含了用户输入的字符串
                handleBvInput(bvInput)
            }
            .setNegativeButton("取消") { dialog, which ->
                dialog.cancel()
            }
            .show()
    }

    //处理直链输入
    private fun handleUrlInput(linkUrl: String,linkName: String) {
        // 调试日志，确认捕获成功
        Log.d("LinkAudio", "用户输入的直链地址: $linkUrl")
        Toast.makeText(this, "收到直链: $linkUrl", Toast.LENGTH_SHORT).show()

        val mainContext = this

        lifecycleScope.launch{
            val uuid = UUID.randomUUID().toString()
            val musicEntity  = MusicEntity(id = null, name = linkName, searchString = linkUrl, playUrl= linkUrl, playCache = uuid, addTime = ZonedDateTime.now(), hasPlay = true)

            //传输播放信息
            sendPlayInfoToForegroundService(this@MainActivity, linkUrl, linkName, uuid)

            //持久化数据
            musicViewModel.addMusic(musicEntity)
        }

    }

    //处理 BV 号输入的函数--单曲
    private fun handleBvInput(bvString: String) {
        // 调试日志，确认捕获成功
        Log.d("AudioOnly", "用户输入的 BV 号: $bvString")
        Toast.makeText(this, "收到 BV: $bvString", Toast.LENGTH_SHORT).show()

        val mainContext = this

        lifecycleScope.launch{
            try {
                //获取流链接
                val playInfo = WebUtils.getPlayUrl(bvString,HttpClientProvider.client)

                val uuid = UUID.randomUUID().toString()
                val musicEntity  = MusicEntity(id = null, name = playInfo.playName?:"未知", searchString = bvString, playUrl= playInfo.playUrl?:"unknown data", playCache = uuid, addTime = ZonedDateTime.now(), hasPlay = true)

                //传输播放信息
                sendPlayInfoToForegroundService(this@MainActivity, musicEntity.playUrl, musicEntity.name, musicEntity.playCache)

                //持久化数据
                musicViewModel.addMusic(musicEntity)
            }catch (e: Exception){
                Log.d("WebUtils.getPlayUrl error", e.toString())
                Toast.makeText(this@MainActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    //处理 BV 号输入的函数--合集
    private fun handleBvInputCollection(bvString: String) {
        // 调试日志，确认捕获成功
        Log.d("AudioOnly", "用户输入的 BV 号: $bvString")
        Toast.makeText(this, "收到 BV: $bvString", Toast.LENGTH_SHORT).show()

        val mainContext = this

        lifecycleScope.launch{
            try {
                //获取合计数
                val count = WebUtils.getMusicListSizeByBVId(bvString,HttpClientProvider.client)
                if (count == 0){
                    Log.d("AudioOnly", "当前BV号$bvString,未获取到合集信息")
                    Toast.makeText(mainContext, "收到 BV: $bvString", Toast.LENGTH_SHORT).show()
                }else {
                    //获取第一个分P流链接  直接播放
                    val firstMusic = "$bvString?p=1"
                    val playInfo = WebUtils.getPlayUrl(firstMusic, HttpClientProvider.client)

                    val uuid = UUID.randomUUID().toString()
                    val musicEntity = MusicEntity(
                        id = null,
                        name = playInfo.playName ?: "未知",
                        searchString = firstMusic,
                        playUrl = playInfo.playUrl ?: "unknown data",
                        playCache = uuid,
                        addTime = ZonedDateTime.now(),
                        hasPlay = true
                    )

                    //传输播放信息
                    sendPlayInfoToForegroundService(this@MainActivity, musicEntity.playUrl, musicEntity.name, musicEntity.playCache)

                    //持久化数据
                    musicViewModel.addMusic(musicEntity)

                    //剩余分P处理
                    withContext(Dispatchers.IO) {
                        for(currentCount in 2..count){
                            try {
                                val musicSearchString = "$bvString?p=$currentCount"
                                val playInfo = WebUtils.getPlayUrl(musicSearchString, HttpClientProvider.client)

                                val uuid = UUID.randomUUID().toString()
                                val musicEntity = MusicEntity(
                                    id = null,
                                    name = playInfo.playName ?: "未知",
                                    searchString = musicSearchString,
                                    playUrl = playInfo.playUrl ?: "unknown data",
                                    playCache = uuid,
                                    addTime = ZonedDateTime.now(),
                                    hasPlay = true
                                )
                                //持久化数据
                                musicViewModel.addMusic(musicEntity,false)
                            } catch (e: Exception) {
                                Log.e("GetSource", "获取第 $currentCount 分P失败: ${e.message}")
                                Toast.makeText(mainContext, "获取第 $currentCount 分P失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }catch (e: Exception){
                Log.d("WebUtils.getPlayUrl error", e.toString())
                Toast.makeText(mainContext, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearMusicList(){
        lifecycleScope.launch {
            musicViewModel.clearMusicList()
            Toast.makeText(this@MainActivity, "播放列表已清空", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeMusic(position: Int, music: MusicEntity, isPlaying: Boolean){
        lifecycleScope.launch {
            musicViewModel.removeMusic(music)
            //删除当前播放--切歌--更新播放位置
            if (isPlaying){
                val playThisSong = adapter.getNextMusicEntityByPosition(position)
                sendPlayInfoToForegroundService(this@MainActivity, playThisSong.playUrl,
                    playThisSong.name,playThisSong.playCache)
                adapter.submitPlayingId(playThisSong.id)
                musicViewModel.playSong(playThisSong.id?:0)
            }
            Toast.makeText(this@MainActivity, "${music.name} 已移除", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scrollToPlayingPosition(playingId: Int) {
        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
        val list = adapter.musicList

        // 找到当前播放歌曲的索引
        val position = list.indexOfFirst { it.id == playingId }
        if (position == -1) return

        // 计算滚动偏移量
        // 目标 Item 停在 RecyclerView 高度的 1/3 处
        val recyclerViewHeight = recyclerView.height
        val offset = (recyclerViewHeight / 3)

        // 执行平滑滚动
        // scrollToPositionWithOffset 是 LinearLayoutManager 的特有方法
        layoutManager.scrollToPositionWithOffset(position, offset)
    }

    private fun sendPlayInfoToForegroundService(context: MainActivity, playUrl: String, playName: String, chacheKey: String){
        val serviceIntent = Intent(context, AudioPlaybackService::class.java)
        serviceIntent.action = AudioPlaybackService.ACTION_PLAY_URL
        serviceIntent.putExtra(AudioPlaybackService.EXTRA_STREAM_URL, playUrl)
        serviceIntent.putExtra(
            AudioPlaybackService.EXTRA_STREAM_NAME,
            playName
        )
        serviceIntent.putExtra(AudioPlaybackService.EXTRA_STREAM_CACHE, chacheKey)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}