package fansirsqi.xposed.sesame.task.antForest

import fansirsqi.xposed.sesame.data.Status
import fansirsqi.xposed.sesame.task.DailyTaskLogPolicy
import fansirsqi.xposed.sesame.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Calendar

object Privilege {
    private const val TAG = "Privilege"

    // 标记 & 前缀
    private const val FLAG_RECEIVED = "youth_privilege_forest_received"
    private const val FLAG_STUDENT_TASK = "youth_privilege_student_task"
    private const val PREFIX_PRIVILEGE = "青春特权🌸"
    private const val PREFIX_SIGN = "青春特权🧧"

    // 任务状态
    private const val TASK_RECEIVED = "RECEIVED"
    private const val TASK_FINISHED = "FINISHED"
    private const val RPC_SUCCESS = "SUCCESS"

    // 时间范围
    private const val SIGN_START_HOUR = 5
    private const val SIGN_END_HOUR = 10

    // 青春特权任务配置
    private val YOUTH_TASKS = listOf(
        YouthTask("DNHZ_SL_college", "DAXUESHENG_SJK", "双击卡"),
        YouthTask("DXS_BHZ", "NENGLIANGZHAO_20230807", "保护罩"),
        YouthTask("DXS_JSQ", "JIASUQI_20230808", "加速器")
    )

    fun youthPrivilege(): Boolean {
        if (Status.hasFlagToday(FLAG_RECEIVED)) return false

        val results = mutableListOf<String>()
        for (task in YOUTH_TASKS) {
            results += processYouthTask(task)
        }

        val allSuccess = results.all { it == "处理成功" }
        if (allSuccess) Status.setFlagToday(FLAG_RECEIVED)
        return allSuccess
    }

    private fun processYouthTask(task: YouthTask): List<String> {
        val forestTasksNew = getForestTasks(task.queryParam)
        return handleForestTasks(forestTasksNew, task.receiveParam, task.name)
    }

    private fun getForestTasks(queryParam: String): JSONArray? {
        val response = AntForestRpcCall.queryTaskListV2(queryParam)
        return try {
            JSONObject(response).getJSONArray("forestTasksNew")
        } catch (e: JSONException) {
            Log.error(TAG, "获取任务列表失败$e")
            null
        }
    }

    private fun handleForestTasks(forestTasks: JSONArray?, taskType: String, taskName: String): List<String> {
        val results = mutableListOf<String>()

        try {
            if (forestTasks != null && forestTasks.length() > 0) {
                for (i in 0 until forestTasks.length()) {
                    val taskGroup = forestTasks.optJSONObject(i) ?: continue
                    val taskInfoList = taskGroup.getJSONArray("taskInfoList") ?: continue

                    for (j in 0 until taskInfoList.length()) {
                        val task = taskInfoList.optJSONObject(j) ?: continue
                        val baseInfo = task.getJSONObject("taskBaseInfo") ?: continue

                        if (baseInfo.optString("taskType") != taskType) continue

                        processSingleYouthTask(baseInfo, taskType, taskName, results)
                    }
                }
            }
        } catch (e: JSONException) {
            Log.error(TAG, "任务列表解析失败$e")
            results.add("处理异常")
        }

        return results
    }

    private fun processSingleYouthTask(baseInfo: JSONObject, taskType: String, taskName: String, results: MutableList<String>) {
        val status = baseInfo.optString("taskStatus")

        when (status) {
            TASK_RECEIVED -> {
                Log.forest("$PREFIX_PRIVILEGE[$taskName]已领取")
                recordPrivilege(DailyTaskLogPolicy.ACTION_PROP_USE, "青春特权道具", taskName, true, "已领取")
            }
            TASK_FINISHED -> handleYouthTaskAward(taskType, taskName, results)
        }
    }

    private fun handleYouthTaskAward(taskType: String, taskName: String, results: MutableList<String>) {
        try {
            val response = JSONObject(AntForestRpcCall.receiveTaskAwardV2(taskType))
            val resultDesc = response.optString("desc")
            results.add(resultDesc)

            val logMessage = if (resultDesc == "处理成功") "领取成功" else "领取结果：$resultDesc"
            Log.forest("$PREFIX_PRIVILEGE[$taskName]$logMessage")
            recordPrivilege(
                DailyTaskLogPolicy.ACTION_PROP_USE, "青春特权道具", taskName,
                resultDesc == "处理成功", resultDesc
            )
        } catch (e: JSONException) {
            Log.error(TAG, "奖励领取结果解析失败$e")
            results.add("处理异常")
        }
    }

    fun studentSignInRedEnvelope() {
        if (!isSignInTimeValid()) {
            Log.record("$PREFIX_SIGN 5点前不执行签到")
            recordPrivilege(DailyTaskLogPolicy.ACTION_CHECKIN, "青春特权签到", "5点前", true, "5点前不执行")
            return
        }

        if (Status.hasFlagToday(FLAG_STUDENT_TASK)) {
            Log.record("$PREFIX_SIGN 今日已完成签到")
            recordPrivilege(DailyTaskLogPolicy.ACTION_CHECKIN, "青春特权签到", "已完成", true, "今日已完成")
            return
        }

        try {
            processStudentSignIn()
        } catch (e: Exception) {
            Log.error(TAG, "学生签到异常$e")
        }
    }

    private fun isSignInTimeValid(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= SIGN_START_HOUR
    }

    private fun processStudentSignIn() {
        val response = AntForestRpcCall.studentQqueryCheckInModel()
        val result = try {
            JSONObject(response)
        } catch (e: JSONException) {
            Log.error(TAG, "学生签到模型解析失败$e")
            return
        }

        if (result.optString("resultCode") != RPC_SUCCESS) {
            val resultDesc = result.optString("resultDesc", "查询失败")
            Log.record("$PREFIX_SIGN 查询失败：$resultDesc")
            recordPrivilege(DailyTaskLogPolicy.ACTION_CHECKIN, "青春特权签到", "查询", false, resultDesc)
            return
        }

        val checkInInfo = result.optJSONObject("studentCheckInInfo")
        if (checkInInfo == null || checkInInfo.optString("action") == "DO_TASK") {
            Status.setFlagToday(FLAG_STUDENT_TASK)
            return
        }

        executeStudentSignIn()
    }

    private fun executeStudentSignIn() {
        try {
            val tag = if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < SIGN_END_HOUR) "double" else "single"
            val response = AntForestRpcCall.studentCheckin()
            val result = JSONObject(response)
            handleSignInResult(result, tag)
        } catch (e: JSONException) {
            Log.error(TAG, "学生签到失败：${e.message}")
        }
    }

    private fun handleSignInResult(result: JSONObject, tag: String) {
        val code = result.optString("resultCode")
        val desc = result.optString("resultDesc")

        if (code == RPC_SUCCESS) {
            Status.setFlagToday(FLAG_STUDENT_TASK)
            Log.forest("$PREFIX_SIGN$tag$desc")
            recordPrivilege(DailyTaskLogPolicy.ACTION_CHECKIN, "青春特权签到", tag, true, desc)
        } else {
            var errorMsg = desc
            if (desc.contains("不匹配")) {
                errorMsg += "可能账户不符合条件"
            }
            Log.error(TAG, "$PREFIX_SIGN$tag 失败：$errorMsg")
            recordPrivilege(DailyTaskLogPolicy.ACTION_CHECKIN, "青春特权签到", tag, false, errorMsg)
        }
    }

    private fun recordPrivilege(kind: String, title: String, detail: String, success: Boolean, reason: String?) {
        AntForest.recordForestAction(kind, title, detail, success, reason)
    }

    data class YouthTask(val queryParam: String, val receiveParam: String, val name: String)
}