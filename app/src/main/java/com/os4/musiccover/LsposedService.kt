package com.os4.musiccover

import android.content.Context
import androidx.core.content.edit
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's line to LSPosed (libxposed service, API 102), bound by the framework itself through
 * the service library's provider once the app is running.
 *
 * Two things come through it. The log level goes out to every process the module is loaded into,
 * as a remote preferences group they each read (LogLevel, Xp) - the module's own channels reach
 * SystemUI and the wallpaper only. And LSPosed says which processes the module is loaded into
 * right now and whether each runs the installed build, which is what 重启全部作用域 restarts and
 * what the home page's "still on the old version" row counts.
 *
 * Without the service - an older framework, or before it has bound - the level stays local and
 * the restart falls back to the scope the APK ships.
 */
object LsposedService {
    private const val PREFS_NAME = "app_settings"

    private val bound = MutableStateFlow<XposedService?>(null)

    /** The bound service, for a page that has to ask again once it arrives. */
    val service: StateFlow<XposedService?> = bound

    @Volatile private var registered = false

    fun init(context: Context) {
        if (registered) return
        registered = true
        val app = context.applicationContext
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                // The app's copy is the setting; the group is only where the module reads it. A
                // group that was never written, or was lost with LSPosed's data, gets it again.
                write(service, logLevel(app))
                bound.value = service
            }

            override fun onServiceDied(service: XposedService) {
                if (bound.value === service) bound.value = null
            }
        })
    }

    fun logLevel(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(LogLevel.KEY, LogLevel.NORMAL)

    fun setLogLevel(context: Context, level: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(LogLevel.KEY, level)
        }
        bound.value?.let { write(it, level) }
    }

    private fun write(service: XposedService, level: Int) {
        runCatching {
            service.getRemotePreferences(LogLevel.GROUP).edit().putInt(LogLevel.KEY, level).apply()
        }
    }

    /**
     * Every process the module is loaded into right now; null without the service. A binder
     * call into the framework, so not on the main thread.
     */
    fun runningTargets(): List<HookedTarget>? =
        bound.value?.let { runCatching { it.runningTargets }.getOrNull() }

    /** How many of those still run a build older than the one installed; 0 without the service. */
    fun staleCount(): Int = runningTargets()?.count { it.state == HookedTarget.State.STALE } ?: 0
}
