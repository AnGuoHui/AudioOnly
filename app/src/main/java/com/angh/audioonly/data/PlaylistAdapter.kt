package com.angh.audioonly.data

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.core.graphics.toColorInt
import com.angh.audioonly.R

class PlaylistAdapter(
    // 在构造函数中接收监听器（回调接口）
    private val clickListener: OnItemClickListener,
    private val onLongClickListener: (Int, MusicEntity, Boolean) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>(){

    // 数据列表
    public var musicList = emptyList<MusicEntity>()

    // 当前播放的 ID
    private var currentPlayingId: Int? = null

    interface OnItemClickListener {
        fun onItemClick(song: MusicEntity, position: Int)
    }

    // ViewHolder 负责找到列表项里的控件
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val titleText: TextView = itemView.findViewById(R.id.tv_item_title)
        val artistText: TextView = itemView.findViewById(R.id.tv_item_artist)
    }

    // 创建列表项的布局
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return ViewHolder(view)
    }

    // 绑定数据（把数据填进控件里）
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val currentSong = musicList[position]
        holder.titleText.text = currentSong.name
        holder.artistText.text = currentSong.searchString

        // --- 高亮逻辑 ---
        val isPlaying = (currentSong.id == currentPlayingId)

        if (isPlaying) {
            // 高亮状态：改变背景色或文字颜色
            holder.itemView.setBackgroundColor("#33000000".toColorInt()) // 半透明黑色背景
            holder.titleText.setTextColor("#1DB954".toColorInt()) // 绿色文字 (类似 Spotify)
            // holder.playIcon.visibility = View.VISIBLE
        } else {
            // 普通状态：恢复默认
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.titleText.setTextColor("#FFFFFF".toColorInt()) // 白色文字
            // holder.playIcon.visibility = View.GONE
        }
        // --- 高亮逻辑 ---

        // --- 点击事件 ---
        // 这里的 setOnClickListener 是设置在整个 item 布局上
        holder.itemView.setOnClickListener {
            // 调用接口方法，把数据传出去
            // 使用 holder.adapterPosition 获取当前位置，比 position 参数更安全
            val clickPosition = holder.getAbsoluteAdapterPosition()
            if (clickPosition != RecyclerView.NO_POSITION) {
                clickListener.onItemClick(currentSong, clickPosition)
            }
        }

        //长按事件
        holder.itemView.setOnLongClickListener {
            // 返回 true 表示事件已处理，不会触发点击事件
            val clickPosition = holder.getAbsoluteAdapterPosition()
            if (clickPosition != RecyclerView.NO_POSITION) {
                onLongClickListener(position, currentSong,isPlaying)
            }
            true
        }
    }

    // 告诉 RecyclerView 有多少条数据
    override fun getItemCount() = musicList.size

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newList: List<MusicEntity>, playingId: Int?) {
        this.musicList = newList
        this.currentPlayingId = playingId
//        musicList = newList
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submitPlayingId(playingId: Int?) {
        this.currentPlayingId = playingId
        notifyDataSetChanged()
    }

    fun getNextMusicEntityInMusicListByMediaId(mediaId: String?) : MusicEntity{
        val subtractPrefix = mediaId?.removePrefix("audio_")
        val thisAudioInMusicList = musicList.indexOfFirst { it.playCache == subtractPrefix }
        if (thisAudioInMusicList == -1 || thisAudioInMusicList+1 >= musicList.size) return musicList[0]
        return musicList[thisAudioInMusicList+1]
    }

    fun getNextMusicEntityByPosition(position: Int) : MusicEntity{
        if (position == -1 || position+1 >= musicList.size) return musicList[0]
        return musicList[position+1]
    }
}