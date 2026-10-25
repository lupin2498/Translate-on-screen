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

    data class Status(
        val state: State,
        val downloaded: Long,
        val total: Long,
        val error: String? = null,
        /** READY nhưng thiếu file đọc ảnh (mmproj): vẫn dịch chữ được, không đọc ảnh. */
        val visionMissing: Boolean = false,
        /** Ghi chú khi đang tải (tạm dừng chờ Wi‑Fi, mất mạng...). */
        val note: String? = null,
    ) {
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

    /**
     * File mô hình chỉ mang tên chính thức sau khi đã tải/chép xong và được kiểm tra (xem [status],
     * [importGguf]) nên có file là dùng được. Không so với kích thước trong danh mục: nhà phát hành
     * cập nhật file trên Hugging Face thì kích thước đổi.
     */
    private fun isComplete(ctx: Context, f: ModelFile): Boolean {
        val file = file(ctx, f)
        return file.isFile && file.length() > 1_000_000
    }

    private fun isGguf(file: File): Boolean = runCatching {
        file.inputStream().use { val b = ByteArray(4); it.read(b) == 4 && String(b, Charsets.US_ASCII) == "GGUF" }
    }.getOrDefault(false)

    /**
     * Mô hình đã sẵn sàng chưa. File tải xong lúc ứng dụng đang đóng cũng được hoàn tất (đổi tên) ở
     * đây, nên dịch vụ dịch màn hình dùng được ngay mà không cần mở lại màn hình AI.
     */
    fun isReady(ctx: Context, spec: ModelSpec): Boolean {
        finishDownloads(ctx, spec)
        // Chỉ cần file mô hình chính để dịch chữ; mmproj (đọc ảnh) là phần thêm
        return isComplete(ctx, spec.model)
    }

    /** Có đủ file để mô hình nhìn ảnh (mmproj) không. */
    fun hasVision(ctx: Context, spec: ModelSpec): Boolean {
        val mm = spec.mmproj ?: return false
        finishDownloads(ctx, spec)
        return isComplete(ctx, spec.model) && isComplete(ctx, mm)
    }

    /** Hoàn tất (kiểm tra, đổi tên) các file đã tải xong – kể cả khi tải xong lúc ứng dụng đóng. */
    private fun finishDownloads(ctx: Context, spec: ModelSpec) {
        if (spec.files.all { isComplete(ctx, it) }) return
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return
        for (f in spec.files) if (!isComplete(ctx, f)) checkDownload(ctx, f, dm)
    }

    /** Đường dẫn file mô hình và mmproj (mmproj null nếu chưa có); null nếu chưa có mô hình. */
    fun paths(ctx: Context, spec: ModelSpec): Pair<String, String?>? {
        if (!isReady(ctx, spec)) return null
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

    private class FileState(val state: State, val bytes: Long, val error: String? = null, val note: String? = null)

    private fun pausedReason(code: Int): String = when (code) {
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "đang chờ Wi‑Fi (máy chặn tải file lớn bằng dữ liệu di động)"
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "mất mạng, đang chờ kết nối lại"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "lỗi mạng, đang thử lại"
        else -> "tạm dừng"
    }

    /** Trạng thái tải của một file; file đã tải xong thì kiểm tra rồi đổi sang tên chính thức. */
    private fun checkDownload(ctx: Context, f: ModelFile, dm: DownloadManager): FileState {
        if (isComplete(ctx, f)) return FileState(State.READY, file(ctx, f).length())
        val id = prefs(ctx).getLong(key(f), -1L)
        if (id < 0) return FileState(State.NOT_DOWNLOADED, 0)
        val c = dm.query(DownloadManager.Query().setFilterById(id)) ?: return FileState(State.NOT_DOWNLOADED, 0)
        return c.use {
            if (!c.moveToFirst()) {
                forget(ctx, f)
                return@use FileState(State.NOT_DOWNLOADED, 0)
            }
            val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            when (st) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    // Vị trí thật của file (một số máy tự đổi tên file đích)
                    val local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                        ?.let { Uri.parse(it).path }?.let { File(it) }
                    val part = local?.takeIf { it.isFile } ?: partFile(ctx, f)
                    // So với kích thước máy chủ báo (đúng cả khi file trên Hugging Face được cập nhật)
                    val reported = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val expected = if (reported > 0) reported else f.size
                    val len = part.length()
                    val ok = part.isFile && len > 1_000_000 && (expected <= 0 || len == expected) && isGguf(part)
                    forget(ctx, f)
                    if (ok && part.renameTo(file(ctx, f))) {
                        Log.i(TAG, "download complete ${f.name} ($len bytes)")
                        FileState(State.READY, len)
                    } else {
                        part.delete()
                        FileState(State.FAILED, 0, "File tải về bị lỗi ($len / $expected byte), hãy tải lại")
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    dm.remove(id)
                    forget(ctx, f)
                    FileState(State.FAILED, 0, failReason(reason))
                }
                DownloadManager.STATUS_PAUSED -> {
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    FileState(State.DOWNLOADING, got.coerceAtLeast(0), note = pausedReason(reason))
                }
                DownloadManager.STATUS_PENDING ->
                    FileState(State.DOWNLOADING, got.coerceAtLeast(0), note = "đang chờ bắt đầu")
                else -> FileState(State.DOWNLOADING, got.coerceAtLeast(0))
            }
        }
    }

    /** Trạng thái tải của cả mô hình (hiển thị tiến độ). */
    fun status(ctx: Context, spec: ModelSpec): Status {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val states = spec.files.map { f ->
            when {
                isComplete(ctx, f) -> FileState(State.READY, file(ctx, f).length())
                dm == null -> FileState(State.NOT_DOWNLOADED, 0)
                else -> checkDownload(ctx, f, dm)
            }
        }
        val done = states.sumOf { it.bytes }
        val total = maxOf(spec.totalSize, done)
        val error = states.firstNotNullOfOrNull { it.error }
        return when {
            states.all { it.state == State.READY } -> Status(State.READY, done, done)
            states.any { it.state == State.DOWNLOADING } ->
                Status(State.DOWNLOADING, done, total, note = states.firstNotNullOfOrNull { it.note })
            // Có mô hình chính, thiếu mmproj: dịch chữ được, chưa đọc được ảnh
            states.first().state == State.READY -> Status(State.READY, done, total, error, visionMissing = true)
            states.any { it.state == State.FAILED } -> Status(State.FAILED, done, total, error)
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
        for (f in spec.files) cancelFileDownload(ctx, f)
    }

    private fun cancelFileDownload(ctx: Context, f: ModelFile) {
        val id = prefs(ctx).getLong(key(f), -1L)
        if (id >= 0) runCatching { ctx.getSystemService(DownloadManager::class.java)?.remove(id) }
        forget(ctx, f)
        partFile(ctx, f).delete()
    }

    fun delete(ctx: Context, spec: ModelSpec) {
        cancelDownload(ctx, spec)
        for (f in spec.files) file(ctx, f).delete()
    }

    /** Kết quả nhập một file: mô hình nó thuộc về (null nếu là mmproj lạ) và tên file đã lưu. */
    class ImportResult(val spec: ModelSpec?, val fileName: String, val isMmproj: Boolean)

    /**
     * Nhập file GGUF từ bộ nhớ máy (SAF) vào thư mục mô hình. File chính thức của mô hình trong
     * danh mục (người dùng tự tải bằng trình duyệt) được nhận ra theo tên/kích thước và lưu đúng
     * tên, nên mô hình đó hiện "Đã tải" như khi tải trong ứng dụng.
     * @param onProgress (đã chép, tổng) byte
     */
    suspend fun importGguf(ctx: Context, uri: Uri, onProgress: (Long, Long) -> Unit): ImportResult =
        withContext(Dispatchers.IO) {
            val cr = ctx.contentResolver
            var display = "model.gguf"
            var size = -1L
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { display = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            val match = ModelCatalog.matchFile(display, size)
            var name = match?.second?.name ?: display.replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (!name.endsWith(".gguf", true)) name += ".gguf"
            val isMmproj = match?.let { it.second == it.first.mmproj } ?: ModelCatalog.isMmprojName(name)
            if (size > 0 && freeSpace(ctx) < size + 100_000_000L) {
                throw IOException("Không đủ dung lượng trống (cần thêm ${size / 1_000_000} MB)")
            }
            // Đang tải dở file này trong ứng dụng: dừng tải, dùng file người dùng nhập
            match?.let { cancelFileDownload(ctx, it.second) }
            val dest = File(dir(ctx), name)
            val tmp = File(dir(ctx), "$name.importing")
            try {
                cr.openInputStream(uri)?.use { input ->
                    val magic = ByteArray(4)
                    var got = 0
                    while (got < 4) {
                        val n = input.read(magic, got, 4 - got)
                        if (n < 0) break
                        got += n
                    }
                    if (got != 4 || String(magic, Charsets.US_ASCII) != "GGUF") {
                        throw IOException("\"$display\" không phải file mô hình GGUF")
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
                if (size > 0 && tmp.length() != size) throw IOException("Chép thiếu dữ liệu, hãy thử lại")
                if (!tmp.renameTo(dest)) throw IOException("Không lưu được file")
            } finally {
                tmp.delete()
            }
            Log.i(TAG, "imported $display -> $name (${dest.length()} bytes) match=${match?.first?.id}")
            val spec = when {
                match != null -> match.first
                isMmproj -> null
                else -> ModelCatalog.custom(name, dest.length())
            }
            ImportResult(spec, name, isMmproj)
        }
}
