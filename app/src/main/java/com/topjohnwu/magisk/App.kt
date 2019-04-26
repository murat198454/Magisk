package com.topjohnwu.magisk

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.multidex.MultiDex
import androidx.preference.PreferenceManager
import com.topjohnwu.magisk.data.database.MagiskDB
import com.topjohnwu.magisk.data.database.RepoDatabaseHelper
import com.topjohnwu.magisk.di.koinModules
import com.topjohnwu.magisk.utils.LocaleManager
import com.topjohnwu.magisk.utils.RootUtils
import com.topjohnwu.net.Networking
import com.topjohnwu.superuser.Shell
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import timber.log.Timber
import java.util.concurrent.ThreadPoolExecutor

open class App : Application(), Application.ActivityLifecycleCallbacks {

    // Global resources
    lateinit var protectedContext: Context
    val prefs: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(
            protectedContext
        )
    val DB: MagiskDB by lazy { MagiskDB(protectedContext) }
    @Deprecated("Use dependency injection")
    val repoDB: RepoDatabaseHelper by inject()
    @Volatile
    private var foreground: Activity? = null

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@App)
            modules(koinModules)
        }

        Timber.plant(Timber.DebugTree())
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(base)
        protectedContext = baseContext
        self = this
        deContext = base
        registerActivityLifecycleCallbacks(this)

        if (Build.VERSION.SDK_INT >= 24) {
            protectedContext = base.createDeviceProtectedStorageContext()
            deContext = protectedContext
            deContext.moveSharedPreferencesFrom(base, base.defaultPrefsName)
        }

        Networking.init(base)
        LocaleManager.setLocale(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        LocaleManager.setLocale(this)
    }

    //region ActivityLifecycleCallbacks
    override fun onActivityCreated(activity: Activity, bundle: Bundle?) {}

    override fun onActivityStarted(activity: Activity) {}

    @Synchronized
    override fun onActivityResumed(activity: Activity) {
        foreground = activity
    }

    @Synchronized
    override fun onActivityPaused(activity: Activity) {
        foreground = null
    }

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {}
    //endregion

    private val Context.defaultPrefsName get() = "${packageName}_preferences"

    companion object {

        //fixme this should be at least weak reference, me no likey
        @SuppressLint("StaticFieldLeak")
        @Deprecated("Use dependency injection")
        @JvmStatic
        lateinit var self: App

        //fixme this should be at least weak reference, me no likey
        @SuppressLint("StaticFieldLeak")
        @Deprecated("Use dependency injection")
        @JvmStatic
        lateinit var deContext: Context

        //fixme me no likey
        @JvmField
        var THREAD_POOL: ThreadPoolExecutor

        init {
            AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
            Shell.Config.setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_USE_MAGISK_BUSYBOX)
            Shell.Config.verboseLogging(BuildConfig.DEBUG)
            Shell.Config.addInitializers(RootUtils::class.java)
            Shell.Config.setTimeout(2)
            THREAD_POOL = AsyncTask.THREAD_POOL_EXECUTOR as ThreadPoolExecutor
        }

        //fixme me no likey
        @Deprecated("")
        @JvmStatic
        fun foreground(): Activity? {
            return self.foreground
        }
    }
}
