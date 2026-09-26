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
import fansirsqi.xposed.sesame.util.ToastUtil
import kotlinx.coroutines.CancellationException
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
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong


/**
 * 日志 UI 状态
 */
data class LogUiState(
    val mappingList: List<Int> = emptyList(),
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

    // ✨ tag 索引：行偏移 -> 模块 tag（首次扫描时提取 [tag] 前缀）
    private val tagIndex = HashMap<Long, String>()

    companion object {
        // 日志行内 [tag] 前缀提取正则：匹配行首时间戳后的首个 [xxx] 段
        private val TAG_PATTERN = Regex("""^\d{2}日\s\d{2}:\d{2}:\d{2}\.\d{2,3}\s*\[([^\]]+)]""")
        private val ERROR_MARKERS = listOf("error", "exception", "❌", "⚠️", "失败", "异常")
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
            _uiState.update { it.copy(isLoading = true, mappingList = emptyList(), totalCount = 0) }

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
            // ✅ 如果文件大小为 0，直接清空并返回
            if (fileSize == 0L) {
                synchronized(allLineOffsets) { allLineOffsets.clear() }
                lineCache.evictAll()
                refreshList()
                return@withContext
            }

            // ✅ 优化:一次扫描同时完成计数和索引
            val readBuffer = ByteArray(8192)
            var currentOffset = 0L
            var totalLines = 0L

            localRaf.seek(0)

            // 第一遍:快速扫描,只记录换行符位置
            val allOffsets = mutableListOf<Long>()
            allOffsets.add(0L) // 第一行从 0 开始

            while (true) {
                ensureActive()
                val bytesRead = localRaf.read(readBuffer)
                if (bytesRead == -1) break

                for (i in 0 until bytesRead) {
                    currentOffset++
                    if (readBuffer[i] == '\n'.code.toByte()) {
                        totalLines++
                        // 记录下一行的起始位置
                        if (currentOffset < fileSize) {
                            allOffsets.add(currentOffset)
                        }
                    }
                }
            }

            // ✅ 根据总行数决定保留哪些行
            val finalOffsets = if (totalLines > maxLines) {
                // 只保留最后 maxLines 行
                allOffsets.takeLast(maxLines)
            } else {
                allOffsets
            }

            synchronized(allLineOffsets) {
                allLineOffsets.clear()
                allLineOffsets.addAll(finalOffsets)
            }

            // ✨ 构建 tag 索引 + 错误计数
            rebuildTagIndex(finalOffsets)

            lineCache.evictAll()
            refreshList()

        } catch (e: CancellationException) {
            // ✅ 协程取消异常不记录日志，直接静默处理
            // 这是正常的协程生命周期管理，不需要打印错误
            throw e // 重新抛出让协程框架处理
        } catch (e: Exception) {
            e.printStackTrace()
            val errorMsg = "索引失败: ${e.message}"
            Log.error(tag, errorMsg)
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isLoading = false) }
                ToastUtil.showToast(getApplication(), errorMsg)
            }
        }
    }

    private suspend fun refreshList() {
        val query = _uiState.value.searchQuery.trim()
        val selectedTag = _uiState.value.selectedTag
        val errorOnly = _uiState.value.showErrorOnly

        val resultOffsets = withContext(Dispatchers.IO) {
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
                        if (errorOnly) {
                            val cached = lineCache.get(offset)
                            val line = cached ?: readLineAt(offset)
                            if (line != null && cached == null) lineCache.put(offset, line)
                            val isError = line?.let { l ->
                                ERROR_MARKERS.any { l.contains(it, ignoreCase = true) }
                            } ?: false
                            if (!isError) return@filter false
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
        val newMapping = List(resultOffsets.size) { it }

        _uiState.update {
            it.copy(
                mappingList = newMapping,
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

            val newOffsets = mutableListOf<Long>()
            val readBuffer = ByteArray(8192) // 8KB 缓冲区

            synchronized(localRaf) {
                localRaf.seek(startPosition)
                var currentOffset = startPosition
                // ✅ 读取新增的内容，找到所有换行符位置
                while (currentOffset < currentFileSize) {
                    ensureActive()
                    val remainingBytes = (currentFileSize - currentOffset).toInt()
                    val bytesToRead = minOf(readBuffer.size, remainingBytes)
                    if (bytesToRead <= 0) break
                    val bytesRead = localRaf.read(readBuffer, 0, bytesToRead)
                    if (bytesRead == -1) break
                    // ✅ 遍历读取的字节，找到换行符
                    for (i in 0 until bytesRead) {
                        currentOffset++
                        if (readBuffer[i] == '\n'.code.toByte()) {
                            // 记录下一行的起始位置
                            if (currentOffset < currentFileSize) {
                                newOffsets.add(currentOffset)
                            }
                        }
                    }
                }
            }
            
            if (newOffsets.isNotEmpty()) {
                // ✅ 先更新文件大小,再修改列表
                lastKnownFileSize.set(currentFileSize)
                synchronized(allLineOffsets) {
                    allLineOffsets.addAll(newOffsets)
                    while (allLineOffsets.size > maxLines) {
                        allLineOffsets.removeAt(0)
                    }
                }
                rebuildTagIndex(allLineOffsets.toList())
                refreshList()
            }
        } catch (e: CancellationException) {
            // ✅ 协程取消异常不记录日志，直接静默处理
            // 这是正常的协程生命周期管理，不需要打印错误
            throw e // 重新抛出让协程框架处理
        } catch (e: Exception) {
            Log.printStackTrace(tag, "appendNewLines failed", e)
        }
    }

    /**
     * ✨ 扫描行偏移列表，提取每行 [tag] 前缀建立分组索引，并统计错误行数。
     * 首次全量加载 O(n) 回读；增量追加时只读新增行。
     */
    private suspend fun rebuildTagIndex(offsets: List<Long>) = withContext(Dispatchers.IO) {
        try {
            synchronized(tagIndex) {
                // 增量场景：只处理尚未索引的行
                val pending = offsets.filter { it !in tagIndex }
                for (offset in pending) {
                    val line = readLineAt(offset)
                    val tag = line?.let { TAG_PATTERN.find(it)?.groupValues?.get(1) }
                    tagIndex[offset] = tag ?: ""
                }
                // 裁剪：超出 maxLines 的旧行索引同步移除
                if (tagIndex.size > offsets.size) {
                    val valid = offsets.toHashSet()
                    tagIndex.keys.retainAll(valid)
                }
            }

            val tags = synchronized(tagIndex) {
                tagIndex.values.filter { it.isNotEmpty() }.distinct().sorted()
            }
            val errorCount = withContext(Dispatchers.IO) {
                synchronized(allLineOffsets) {
                    allLineOffsets.count { offset ->
                        ensureActive()
                        val line = readLineAt(offset) ?: return@count false
                        ERROR_MARKERS.any { line.contains(it, ignoreCase = true) }
                    }
                }
            }
            _uiState.update { it.copy(availableTags = tags, errorCount = errorCount) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.printStackTrace(tag, "rebuildTagIndex failed", e)
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
            val size = _uiState.value.mappingList.size
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