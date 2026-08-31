package com.phone.mirror.phone

import android.app.Application
import com.phone.mirror.phone.di.AppContainer

/**
 * 全局 App 入口。负责一次性初始化 [AppContainer]（服务定位器 / 轻量 DI 容器）。
 */
class AppApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
