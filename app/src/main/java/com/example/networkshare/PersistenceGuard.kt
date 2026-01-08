package com.example.networkshare

import android.content.Context
import androidx.core.content.edit

object PersistenceGuard {
    private const val PREFS_NAME = "webdav_safety_prefs"
    private const val KEY_PREFIX_VERIFIED = "verified_"
    private const val KEY_PREFIX_TIME = "time_"

    fun markStarted(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putBoolean(KEY_PREFIX_VERIFIED + path, false)
            putLong(KEY_PREFIX_TIME + path, System.currentTimeMillis())
        }
    }

    fun markVerified(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putBoolean(KEY_PREFIX_VERIFIED + path, true)
        }
    }

    fun isSafeToDelete(context: Context, path: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isVerified = prefs.getBoolean(KEY_PREFIX_VERIFIED + path, false)
        val lastTime = prefs.getLong(KEY_PREFIX_TIME + path, 0L)

        if (lastTime == 0L) return true

        val isExpired = (System.currentTimeMillis() - lastTime) > 60000
        return isVerified || isExpired
    }

    fun clear(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            remove(KEY_PREFIX_VERIFIED + path)
            remove(KEY_PREFIX_TIME + path)
        }
    }
}