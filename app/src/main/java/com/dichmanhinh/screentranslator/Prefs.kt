package com.dichmanhinh.screentranslator

import android.content.Context
import com.dichmanhinh.screentranslator.ai.ModelCatalog

object Prefs {
    private const val FILE = "settings"
    private const val KEY_SRC = "src"
    private const val KEY_TGT = "tgt"
    private const val KEY_FULL = "force_full_screen"
    private const val KEY_ENGINE = "engine"
    private const val KEY_AI_MODEL = "ai_model"
    private const val KEY_AI_VISION = "ai_vision"
    private const val KEY_AI_VISION_FULL = "ai_vision_full"
    private const val KEY_AI_FALLBACK = "ai_fallback"

    const val ENGINE_GOOGLE = "google"
    const val ENGINE_AI = "ai"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun source(ctx: Context): String = sp(ctx).getString(KEY_SRC, Languages.AUTO)!!

    fun target(ctx: Context): String = sp(ctx).getString(KEY_TGT, "vi")!!

    fun forceFullScreen(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_FULL, false)

    fun setForceFullScreen(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_FULL, v).apply()
    }

    fun save(ctx: Context, src: String, tgt: String) {
        sp(ctx).edit().putString(KEY_SRC, src).putString(KEY_TGT, tgt).apply()
    }

    /** Bộ dịch: Google Dịch (trực tuyến) hoặc AI chạy trên máy. */
    fun engine(ctx: Context): String = sp(ctx).getString(KEY_ENGINE, ENGINE_GOOGLE)!!

    fun setEngine(ctx: Context, v: String) {
        sp(ctx).edit().putString(KEY_ENGINE, v).apply()
    }

    fun useAi(ctx: Context): Boolean = engine(ctx) == ENGINE_AI

    fun aiModel(ctx: Context): String = sp(ctx).getString(KEY_AI_MODEL, ModelCatalog.HY_MT.id)!!

    fun setAiModel(ctx: Context, id: String) {
        sp(ctx).edit().putString(KEY_AI_MODEL, id).apply()
    }

    /** Cho AI nhìn ảnh chụp (mô hình có đọc ảnh) khi dịch vùng chọn hoặc khi OCR không thấy chữ. */
    fun aiVision(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AI_VISION, true)

    fun setAiVision(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_AI_VISION, v).apply()
    }

    /** Cho AI nhìn ảnh cả khi dịch toàn màn hình (chậm hơn). */
    fun aiVisionFull(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AI_VISION_FULL, false)

    fun setAiVisionFull(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_AI_VISION_FULL, v).apply()
    }

    /** Dùng Google Dịch cho các đoạn AI không dịch được (cần mạng). */
    fun aiFallback(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AI_FALLBACK, true)

    fun setAiFallback(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_AI_FALLBACK, v).apply()
    }
}
