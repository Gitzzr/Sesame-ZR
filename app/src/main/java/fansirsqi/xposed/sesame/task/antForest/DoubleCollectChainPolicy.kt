package fansirsqi.xposed.sesame.task.antForest

/**
 * 「续收链」深度上限策略。
 *
 * 背景：服务端在响应里返回 `canBeRobbedAgain=true` 时，`AntForest.collectEnergy` 会
 * 直接递归调用自身再收一次（单球路径与批量路径都有）。该路径每次都会调用
 * `resetTryCount()` 把**失败重试**计数清零，因此 `tryCountInt` 上限约束不到它 ——
 * 一旦服务端持续返回 true，递归就无界，实测单次异常栈深达 5360 帧（约 5444 帧触顶）。
 *
 * 这里用独立于重试次数的「续收链深度」计数来设上界：
 * - `tryCountInt` 管「失败重试」；
 * - `chainCount` 管「成功之后的再次续收」；
 * 两者互不干扰。正常场景双击链接 2~4 次，上限 10 不会误伤。
 */
internal object DoubleCollectChainPolicy {
    /** 单个能量球允许的最大续收次数（不含首次收取）。 */
    const val MAX_CHAIN = 10

    /**
     * 是否还允许继续续收。
     *
     * @param chainCount 已经续收过的次数（首次收取后为 0）
     * @param maxChain 上限，默认 [MAX_CHAIN]，便于测试注入
     */
    fun canContinue(chainCount: Int, maxChain: Int = MAX_CHAIN): Boolean {
        return chainCount < maxChain
    }
}
