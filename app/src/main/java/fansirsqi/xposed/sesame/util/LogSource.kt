package fansirsqi.xposed.sesame.util

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * 「一天 = 多个分片」的随机读取层。
 *
 * 查看器原本是「单文件 + `offset: Long`」模型，而一天可能有几十个分片
 * （实测 `error-2026-09-24` 有 62 个），所以这里把
 * `(分片序号, 文件内偏移)` **打包成一个 Long**，让上层的数据结构（偏移表、行缓存、
 * tag 索引、错误行集合）全部保持 `<Long>` 不变：
 *
 * ```
 * packed = (seq shl 40) | fileOffset
 * ```
 *
 * 打包值天然按 (序号, 偏移) 全局升序 —— 这对「超上限时丢最旧的行」尤其重要：
 * `takeLast(maxLines)` 自动等价于「从最旧的分片开始丢」。
 *
 * 句柄管理：62 个分片不可能全开，用访问序 LRU 按需打开、超额即关。
 * 顺序扫描**不要**用这里的句柄（会与逐行读取争用文件指针），见 [LogDayIndexer]。
 */
class LogSource(
    private val partitions: List<LogFileHistory.Partition>,
    private val maxOpen: Int = DEFAULT_MAX_OPEN
) : Closeable {

    companion object {
        /** 高 23 位存分片序号，低 40 位存文件内偏移（单文件最大 7MB，远小于 1TB） */
        const val SHARD_SHIFT = 40
        const val OFF_MASK = (1L shl SHARD_SHIFT) - 1

        /** 同时打开的分片句柄上限 */
        const val DEFAULT_MAX_OPEN = 4

        @JvmStatic
        fun baseOf(seq: Int): Long = seq.toLong() shl SHARD_SHIFT

        @JvmStatic
        fun seqOf(packed: Long): Int = (packed ushr SHARD_SHIFT).toInt()

        @JvmStatic
        fun offOf(packed: Long): Long = packed and OFF_MASK
    }

    /**
     * 访问序 LRU（`accessOrder = true`）。刻意不用 `android.util.LruCache`：
     * 那样这个类就没法在 JVM 单测里跑了。
     */
    private val handles = object : LinkedHashMap<Int, RandomAccessFile>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, RandomAccessFile>): Boolean {
            if (size > maxOpen) {
                runCatching { eldest.value.close() }
                return true
            }
            return false
        }
    }

    val size: Int get() = partitions.size

    fun files(): List<File> = partitions.map { it.file }

    fun fileAt(seq: Int): File? = partitions.getOrNull(seq)?.file

    /** 打包偏移的基址（第 [seq] 个分片的起点） */
    fun base(seq: Int): Long = baseOf(seq)

    /** 读一行；[packed] 来自索引阶段产出的偏移。失败一律返回 null，不抛。 */
    @Synchronized
    fun readLineAt(packed: Long): String? {
        val seq = seqOf(packed)
        val file = partitions.getOrNull(seq)?.file ?: return null
        return runCatching {
            val raf = handles[seq] ?: open(file, seq) ?: return null
            raf.seek(offOf(packed))
            val bytes = raf.readLine()?.toByteArray(StandardCharsets.ISO_8859_1) ?: return null
            String(bytes, StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun open(file: File, seq: Int): RandomAccessFile? = runCatching {
        RandomAccessFile(file, "r").also { handles[seq] = it }
    }.getOrNull()

    override fun close() {
        runCatching { handles.values.forEach { it.close() } }
        handles.clear()
    }
}
