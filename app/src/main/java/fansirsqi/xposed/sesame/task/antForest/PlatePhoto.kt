package fansirsqi.xposed.sesame.task.antForest

/**
 * 一组已抓取到的光盘行动照片。
 *
 * 缓存里原先只存 `before` / `after` 两个图片 ID —— 那是上传接口要用的值，
 * 光有 ID 没法预览，用户也就无从确认「到底抓到没有」。
 * 2026-09-27 起同时保存完整 URL 与抓取时间，界面据此给出查看入口。
 * 旧记录只有 ID，URL 为空，界面按「旧记录」展示，不报错。
 */
data class PlatePhoto(
    /** 上传接口使用的餐前图片 ID */
    val beforeId: String = "",
    /** 上传接口使用的餐后图片 ID */
    val afterId: String = "",
    /** 餐前图片完整地址，可直接打开预览；旧记录为空 */
    val beforeUrl: String = "",
    /** 餐后图片完整地址；旧记录为空 */
    val afterUrl: String = "",
    /** 抓取时间戳（毫秒）；旧记录为 0 */
    val at: Long = 0
) {
    /** 是否存在可预览的地址。 */
    val hasPreview: Boolean get() = beforeUrl.isNotEmpty() || afterUrl.isNotEmpty()

    companion object {
        /**
         * 从 DataStore 里 `plate` 的原始条目解析。
         *
         * 缺字段、值类型不对都只当空处理 —— 缓存跨版本存在，不能因为一条坏数据让整页打不开。
         * 两个 ID 都取不到时返回 null，由调用方丢弃该条。
         */
        @JvmStatic
        fun fromRaw(raw: Map<*, *>?): PlatePhoto? {
            if (raw == null) return null
            val beforeId = raw["before"]?.toString().orEmpty()
            val afterId = raw["after"]?.toString().orEmpty()
            if (beforeId.isEmpty() && afterId.isEmpty()) return null
            return PlatePhoto(
                beforeId = beforeId,
                afterId = afterId,
                beforeUrl = raw["beforeUrl"]?.toString().orEmpty(),
                afterUrl = raw["afterUrl"]?.toString().orEmpty(),
                at = raw["at"]?.toString()?.toLongOrNull() ?: 0L
            )
        }
    }
}
