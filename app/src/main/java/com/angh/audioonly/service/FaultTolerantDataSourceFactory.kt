package com.angh.audioonly.service

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.datasource.HttpDataSource
import com.angh.audioonly.data.database
import com.angh.audioonly.utill.HttpClientProvider
import com.angh.audioonly.utill.WebUtils
import kotlinx.coroutines.runBlocking
import java.io.IOException

object RefreshPlayUrlHelper {
    suspend fun refreshPlayUrl(originalUrl: String, context: Context): String {
         val musicDao = context.database.musicDao()
         val searchString = musicDao.getOneMusicByPlayUrl(originalUrl).searchString
         val playInfo = WebUtils.getPlayUrl(searchString,HttpClientProvider.client)
         return playInfo.playUrl?:"fail"
     }
 }

@OptIn(UnstableApi::class)
class FaultTolerantDataSourceFactory(
    private val upstreamFactory: HttpDataSource.Factory,
    private val context: Context,
    private val maxRetries: Int = 3
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return FaultTolerantDataSource(upstreamFactory.createDataSource(), maxRetries)
    }

    private inner class FaultTolerantDataSource(
        private val upstream: HttpDataSource,
        private val maxRetries: Int
    ) : HttpDataSource {

        private var currentRetryCount = 0
        private var currentUri: String? = null

        @Throws(IOException::class)
        override fun open(dataSpec: DataSpec): Long {
            currentUri = dataSpec.uri.toString()
            var lastException: IOException? = null

            // 重试循环
            while (currentRetryCount <= maxRetries) {
                try {
                    // 1. 尝试打开连接
                    return upstream.open(dataSpec)
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    // 2. 拦截关键错误：HTTP 403
                    if (e.responseCode == 403) {
                        lastException = e
                        currentRetryCount++

                        if (currentRetryCount > maxRetries) {
                            // 重试次数用尽，抛出异常
                            Log.e("FaultTolerantDS", "403 重试次数耗尽 ($maxRetries)")
                            throw IOException("重试失败: 403 Forbidden after $maxRetries retries", e)
                        }

                        // 3.刷新 URL
                        val newUrl =  runBlocking {RefreshPlayUrlHelper.refreshPlayUrl(currentUri!!, context = context)}
                        if (newUrl == "fail"){
                            currentRetryCount += maxRetries
                            Log.e("FaultTolerantDS", "获取资源失败！！")
                            throw IOException("获取资源失败！！", e)
                        }
                        Log.e("FaultTolerantDS", "检测到 403，第 $currentRetryCount 次重试，新地址: $newUrl")

                        // 修改 DataSpec 指向新地址 保留原有参数
                        val newDataSpec = DataSpec.Builder()
                            .setUri(newUrl)
                            .setHttpRequestHeaders(dataSpec.httpRequestHeaders)
                            .setHttpMethod(dataSpec.httpMethod)
                            .setPosition(dataSpec.position) // 保持断点续传的位置
                            .setLength(dataSpec.length)
                            .setKey(dataSpec.key)
                            .setFlags(dataSpec.flags)
                            .build()

                        // 递归调用 open，使用新地址
                        return open(newDataSpec)
                    } else {
                        // 非 403 错误，直接抛出
                        Log.e("FaultTolerantDS", "获取资源失败！！e.message= ${e.message}")
                        throw e
                    }
                } catch (e: IOException) {
                    lastException = e
                    currentRetryCount++
                    if (currentRetryCount > maxRetries) {
                        throw IOException("网络请求失败: ${e.message}", e)
                    }
                    // Thread.sleep(1000) 避免高频重试
                }
            }
            throw IOException("未知错误", null)
        }

        override fun close() {
            return upstream.close()
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int
        ): Int {
            return upstream.read(buffer, offset, length)
        }

        override fun setRequestProperty(name: String, value: String) {
            return upstream.setRequestProperty(name,value)
        }

        override fun clearRequestProperty(name: String) {
            return upstream.clearRequestProperty(name)
        }

        override fun clearAllRequestProperties() {
            return upstream.clearAllRequestProperties()
        }

        override fun getResponseCode(): Int {
            return upstream.responseCode
        }

        override fun getResponseHeaders(): Map<String, List<String>> {
            return upstream.responseHeaders
        }

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun getUri(): Uri? {
            return upstream.uri
        }
    }
}