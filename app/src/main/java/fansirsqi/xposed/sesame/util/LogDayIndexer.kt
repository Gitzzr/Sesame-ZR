package fansirsqi.xposed.sesame.util

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

/**
 * 多分片（一整天）的索引：产出打包偏移 + tag + 错误行偏移。纯逻辑，可 JVM 单测。
 *
 * 与单文件版（[LogIndexBuilder]）相比有两点不同：
 * 1. 每个分片各自用 `LogIndexBuilder(base)` 扫描，偏移带分片基址；
 * 2. **从最新分片往前扫**，凑够 `maxLines` 就停 —— 反正更旧的行随后会被丢掉，
 *    没必要为一个几百 MB 的历史日把最旧的分片全读一遍（实测 `error-2026-09-24` 有 62 个分片）。
 */
object LogDayIndexer {

    /** 索引结果。[truncated] 表示有更旧的内容没有保留（UI 需要如实提示）。 */
    data class IndexResult(
        val offsets: List<Long>,
        val tags: Map<Long, String>,
        val errorOffsets: Set<Long>,
        val truncated: Boolean
    )

    private val EMPTY = IndexResult(emptyList(), emptyMap(), emptySet(), false)

    /**
     * 只读**最后一个分区**的尾部（当天即活动文件的尾部），让界面立刻有内容。
     *
     * 起点可能落在半行中间（那半行在更早的位置），所以先跳到缓冲区里第一个换行之后。
     */
    @JvmStatic
    fun readTail(partitions: List<LogFileHistory.Partition>, tailBytes: Int): IndexResult {
        val lastSeq = partitions.lastIndex
        val file = partitions.lastOrNull()?.file ?: return EMPTY
        val fileSize = file.length()
        if (fileSize <= 0L) return EMPTY

        val start = maxOf(0L, fileSize - tailBytes)
        val length = (fileSize - start).toInt()
        val buffer = ByteArray(length)
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                raf.readFully(buffer)
            }
        }.getOrElse { return EMPTY }

        var from = 0
        if (start > 0L) {
            val newline = buffer.indexOfFirst { it == '\n'.code.toByte() }
            if (newline < 0) return EMPTY
            from = newline + 1
        }
        val builder = LogIndexBuilder(baseOf(lastSeq) + start + from)
        builder.feed(buffer, from, length - from)
        builder.finish()
        return IndexResult(builder.offsets(), builder.tags(), builder.errorOffsets(), false)
    }

    /**
     * 全量索引：逐分区顺序读，**从最新往前**凑够 [maxLines] 即停。
     *
     * @param isActive 返回 false 时中止（协程取消）；中止后返回的是已扫到的部分，调用方应丢弃
     */
    @JvmStatic
    fun scan(
        partitions: List<LogFileHistory.Partition>,
        bufferBytes: Int,
        maxLines: Int,
        isActive: () -> Boolean = { true }
    ): IndexResult {
        if (partitions.isEmpty()) return EMPTY

        // 每个分片**只扫一遍**，同时拿到偏移、tag 与错误行偏移
        val offsetsByShard = HashMap<Int, List<Long>>()
        val tagsByShard = HashMap<Int, Map<Long, String>>()
        val errorsByShard = HashMap<Int, Set<Long>>()
        var obtained = 0
        for (seq in partitions.indices.reversed()) {
            if (!isActive()) break
            val builder = LogIndexBuilder(baseOf(seq))
            feedFile(partitions[seq].file, bufferBytes, isActive, builder)
            builder.finish()
            val offsets = builder.offsets()
            offsetsByShard[seq] = offsets
            tagsByShard[seq] = builder.tags()
            errorsByShard[seq] = builder.errorOffsets()
            obtained += offsets.size
            // 更旧的分片反正会被 takeLast 丢掉，直接停
            if (obtained >= maxLines) break
        }

        // 按分片升序拼回，得到全局升序的偏移表
        val ordered = ArrayList<Long>(obtained)
        val tags = HashMap<Long, String>()
        val errors = HashSet<Long>()
        for (seq in partitions.indices) {
            val offsets = offsetsByShard[seq] ?: continue
            ordered.addAll(offsets)
            tags.putAll(tagsByShard[seq].orEmpty())
            errors.addAll(errorsByShard[seq].orEmpty())
        }

        val truncated = offsetsByShard.size < partitions.size || obtained > maxLines
        val kept = if (obtained > maxLines) ordered.takeLast(maxLines) else ordered
        val keptSet = kept.toHashSet()
        return IndexResult(
            offsets = kept,
            tags = tags.filterKeys { it in keptSet },
            errorOffsets = errors.filterTo(HashSet()) { it in keptSet },
            truncated = truncated
        )
    }

    private fun feedFile(
        file: File,
        bufferBytes: Int,
        isActive: () -> Boolean,
        builder: LogIndexBuilder
    ) {
        val buffer = ByteArray(bufferBytes)
        runCatching {
            FileInputStream(file).use { stream ->
                while (true) {
                    if (!isActive()) break
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    builder.feed(buffer, 0, read)
                }
            }
        }
    }

    private fun baseOf(seq: Int): Long = LogSource.baseOf(seq)
}
