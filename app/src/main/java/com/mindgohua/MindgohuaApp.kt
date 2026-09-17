package com.mindgohua

import android.app.Application
import com.mindgohua.diagnostics.CrashLog

class MindgohuaApp : Application() {

    /**
     * 當機記錄要在**所有其他初始化之前**掛上，否則初始化過程自己爆掉時就沒人記錄了。
     * 這也是 [Application.onCreate] 存在的主要理由之一。
     *
     * 紀錄只寫進本機私有目錄，由使用者自己決定要不要匯出。詳見 [CrashLog]。
     */
    override fun onCreate() {
        super.onCreate()
        CrashLog(this).install()
    }
}
