package fansirsqi.xposed.sesame.util

import java.nio.charset.StandardCharsets

/**
 * 日志文件的一遍式索引器：喂入字节流，产出「行起始偏移 + 该行的 [tag] + 错误行偏移」。
 *
 * 为什么需要它（2026-09-28）：日志保留放宽到单文件 3MB/7MB 之后，日志查看页打开大文件
 * 会一直转圈。旧实现扫完行偏移后，又对**每一行**做一次 `RandomAccessFile.seek + readLine`
 * 建 tag 索引，再逐行读一遍数错误行 —— 20 万行就是 40 万次随机读，在 /sdcard（FUSE）上
 * 要几十秒；而且日志在查看时还在写，每次追加都会把这些工作重做一遍。
 *
 * 现在改成流式一遍过、零随机读。纯逻辑、无 Android 依赖，可直接在 JVM 单测里跑。
 *
 * 行偏移语义与旧实现保持一致：**每个换行符之后**记一行；文件不以换行结尾时，
 * 最后一行要调 [finish] 才会被记（增量追加时故意不调，避免半行被重复记账）。
 */
class LogIndexBuilder(private val startOffset: Long = 0L) {

    private val offsets = ArrayList<Long>()
    private val tags = HashMap<Long, String>()
    private val errorOffsets = HashSet<Long>()

    /** 复用同一个字节数组，避免每行都新建 ByteArray */
    private val lineBytes = ByteArray(MAX_LINE_BYTES)
    private var lineSize = 0

    /** 当前行已消费的字节数（含被截断丢弃的部分），用于计算下一行的起始偏移 */
    private var consumed = 0L
    private var lineStart = startOffset
    private var truncated = false

    /** 记下来的行起始偏移（升序） */
    fun offsets(): List<Long> = offsets

    /** 行起始偏移 → 该行的 [tag]；取不到时为 "" */
    fun tags(): Map<Long, String> = tags

    /** 含错误标记的行起始偏移 */
    fun errorOffsets(): Set<Long> = errorOffsets

    /** 喂入一段字节。[offset]/[length] 允许只喂缓冲区的一部分（例如尾部加载时跳过半行）。 */
    fun feed(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
        val end = offset + length
        var i = offset
        while (i < end) {
            val b = buffer[i]
            if (b == NEWLINE) {
                endLine()
            } else {
                if (lineSize < lineBytes.size) {
                    lineBytes[lineSize++] = b
                } else {
                    truncated = true
                }
                consumed++
            }
            i++
        }
    }

    /** 处理文件末尾没有换行的那一行。增量追加时不要调用。 */
    fun finish() {
        if (consumed > 0L) endLine()
    }

    private fun endLine() {
        val offset = lineStart
        val text = String(lineBytes, 0, lineSize, StandardCharsets.UTF_8)
        offsets += offset
        tags[offset] = tagOf(text)
        if (isError(text)) errorOffsets += offset

        // 下一行的起点：本行字节数 + 换行符本身。被截断时按实际消费量算，保证后续行偏移不漂
        lineStart += consumed + 1L
        consumed = 0L
        lineSize = 0
        truncated = false
    }

    /**
     * 取行首 tag：只认「时间戳 + 可选空格 + [xxx]」这一种形态。
     *
     * 与旧实现的正则 `^\d{2}日\s\d{2}:\d{2}:\d{2}\.\d{2,3}\s*\[([^\]]+)]` 等价，
     * 但手写判定比逐行跑正则便宜得多（20 万行时差一个量级）。
     * 刻意不用「行首附近第一个方括号」的宽松规则 —— 那会把正文里的 `[json]` 当成 tag。
     */
    private fun tagOf(text: String): String {
        val length = text.length
        if (length < 16) return ""
        if (!text[0].isDigit() || !text[1].isDigit() || text[2] != '日') return ""
        if (text[3] != ' ') return ""
        // HH:MM:SS —— 下标 2、5 是冒号，其余必须是数字
        for (k in 0 until 8) {
            val c = text[4 + k]
            if (k == 2 || k == 5) {
                if (c != ':') return ""
            } else if (!c.isDigit()) {
                return ""
            }
        }
        var i = 12
        if (text[i] != '.') return ""
        i++
        var digits = 0
        while (i < length && digits < 3 && text[i].isDigit()) {
            i++
            digits++
        }
        if (digits < 2) return ""
        while (i < length && text[i] == ' ') i++
        if (i >= length || text[i] != '[') return ""
        val close = text.indexOf(']', i + 1)
        if (close < 0) return ""
        return text.substring(i + 1, close)
    }

    private fun isError(text: String): Boolean {
        if (text.isEmpty()) return false
        for (marker in ERROR_MARKERS) {
            if (text.contains(marker, ignoreCase = true)) return true
        }
        return false
    }

    companion object {
        /** 与旧实现保持一致：命中任一标记即算错误行 */
        val ERROR_MARKERS: List<String> = listOf("error", "exception", "❌", "⚠️", "失败", "异常")

        /** 单行最多保留的字节数：超长行（整段 JSON）不再进缓冲，只用于判 tag 与错误标记 */
        private const val MAX_LINE_BYTES = 8 * 1024

        private val NEWLINE: Byte = '\n'.code.toByte()
    }
}
