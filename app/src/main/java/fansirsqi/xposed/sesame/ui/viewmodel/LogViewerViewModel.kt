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
import fansirsqi.xposed.sesame.data.General
import fansirsqi.xposed.sesame.util.LogCatalog
import fansirsqi.xposed.sesame.util.LogDayIndexer
import fansirsqi.xposed.sesame.util.LogFileHistory
import fansirsqi.xposed.sesame.util.LogIndexBuilder
import fansirsqi.xposed.sesame.util.LogSource
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale
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
    val errorCount: Int = 0,
    // ✨ 日期维度（历史日志）
    /** 当前日志的 logName，如 error */
    val logName: String = "",
    /** logName 的中文名，用于标题 */
    val logLabel: String = "",
    /** 当前查看的日期（yyyy-MM-dd） */
    val currentDate: String = "",
    /** 今天（yyyy-MM-dd），界面据此判断能否继续往后翻 */
    val todayKey: String = "",
    /** 有数据的日期，倒序（今天在最前） */
    val availableDates: List<String> = emptyList(),
    /** 是否在看历史（非今天）：历史只读，且不起文件监听 */
    val isHistory: Boolean = false,
    /** 当天的分片数（>1 时界面会标注） */
    val shardCount: Int = 0,
    /** 有更旧的内容被丢弃（超过 maxLines，或为省时间没读更旧的分片） */
    val truncated: Boolean = false,
    /** 该日期没有可读日志（例如历史已被清理） */
    val noData: Boolean = false,
    /** 导出前需要确认（当天分片多/体积大时先问一次） */
    val exportConfirm: Boolean = false,
    /** 确认弹窗里的说明文案 */
    val exportSummary: String = ""
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

    /** 当前查看的日期键（"logName@yyyy-MM-dd"），用于幂等守卫 */
    private var currentKey: String? = null

    /** 仅当查看「今天」时指向活动文件（供清空 / 监听 / 追加）；历史态为 null */
    private var currentFilePath: String? = null
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var updateJob: Job? = null // ✅ 新增:文件更新任务

    // --- 核心数据结构 ---
    /** 当前这一天对应的分片集合（今天 = 当日分片 + 活动文件；历史 = bak 分片） */
    private var source: LogSource? = null
    private var partitions: List<LogFileHistory.Partition> = emptyList()
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

        /** 导出前需要确认的分片数阈值 */
        private const val EXPORT_CONFIRM_SHARDS = 20

        /** 导出前需要确认的总体积阈值（50MB） */
        private const val EXPORT_CONFIRM_BYTES = 50L * 1024 * 1024
    }

    // ✅ 使用 AtomicLong 保证线程安全
    private val lastKnownFileSize = AtomicLong(0L)
    private val maxLines = 200_000

    // ✅ 用于防抖的互斥锁
    private val updateMutex = Mutex()

    /**
     * 打开某分类的**当天**日志（旧入口：调用方只给一个文件路径）。
     *
     * 历史日期与前后翻页走 [openDay]；这里把路径反解成 logName，语义等价于「打开今天的该日志」。
     * 一天通常是多个分片（实测 `error-2026-09-24` 有 62 个），所以内部不按单文件处理。
     */
    fun loadLogs(path: String) {
        val logName = File(path).nameWithoutExtension
        if (logName.isEmpty()) return
        // Activity 重建（旋转 / 恢复）会再调一次 loadLogs：同一个分类时保持当前日期，
        // 别把正在看历史的用户拉回今天；openDay 内部有幂等守卫，重复调用不会重扫。
        val opened = _uiState.value
        val date = if (opened.logName == logName && opened.currentDate.isNotEmpty()) {
            opened.currentDate
        } else {
            LogFileHistory.dayKey()
        }
        openDay(logName, date)
    }

    /** 打开某个 logName 的某一天 */
    fun openDay(logName: String, date: String) {
        val key = "$logName@$date"
        if (currentKey == key && loadJob?.isActive == true) return
        currentKey = key

        loadJob?.cancel()
        updateJob?.cancel()
        closeFile()

        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true, hasContent = false, totalCount = 0, noData = false,
                    logName = logName, logLabel = LogCatalog.labelOf(logName),
                    currentDate = date, todayKey = LogFileHistory.dayKey(),
                    isHistory = !LogFileHistory.isToday(date),
                    exportConfirm = false
                )
            }

            val logDir = LogFileHistory.resolveReadableLogDir(candidateLogDirs(), logName)
            if (logDir == null) {
                _uiState.update { it.copy(isLoading = false, noData = true) }
                return@launch
            }

            val dates = LogFileHistory.availableDates(logDir, logName)
            val parts = LogFileHistory.partitionsOf(logDir, logName, date)
                .filter { it.file.exists() && it.file.canRead() }
            if (parts.isEmpty()) {
                // 历史可能刚被 Logback 的 isCleanHistoryOnStart 清掉
                _uiState.update {
                    it.copy(isLoading = false, noData = true, availableDates = dates)
                }
                return@launch
            }

            source = LogSource(parts)
            partitions = parts
            val isToday = LogFileHistory.isToday(date)
            currentFilePath = if (isToday) parts.last().file.absolutePath else null
            _uiState.update {
                it.copy(availableDates = dates, shardCount = parts.size, isHistory = !isToday)
            }

            indexContent(parts)

            // 只有今天需要跟随写入：历史是静态归档
            if (isToday) {
                startUpdateJob()
                currentFilePath?.let { startFileObserver(it) }
            }
        }
    }

    /** 切到某一天（日期下拉用） */
    fun selectDate(date: String) {
        val logName = _uiState.value.logName.ifEmpty { return }
        openDay(logName, date)
    }

    /** 往前一天（更旧）；已是最旧则不动 */
    fun goToOlderDay() {
        val state = _uiState.value
        val idx = state.availableDates.indexOf(state.currentDate)
        if (idx < 0 || idx >= state.availableDates.lastIndex) return
        openDay(state.logName, state.availableDates[idx + 1])
    }

    /** 往后一天（更新）；已是今天则不动 */
    fun goToNewerDay() {
        val state = _uiState.value
        val idx = state.availableDates.indexOf(state.currentDate)
        if (idx <= 0) return
        openDay(state.logName, state.availableDates[idx - 1])
    }

    /** 日志目录候选：与 Logback 的写入口径一致（media 目录不可写时会回退到应用私有目录） */
    private fun candidateLogDirs(): List<File> {
        val ctx = getApplication<Application>()
        return listOfNotNull(
            Files.LOG_DIR,
            File("/sdcard/Android/data/${General.PACKAGE_NAME}/files/logs"),
            ctx.getExternalFilesDir("logs"),
            File(ctx.filesDir, "logs")
        ).distinct()
    }

    @OptIn(FlowPreview::class)
    private fun startUpdateJob() {
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            fileUpdateChannel.receiveAsFlow()
                .debounce(200)
                .collectLatest {
                    handleFileUpdate()
                }
        }
    }



    /**
     * 索引「一整天」：[parts] 是该日期的分片（升序，今天的话最后一个分片是活动文件）。
     *
     * ① 先读最后一个分片的尾部让界面立刻出内容；
     * ② 再全量索引 —— [LogDayIndexer.scan] 会从最新分片往前扫，凑够 [maxLines] 即停。
     */
    private suspend fun indexContent(parts: List<LogFileHistory.Partition>) = withContext(Dispatchers.IO) {
        try {
            lastKnownFileSize.set(parts.lastOrNull()?.file?.length() ?: 0L)
            if (parts.all { it.file.length() == 0L }) {
                applyIndex(emptyList(), emptyMap(), emptySet())
                lineCache.evictAll()
                refreshList()
                return@withContext
            }

            // ① 尾部优先：打开即出内容，不必等整份（可能几十个分片）索引完
            runCatching { LogDayIndexer.readTail(parts, TAIL_BYTES) }.getOrNull()?.let { tail ->
                if (tail.offsets.isNotEmpty()) {
                    applyIndex(tail.offsets, tail.tags, tail.errorOffsets)
                    _uiState.update { it.copy(truncated = false) }
                    refreshList()
                }
            }

            // ② 全量索引
            val scope = this
            val result = LogDayIndexer.scan(parts, SCAN_BUFFER_BYTES, maxLines) { scope.isActive }
            ensureActive()
            applyIndex(result.offsets, result.tags, result.errorOffsets)
            _uiState.update { it.copy(truncated = result.truncated) }
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
            android.util.Log.e("LogViewerVM", "索引日志失败: ${parts.firstOrNull()?.file?.name}", e)
            val errorMsg = "索引失败: ${e.message}"
            Log.error(tag, errorMsg)
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isLoading = false) }
                ToastUtil.showToast(getApplication(), errorMsg)
            }
        }
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

    /** 读一行；[offset] 是打包偏移（分片序号 + 文件内偏移），由 [LogSource] 解包读取 */
    private fun readLineAt(offset: Long): String? = source?.readLineAt(offset)

    /**
     * 监听活动文件的新增。
     *
     * ⚠️ 刻意**观察父目录**并按文件名过滤，而不是只观察文件本身：
     * Android 10+ 按文件观察盯的是 inode，日志一旦滚动（活动文件被 rename 成 `bak/<name>-<日期>.i.log`）
     * 就再也收不到事件 —— 表现为「滚动之后看不到新内容」。观察目录还能顺带感知重建的日志文件。
     */
    private fun startFileObserver(path: String) {
        val file = File(path)
        val parent = file.parent ?: return
        fileObserver?.stopWatching()
        val eventMask = FileObserver.MODIFY or FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.DELETE

        val onEvent: (String?) -> Unit = { name ->
            if (name == null || name == file.name) triggerDebouncedUpdate()
        }

        fileObserver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(File(parent), eventMask) {
                override fun onEvent(event: Int, p: String?) = onEvent(p)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(parent, eventMask) {
                override fun onEvent(event: Int, p: String?) = onEvent(p)
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
                        // 活动文件被清空或**滚动**（rename 成当天分片、并新建同名文件）：
                        // 重新枚举当天分区，才能把新分片接进来
                        withContext(Dispatchers.Main) {
                            val state = _uiState.value
                            if (state.logName.isNotEmpty() && state.currentDate.isNotEmpty()) {
                                currentKey = null   // 清掉幂等守卫，强制重新加载
                                openDay(state.logName, state.currentDate)
                            }
                        }
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
        // 只有「今天」会走这里（历史是静态归档），活动文件是最后一个分区
        val activePath = currentFilePath ?: return@withContext
        val baseSeq = partitions.lastIndex
        if (baseSeq < 0) return@withContext
        try {
            val startPosition = lastKnownFileSize.get()

            // ✅ 再次验证,防止并发问题
            if (currentFileSize <= startPosition) {
                return@withContext
            }

            // 一遍扫描新增字节：偏移、tag、错误行一起算出来，不再逐行回读文件。
            // 偏移要带上活动文件的基址（打包偏移 = 分片序号 + 文件内偏移），与索引阶段一致。
            val builder = LogIndexBuilder(LogSource.baseOf(baseSeq) + startPosition)
            RandomAccessFile(File(activePath), "r").use { appendRaf ->
                appendRaf.seek(startPosition)
                val readBuffer = ByteArray(SCAN_BUFFER_BYTES)
                var currentOffset = startPosition
                while (currentOffset < currentFileSize) {
                    ensureActive()
                    val remainingBytes = (currentFileSize - currentOffset).toInt()
                    val bytesToRead = minOf(readBuffer.size, remainingBytes)
                    if (bytesToRead <= 0) break
                    val bytesRead = appendRaf.read(readBuffer, 0, bytesToRead)
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
        // 历史分片是滚动归档：清掉它等于毁掉排查依据，界面也不会给出这个入口
        if (_uiState.value.isHistory) return
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

    /**
     * 导出当前这一天。
     *
     * 分片多或体积大时先返回一次确认（写进 uiState，由界面弹框），避免几十个分片、
     * 数百 MB 的合并悄悄开始又失败。今天单分片时与旧的「导出文件」等价。
     */
    fun requestExport(context: Context) {
        val parts = partitions
        if (parts.isEmpty()) {
            ToastUtil.showToast(context, "没有可导出的日志")
            return
        }
        val totalBytes = parts.sumOf { it.file.length() }
        if (parts.size > EXPORT_CONFIRM_SHARDS || totalBytes > EXPORT_CONFIRM_BYTES) {
            _uiState.update {
                it.copy(
                    exportConfirm = true,
                    exportSummary = "${parts.size} 个分片，共 ${formatSize(totalBytes)}"
                )
            }
            return
        }
        exportLogFile(context)
    }

    /** 用户在确认框里点了「导出」 */
    fun confirmExport(context: Context) {
        _uiState.update { it.copy(exportConfirm = false, exportSummary = "") }
        exportLogFile(context)
    }

    /** 取消导出 */
    fun dismissExport() {
        _uiState.update { it.copy(exportConfirm = false, exportSummary = "") }
    }

    fun exportLogFile(context: Context) {
        val parts = partitions
        if (parts.isEmpty()) {
            ToastUtil.showToast(context, "没有可导出的日志")
            return
        }
        try {
            val state = _uiState.value
            // 历史导出带上日期，避免不同天的同名文件互相覆盖
            val baseName = if (state.isHistory && state.currentDate.isNotEmpty()) {
                "${state.logName}-${state.currentDate}"
            } else {
                parts.last().file.nameWithoutExtension
            }
            val exportFile = Files.exportMerged(parts.map { it.file }, baseName, true)
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

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format(Locale.US, "%.0fKB", bytes / 1024.0)
        else -> "${bytes}B"
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
            source?.close()
            source = null
            partitions = emptyList()
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