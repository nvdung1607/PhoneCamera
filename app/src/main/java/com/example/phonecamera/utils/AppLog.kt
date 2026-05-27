package com.example.phonecamera.utils

import android.util.Log

/**
 * Unified logging utility for Phone Camera project.
 * Automatically prepends [FileName:MethodName] to all logs.
 * Tag is always "PhoneCamera".
 */
object AppLog {
    private const val GLOBAL_TAG = "PhoneCamera"

    private fun getCallerInfo(): String {
        val stackTrace = Thread.currentThread().stackTrace
        // Find the index of the AppLog call in the stack trace
        var index = -1
        for (i in stackTrace.indices) {
            if (stackTrace[i].className == AppLog::class.java.name) {
                index = i
            } else if (index != -1) {
                // The first element after AppLog entries is the caller
                val caller = stackTrace[i]
                val fileName = caller.fileName?.substringBefore(".") ?: "Unknown"
                val methodName = caller.methodName
                val lineNumber = caller.lineNumber
                return "[$fileName:$methodName:$lineNumber]"
            }
        }
        return "[Unknown]"
    }

    fun net(message: String) {
        Log.i("PhoneCameraNet", "${getCallerInfo()} 🌐 [NET_PACKET] $message")
    }

    fun d(message: String) {
        Log.d(GLOBAL_TAG, "${getCallerInfo()} $message")
    }

    fun i(message: String) {
        Log.i(GLOBAL_TAG, "${getCallerInfo()} $message")
    }

    fun w(message: String) {
        Log.w(GLOBAL_TAG, "${getCallerInfo()} $message")
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(GLOBAL_TAG, "${getCallerInfo()} $message", throwable)
        } else {
            Log.e(GLOBAL_TAG, "${getCallerInfo()} $message")
        }
    }

    fun v(message: String) {
        Log.v(GLOBAL_TAG, "${getCallerInfo()} $message")
    }
}
