package fansirsqi.xposed.sesame.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 今日任务记录合并策略的单元测试 —— 纯逻辑，无 Android 依赖。
 */
class DailyTaskLogPolicyTest {

    private val uid = "2088001"

    private fun rain(grams: Int, success: Boolean, reason: String? = null) =
        EnergyRainEntry(at = 1L, grams = grams, success = success, reason = reason)

    private fun gift(kind: String, target: String, name: String, success: Boolean, reason: String? = null) =
        GiftEntry(
            at = 1L, kind = kind, targetUserId = target, targetName = name,
            amount = "66g", success = success, reason = reason
        )

    private fun action(title: String, success: Boolean, reason: String? = null, detail: String = "") =
        ActionEntry(
            at = 1L, module = DailyTaskLogPolicy.MODULE_FARM, kind = DailyTaskLogPolicy.ACTION_TASK,
            title = title, detail = detail, success = success, reason = reason
        )

    private fun reward(module: String, title: String, detail: String, success: Boolean) =
        RewardEntry(at = 1L, module = module, title = title, detail = detail, success = success, reason = null)

    @Test
    fun `同一天的记录会追加而不是覆盖`() {
        val first = DailyTaskLogPolicy.addEnergyRain(null, uid, "2026-09-27", rain(10, true), 1L)
        val second = DailyTaskLogPolicy.addEnergyRain(first, uid, "2026-09-27", rain(8, true), 2L)

        assertEquals(2, second.energyRain.size)
        assertEquals(10, second.energyRain[0].grams)
        assertEquals(8, second.energyRain[1].grams)
        assertEquals(2L, second.updatedAt)
    }

    @Test
    fun `跨日后首次写入清空前一天`() {
        val yesterday = DailyTaskLogPolicy.addGift(
            null, uid, "2026-09-26",
            gift(DailyTaskLogPolicy.GIFT_WATER, "2088002", "大号", true), 1L
        )
        val today = DailyTaskLogPolicy.addEnergyRain(yesterday, uid, "2026-09-27", rain(5, true), 2L)

        assertEquals("2026-09-27", today.day)
        assertTrue(today.gifts.isEmpty())
        assertEquals(1, today.energyRain.size)
    }

    @Test
    fun `浇水按类型和接收方分组计数`() {
        var log: DailyTaskLog? = null
        log = DailyTaskLogPolicy.addGift(log, uid, "2026-09-27", gift(DailyTaskLogPolicy.GIFT_WATER, "2088002", "大号", true), 1L)
        log = DailyTaskLogPolicy.addGift(log, uid, "2026-09-27", gift(DailyTaskLogPolicy.GIFT_WATER, "2088002", "大号", true), 2L)
        log = DailyTaskLogPolicy.addGift(log, uid, "2026-09-27", gift(DailyTaskLogPolicy.GIFT_WATER, "2088003", "好友", false, "能量不足"), 3L)
        log = DailyTaskLogPolicy.addGift(log, uid, "2026-09-27", gift(DailyTaskLogPolicy.GIFT_PROP, "2088002", "大号", true), 4L)

        val tallies = DailyTaskLogPolicy.giftTallies(log!!).associateBy { it.kind to it.targetUserId }

        assertEquals(2, tallies.getValue(DailyTaskLogPolicy.GIFT_WATER to "2088002").successCount)
        assertEquals(0, tallies.getValue(DailyTaskLogPolicy.GIFT_WATER to "2088002").failCount)
        assertEquals("大号", tallies.getValue(DailyTaskLogPolicy.GIFT_WATER to "2088002").targetName)
        assertEquals(1, tallies.getValue(DailyTaskLogPolicy.GIFT_WATER to "2088003").failCount)
        assertEquals(1, tallies.getValue(DailyTaskLogPolicy.GIFT_PROP to "2088002").successCount)
    }

    @Test
    fun `失败与零记录分别判定`() {
        val log = DailyTaskLogPolicy.addEnergyRain(null, uid, "2026-09-27", rain(0, false, "结算失败"), 1L)

        val status = DailyTaskLogPolicy.expectedStatus(
            enabled = mapOf("能量雨" to true, "好友浇水" to true, "赠送道具" to false),
            counter = { name ->
                when (name) {
                    "能量雨" -> Triple(log.energyRain.count { it.success }, log.energyRain.count { !it.success }, "结算失败")
                    else -> Triple(0, 0, null)
                }
            }
        ).associateBy { it.name }

        assertEquals(2, status.size)
        assertFalse(status.containsKey("赠送道具"))
        assertEquals(1, status.getValue("能量雨").failCount)
        assertEquals("结算失败", status.getValue("能量雨").lastFailReason)
        assertFalse(status.getValue("能量雨").missing)
        assertTrue(status.getValue("好友浇水").missing)
        assertNull(status.getValue("好友浇水").lastFailReason)
    }

    @Test
    fun `同一天的动作会追加且跨日清空`() {
        val first = DailyTaskLogPolicy.addAction(null, uid, "2026-09-27", action("庄园签到", true), 1L)
        val sameDay = DailyTaskLogPolicy.addAction(first, uid, "2026-09-27", action("捐蛋", false, "需验证"), 2L)
        assertEquals(listOf("庄园签到", "捐蛋"), sameDay.actions.map { it.title })

        val nextDay = DailyTaskLogPolicy.addAction(sameDay, uid, "2026-09-28", action("庄园签到", true), 3L)
        assertEquals("2026-09-28", nextDay.day)
        assertEquals(1, nextDay.actions.size)
        assertTrue(nextDay.energyRain.isEmpty())
        assertTrue(nextDay.rewards.isEmpty())
    }

    @Test
    fun `同模块奖励和动作按标题分开计`() {
        var log: DailyTaskLog? = null
        log = DailyTaskLogPolicy.addReward(
            log, uid, "2026-09-27",
            reward(DailyTaskLogPolicy.MODULE_FARM, "喂鸡奖励", "10g", true), 1L
        )
        log = DailyTaskLogPolicy.addReward(
            log, uid, "2026-09-27",
            reward(DailyTaskLogPolicy.MODULE_FARM, "家庭奖励", "饲料 x1", false), 2L
        )
        log = DailyTaskLogPolicy.addReward(
            log, uid, "2026-09-27",
            reward(DailyTaskLogPolicy.MODULE_OCEAN, "每日任务", "2拼图", true), 3L
        )
        log = DailyTaskLogPolicy.addReward(
            log, uid, "2026-09-27",
            reward(DailyTaskLogPolicy.MODULE_OCEAN, "海域任务", "潘多拉能量x3", false), 4L
        )
        log = DailyTaskLogPolicy.addAction(log, uid, "2026-09-27", action("抽抽乐", true, detail = "已完成"), 5L)
        log = DailyTaskLogPolicy.addAction(log, uid, "2026-09-27", action("捐蛋", false, "需验证"), 6L)

        val farmTask = DailyTaskLogPolicy.countFor("庄园任务奖励", log!!)
        val family = DailyTaskLogPolicy.countFor("家庭奖励", log)
        val oceanTask = DailyTaskLogPolicy.countFor("海洋任务奖励", log)
        val pandora = DailyTaskLogPolicy.countFor("潘多拉能量", log)
        val draw = DailyTaskLogPolicy.countFor("抽抽乐", log)
        val missing = DailyTaskLogPolicy.countFor("厨房做菜", log)

        assertEquals(Triple(1, 0, null), farmTask)
        assertEquals(1, family.second)
        assertEquals(Triple(1, 0, null), oceanTask)
        assertEquals(1, pandora.second)
        assertEquals(Triple(1, 0, null), draw)
        assertEquals(Triple(0, 0, null), missing)
        assertEquals("需验证", DailyTaskLogPolicy.countFor("捐蛋", log).third)
    }

    @Test
    fun `日期键按东八区自然日计算且不随设备时区漂移`() {
        val midnight = Calendar.getInstance(TimeZone.getTimeZone("GMT+8")).apply {
            set(2026, Calendar.SEPTEMBER, 27, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertEquals("2026-09-27", DailyTaskLogRecorder.dayKey(midnight))
        assertEquals("2026-09-26", DailyTaskLogRecorder.dayKey(midnight - 1))
    }
}
