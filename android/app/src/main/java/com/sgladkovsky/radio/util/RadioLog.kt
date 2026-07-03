package com.sgladkovsky.radio.util

import android.util.Log

object RadioLog {
    private const val TAG = "UsbRadio"

    fun d(component: String, message: String) {
        Log.d(TAG, "[$component] $message")
    }

    fun i(component: String, message: String) {
        Log.i(TAG, "[$component] $message")
    }

    fun w(component: String, message: String, error: Throwable? = null) {
        if (error != null) {
            Log.w(TAG, "[$component] $message", error)
        } else {
            Log.w(TAG, "[$component] $message")
        }
    }

    fun e(component: String, message: String, error: Throwable? = null) {
        if (error != null) {
            Log.e(TAG, "[$component] $message", error)
        } else {
            Log.e(TAG, "[$component] $message")
        }
    }
}
