package com.angh.audioonly.data

import android.app.Application
import android.content.Context
import androidx.room.Room

class DbConn : Application() {
    // 使用 lazy 实现懒加载单例
    val database: AppDataBase by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDataBase::class.java,
            "app-db-adio_only" // 数据库文件名
        ).build()
    }
}

/**
 * 这是一个扩展属性，允许在任何 Context 作用域下
 * 直接通过 context.database 来获取单例数据库
 */
val Context.database: AppDataBase
    get() = (applicationContext as DbConn).database