package fansirsqi.xposed.sesame.task.antForest

import com.fasterxml.jackson.core.type.TypeReference
import fansirsqi.xposed.sesame.data.Status
import fansirsqi.xposed.sesame.hook.Toast
import fansirsqi.xposed.sesame.util.DataStore
import fansirsqi.xposed.sesame.util.DataStore.put
import fansirsqi.xposed.sesame.util.JsonUtil
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.RandomUtil
import fansirsqi.xposed.sesame.util.ResChecker
import fansirsqi.xposed.sesame.util.StringUtil
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.regex.Pattern

object EcoLife {
    val TAG: String = EcoLife::class.java.getSimpleName()

    /**
     * 绿色行动下的两个核对项名称。
     *
     * 必须与 `AntForest.dailyCheckSwitches()` 里的键一字不差，否则今日完成页会显示「今日无记录」。
     * 拆成两项是因为「绿色打卡」与「光盘行动」是两个独立选项（见 `listEcoLifeOptions`），
     * 合并成一项会让用户看不出到底缺哪一个。
     */
    const val TITLE_CHECK_IN: String = "绿色打卡"
    const val TITLE_PLATE: String = "光盘行动"

    /** 绿色行动的开通、查询、打卡记到「绿色打卡」，失败原因只用响应里已有的描述。 */
    private fun recordEcoAction(title: String, detail: String, success: Boolean, reason: String?) {
        AntForest.recordForestAction(
            fansirsqi.xposed.sesame.task.DailyTaskLogPolicy.ACTION_CHECKIN,
            title, detail, success, reason
        )
    }

    /**
     * 执行绿色行动任务，包括查询任务开通状态、开通绿色任务、执行打卡任务等操作。
     * 1. 调用接口查询绿色行动的首页数据，检查是否成功。
     * 2. 如果绿色任务尚未开通，且用户未开通绿色任务，则记录日志并返回。
     * 3. 如果绿色任务尚未开通，且用户已开通绿色任务，则尝试开通绿色任务。
     * 4. 开通绿色任务成功后，再次查询任务状态，并更新数据。
     * 5. 获取任务的日期标识和任务列表，执行打卡任务。
     * 6. 如果绿色打卡设置为启用，执行 `ecoLifeTick` 方法提交打卡任务。
     * 7. 如果光盘打卡设置为启用，执行 `photoGuangPan` 方法上传光盘照片。
     * 8. 异常发生时，记录错误信息并打印堆栈。
     */
    fun ecoLife() {
        try {
            // 查询首页信息
            var jsonObject = JSONObject(AntForestRpcCall.ecolifeQueryHomePage())
            if (!jsonObject.optBoolean("success")) {
                val resultDesc = jsonObject.optString("resultDesc", "查询失败")
                Log.record("$TAG.ecoLife.queryHomePage", resultDesc)
                recordEcoAction(TITLE_CHECK_IN, "查询", false, resultDesc)
                return
            }
            var data = jsonObject.getJSONObject("data")


            // 获取当天的积分和任务列表
            var dayPoint = data.optString("dayPoint", "0")
            if (dayPoint == "0") {
                Log.error(TAG, "不知道什么B原因自己去绿色行动找")
                return
            }

            if (AntForest.ecoLifeOption!!.value.contains("plate")) {
                // 光盘行动
                photoGuangPan(dayPoint)
            }

            val actionListVO = data.getJSONArray("actionListVO")
            // 绿色打卡
            if (AntForest.ecoLifeOption!!.value.contains("tick")) {
                if (!data.getBoolean("openStatus")) {
                    if (!openEcoLife() || !AntForest.ecoLifeOpen!!.value) {
                        return
                    }
                    jsonObject = JSONObject(AntForestRpcCall.ecolifeQueryHomePage())
                    data = jsonObject.getJSONObject("data")
                    dayPoint = data.getString("dayPoint")
                }
                ecoLifeTick(actionListVO, dayPoint)
            }
        } catch (th: Throwable) {
            Log.record(TAG, "ecoLife err:")
            Log.printStackTrace(TAG, th)
        }
    }

    /**
     * 封装绿色任务开通的逻辑
     *
     * @return 是否成功开通绿色任务
     */
    @Throws(JSONException::class)
    fun openEcoLife(): Boolean {
        val jsonObject = JSONObject(AntForestRpcCall.ecolifeOpenEcolife())
        if (!jsonObject.optBoolean("success")) {
            val resultDesc = jsonObject.optString("resultDesc", "开通失败")
            Log.record("$TAG.ecoLife.openEcolife", resultDesc)
            recordEcoAction(TITLE_CHECK_IN, "开通", false, resultDesc)
            return false
        }
        val opResult = JsonUtil.getValueByPath(jsonObject, "data.opResult")
        if ("true" != opResult) {
            recordEcoAction(TITLE_CHECK_IN, "开通", false, "开通失败")
            return false
        }
        Log.forest("绿色任务🍀报告大人，开通成功(～￣▽￣)～可以愉快的玩耍了")
        recordEcoAction(TITLE_CHECK_IN, "开通", true, null)
        return true
    }

    /**
     * 执行绿色行动打卡任务，遍历任务列表，依次提交每个未完成的任务。
     * 1. 遍历给定的任务列表（`actionListVO`），每个任务项包含多个子任务。
     * 2. 对于每个子任务，检查其是否已完成，如果未完成则提交打卡请求。
     * 3. 特别处理任务 ID 为 "photoguangpan" 的任务，跳过该任务的打卡。
     * 4. 如果任务打卡成功，记录成功日志；否则记录失败原因。
     * 5. 每次打卡请求后，等待 500 毫秒以避免请求过于频繁。
     * 6. 异常发生时，记录详细的错误信息。
     *
     * @param actionListVO 任务列表，每个任务包含多个子任务
     * @param dayPoint     任务的日期标识，用于标识任务的日期
     */
    fun ecoLifeTick(actionListVO: JSONArray, dayPoint: String?) {
        try {
            val source = "source"
            for (i in 0..<actionListVO.length()) {
                val actionVO = actionListVO.getJSONObject(i)
                val actionItemList = actionVO.getJSONArray("actionItemList")
                for (j in 0..<actionItemList.length()) {
                    val actionItem = actionItemList.getJSONObject(j)
                    if (!actionItem.has("actionId")) continue
                    if (actionItem.getBoolean("actionStatus")) continue
                    val actionId = actionItem.getString("actionId")
                    val actionName = actionItem.getString("actionName")
                    if ("photoguangpan" == actionId) continue
                    val jo = JSONObject(AntForestRpcCall.ecolifeTick(actionId, dayPoint, source))
                    if (ResChecker.checkRes(TAG, jo)) {
                        Log.forest("绿色打卡🍀[$actionName]") // 成功打卡日志
                        recordEcoAction(TITLE_CHECK_IN, actionName, true, null)
                    } else {
                        // 记录失败原因
                        val resultDesc = jo.optString("resultDesc", "打卡失败")
                        Log.error(TAG + resultDesc)
                        Log.error(TAG + jo)
                        recordEcoAction(TITLE_CHECK_IN, actionName, false, resultDesc)
                    }
                }
            }
        } catch (th: Throwable) {
            Log.record(TAG, "ecoLifeTick err:")
            Log.printStackTrace(TAG, th)
        }
    }

    /**
     * 执行光盘行动任务，上传餐前餐后照片并提交任务。
     * 1. 查询当前任务的状态。
     * 2. 如果任务未完成，检查是否已有餐前餐后照片的URL，如果没有则从接口获取并保存。
     * 3. 上传餐前餐后照片，上传成功后提交任务，标记任务为完成。
     * 4. 如果任务已完成，则不做任何操作。
     * 5. 如果遇到任何错误，记录错误信息并停止执行。
     *
     * @param dayPoint 任务的日期标识，用于标识任务的日期
     */
    fun photoGuangPan(dayPoint: String?) {
        try {
            if (Status.hasFlagToday("EcoLife::photoGuangPan")) return

            val source = "renwuGD" // 任务来源标识

            val typeRef: TypeReference<MutableList<MutableMap<String?, String?>>> =
                object : TypeReference<MutableList<MutableMap<String?, String?>>>() {
                }
            val allPhotos: MutableList<MutableMap<String?, String?>> = DataStore.getOrCreate("plate", typeRef)
            Log.record("$TAG [DEBUG] guangPanPhoto 数据内容: $allPhotos")
            // 查询今日任务状态
            var str = AntForestRpcCall.ecolifeQueryDish(source, dayPoint)
            var jo = JSONObject(str)
            // 如果请求失败，则记录错误信息并返回
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.record("$TAG.photoGuangPan.ecolifeQueryDish", jo.optString("resultDesc"))
                return
            }
            var photo: MutableMap<String?, String?>? = HashMap()
            val data = jo.optJSONObject("data")
            if (data != null) {
                val beforeMealsImageUrl = data.optString("beforeMealsImageUrl")
                val afterMealsImageUrl = data.optString("afterMealsImageUrl")
                // 如果餐前和餐后照片URL都存在，进行提取
                if (!StringUtil.isEmpty(beforeMealsImageUrl) && !StringUtil.isEmpty(
                        afterMealsImageUrl
                    )
                ) {
                    // 使用正则从URL中提取照片的路径部分
                    val pattern = Pattern.compile("img/(.*)/original")
                    val beforeMatcher = pattern.matcher(beforeMealsImageUrl)
                    val beforeId = if (beforeMatcher.find()) beforeMatcher.group(1) else null
                    val afterMatcher = pattern.matcher(afterMealsImageUrl)
                    val afterId = if (afterMatcher.find()) afterMatcher.group(1) else null
                    // 两个 ID 缺一不可：只有单个 ID 时上传接口必然失败，
                    // 旧实现会把这种残缺条目也塞进缓存，后面随机取到就白跑一次。
                    if (beforeId != null && afterId != null) {
                        val captured = HashMap<String?, String?>()
                        captured["before"] = beforeId
                        captured["after"] = afterId
                        // 同时留一份完整地址与抓取时间，今日完成页据此提供查看入口（见 PlatePhoto）。
                        // 原先只存 ID，用户没法确认到底抓到没有。
                        captured["beforeUrl"] = beforeMealsImageUrl
                        captured["afterUrl"] = afterMealsImageUrl
                        captured["at"] = System.currentTimeMillis().toString()
                        // 避免重复添加相同的照片信息
                        var exists = false
                        for (p in allPhotos) {
                            if (p["before"] == beforeId && p["after"] == afterId) {
                                exists = true
                                break
                            }
                        }
                        if (!exists) {
                            allPhotos.add(captured)
                            put("plate", allPhotos)
                            Log.record("$TAG 已抓取光盘照片: $beforeId / $afterId")
                        }
                    } else {
                        Log.record("$TAG 光盘照片地址无法解析，跳过缓存")
                    }
                }
            }
            if ("SUCCESS" == JsonUtil.getValueByPath(jo, "data.status")) {
                // 服务端认为今天的光盘行动已经完成。最常见的原因是用户自己在支付宝打了卡：
                // 模块只负责补交，看到已完成就不再重复提交。
                // 但这正是用户想确认的事 —— 不记一笔的话，今日完成页会显示「光盘行动 今日无记录」，
                // 反而看不出今天到底完成没有。用当天标志保证只记一次。
                if (!Status.hasFlagToday("EcoLife::plateAlreadyDone")) {
                    recordEcoAction(TITLE_PLATE, "已完成（无需重复提交）", true, null)
                    Status.setFlagToday("EcoLife::plateAlreadyDone")
                }
                return
            }
            if (allPhotos.isEmpty()) {
                if (!Status.hasFlagToday("EcoLife::plateNotify0")) {
                    Log.forest("光盘行动🍛缓存中没有照片数据")
                    // 记一条失败，今日完成页才会显示「光盘行动 失败 + 原因」，
                    // 否则用户只看到没记录，分不清是没跑还是跑了没照片。
                    recordEcoAction(TITLE_PLATE, "", false, "缓存中没有照片数据")
                    Status.setFlagToday("EcoLife::plateNotify0")
                }
                photo = null
            } else {
                photo = allPhotos[RandomUtil.nextInt(0, allPhotos.size)]
            }
            if (photo == null) {
                if (!Status.hasFlagToday("EcoLife::plateNotify1")) {
                    Log.forest("光盘行动🍛请先完成一次光盘打卡")
                    recordEcoAction(TITLE_PLATE, "", false, "请先完成一次光盘打卡")
                    Status.setFlagToday("EcoLife::plateNotify1")
                }
                return
            }
            str = AntForestRpcCall.ecolifeUploadDishImage(
                "BEFORE_MEALS",
                photo["before"],
                0.16571736,
                0.07448776,
                0.7597949,
                dayPoint
            )
            jo = JSONObject(str)
            if (!ResChecker.checkRes(TAG, jo)) {
                return
            }
            str = AntForestRpcCall.ecolifeUploadDishImage(
                "AFTER_MEALS",
                photo["after"],
                0.00040030346,
                0.99891376,
                0.0006858421,
                dayPoint
            )
            jo = JSONObject(str)
            if (!ResChecker.checkRes(TAG, jo)) {
                return
            }
            // 提交任务
            str = AntForestRpcCall.ecolifeTick("photoguangpan", dayPoint, source)
            jo = JSONObject(str)
            // 如果提交失败，记录错误信息并返回
            if (!ResChecker.checkRes(TAG, jo)) {
                return
            }
            // 任务完成，输出完成日志
            val toastMsg = "光盘行动🍛任务完成#" + jo.getJSONObject("data").getString("toastMsg")
            Status.setFlagToday("EcoLife::photoGuangPan")
            Log.forest(toastMsg)
            Toast.show(toastMsg)
            recordEcoAction(TITLE_PLATE, "提交成功", true, null)
        } catch (t: Throwable) {
            // 捕获异常，记录错误信息和堆栈追踪
            Log.record(TAG, "photoGuangPan err:")
            Log.printStackTrace(TAG, t)
        }
    }
}