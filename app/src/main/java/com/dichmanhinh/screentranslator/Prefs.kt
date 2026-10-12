package com.dichmanhinh.screentranslator

import android.content.Context

object Prefs {
    private const val FILE = "settings"
    private const val KEY_SRC = "src"
    private const val KEY_TGT = "tgt"
    private const val KEY_FULL = "force_full_screen"

    fun source(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_SRC, Languages.AUTO)!!

    fun target(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_TGT, "vi")!!

    fun forceFullScreen(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_FULL, false)

    fun setForceFullScreen(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_FULL, v).apply()
    }

    fun save(ctx: Context, src: String, tgt: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_SRC, src).putString(KEY_TGT, tgt).apply()
    }
}
