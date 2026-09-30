package fansirsqi.xposed.sesame.task.antOcean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class AntOceanAiFishTest {

    @Test
    fun `AI摸鱼开关属于神奇海洋且默认关闭`() {
        val fields = requireNotNull(AntOcean().fields)

        assertTrue(fields.containsKey("aiFish"))
        assertFalse(fields["aiFish"]?.value as Boolean)
    }

    @Test
    fun `主页解析被抓状态且未知结构不推定成功`() {
        val snapshot = AntOcean.parseAiFishHome(home("CAPTURED", 3, 7))
        val unknown = AntOcean.parseAiFishHome("""{"success":true}""")

        assertTrue(snapshot.isRecognized)
        assertEquals("CAPTURED", snapshot.fishStatus)
        assertEquals(3, snapshot.remainTouchChance)
        assertEquals(7, snapshot.touchTotal)
        assertFalse(unknown.isRecognized)
        assertNull(unknown.fishStatus)
    }

    @Test
    fun `任务解析限制等待时间并稳定选择救援任务`() {
        val snapshot = AntOcean.parseAiFishTasks(
            taskResponse(
                task("RESCUE_Z", "TODO", 90, "VISIT_FLOAT_BALL", AntOcean.AI_FISH_RESCUE_SCENE),
                task("RESCUE_A", "TODO", 10, "VISIT_FLOAT_BALL", AntOcean.AI_FISH_RESCUE_SCENE),
                task("RESCUE_ZERO", "TODO", 0, "VISIT_FLOAT_BALL", AntOcean.AI_FISH_RESCUE_SCENE),
                task("RESCUE_OTHER", "TODO", 15, "OTHER", AntOcean.AI_FISH_RESCUE_SCENE)
            )
        )

        assertTrue(snapshot.isRecognized)
        assertEquals(60, snapshot.tasks.first().waitSeconds)
        assertEquals("RESCUE_A", AntOcean.selectAiFishRescueTask(snapshot)?.taskType)
    }

    @Test
    fun `被抓时等待后使用专用接口找回且不调用通用任务接口`() {
        val gateway = FakeGateway(
            homes = dequeOf(home("CAPTURED", 3, 0), home("CAN_TOUCH", 0, 0), home("CAN_TOUCH", 0, 0)),
            rescueTasks = taskResponse(
                task("RESCUE_15S", "TODO", 15, "VISIT_FLOAT_BALL", AntOcean.AI_FISH_RESCUE_SCENE)
            ),
            mainTasks = dequeOf(taskResponse())
        )

        val result = AntOcean.AiFishRunner(gateway).run()

        assertTrue(result.isRescued)
        assertEquals(listOf(16_000L), gateway.waits)
        assertEquals(1, gateway.rescueCalls)
        assertTrue(gateway.finishCalls.isEmpty())
        assertTrue(gateway.receiveCalls.isEmpty())
    }

    @Test
    fun `救援状态未推进时停止主任务和摸鱼`() {
        val gateway = FakeGateway(
            homes = dequeOf(home("CAPTURED", 3, 0), home("CAPTURED", 3, 0)),
            rescueTasks = taskResponse(
                task("RESCUE_5S", "TODO", 5, "VISIT_FLOAT_BALL", AntOcean.AI_FISH_RESCUE_SCENE)
            )
        )

        val result = AntOcean.AiFishRunner(gateway).run()

        assertFalse(result.isRescued)
        assertTrue(gateway.mainListCalls == 0)
        assertEquals(0, gateway.touchCalls)
    }

    @Test
    fun `完成所有主任务并逐步回查领取奖励`() {
        val gateway = FakeGateway(
            homes = dequeOf(home("CAN_TOUCH", 0, 0), home("CAN_TOUCH", 0, 0)),
            mainTasks = dequeOf(
                taskResponse(
                    task("daily_add_touch_fish", "FINISHED", 0),
                    task("AIFISH_SHJF", "TODO", 5)
                ),
                taskResponse(
                    task("daily_add_touch_fish", "RECEIVED", 0),
                    task("AIFISH_SHJF", "TODO", 5)
                ),
                taskResponse(
                    task("daily_add_touch_fish", "RECEIVED", 0),
                    task("AIFISH_SHJF", "FINISHED", 5)
                ),
                taskResponse(
                    task("daily_add_touch_fish", "RECEIVED", 0),
                    task("AIFISH_SHJF", "RECEIVED", 5)
                ),
                taskResponse(
                    task("daily_add_touch_fish", "RECEIVED", 0),
                    task("AIFISH_SHJF", "RECEIVED", 5)
                )
            )
        )

        val result = AntOcean.AiFishRunner(gateway).run()

        assertEquals(listOf(5_000L), gateway.waits)
        assertEquals(listOf("ANTAIFISH|AIFISH_SHJF"), gateway.finishCalls)
        assertEquals(
            listOf("ANTAIFISH|daily_add_touch_fish", "ANTAIFISH|AIFISH_SHJF"),
            gateway.receiveCalls
        )
        assertEquals(1, result.completedTaskCount)
        assertEquals(2, result.receivedRewardCount)
    }

    @Test
    fun `单个任务抛出异常时继续处理后续任务`() {
        val gateway = FakeGateway(
            homes = dequeOf(home("CAN_TOUCH", 0, 0), home("CAN_TOUCH", 0, 0)),
            mainTasks = dequeOf(
                taskResponse(task("TASK_A", "TODO", 0), task("TASK_B", "TODO", 0)),
                taskResponse(task("TASK_A", "TODO", 0), task("TASK_B", "RECEIVED", 0)),
                taskResponse(task("TASK_A", "TODO", 0), task("TASK_B", "RECEIVED", 0))
            ),
            throwingFinishTasks = setOf("TASK_A")
        )

        val result = AntOcean.AiFishRunner(gateway).run()

        assertEquals(listOf("ANTAIFISH|TASK_A", "ANTAIFISH|TASK_B"), gateway.finishCalls)
        assertEquals(1, result.completedTaskCount)
    }

    @Test
    fun `保卫向日葵任务每天只完成一次`() {
        val taskList = taskResponse(task("AIFISH_ZHUANHUA_BWXRK", "TODO", 0))
        val gateway = FakeGateway(
            homes = dequeOf(
                home("CAN_TOUCH", 0, 0),
                home("CAN_TOUCH", 0, 0),
                home("CAN_TOUCH", 0, 0),
                home("CAN_TOUCH", 0, 0)
            ),
            defaultMainTasks = taskList
        )

        AntOcean.AiFishRunner(gateway).run()
        AntOcean.AiFishRunner(gateway).run()

        assertEquals(
            listOf("ANTAIFISH|AIFISH_ZHUANHUA_BWXRK"),
            gateway.finishCalls
        )
    }

    @Test
    fun `广告转化类任务被拒收后当天不再重试`() {
        // 同为 OTHER 的任务有的确实能被 finishTask 完成（实测「测一测摸鱼运势」「添加海洋至首页」都能），
        // 所以不按类型预判，而是"试一次、被拒就记下来"：一次请求换一个确定结论。
        // 实测 2026-09-29 这类任务每轮重试、全天 85 次无效调用 —— 现在当天只发一次。
        val gateway = FakeGateway(
            homes = dequeOf(
                home("CAN_TOUCH", 0, 0),
                home("CAN_TOUCH", 0, 0),
                home("CAN_TOUCH", 0, 0),
                home("CAN_TOUCH", 0, 0)
            ),
            defaultMainTasks = taskResponse(
                task("GAME_TASK", "TODO", 0, "OTHER"),
                task("VISIT_TASK", "TODO", 0, "VISIT_FLOAT_BALL")
            ),
            rejectingFinishTasks = setOf("GAME_TASK")
        )

        val first = AntOcean.AiFishRunner(gateway).run()

        assertEquals(
            listOf("ANTAIFISH|GAME_TASK", "ANTAIFISH|VISIT_TASK"),
            gateway.finishCalls
        )
        assertTrue(first.events.any { it == "AI摸鱼任务完成未受理[GAME_TASK]" })
        assertTrue(gateway.abandonedToday.contains("GAME_TASK"))

        // 后续轮次静默：拒收任务不再重试，也不再重复播报
        val second = AntOcean.AiFishRunner(gateway).run()
        val third = AntOcean.AiFishRunner(gateway).run()

        assertEquals(1, gateway.finishCalls.count { it.endsWith("GAME_TASK") })
        assertFalse(second.events.any { it.startsWith("AI摸鱼任务完成未受理[") })
        assertFalse(third.events.any { it.startsWith("AI摸鱼任务完成未受理[") })
    }

    @Test
    fun `时长类任务被拒收后当天不再重试`() {
        // 兜底路径：元数据看起来是时长类，但服务端仍然不受理 → 拒收一次当天不再试
        val gateway = FakeGateway(
            homes = dequeOf(
                home("CAN_TOUCH", 0, 0),
                home("CAN_TOUCH", 0, 0),
                home("CAN_TOUCH", 0, 0),
                home("CAN_TOUCH", 0, 0)
            ),
            defaultMainTasks = taskResponse(task("VISIT_TASK", "TODO", 0, "VISIT_FLOAT_BALL")),
            rejectingFinishTasks = setOf("VISIT_TASK")
        )

        val first = AntOcean.AiFishRunner(gateway).run()
        assertTrue(first.events.any { it.startsWith("AI摸鱼任务完成未受理[") })

        val second = AntOcean.AiFishRunner(gateway).run()
        val third = AntOcean.AiFishRunner(gateway).run()

        assertEquals(1, gateway.finishCalls.count { it.endsWith("VISIT_TASK") })
        assertFalse(second.events.any { it.startsWith("AI摸鱼任务完成未受理[") })
        assertFalse(third.events.any { it.startsWith("AI摸鱼任务完成未受理[") })
    }

    @Test
    fun `放弃自动执行不影响手动完成后的领奖`() {
        // 用户在宿主应用里手动做完 → 状态变 FINISHED → 模块照常领奖，
        // 放弃标记只拦「替用户完成」，不拦「领取」。
        val gateway = FakeGateway(
            homes = dequeOf(
                home("CAN_TOUCH", 0, 0),
                home("CAN_TOUCH", 0, 0),
                home("CAN_TOUCH", 0, 0),
                home("CAN_TOUCH", 0, 0)
            ),
            mainTasks = dequeOf(
                // 第一轮：试一次被拒 → 当天不再尝试
                taskResponse(task("GAME_TASK", "TODO", 0, "OTHER")),
                // 第二轮：用户手动做过了，状态 FINISHED → 应走领奖分支
                taskResponse(task("GAME_TASK", "FINISHED", 0, "OTHER")),
                taskResponse(task("GAME_TASK", "RECEIVED", 0, "OTHER")),
                taskResponse(task("GAME_TASK", "RECEIVED", 0, "OTHER"))
            ),
            rejectingFinishTasks = setOf("GAME_TASK")
        )

        AntOcean.AiFishRunner(gateway).run()
        AntOcean.AiFishRunner(gateway).run()

        assertEquals(1, gateway.finishCalls.count { it.endsWith("GAME_TASK") })
        assertEquals(1, gateway.receiveCalls.count { it.endsWith("GAME_TASK") })
    }

    @Test
    fun `摸鱼响应无进展时立即停止`() {
        val gateway = FakeGateway(
            homes = dequeOf(home("CAN_TOUCH", 1, 0), home("CAN_TOUCH", 1, 0)),
            mainTasks = dequeOf(taskResponse()),
            touches = dequeOf(home("CAN_TOUCH", 1, 0))
        )

        val result = AntOcean.AiFishRunner(gateway).run()

        assertEquals(1, gateway.touchCalls)
        assertEquals(0, result.touchCount)
    }

    @Test
    fun `摸鱼单轮最多执行二十次`() {
        val touches = ArrayDeque<String>()
        for (count in 1..20) {
            touches.add(home("CAN_TOUCH", 25 - count, count))
        }
        val gateway = FakeGateway(
            homes = dequeOf(home("CAN_TOUCH", 25, 0), home("CAN_TOUCH", 25, 0)),
            mainTasks = dequeOf(taskResponse()),
            touches = touches
        )

        val result = AntOcean.AiFishRunner(gateway).run()

        assertEquals(20, gateway.touchCalls)
        assertEquals(20, result.touchCount)
    }

    @Test
    fun `动作响应仅接受明确成功结构`() {
        assertTrue(AntOcean.isAiFishActionAccepted("""{"success":true}"""))
        assertTrue(AntOcean.isAiFishActionAccepted("""{"resultCode":"SUCCESS"}"""))
        assertTrue(AntOcean.isAiFishActionAccepted("""{"resData":{"code":"100000000"}}"""))
        assertFalse(AntOcean.isAiFishActionAccepted("""{"success":false}"""))
        assertFalse(AntOcean.isAiFishActionAccepted("not-json"))
    }

    private class FakeGateway(
        private val homes: ArrayDeque<String> = ArrayDeque(),
        private val rescueTasks: String = taskResponse(),
        private val mainTasks: ArrayDeque<String> = ArrayDeque(),
        private val touches: ArrayDeque<String> = ArrayDeque(),
        private val throwingFinishTasks: Set<String> = emptySet(),
        private val rejectingFinishTasks: Set<String> = emptySet(),
        private val defaultMainTasks: String = taskResponse()
    ) : AntOcean.AiFishGateway {
        val waits = mutableListOf<Long>()
        val finishCalls = mutableListOf<String>()
        val receiveCalls = mutableListOf<String>()
        var rescueCalls = 0
        var touchCalls = 0
        var mainListCalls = 0
        private val completedTodayTaskTypes = mutableSetOf<String>()
        val abandonedToday = mutableSetOf<String>()

        override fun queryStatus() = """{"success":true}"""

        override fun queryHome(): String = homes.pollFirst() ?: home("CAN_TOUCH", 0, 0)

        override fun listTasks(sceneCode: String): String {
            if (sceneCode == AntOcean.AI_FISH_RESCUE_SCENE) return rescueTasks
            mainListCalls++
            return mainTasks.pollFirst() ?: defaultMainTasks
        }

        override fun finishTask(sceneCode: String, taskType: String): String {
            finishCalls += "$sceneCode|$taskType"
            if (taskType in throwingFinishTasks) {
                error("模拟任务异常")
            }
            if (taskType in rejectingFinishTasks) {
                return """{"success":false,"resultCode":"NOT_ACCEPTED"}"""
            }
            return """{"success":true}"""
        }

        override fun receiveTaskAward(sceneCode: String, taskType: String): String {
            receiveCalls += "$sceneCode|$taskType"
            return """{"success":true}"""
        }

        override fun hasCompletedToday(taskType: String): Boolean =
            taskType in completedTodayTaskTypes

        override fun markCompletedToday(taskType: String) {
            completedTodayTaskTypes += taskType
        }

        override fun hasAbandonedToday(taskType: String): Boolean =
            taskType in abandonedToday

        override fun markAbandonedToday(taskType: String) {
            abandonedToday += taskType
        }

        override fun rescueFish(): String {
            rescueCalls++
            return """{"success":true}"""
        }

        override fun touchFish(): String {
            touchCalls++
            return touches.pollFirst() ?: """{"success":false}"""
        }

        override fun waitMillis(millis: Long) {
            waits += millis
        }
    }

    companion object {
        private fun dequeOf(vararg values: String) = ArrayDeque(values.toList())

        private fun home(status: String, remain: Int, total: Int): String =
            """{"success":true,"myFish":{"interactVO":{"fishInteractStatus":"$status","remainTouchChance":$remain,"touchTotal":$total}}}"""

        private fun taskResponse(vararg tasks: String): String =
            """{"success":true,"taskInfoList":[${tasks.joinToString(",") }]}"""

        private fun task(
            taskType: String,
            status: String,
            waitSeconds: Int,
            playType: String = "VISIT_FLOAT_BALL",
            sceneCode: String = "ANTAIFISH"
        ): String {
            // 有 timeCount ⇒ 时长类；没有 ⇒ 广告转化类（OTHER），与真机元数据一致
            // 注意：这段要嵌进下面的 raw string（JSON 里是字符串值），引号必须转义
            val playParam = (
                    if (waitSeconds > 0) {
                        """{"timeCount":$waitSeconds}"""
                    } else {
                        """{"taskCategorization":{"categorizationFirstLevel":"Commercialization"}}"""
                    }
                    ).replace("\"", "\\\"")
            return """
            {
              "taskBaseInfo": {
                "bizInfo": "{\"taskTitle\":\"$taskType\"}",
                "prodPlayParam": "$playParam",
                "sceneCode": "$sceneCode",
                "taskStatus": "$status",
                "taskType": "$taskType",
                "taskProdPlayType": "$playType"
              },
              "taskRights": {"awardCount": 1, "directReceiveAward": false}
            }
        """.trimIndent()
        }
    }
}
