package fansirsqi.xposed.sesame.task.antForest

/**
 * 「赠送道具」循环该不该继续。
 *
 * 抽成纯函数是因为这里三个出口都踩过坑：查询失败、查到了但列表为空、以及只剩一个道具 ——
 * 旧实现前两种都会落回 `do { } while (true)` 无限重查。
 * 2026-09-28 09:27 实测：安全验证暂停期间 RPC 即时返回，连 1.5 秒的成功间隔都等不到，
 * 一分钟内重查了 1300+ 次，日志与当日记录被刷爆。
 */
object PropGiftLoopPolicy {

    /**
     * @param queryOk      道具列表查询是否成功
     * @param propListSize 可赠送道具条数
     * @param holdsNum     当前道具持有数
     * @return true = 还有可赠送的道具，继续下一轮；false = 结束本次赠送
     */
    @JvmStatic
    fun shouldContinue(queryOk: Boolean, propListSize: Int, holdsNum: Int): Boolean {
        // 查询失败没有继续的依据：下一轮还是同一个结果，只会把请求量放大
        if (!queryOk) return false
        // 没有可赠送的道具
        if (propListSize <= 0) return false
        // 持有数只剩 1 且列表只有一个道具时已经没有可赠送的了
        return holdsNum > 1 || propListSize > 1
    }
}
