package org.fossify.gallery.utils

import android.util.Log
import org.fossify.gallery.BuildConfig

/**
 * 调试日志工具类
 *
 * 使用方式：
 * DebugLog.d("Tag", "message")  // 只在 debug 包输出
 * DebugLog.e("Tag", "error")    // 所有版本输出错误
 * DebugLog.i("Tag", "info")     // 所有版本输出信息
 */
object DebugLog {
    private val ENABLE_VERBOSE = BuildConfig.DEBUG

    /**
     * Verbose 日志，只在 debug 版本输出
     */
    fun v(tag: String, msg: String, throwable: Throwable? = null) {
        if (ENABLE_VERBOSE) {
            if (throwable != null) {
                Log.v(tag, msg, throwable)
            } else {
                Log.v(tag, msg)
            }
        }
    }

    /**
     * Debug 日志，只在 debug 版本输出
     */
    fun d(tag: String, msg: String, throwable: Throwable? = null) {
        if (ENABLE_VERBOSE) {
            if (throwable != null) {
                Log.d(tag, msg, throwable)
            } else {
                Log.d(tag, msg)
            }
        }
    }

    /**
     * Info 日志，所有版本输出
     */
    fun i(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.i(tag, msg, throwable)
        } else {
            Log.i(tag, msg)
        }
    }

    /**
     * Warn 日志，所有版本输出
     */
    fun w(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, msg, throwable)
        } else {
            Log.w(tag, msg)
        }
    }

    /**
     * Error 日志，所有版本输出
     */
    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, msg, throwable)
        } else {
            Log.e(tag, msg)
        }
    }
}
