package com.angh.audioonly.utill

import android.util.Log
import androidx.annotation.OptIn
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.InternalSerializationApi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

object WebUtils {

    // 扩展 OkHttp Call 支持 suspend
    private suspend fun Call.await(): Response {
        return suspendCancellableCoroutine { continuation ->
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }
            })
        }
    }

    suspend fun getPlayUrl(
        bVid: String,
        client: OkHttpClient
    ): PlayInfo {

        //发起请求
        val request = Request.Builder()
            .url("https://www.bilibili.com/video/$bVid")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
            .header("Referer", "https://www.bilibili.com/")
            .build()

        val response = client.newCall(request).await()
        val body = response.body?.string() ?: throw Exception("Empty response body")
        response.close()

        val back = extractPlayInfo(body) ?: PlayInfoResponse(message = "Non",null)
        val playUrl = back.data?.dash?.audio?.get(0)?.baseUrl
        val playDuration = back.data?.dash?.duration
        val playInitialization = back.data?.dash?.audio?.get(0)?.segmentBase?.initializationCamel
        val playName = back.message
        return PlayInfo(playDuration,playUrl, playInitialization,playName)
    }

    //获取分P信息
    suspend fun getMusicListSizeByBVId(
        bVid: String,
        client: OkHttpClient
    ): Int {
        //发起请求
        val request = Request.Builder()
            .url("https://api.bilibili.com/x/player/pagelist?bvid=$bVid")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
            .header("Referer", "https://www.bilibili.com/")
            .build()

        val response = client.newCall(request).await()
        val body = response.body?.string() ?: throw Exception("Empty response body")
        response.close()

        val playListInBVId = extractPlayListInBVId(body)
        return if (playListInBVId == null || playListInBVId.data == null) {
            0
        }
        else{
            playListInBVId.data.size
        }
    }

    @Serializable
    @OptIn(InternalSerializationApi::class)
    data class PlayListInBVId(
        val code: Long?,
        val data : List<PlayShortInfo>?
    )

    @Serializable
    @OptIn(InternalSerializationApi::class)
    data class PlayShortInfo(
        val page: Long?
    )

    @Serializable
    @OptIn(InternalSerializationApi::class)
    data class PlayInfo(
        val playDuration: Long?,
        val playUrl: String?,
        val playInitialization: String?,
        val playName:String?
    )
    @Serializable
    @OptIn(InternalSerializationApi::class)
    data class PlayInfoResponse(
        var message: String,//存储title
        val data: PlayData?
    )

    @Serializable
    @OptIn(InternalSerializationApi::class)
    data class PlayData(
        val dash: Dash?
    )

    @Serializable
    @OptIn(InternalSerializationApi::class)
    data class Dash(
        val duration: Long?,
        val audio: List<AudioTrack>?
    )

    @Serializable
    @OptIn(InternalSerializationApi::class)
    data class AudioTrack(
        @SerialName("baseUrl")
        val baseUrl: String,
        @SerialName("SegmentBase")
        val segmentBase: SegmentBase?
    )

    @Serializable
    @OptIn(InternalSerializationApi::class)
    data class SegmentBase(
        @SerialName("Initialization")
        val initializationCamel: String?
    )

    val json = Json {
        ignoreUnknownKeys = true // 忽略 JSON 中存在但类中未定义的字段
        isLenient = true         // 允许非标准的 JSON 格式（如注释、单引号等）
    }

    fun extractPlayInfo(html: String): PlayInfoResponse? {
        val doc = Jsoup.parse(html)
        var playInfoScript: String? = null

        // 查找包含 window.__playinfo__ 的 script 标签
        for (script in doc.select("script")) {
            val data = script.data()
            if (data.contains("window.__playinfo__")) {
                // 提取 JSON 部分
                val start = data.indexOf("={") + 1
                val end = data.indexOfLast { it == '}' } + 1
                if (start in 1..<end) {
                    playInfoScript = data.substring(start, end)
                    break
                }
            }
        }

        val title = doc.title()

        return if (playInfoScript != null) {
            try {
                val backData = json.decodeFromString<PlayInfoResponse>(playInfoScript)
                backData.message = title
                return backData
            } catch (e: Exception) {
                Log.e("FaultToParseJS", "JSON 解析失败: ${e.message}")
                null
            }
        } else {
            null
        }
    }

    fun extractPlayListInBVId(jsonString: String): PlayListInBVId? {
        try {
            val backData = json.decodeFromString<PlayListInBVId>(jsonString)
            return backData
        } catch (e: Exception) {
            Log.e("FaultToParseJS", "JSON 解析失败: ${e.message}")
            return null
        }
    }
}