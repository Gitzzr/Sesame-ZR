package fansirsqi.xposed.sesame.hook.rpc.intervallimit

import fansirsqi.xposed.sesame.util.GlobalThreadPools
import fansirsqi.xposed.sesame.util.Log
import java.util.concurrent.ConcurrentHashMap

object RpcIntervalLimit {
    private const val TAG = "RpcIntervalLimit"
    private const val DEFAULT_INTERVAL = 500
    private val DEFAULT_INTERVAL_LIMIT = DefaultIntervalLimit(DEFAULT_INTERVAL)
    private val intervalLimitMap = ConcurrentHashMap<String, IntervalLimit>()

    /**
     * 为指定方法添加间隔限制。
     *
     * @param method 方法名称
     * @param interval 间隔时间（毫秒）
     */
    fun addIntervalLimit(method: String, interval: Int) {
        addIntervalLimit(method, DefaultIntervalLimit(interval))
    }

    /**
     * 为指定方法添加自定义间隔限制对象。
     *
     * @param method 方法名称
     * @param intervalLimit 自定义的间隔限制对象
     */
    fun addIntervalLimit(method: String, intervalLimit: IntervalLimit) {
        synchronized(intervalLimitMap) {
            if (intervalLimitMap.containsKey(method)) {
                Log.record(TAG, "方法：$method 间隔限制已存在")
                throw IllegalArgumentException("方法：$method 间隔限制已存在")
            }
            intervalLimitMap[method] = intervalLimit
        }
    }

    /**
     * 更新指定方法的间隔限制。
     *
     * @param method 方法名称
     * @param interval 新的间隔时间（毫秒）
     */
    fun updateIntervalLimit(method: String, interval: Int) {
        updateIntervalLimit(method, DefaultIntervalLimit(interval))
    }

    /**
     * 更新指定方法的间隔限制对象。
     *
     * @param method 方法名称
     * @param intervalLimit 新的自定义间隔限制对象
     */
    fun updateIntervalLimit(method: String, intervalLimit: IntervalLimit) {
        intervalLimitMap[method] = intervalLimit
    }

    /**
     * 进入指定方法的间隔限制，确保调用间隔时间不小于设定值。
     *
     * @param method 方法名称
     */
    fun enterIntervalLimit(method: String) {
        val intervalLimit = intervalLimitMap.getOrDefault(method, DEFAULT_INTERVAL_LIMIT)
        val lock = requireNotNull(intervalLimit) { "间隔限制对象不能为空" }

        // 锁内只「预约」一个不早于 (上次预约时刻 + interval) 的时刻，sleep 挪到锁外。
        //
        // 原实现把 sleepCompat 放在 synchronized 内部，并发调用会一起堵在监视器上 ——
        // 实测 logcat 的 dvm_lock_sample 在该锁上采样到 310 次。
        // 预约式写法保持「相邻调用间隔不小于 interval」的语义不变（每个调用者各占一个时间片），
        // 但等待期间不再持锁。
        val waitMs: Long
        synchronized(lock) {
            // 解决 Int? 的问题，使用默认值兜底
            val interval = intervalLimit.interval ?: DEFAULT_INTERVAL
            val now = System.currentTimeMillis()
            val scheduled = maxOf(now, intervalLimit.time + interval)
            intervalLimit.time = scheduled
            waitMs = scheduled - now
        }

        if (waitMs > 0) {
            GlobalThreadPools.sleepCompat(waitMs)
        }
    }

    /**
     * 清除所有方法的间隔限制。
     */
    fun clearIntervalLimit() {
        intervalLimitMap.clear()
    }
}