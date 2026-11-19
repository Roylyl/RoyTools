package com.github.kr328.clash.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import java.io.File
import java.util.zip.ZipFile

object ApplicationObserver {
    private val _createdActivities: MutableSet<Activity> = mutableSetOf()
    private val _visibleActivities: MutableSet<Activity> = mutableSetOf()

    private var visibleChanged: (Boolean) -> Unit = {}

    private var appVisible = false
        private set(value) {
            if (field != value) {
                field = value

                visibleChanged(value)
            }
        }

    val createdActivities: Set<Activity>
        get() = _createdActivities
    fun visibleActivity(): Activity? = _visibleActivities.lastOrNull { !it.isFinishing && !it.isDestroyed }

    private val activityObserver = object : Application.ActivityLifecycleCallbacks {
        @Synchronized
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            _createdActivities.add(activity)
            com.github.kr328.clash.common.ui.TouchFeedback.install(activity.window)
            activity.applyRecentTaskVisibility()
        }

        @Synchronized
        override fun onActivityDestroyed(activity: Activity) {
            _createdActivities.remove(activity)
            _visibleActivities.remove(activity)
            appVisible = _visibleActivities.isNotEmpty()
        }

        override fun onActivityStarted(activity: Activity) {
            _visibleActivities.add(activity)
            appVisible = true
        }

        override fun onActivityStopped(activity: Activity) {
            _visibleActivities.remove(activity)
            appVisible = _visibleActivities.isNotEmpty()
        }

        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {
            activity.applyRecentTaskVisibility()
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }

    fun onVisibleChanged(visibleChanged: (Boolean) -> Unit) {
        this.visibleChanged = visibleChanged
    }

    fun attach(application: Application) {
        application.registerActivityLifecycleCallbacks(activityObserver)
    }
}

fun Context.verifyApk(): Boolean {
    return try {
        val info = applicationInfo
        val sources = listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty()
        sources.any { path ->
            File(path).isFile && ZipFile(path).use { apk ->
                Build.SUPPORTED_ABIS.any { abi -> apk.getEntry("lib/$abi/libclash.so") != null }
            }
        }
    } catch (e: Exception) {
        false
    }
}
