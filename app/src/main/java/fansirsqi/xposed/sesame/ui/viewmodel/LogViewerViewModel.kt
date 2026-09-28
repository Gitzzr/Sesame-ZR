package fansirsqi.xposed.sesame.ui.viewmodel

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.FileObserver
import android.util.LruCache
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fansirsqi.xposed.sesame.R
import fansirsqi.xposed.sesame.SesameApplication.Companion.PREFERENCES_KEY
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.LogIndexBuilder
import fansirsqi.xposed.sesame.util.ToastUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong


/**
 * 日志 UI 状态
 */
data class LogUiState(
    /** 是否已有可显示的行（列表本身用 totalCount + 偏移表，不再为此建一份装箱 Int 列表） */
    val hasContent: Boolean = false,
    val isLoading: Boolean = true,
    val isSearching: Boolean = false,
    val searchQuery: String = "",
    val totalCount: Int = 0,
    val autoScroll: Boolean = true,
    // ✨ 新增：tag 索引与过滤
    val availableTags: List<String> = emptyList(),
    val selectedTag: String? = null,
    val showErrorOnly: Boolean = false,
    val errorCount: Int = 0
)

/**
 * 日志查看器 ViewModel
 * ✨ 使用防抖 + 原子操作彻底解决重复问题
 */
class LogViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val tag = "LogViewerViewModel"

    private val prefs = application.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
    private val logFontSizeKey = "pref_font_size"

    private val _uiState = MutableStateFlow(LogUiState())
    val uiState = _uiState.asStateFlow()

    private val _fontSize = MutableStateFlow(prefs.getFloat(logFontSizeKey, 12f))
    val fontSize = _fontSize.asStateFlow()

    private val _scrollEvent = Channel<Int>(Channel.BUFFERED)
    val scrollEvent = _scrollEvent.receiveAsFlow()

    // 新增：文件更新信号通道 (CONFLATED 表示如果处理不过来，只保留最新的信号)
    private val fileUpdateChannel = Channel<Unit>(Channel.CONFLATED)
    private var fileObserver: FileObserver? = null
    private var currentFilePath: String? = null
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var updateJob: Job? = null // ✅ 新增:文件更新任务

    // --- 核心数据结构 ---
    private var raf: RandomAccessFile? = null
    private val allLineOffsets = ArrayList<Long>()
    private var displayLineOffsets: List<Long> = emptyList()
    private val lineCache = LruCache<Long, String>(200)

    // ✨ tag 索引：行偏移 -> 模块 tag（扫描时顺便提取行首 [tag] 前缀）
    private val tagIndex = HashMap<Long, String>()

    // ✨ 错误行偏移：扫描时顺便判定，替代「逐行回读文件数一遍」的老做法
    private val errorOffsets = HashSet<Long>()

    companion object {
        /** 首屏只读文件尾部这么多字节：够铺满几十屏，读一次就能立刻出内容 */
        private const val TAIL_BYTES = 256 * 1024

        /** 全量扫描的读缓冲 */
        private const val SCAN_BUFFER_BYTES = 64 * 1024
    }

    // ✅ 使用 AtomicLong 保证线程安全
    private val lastKnownFileSize = AtomicLong(0L)
    private val maxLines = 200_000

    // ✅ 用于防抖的互斥锁
    private val updateMutex = Mutex()

    @OptIn(FlowPreview::class)
    fun loadLogs(path: String) {
        if (currentFilePath == path && loadJob?.isActive == true) return
        currentFilePath = path

        loadJob?.cancel()
        updateJob?.cancel()

        updateJob = viewModelScope.launch {
            fileUpdateChannel.receiveAsFlow()
                .debounce(200)
                .collectLatest {
                    handleFileUpdate()
                }
        }

        loadJob = viewModelScope.launch {
            closeFile()
            _uiState.update { it.copy(isLoading = true, hasContent = false, totalCount = 0) }

            val file = File(path)
            if (!file.exists() || !file.canRead()) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }

            indexFileContent(file)
            startFileObserver(path)
        }
    }



    private suspend fun indexFileContent(file: File) = withContext(Dispatchers.IO) {
        try {
            val localRaf = RandomAccessFile(file, "r")
            raf = localRaf

            val fileSize = localRaf.length()
            lastKnownFileSize.set(fileSize)
            if (fileSize == 0L) {
                applyIndex(emptyList(), emptyMap(), emptySet())
                lineCache.evictAll()
                refreshList()
                return@withContext
            }

            // ① 先只读尾部，让界面立刻出内容。
            //    旧实现要等整份文件索引完才显示 —— 单文件放宽到 3MB/7MB 后就是十几秒的转圈。
            if (fileSize > TAIL_BYTES) {
                runCatching { readTail(file, fileSize) }.getOrNull()?.let { tail ->
                    applyIndex(tail.offsets, tail.tags, tail.errorOffsets)
                    refreshList()
                }
            }

            // ② 再建立完整索引：单次顺序扫描，零随机读
            val builder = scanWholeFile(file, fileSize)
            val allOffsets = builder.offsets()
            val kept = if (allOffsets.size > maxLines) allOffsets.takeLast(maxLines) else allOffsets
            val keptSet = kept.toHashSet()
            applyIndex(
                kept,
                builder.tags().filterKeys { it in keptSet },
                builder.errorOffsets().filterTo(HashSet()) { it in keptSet }
            )
            lineCache.evictAll()
            refreshList()

        } catch (e: CancellationException) {
            // ✅ 协程取消异常不记录日志，直接静默处理
            // 这是正常的协程生命周期管理，不需要打印错误
            throw e // 重新抛出让协程框架处理
        } catch (e: Exception) {
            e.printStackTrace()
            // 这里刻意用 android.util.Log 再打一份到 logcat：模块 App 自身进程写不进模块日志目录
            // （那些文件的属主是宿主），日志页自己出问题时只能靠 logcat 排查。
            android.util.Log.e("LogViewerVM", "索引日志失败: ${file.name}", e)
            val errorMsg = "索引失败: ${e.message}"
            Log.error(tag, errorMsg)
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isLoading = false) }
                ToastUtil.showToast(getApplication(), errorMsg)
            }
        }
    }

    /** 尾部一次读入并索引的结果 */
    private class IndexedChunk(
        val offsets: List<Long>,
        val tags: Map<Long, String>,
        val errorOffsets: Set<Long>
    )

    /**
     * 只读文件尾部 [TAIL_BYTES]，从第一个完整行开始索引。
     *
     * 起点可能落在半行中间（那半行在更早的位置），所以要先跳到缓冲区里的第一个换行之后。
     *
     * ⚠️ 刻意用**独立的** RandomAccessFile：与 [raf] 共用会把文件指针让给并发的逐行读取，
     * 扫描就会提前撞到 EOF（2026-09-28 实测：1.2MB / 3436 行的文件只索引出 1229~1403 行，且每次不同）。
     */
    private fun readTail(file: File, fileSize: Long): IndexedChunk {
        val start = maxOf(0L, fileSize - TAIL_BYTES)
        val length = (fileSize - start).toInt()
        val buffer = ByteArray(length)
        RandomAccessFile(file, "r").use { tailRaf ->
            tailRaf.seek(start)
            tailRaf.readFully(buffer)
        }

        var from = 0
        if (start > 0L) {
            val newline = buffer.indexOfFirst { it == '\n'.code.toByte() }
            if (newline < 0) return IndexedChunk(emptyList(), emptyMap(), emptySet())
            from = newline + 1
        }
        val builder = LogIndexBuilder(start + from)
        builder.feed(buffer, from, length - from)
        builder.finish()
        return IndexedChunk(builder.offsets(), builder.tags(), builder.errorOffsets())
    }

    /**
     * 顺序读完整份文件做一遍索引。
     *
     * 声明成 [CoroutineScope] 扩展是为了能用 `ensureActive()` 响应取消；
     * 用独立的 [FileInputStream] 读，避免与逐行读取争用同一个文件指针（原因见 [readTail]）。
     */
    private suspend fun CoroutineScope.scanWholeFile(file: File, fileSize: Long): LogIndexBuilder {
        val builder = LogIndexBuilder(0L)
        val buffer = ByteArray(SCAN_BUFFER_BYTES)
        FileInputStream(file).use { stream ->
            var readTotal = 0L
            while (readTotal < fileSize) {
                ensureActive()
                val read = stream.read(buffer)
                if (read <= 0) break
                builder.feed(buffer, 0, read)
                readTotal += read
            }
        }
        builder.finish()
        return builder
    }

    /** 用一次扫描的结果整体替换索引与偏移表，并刷新 tag/错误计数。 */
    private suspend fun applyIndex(
        offsets: List<Long>,
        tags: Map<Long, String>,
        errors: Set<Long>
    ) {
        synchronized(allLineOffsets) {
            allLineOffsets.clear()
            allLineOffsets.addAll(offsets)
        }
        synchronized(tagIndex) {
            tagIndex.clear()
            tagIndex.putAll(tags)
        }
        synchronized(errorOffsets) {
            errorOffsets.clear()
            errorOffsets.addAll(errors)
        }
        updateIndexMeta()
    }

    /** 把 tag 索引与错误行偏移折算成界面用的 tag 列表和错误计数。 */
    private suspend fun updateIndexMeta() {
        val tags = synchronized(tagIndex) {
            tagIndex.values.filter { it.isNotEmpty() }.distinct().sorted()
        }
        val errorCount = synchronized(errorOffsets) { errorOffsets.size }
        _uiState.update { it.copy(availableTags = tags, errorCount = errorCount) }
    }

    private suspend fun refreshList() {
        val query = _uiState.value.searchQuery.trim()
        val selectedTag = _uiState.value.selectedTag
        val errorOnly = _uiState.value.showErrorOnly

        val resultOffsets = withContext(Dispatchers.IO) {
            // 「仅看错误」用建索引时顺手记下的错误行偏移，不再逐行回读文件
            val errorSnapshot =
                if (errorOnly) synchronized(errorOffsets) { HashSet(errorOffsets) } else null
            synchronized(allLineOffsets) {
                if (query.isEmpty() && selectedTag == null && !errorOnly) {
                    ArrayList(allLineOffsets)
                } else {
                    allLineOffsets.filter { offset ->
                        ensureActive()
                        // ✨ 先用 tag 索引缩圈（O(1)），避免每行回读文件
                        if (selectedTag != null && tagIndex[offset] != selectedTag) {
                            return@filter false
                        }
                        if (errorSnapshot != null && offset !in errorSnapshot) {
                            return@filter false
                        }
                        if (query.isEmpty()) {
                            return@filter true
                        }
                        val line = readLineAt(offset)
                        line?.contains(query, ignoreCase = true) ?: false
                    }
                }
            }
        }

        displayLineOffsets = resultOffsets

        _uiState.update {
            it.copy(
                hasContent = resultOffsets.isNotEmpty(),
                totalCount = resultOffsets.size,
                isLoading = false,
                isSearching = false
            )
        }

        if (_uiState.value.autoScroll && resultOffsets.isNotEmpty()) {
            _scrollEvent.send(resultOffsets.size - 1)
        }
    }

    fun getLineContent(position: Int): String {
        if (position !in displayLineOffsets.indices) return ""
        val offset = displayLineOffsets[position]

        val cachedLine = lineCache.get(offset)
        if (cachedLine != null) {
            return cachedLine
        }

        val line = readLineAt(offset) ?: " [读取错误]"
        lineCache.put(offset, line)
        return line
    }

    private fun readLineAt(offset: Long): String? {
        val localRaf = raf ?: return null

        return try {
            synchronized(localRaf) {
                localRaf.seek(offset)
                val lineBytes = localRaf.readLine()?.toByteArray(StandardCharsets.ISO_8859_1)
                lineBytes?.let { bytes -> String(bytes, StandardCharsets.UTF_8) }
            }
        } catch (e: Exception) {
            Log.printStackTrace(tag, "readLineAt failed at offset $offset", e)
            null
        }
    }

    private fun startFileObserver(path: String) {
        val file = File(path)
        val parentPath = file.parent ?: return
        fileObserver?.stopWatching()
        val eventMask = FileObserver.MODIFY or FileObserver.CREATE
        val observerFile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) file else File(parentPath)

        val onFileEvent: (String?) -> Unit = { p ->
            val eventFileName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) null else p
            if (eventFileName == null || eventFileName == file.name) {
                // ✅ 触发防抖更新
                triggerDebouncedUpdate()
            }
        }

        fileObserver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(observerFile, eventMask) {
                override fun onEvent(event: Int, p: String?) { onFileEvent(p) }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(observerFile.absolutePath, eventMask) {
                override fun onEvent(event: Int, p: String?) { onFileEvent(p) }
            }
        }
        fileObserver?.startWatching()
    }

    private fun triggerDebouncedUpdate() {
        fileUpdateChannel.trySend(Unit)
    }

    private suspend fun handleFileUpdate() = withContext(Dispatchers.IO) {
        val path = currentFilePath ?: return@withContext
        val file = File(path)
        if (!file.exists()) return@withContext

        // ✅ 使用互斥锁确保同一时刻只有一个更新在执行
        try {
            updateMutex.withLock {
                ensureActive() // 在获取锁后立即检查协程状态
                
                val currentSize = file.length()
                val lastSize = lastKnownFileSize.get()

                when {
                    currentSize > lastSize -> appendNewLines(currentSize)
                    currentSize < lastSize -> {
                        withContext(Dispatchers.Main) { loadLogs(path) }
                    }
                }
            }
        } catch (e: CancellationException) {
            // ✅ 协程取消异常不记录日志，直接静默处理
            // 这是正常的协程生命周期管理，不需要打印错误
            throw e // 重新抛出让协程框架处理
        } catch (e: Exception) {
            // ✅ 只记录真正的异常
            Log.printStackTrace(tag, "handleFileUpdate failed", e)
        }
    }

    private suspend fun appendNewLines(currentFileSize: Long) = withContext(Dispatchers.IO) {
        val localRaf = raf ?: return@withContext
        try {
            val startPosition = lastKnownFileSize.get()

            // ✅ 再次验证,防止并发问题
            if (currentFileSize <= startPosition) {
                return@withContext
            }

            // 一遍扫描新增字节：偏移、tag、错误行一起算出来，不再逐行回读文件
            val builder = LogIndexBuilder(startPosition)
            synchronized(localRaf) {
                localRaf.seek(startPosition)
                val readBuffer = ByteArray(SCAN_BUFFER_BYTES)
                var currentOffset = startPosition
                while (currentOffset < currentFileSize) {
                    ensureActive()
                    val remainingBytes = (currentFileSize - currentOffset).toInt()
                    val bytesToRead = minOf(readBuffer.size, remainingBytes)
                    if (bytesToRead <= 0) break
                    val bytesRead = localRaf.read(readBuffer, 0, bytesToRead)
                    if (bytesRead <= 0) break
                    builder.feed(readBuffer, 0, bytesRead)
                    currentOffset += bytesRead
                }
            }
            // 刻意不调 builder.finish()：末尾可能是还没写完的半行，
            // 留到下次追加时再记账，避免同一行的起始偏移被记两次

            val addedOffsets = builder.offsets()
            lastKnownFileSize.set(currentFileSize)
            if (addedOffsets.isEmpty()) return@withContext

            synchronized(allLineOffsets) {
                allLineOffsets.addAll(addedOffsets)
                // 超上限的旧行连索引一起丢，避免索引无限膨胀
                while (allLineOffsets.size > maxLines) {
                    val dropped = allLineOffsets.removeAt(0)
                    synchronized(tagIndex) { tagIndex.remove(dropped) }
                    synchronized(errorOffsets) { errorOffsets.remove(dropped) }
                }
            }
            synchronized(tagIndex) { tagIndex.putAll(builder.tags()) }
            synchronized(errorOffsets) { errorOffsets.addAll(builder.errorOffsets()) }
            updateIndexMeta()
            refreshList()
        } catch (e: CancellationException) {
            // ✅ 协程取消异常不记录日志，直接静默处理
            // 这是正常的协程生命周期管理，不需要打印错误
            throw e // 重新抛出让协程框架处理
        } catch (e: Exception) {
            Log.printStackTrace(tag, "appendNewLines failed", e)
        }
    }

    /** ✨ 选择/取消 tag 过滤（null = 清除过滤） */
    fun filterByTag(tag: String?) {
        if (_uiState.value.selectedTag == tag) return
        _uiState.update { it.copy(selectedTag = tag, isSearching = true) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            refreshList()
        }
    }

    /** ✨ 「仅看错误」开关 */
    fun toggleErrorOnly(enabled: Boolean) {
        if (_uiState.value.showErrorOnly == enabled) return
        _uiState.update { it.copy(showErrorOnly = enabled, isSearching = true) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            refreshList()
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        _uiState.update { it.copy(searchQuery = query, isSearching = true) }
        searchJob = viewModelScope.launch {
            if (query.isNotEmpty()) {
                delay(300)
            }
            refreshList()
        }
    }

    fun clearLogFile(context: Context) {
        val path = currentFilePath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (Files.clearFile(File(path))) {
                    withContext(Dispatchers.Main) {
                        ToastUtil.showToast(context, "文件已清空")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        ToastUtil.showToast(context, "清空失败")
                    }
                }
            } catch (e: Exception) {
                Log.printStackTrace(tag, "Clear error", e)
                withContext(Dispatchers.Main) {
                    ToastUtil.showToast(context, "清空异常: ${e.message}")
                }
            }
        }
    }

    fun exportLogFile(context: Context) {
        val path = currentFilePath ?: return
        try {
            val file = File(path)
            if (!file.exists()) {
                ToastUtil.showToast(context, "源文件不存在")
                return
            }
            val exportFile = Files.exportFile(file, true)
            if (exportFile != null && exportFile.exists()) {
                val msg = "${context.getString(R.string.file_exported)} ${exportFile.path}"
                ToastUtil.showToast(context, msg)
            } else {
                ToastUtil.showToast(context, "导出失败")
            }
        } catch (e: Exception) {
            Log.printStackTrace(tag, "Export error", e)
            ToastUtil.showToast(context, "导出异常: ${e.message}")
        }
    }

    private fun saveFontSize(size: Float) {
        prefs.edit { putFloat(logFontSizeKey, size) }
    }

    fun increaseFontSize() {
        _fontSize.update { current ->
            val newValue = (current + 2f).coerceAtMost(30f)
            saveFontSize(newValue)
            newValue
        }
    }

    fun decreaseFontSize() {
        _fontSize.update { current ->
            val newValue = (current - 2f).coerceAtLeast(8f)
            saveFontSize(newValue)
            newValue
        }
    }

    fun scaleFontSize(factor: Float) {
        _fontSize.update { current ->
            val newValue = (current * factor).coerceIn(8f, 50f)
            saveFontSize(newValue)
            newValue
        }
    }

    fun resetFontSize() {
        _fontSize.value = 12f
        saveFontSize(12f)
    }

    fun toggleAutoScroll(enabled: Boolean) {
        if (_uiState.value.autoScroll == enabled) return
        _uiState.update { it.copy(autoScroll = enabled) }
        if (enabled) viewModelScope.launch {
            val size = _uiState.value.totalCount
            if (size > 0) _scrollEvent.send(size - 1)
        }
    }

    private fun closeFile() {
        try {
            // updateJob?.cancel()
            raf?.close()
            raf = null
            fileObserver?.stopWatching()
            fileObserver = null
        } catch (e: Exception) {
            Log.printStackTrace(tag, "closeFile failed", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeFile()
        loadJob?.cancel()
        searchJob?.cancel()
        updateJob?.cancel()
    }
}