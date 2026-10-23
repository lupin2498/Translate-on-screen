package com.dichmanhinh.screentranslator.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Tải, lưu và quản lý file mô hình. Việc tải giao cho DownloadManager của hệ thống: chạy nền,
 * tự nối lại khi rớt mạng, có thông báo tiến độ – phù hợp với file vài GB.
 */
object ModelManager {
    private const val TAG = "ScreenAI"
    private const val PREFS = "ai_models"
    private const val DIR = "models"

    enum class State { NOT_DOWNLOADED, DOWNLOADING, READY, FAILED }

    data class Status(val state: State, val downloaded: Long, val total: Long, val error: String? = null) {
        val percent: Int get() = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
    }

    fun dir(ctx: Context): File {
        val d = ctx.getExternalFilesDir(DIR) ?: File(ctx.filesDir, DIR)
        d.mkdirs()
        return d
    }

    fun file(ctx: Context, f: ModelFile): File = File(dir(ctx), f.name)

    private fun partFile(ctx: Context, f: ModelFile) = File(dir(ctx), f.name + ".part")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isComplete(ctx: Context, f: ModelFile): Boolean {
        val file = file(ctx, f)
        return file.isFile && (if (f.size > 0) file.length() == f.size else file.length() > 1_000_000)
    }

    fun isReady(ctx: Context, spec: ModelSpec): Boolean = spec.files.all { isComplete(ctx, it) }

    /** Đường dẫn file mô hình và mmproj (null nếu chưa tải đủ). */
    fun paths(ctx: Context, spec: ModelSpec): Pair<String, String?>? {
        if (!isComplete(ctx, spec.model)) return null
        val mm = spec.mmproj?.let { if (isComplete(ctx, it)) file(ctx, it).absolutePath else null }
        return file(ctx, spec.model).absolutePath to mm
    }

    /** Các mô hình đã nhập từ máy (file *.gguf không thuộc danh mục). */
    fun customModels(ctx: Context): List<ModelSpec> {
        val known = ModelCatalog.builtIn.flatMap { s -> s.files.map { it.name } }.toSet()
        return dir(ctx).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".gguf") && it.name !in known && !it.name.startsWith("mmproj") }
            .sortedBy { it.name }
            .map { ModelCatalog.custom(it.name, it.length()) }
    }

    fun allModels(ctx: Context): List<ModelSpec> = ModelCatalog.builtIn + customModels(ctx)

    fun find(ctx: Context, id: String?): ModelSpec? =
        ModelCatalog.byId(id) ?: customModels(ctx).firstOrNull { it.id == id }

    /** Trạng thái tải; đồng thời hoàn tất (đổi tên) file đã tải xong. */
    fun status(ctx: Context, spec: ModelSpec): Status {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        var done = 0L
        var downloading = false
        var error: String? = null
        for (f in spec.files) {
            if (isComplete(ctx, f)) {
                done += f.size.takeIf { it > 0 } ?: file(ctx, f).length(); continue
            }
            val id = prefs(ctx).getLong(key(f), -1L)
            if (id < 0 || dm == null) continue
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) {
                    forget(ctx, f); return@use
                }
                val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                when (st) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val part = partFile(ctx, f)
                        val ok = part.isFile && (f.size <= 0 || part.length() == f.size)
                        if (ok && part.renameTo(file(ctx, f))) {
                            done += f.size
                        } else {
                            error = "File tải về bị lỗi (${part.length()} / ${f.size} byte)"
                            part.delete()
                        }
                        forget(ctx, f)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        error = failReason(reason)
                        dm.remove(id)
                        forget(ctx, f)
                    }
                    else -> {
                        downloading = true
                        done += got.coerceAtLeast(0)
                    }
                }
            }
        }
        val total = spec.totalSize
        return when {
            isReady(ctx, spec) -> Status(State.READY, total, total)
            downloading -> Status(State.DOWNLOADING, done, total)
            error != null -> Status(State.FAILED, done, total, error)
            else -> Status(State.NOT_DOWNLOADED, done, total)
        }
    }

    private fun failReason(code: Int): String = when (code) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Không đủ dung lượng trống"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Lỗi máy chủ ($code)"
        DownloadManager.ERROR_CANNOT_RESUME -> "Không nối tiếp được, hãy tải lại"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Không tìm thấy bộ nhớ"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Quá nhiều chuyển hướng"
        in 400..599 -> "Lỗi HTTP $code"
        else -> "Lỗi tải ($code)"
    }

    private fun key(f: ModelFile) = "dl_" + f.name

    private fun forget(ctx: Context, f: ModelFile) {
        prefs(ctx).edit().remove(key(f)).apply()
    }

    /** Dung lượng còn thiếu để tải mô hình (byte). */
    fun missingBytes(ctx: Context, spec: ModelSpec): Long =
        spec.files.filterNot { isComplete(ctx, it) }.sumOf { it.size }

    fun freeSpace(ctx: Context): Long = dir(ctx).usableSpace

    /** Bắt đầu tải các file còn thiếu. */
    fun download(ctx: Context, spec: ModelSpec) {
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: throw IOException("DownloadManager unavailable")
        if (ctx.getExternalFilesDir(DIR) == null) throw IOException("Không có bộ nhớ để lưu mô hình")
        for (f in spec.files) {
            if (isComplete(ctx, f) || f.url.isEmpty()) continue
            val old = prefs(ctx).getLong(key(f), -1L)
            if (old >= 0) dm.remove(old)
            partFile(ctx, f).delete()
            val req = DownloadManager.Request(Uri.parse(f.url))
                .setTitle("${spec.name}: ${f.name}")
                .setDescription("Mô hình AI dịch màn hình")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(ctx, DIR, f.name + ".part")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            val id = dm.enqueue(req)
            prefs(ctx).edit().putLong(key(f), id).apply()
            Log.i(TAG, "download ${f.name} id=$id")
        }
    }

    fun cancelDownload(ctx: Context, spec: ModelSpec) {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        for (f in spec.files) {
            val id = prefs(ctx).getLong(key(f), -1L)
            if (id >= 0) dm?.remove(id)
            forget(ctx, f)
            partFile(ctx, f).delete()
        }
    }

    fun delete(ctx: Context, spec: ModelSpec) {
        cancelDownload(ctx, spec)
        for (f in spec.files) file(ctx, f).delete()
    }

    /**
     * Nhập file GGUF từ bộ nhớ máy (SAF) vào thư mục mô hình.
     * @param onProgress (đã chép, tổng) byte
     */
    suspend fun importGguf(ctx: Context, uri: Uri, onProgress: (Long, Long) -> Unit): ModelSpec =
        withContext(Dispatchers.IO) {
            val cr = ctx.contentResolver
            var name = "model.gguf"
            var size = -1L
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (!name.endsWith(".gguf", true)) name += ".gguf"
            if (ModelCatalog.builtIn.any { s -> s.files.any { it.name == name } }) name = "custom-$name"
            if (size > 0 && freeSpace(ctx) < size + 100_000_000L) throw IOException("Không đủ dung lượng trống")
            val dest = File(dir(ctx), name)
            val tmp = File(dir(ctx), "$name.importing")
            try {
                cr.openInputStream(uri)?.use { input ->
                    val magic = ByteArray(4)
                    if (input.read(magic) != 4 || String(magic, Charsets.US_ASCII) != "GGUF") {
                        throw IOException("Không phải file GGUF")
                    }
                    tmp.outputStream().use { out ->
                        out.write(magic)
                        val buf = ByteArray(1 shl 20)
                        var copied = 4L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            if (copied - lastReport > 16L shl 20) {
                                coroutineContext.ensureActive()
                                lastReport = copied
                                onProgress(copied, size)
                            }
                        }
                    }
                } ?: throw IOException("Không mở được file")
                if (!tmp.renameTo(dest)) throw IOException("Không lưu được file")
            } finally {
                tmp.delete()
            }
            ModelCatalog.custom(name, dest.length())
        }
}
