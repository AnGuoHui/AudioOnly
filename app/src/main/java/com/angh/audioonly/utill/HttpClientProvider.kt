package com.angh.audioonly.utill

import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object HttpClientProvider {

    private var cookieStore: ConcurrentHashMap<String, List<Cookie>> =
        ConcurrentHashMap<String, List<Cookie>>()
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES)) // 自定义连接池配置
            .cookieJar(object : CookieJar {
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    val cookies: List<Cookie>? = cookieStore.get(url.host)
                    return cookies ?: ArrayList()
                }

                override fun saveFromResponse(
                    url: HttpUrl,
                    cookies: List<Cookie>
                ) {
                    cookieStore[url.host] = cookies
                }
            })
            .build()
    }
}