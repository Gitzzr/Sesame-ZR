package fansirsqi.xposed.sesame.hook

import android.annotation.SuppressLint
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import fansirsqi.xposed.sesame.BuildConfig
import fansirsqi.xposed.sesame.data.Config
import fansirsqi.xposed.sesame.data.General
import fansirsqi.xposed.sesame.data.Status
import fansirsqi.xposed.sesame.data.Status.Companion.load
import fansirsqi.xposed.sesame.data.Status.Companion.save
import fansirsqi.xposed.sesame.entity.AlipayVersion
import fansirsqi.xposed.sesame.hook.Toast.show
import fansirsqi.xposed.sesame.hook.TokenHooker.start
import fansirsqi.xposed.sesame.hook.XposedEnv.processName
import fansirsqi.xposed.sesame.hook.internal.AlipayMiniMarkHelper
import fansirsqi.xposed.sesame.hook.internal.LocationHelper
import fansirsqi.xposed.sesame.hook.internal.AuthCodeHelper
import fansirsqi.xposed.sesame.hook.internal.SecurityBodyHelper
import fansirsqi.xposed.sesame.hook.keepalive.SmartSchedulerManager
import fansirsqi.xposed.sesame.hook.keepalive.SmartSchedulerManager.cleanup
import fansirsqi.xposed.sesame.hook.keepalive.SmartSchedulerManager.schedule
import fansirsqi.xposed.sesame.hook.modern.ModernXposedRuntime
import fansirsqi.xposed.sesame.hook.modern.ReflectionHelper
import fansirsqi.xposed.sesame.hook.rpc.bridge.NewRpcBridge
import fansirsqi.xposed.sesame.hook.rpc.bridge.OldRpcBridge
import fansirsqi.xposed.sesame.hook.rpc.bridge.RpcBridge
import fansirsqi.xposed.sesame.hook.rpc.bridge.RpcVersion
import fansirsqi.xposed.sesame.hook.rpc.debug.DebugRpc
import fansirsqi.xposed.sesame.hook.rpc.intervallimit.RpcIntervalLimit.clearIntervalLimit
import fansirsqi.xposed.sesame.hook.server.ModuleHttpServerManager.startIfNeeded
import fansirsqi.xposed.sesame.model.BaseModel.Companion.batteryPerm
import fansirsqi.xposed.sesame.model.BaseModel.Companion.checkInterval
import fansirsqi.xposed.sesame.model.BaseModel.Companion.debugMode
import fansirsqi.xposed.sesame.model.BaseModel.Companion.destroyData
import fansirsqi.xposed.sesame.model.BaseModel.Companion.execAtTimeList
import fansirsqi.xposed.sesame.model.BaseModel.Companion.newRpc
import fansirsqi.xposed.sesame.model.BaseModel.Companion.sendHookData
import fansirsqi.xposed.sesame.model.BaseModel.Companion.sendHookDataUrl
import fansirsqi.xposed.sesame.model.BaseModel.Companion.wakenAtTimeList
import fansirsqi.xposed.sesame.model.Model
import fansirsqi.xposed.sesame.task.CoroutineTaskRunner
import fansirsqi.xposed.sesame.task.MainTask
import fansirsqi.xposed.sesame.task.ModelTask.Companion.stopAllTaskAndJoin
import fansirsqi.xposed.sesame.task.antForest.AntForest
import fansirsqi.xposed.sesame.task.customTasks.CustomTask
import fansirsqi.xposed.sesame.task.customTasks.ManualTask
import fansirsqi.xposed.sesame.task.customTasks.ManualTaskModel
import fansirsqi.xposed.sesame.util.AssetUtil.checkerDestFile
import fansirsqi.xposed.sesame.util.AssetUtil.copyStorageSoFileToPrivateDir
import fansirsqi.xposed.sesame.util.AssetUtil.dexkitDestFile
import fansirsqi.xposed.sesame.util.DataStore.init
import fansirsqi.xposed.sesame.util.Detector
import fansirsqi.xposed.sesame.util.Detector.loadLibrary
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.GlobalThreadPools.execute
import fansirsqi.xposed.sesame.util.GlobalThreadPools.shutdownAndRestart
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.Log.error
import fansirsqi.xposed.sesame.util.Log.printStackTrace
import fansirsqi.xposed.sesame.util.Log.record
import fansirsqi.xposed.sesame.util.Notify
import fansirsqi.xposed.sesame.util.Notify.stop
import fansirsqi.xposed.sesame.util.Notify.updateStatusText
import fansirsqi.xposed.sesame.util.PermissionUtil
import fansirsqi.xposed.sesame.util.PermissionUtil.checkBatteryPermissions
import fansirsqi.xposed.sesame.util.StatusManager.updateStatus
import fansirsqi.xposed.sesame.util.TimeUtil
import fansirsqi.xposed.sesame.util.maps.UserMap
import fansirsqi.xposed.sesame.util.maps.UserMap.currentUid
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.luckypray.dexkit.DexKitBridge
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.AutoCloseable
import java.util.Calendar
import kotlin.concurrent.Volatile

class ApplicationHook {
    var xposedInterface: XposedInterface? = null

    internal object BroadcastActions {
        const val RESTART: String = "com.eg.android.AlipayGphone.sesame.restart"
        const val RE_LOGIN: String = "com.eg.android.AlipayGphone.sesame.reLogin"
        const val RESUME_VERIFIED: String = "com.eg.android.AlipayGphone.sesame.resumeVerified"

        /** 用户明确选择「跳过安全验证」时恢复自动任务，给误判场景留一条自救通路。 */
        const val SKIP_VERIFICATION: String = "com.eg.android.AlipayGphone.sesame.skipVerification"
        const val STATUS: String = "com.eg.android.AlipayGphone.sesame.status"
        const val RPC_TEST: String = "com.eg.android.AlipayGphone.sesame.rpctest"
        const val MANUAL_TASK: String = "com.eg.android.AlipayGphone.sesame.manual_task"

        /** 广播载荷：目标账号。 */
        const val EXTRA_USER_ID: String = "userId"

        /** 广播载荷：一次性验证令牌，仅用于「已验证」入口防重放。 */
        const val EXTRA_VERIFICATION_TOKEN: String = "verificationToken"
    }

    private object AlipayClasses {
        const val APPLICATION: String = "com.alipay.mobile.framework.AlipayApplication"
        const val SOCIAL_SDK: String = "com.alipay.mobile.personalbase.service.SocialSdkContactService"
        const val LAUNCHER_ACTIVITY: String = "com.alipay.mobile.quinox.LauncherActivity"
        const val SERVICE: String = "android.app.Service"
        const val LOADED_APK: String = "android.app.LoadedApk"
    }

    private class TaskLock : AutoCloseable {
        private val acquired: Boolean

        init {
            synchronized(taskLock) {
                if (isTaskRunning) {
                    acquired = false
                    throw IllegalStateException("任务已在运行中")
                }
                isTaskRunning = true
                acquired = true
            }
        }

        override fun close() {
            if (acquired) {
                synchronized(taskLock) {
                    isTaskRunning = false
                }
            }
        }
    }

    // --- 入口方法 ---
    fun loadPackage(lpparam: PackageReadyParam) {
        if (General.PACKAGE_NAME != lpparam.packageName) return
        handleHookLogic(
            lpparam.classLoader,
            lpparam.packageName,
            lpparam.applicationInfo.sourceDir
        )
    }

    @SuppressLint("PrivateApi")
    private fun handleHookLogic(loader: ClassLoader?, packageName: String, apkPath: String) {
        classLoader = loader
        finalProcessName = processName
        if (!shouldHookProcess()) return

        init(Files.CONFIG_DIR)
        if (isHooked) return
        isHooked = true

        // 3. 基础环境 Hook
        updateStatus(ModernXposedRuntime.frameworkName, packageName)
        VersionHook.installHook(classLoader)
        initReflection(classLoader!!)

        // 4. 核心生命周期 Hook
        hookApplicationAttach(packageName)
        hookLauncherResume()
        hookServiceLifecycle(apkPath)

        HookUtil.hookOtherService(classLoader!!)
    }

    private fun shouldHookProcess(): Boolean {
        val isMainProcess = General.PACKAGE_NAME == finalProcessName
        return isMainProcess
//            record(TAG, "跳过辅助进程: $finalProcessName")
    }

    private fun initReflection(loader: ClassLoader) {
        try {
            ReflectionHelper.findClass(AlipayClasses.APPLICATION, loader)
            ReflectionHelper.findClass(AlipayClasses.SOCIAL_SDK, loader)
        } catch (_: Throwable) {
            // ignore
        }

        try {
            @SuppressLint("PrivateApi") val loadedApkClass = loader.loadClass(AlipayClasses.LOADED_APK)
            deoptimizeClass(loadedApkClass)
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun hookApplicationAttach(packageName: String?) {
        try {
            val attachMethod = ReflectionHelper.findMethodExact(
                Application::class.java,
                "attach",
                Context::class.java
            )
            ModernXposedRuntime.hook(attachMethod, after = { invocation ->
                appContext = invocation.args[0] as Context?
                mainHandler = Handler(Looper.getMainLooper())
                Log.init(appContext!!)
                ensureScheduler()

                SecurityBodyHelper.init(classLoader!!)
                AlipayMiniMarkHelper.init(classLoader!!)
                LocationHelper.init(classLoader!!)
                AuthCodeHelper.init(classLoader!!)
                AuthCodeHelper.getAuthCode("2021005114632037")

                initVersionInfo(packageName)
                loadLibs()
                if (VersionHook.hasVersion() && alipayVersion.compareTo(AlipayVersion("10.7.26.8100")) == 0) {
                    HookUtil.fuckAccounLimit(classLoader!!)
                }
            })
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "Hook attach failed", e)
        }
    }

    private fun hookLauncherResume() {
        try {
            val launcherClass = ReflectionHelper.findClass(AlipayClasses.LAUNCHER_ACTIVITY, classLoader)
            val onResumeMethod = ReflectionHelper.findMethodExact(launcherClass, "onResume")
            ModernXposedRuntime.hook(onResumeMethod, after = { invocation ->
                (invocation.thisObject as? android.app.Activity)?.let { activity ->
                    activity.window.decorView.post {
                        RequestManager.showVerificationResumeDialog(activity)
                    }
                }
                val targetUid = HookUtil.getUserId(classLoader!!)
                if (targetUid == null) {
                    show("用户未登录")
                    return@hook
                }
                if (!init) {
                    if (initHandler()) init = true
                    return@hook
                }
                val currentUid = currentUid
                if (targetUid != currentUid) {
                    if (currentUid != null) {
                        initHandler()
                        lastExecTime = 0
                        show("用户已切换")
                        return@hook
                    }
                    HookUtil.hookUser(classLoader!!)
                }
            })
        } catch (t: Throwable) {
            printStackTrace(TAG, "Hook Launcher failed", t)
        }
    }

    private fun hookServiceLifecycle(apkPath: String) {
        try {
            val serviceClass = ReflectionHelper.findClass(AlipayClasses.SERVICE, classLoader)
            val onCreateMethod = ReflectionHelper.findMethodExact(serviceClass, "onCreate")
            ModernXposedRuntime.hook(onCreateMethod, after = { invocation ->
                    val appService = invocation.thisObject as Service
                    if (General.CURRENT_USING_SERVICE != appService.javaClass.getCanonicalName()) {
                        return@hook
                    }

                    service = appService
                    appContext = appService.applicationContext
                    ensureScheduler()

                    if (Detector.isLegitimateEnvironment(appContext!!)) {
                        Detector.dangerous(appContext!!)
                        return@hook
                    }

                    DexKitBridge.create(apkPath).use { _ ->
                        record(TAG, "Hook DexKit successfully")
                    }
                    mainTask = MainTask("主任务") { runMainTaskLogic() }
                    dayCalendar = Calendar.getInstance()
                    if (initHandler()) {
                        init = true
                    }
            })

            val onDestroyMethod = ReflectionHelper.findMethodExact(serviceClass, "onDestroy")
            ModernXposedRuntime.hook(onDestroyMethod, after = { invocation ->
                    val s = invocation.thisObject as Service
                    if (General.CURRENT_USING_SERVICE == s.javaClass.getCanonicalName()) {
                        updateStatusText("目标应用前台服务被销毁")
                        destroyHandler()
                        restartByBroadcast()
                    }
            })
        } catch (t: Throwable) {
            printStackTrace(TAG, "Hook Service failed", t)
        }
    }

    private fun initVersionInfo(packageName: String?) {
        if (VersionHook.hasVersion()) {
            alipayVersion = VersionHook.getCapturedVersion() ?: AlipayVersion("")
            record(TAG, "📦 目标应用版本(Hook): $alipayVersion")
        } else {
            try {
                val pInfo: PackageInfo = appContext!!.packageManager.getPackageInfo(packageName!!, 0)
                alipayVersion = AlipayVersion(pInfo.versionName.toString())
            } catch (_: Exception) {
                alipayVersion = AlipayVersion("")
            }
        }
    }

    private fun loadLibs() {
        loadNativeLibs(appContext!!, checkerDestFile)
        loadNativeLibs(appContext!!, dexkitDestFile)
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun loadNativeLibs(context: Context, soFile: File) {
        try {
            val finalSoFile = copyStorageSoFileToPrivateDir(context, soFile)
            if (finalSoFile != null) {
                System.load(finalSoFile.absolutePath)
            } else {
                loadLibrary(soFile.getName().replace(".so", "").replace("lib", ""))
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "载入so库失败: " + soFile.getName(), e)
        }
    }

    // --- 广播接收器 ---
    internal class AlipayBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val action = intent.action ?: return

            if (finalProcessName != null && finalProcessName!!.endsWith(":widgetProvider")) {
                return  // 忽略小组件进程
            }

            when (action) {
                BroadcastActions.RESTART -> execute(Runnable {
                    val targetUserId = intent.getStringExtra("userId")
                    val currentUserId = HookUtil.getUserId(classLoader!!)
                    if (targetUserId != null && targetUserId != currentUserId) {
                        record(TAG, "忽略非当前用户的重启广播: target=$targetUserId, current=$currentUserId")
                        return@Runnable
                    }
                    initHandler()
                })

                BroadcastActions.RE_LOGIN -> reOpenApp()
                BroadcastActions.RESUME_VERIFIED -> execute {
                    if (RequestManager.resumeAfterManualVerification(intent)) {
                        execHandler()
                    }
                }
                BroadcastActions.SKIP_VERIFICATION -> execute {
                    if (RequestManager.forceResumeAfterVerification(intent)) {
                        execHandler()
                    }
                }
                BroadcastActions.RPC_TEST -> handleRpcTest(intent)
                BroadcastActions.MANUAL_TASK -> {
                    record(TAG, "🚀 收到手动庄园任务指令")
                    execute {
                        val taskName = intent.getStringExtra("task")
                        if (taskName != null) {
                            val normalizedTaskName = taskName.replace("+", "_")
                            try {
                                val task = CustomTask.valueOf(normalizedTaskName)
                                val extraParams = HashMap<String, Any>()
                                when (task) {
                                    CustomTask.FOREST_WHACK_MOLE -> {
                                        extraParams["whackMoleMode"] = intent.getIntExtra("whackMoleMode", 1)
                                        extraParams["whackMoleGames"] = intent.getIntExtra("whackMoleGames", 5)
                                    }

                                    CustomTask.FOREST_ENERGY_RAIN -> {
                                        extraParams["exchangeEnergyRainCard"] = intent.getBooleanExtra("exchangeEnergyRainCard", false)
                                    }

                                    CustomTask.FARM_SPECIAL_FOOD -> {
                                        extraParams["specialFoodCount"] = intent.getIntExtra("specialFoodCount", 0)
                                    }

                                    CustomTask.FARM_USE_TOOL -> {
                                        extraParams["toolType"] = intent.getStringExtra("toolType") ?: ""
                                        extraParams["toolCount"] = intent.getIntExtra("toolCount", 1)
                                    }

                                    else -> {
                                        record(TAG, "❌ 无效的任务指令: $taskName")
                                    }
                                }
                                ManualTask.runSingle(task, extraParams)
                            } catch (e: Exception) {
                                record(TAG, "❌ 无效的任务指令: $taskName -> ${e.message}")
                            }
                        } else {
                            for (model in Model.modelArray) {
                                if (model is ManualTaskModel) {
                                    model.startTask(true, 1)
                                    break
                                }
                            }
                        }
                    }
                }
            }
        }

        private fun handleRpcTest(intent: Intent) {
            execute({
                record(TAG, "RPC测试: $intent")
                try {
                    val rpc = DebugRpc()
                    rpc.start(
                        intent.getStringExtra("method"),
                        intent.getStringExtra("data"),
                        intent.getStringExtra("type")
                    )
                } catch (_: Throwable) { /* ignore */
                }
            })
        }
    }

    companion object {
        const val TAG: String = "ApplicationHook" // 简化TAG
        var finalProcessName: String? = ""

        // 广播接收器实例，用于注销。按发送方信任边界拆成两个（见 registerBroadcastReceiver）。
        private var mCommandReceiver: AlipayBroadcastReceiver? = null
        private var mRescueReceiver: AlipayBroadcastReceiver? = null

        @JvmField
        var classLoader: ClassLoader? = null

        @JvmField
        @get:JvmStatic
        @Volatile
        var appContext: Context? = null

        // 任务锁
        private val taskLock = Any()

        @Volatile
        private var isTaskRunning = false

        @JvmStatic
        var alipayVersion: AlipayVersion = AlipayVersion("")

        @get:JvmStatic
        @Volatile
        var isHooked: Boolean = false
            private set

        @Volatile
        private var init = false

        @Volatile
        var dayCalendar: Calendar?

        @JvmField
        @Volatile
        var offline: Boolean = false

        @JvmStatic
        fun setOffline(value: Boolean) {
            offline = value
        }

        @Volatile
        private var batteryPermissionChecked = false

        @SuppressLint("StaticFieldLeak")
        var service: Service? = null

        var mainHandler: Handler? = null

        var mainTask: MainTask? = null

        @Volatile
        var rpcBridge: RpcBridge? = null
        private val rpcBridgeLock = Any()

        private var rpcVersion: RpcVersion? = null

        @Volatile
        var lastExecTime: Long = 0

        @Volatile
        var nextExecutionTime: Long = 0
        private const val MAX_INACTIVE_TIME: Long = 3600000 // 1小时

        init {
            dayCalendar = Calendar.getInstance()
            resetToMidnight(dayCalendar!!)
        }

        private suspend fun runMainTaskLogic() {
            try {
                TaskLock().use { _ ->
                    if (!init || !Config.isLoaded()) return
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastExecTime < 2000) {
                        record(TAG, "⚠️ 间隔过短，跳过")
                        schedule(checkInterval.value.toLong(), "间隔重试") {
                            execHandler()
                        }
                        return
                    }

                    val currentUid = currentUid
                    val targetUid = HookUtil.getUserId(classLoader!!)
                    if (targetUid == null || targetUid != currentUid) {
                        reOpenApp()
                        return
                    }

                    lastExecTime = currentTime
                    CoroutineTaskRunner(Model.modelArray.toList()).run()
                }
            } catch (e: IllegalStateException) {
                record(TAG, "⚠️ " + e.message)
            } catch (e: Exception) {
                Log.printStackTrace(TAG, e)
            }
        }

        // --- 辅助方法 ---
        private fun ensureScheduler() {
            if (appContext != null) {
                SmartSchedulerManager.initialize(appContext!!)
            }
        }

        fun deoptimizeClass(c: Class<*>) {
            for (m in c.getDeclaredMethods()) {
                if (m.name == "makeApplicationInner") {
                    ModernXposedRuntime.deoptimize(m)
                }
            }
        }


        fun scheduleNextExecutionInternal(lastTime: Long) {
            try {
                checkInactiveTime()
                val checkInterval = checkInterval.value
                val execAtTimeList = execAtTimeList.value
                if (execAtTimeList != null && execAtTimeList.contains("-1")) {
                    record(TAG, "定时执行未开启")
                    return
                }
                var delayMillis = checkInterval.toLong()
                var targetTime: Long = 0
                if (execAtTimeList != null) {
                    val lastCal = TimeUtil.getCalendarByTimeMillis(lastTime)
                    val nextCal = TimeUtil.getCalendarByTimeMillis(lastTime + checkInterval)
                    for (timeStr in execAtTimeList) {
                        val execCal = TimeUtil.getTodayCalendarByTimeStr(timeStr)
                        if (execCal != null && lastCal < execCal && nextCal > execCal) {
                            record(TAG, "设置定时执行:$timeStr")
                            targetTime = execCal.getTimeInMillis()
                            delayMillis = targetTime - lastTime
                            break
                        }
                    }
                }
                nextExecutionTime = if (targetTime > 0) targetTime else (lastTime + delayMillis)
                ensureScheduler()
                schedule(delayMillis, "轮询任务") {
                    execHandler()
                }
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "scheduleNextExecution failed", e)
            }
        }

        // --- 初始化核心逻辑 ---
        @Synchronized
        private fun initHandler(): Boolean {
            try {
                if (init) destroyHandler()

                // 调试模式初始化
                if (BuildConfig.DEBUG) {
                    try {
                        startIfNeeded(8080, "ET3vB^#td87sQqKaY*eMUJXP", processName, General.PACKAGE_NAME)
                        registerBroadcastReceiver(appContext!!)
                    } catch (_: Throwable) { /* ignore */
                    }
                }

                ensureScheduler()
                Model.initAllModel()

                if (service == null) return false
                val userId = HookUtil.getUserId(classLoader!!)
                if (userId == null) {
                    show("用户未登录")
                    return false
                }

                HookUtil.hookUser(classLoader!!)
                record(TAG, "芝麻粒-TK 开始初始化...")

                Config.load(userId)
                if (!Config.isLoaded()) return false

                Notify.start(service!!)
                setWakenAtTimeAlarm()

                synchronized(rpcBridgeLock) {
                    rpcBridge = if (newRpc.value) NewRpcBridge() else OldRpcBridge()
                    rpcBridge!!.load()
                    rpcVersion = rpcBridge!!.getVersion()
                }

                if (newRpc.value && debugMode.value) {
                    HookUtil.hookRpcBridgeExtension(classLoader!!, sendHookData.value, sendHookDataUrl.value)
                    HookUtil.hookDefaultBridgeCallback(classLoader!!)
                }

                start(userId)
                checkBatteryPermission()

                Model.bootAllModel(classLoader)
                load(userId)
                updateDay()

                val successMsg = "Loaded Sesame-ZR " + BuildConfig.VERSION_NAME + "✨"
                record(successMsg)
                show(successMsg)

                offline = false
                RequestManager.onRpcBridgeReady()
                init = true
                execHandler()
                return true
            } catch (th: Throwable) {
                printStackTrace(TAG, "startHandler", th)
                return false
            }
        }

        private fun checkBatteryPermission() {
            if (!batteryPerm.value || batteryPermissionChecked) return

            val hasPermission = checkBatteryPermissions(appContext)
            batteryPermissionChecked = true
            if (!hasPermission) {
                record(TAG, "无后台运行权限，2秒后申请")
                mainHandler!!.postDelayed({
                    if (!PermissionUtil.checkOrRequestBatteryPermissions(appContext!!)) {
                        show("请授予目标应用始终在后台运行权限")
                    }
                }, 2000)
            }
        }

        @Synchronized
        fun destroyHandler() {
            try {
                stopHandler()
                shutdownAndRestart()

                if (service != null) {
                    destroyData()
                    Status.unload()
                    stop()
                    clearIntervalLimit()
                    Config.unload()
                    UserMap.unload()
                }

                cleanup()

                // 注销广播接收器
                unregisterBroadcastReceiver(appContext)

                synchronized(rpcBridgeLock) {
                    if (rpcBridge != null) {
                        rpcVersion = null
                        rpcBridge!!.unload()
                        rpcBridge = null
                    }
                }
            } catch (th: Throwable) {
                printStackTrace(TAG, "stopHandler err:", th)
            }
        }

        fun execHandler() {
            if (mainTask != null) mainTask!!.startTask(false)
        }

        private fun stopHandler() {
            runBlocking {
                mainTask?.stopTaskAndJoin()
                stopAllTaskAndJoin()
            }
        }

        // --- 杂项方法 ---
        private fun checkInactiveTime() {
            if (lastExecTime == 0L) return
            val inactiveTime: Long = System.currentTimeMillis() - lastExecTime
            if (inactiveTime > MAX_INACTIVE_TIME) {
                record(TAG, "⚠️ 检测到长时间未执行(" + inactiveTime / 60000 + "m)，重新登录")
                reOpenApp()
            }
        }

        fun updateDay() {
            val now = Calendar.getInstance()
            if (dayCalendar == null || dayCalendar!!.get(Calendar.DAY_OF_MONTH) != now.get(Calendar.DAY_OF_MONTH)) {
                dayCalendar = now.clone() as Calendar
                resetToMidnight(dayCalendar!!)
                record(TAG, "日期更新")
                setWakenAtTimeAlarm()
            }
            try {
                save(now)
            } catch (_: Exception) {
            }
        }

        private fun resetToMidnight(calendar: Calendar) {
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
        }

        fun sendBroadcast(action: String?) {
            if (appContext != null) appContext!!.sendBroadcast(Intent(action))
        }

        fun sendBroadcastShell(api: String?, message: String?) {
            if (appContext == null) return
            val intent = Intent("fansirsqi.xposed.sesame.SHELL")
            intent.putExtra(api, message)
            appContext!!.sendBroadcast(intent, null)
        }

        @JvmStatic
        fun reLoginByBroadcast() {
            sendBroadcast(BroadcastActions.RE_LOGIN)
        }

        fun restartByBroadcast() {
            sendBroadcast(BroadcastActions.RESTART)
        }

        fun reOpenApp() {
            if (RequestManager.isVerificationPaused()) return
            ensureScheduler()
            schedule(20000L, "重新登录") {
                if (RequestManager.isVerificationPaused()) return@schedule
                try {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.setClassName(General.PACKAGE_NAME, General.CURRENT_USING_ACTIVITY)
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    offline = true
                    if (appContext != null) appContext!!.startActivity(intent)
                } catch (e: Exception) {
                    error(TAG, "重启Activity失败: " + e.message)
                }
            }
        }

        // --- 定时唤醒 ---
        private fun setWakenAtTimeAlarm() {
            if (appContext == null) return
            ensureScheduler()

            val wakenAtTimeList = wakenAtTimeList.value
            if (wakenAtTimeList != null && wakenAtTimeList.contains("-1")) return

            // 1. 每日0点
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            resetToMidnight(calendar)
            val delayToMidnight = calendar.getTimeInMillis() - System.currentTimeMillis()

            if (delayToMidnight > 0) {
                schedule(delayToMidnight, "每日0点任务") {
                    record(TAG, "⏰ 0点任务触发")
                    updateDay()
                    execHandler()
                    setWakenAtTimeAlarm() // 递归设置明天
                }
            }

            // 2. 自定义时间
            if (wakenAtTimeList != null) {
                val now = Calendar.getInstance()
                for (timeStr in wakenAtTimeList) {
                    try {
                        val target = TimeUtil.getTodayCalendarByTimeStr(timeStr)
                        if (target != null && target > now) {
                            val delay = target.getTimeInMillis() - System.currentTimeMillis()
                            schedule(delay, "自定义: $timeStr") {
                                record(TAG, "⏰ 自定义触发: $timeStr")
                                execHandler()
                            }
                        }
                    } catch (_: Exception) { /* ignore */
                    }
                }
            }
        }

        fun registerBroadcastReceiver(context: Context) {
            if (mCommandReceiver != null) return  // 防止重复注册

            try {
                // 命令通道：模块 App（fansirsqi.xposed.sesame）在自己的进程里下发
                // 「手动任务 / 重启 / 调试」等指令，跨进程投递**必须导出**。
                // 动作本身仍需用户在界面上显式触发。
                val commandReceiver = AlipayBroadcastReceiver()
                mCommandReceiver = commandReceiver
                val commandFilter = IntentFilter().apply {
                    addAction(BroadcastActions.RESTART)
                    addAction(BroadcastActions.STATUS)
                    addAction(BroadcastActions.RPC_TEST)
                    addAction(BroadcastActions.MANUAL_TASK)
                }
                registerDynamicReceiver(context, commandReceiver, commandFilter, exported = true)

                // 自救通道：只承载「恢复安全验证暂停 / 重新登录」，发送方**只有宿主自身**
                // （通知的 PendingIntent、宿主对话框，以及 RPC 桥内部的 reLoginByBroadcast），
                // 因此不需要导出 —— 外部 App 伪造的恢复广播会被系统直接丢弃。
                val rescueReceiver = AlipayBroadcastReceiver()
                mRescueReceiver = rescueReceiver
                val rescueFilter = IntentFilter().apply {
                    addAction(BroadcastActions.RESUME_VERIFIED)
                    addAction(BroadcastActions.SKIP_VERIFICATION)
                    addAction(BroadcastActions.RE_LOGIN)
                }
                // ⚠️ 12L 及以下不能用 NOT_EXPORTED：ContextCompat 在 API < 33 上会要求本应用
                // manifest 声明 `<包名>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，否则直接抛异常。
                // 而这里是注册在宿主（支付宝）进程里的，宿主 APK 已原样签名、无法新增权限声明，
                // 强行使用会让整个接收器注册失败 —— Android 8.0–12L 上曾因此彻底收不到任何广播。
                // 故仅 13+ 使用原生 NOT_EXPORTED，12L 及以下退化为导出，由 RequestManager
                // 的令牌校验兜底（令牌是随机 UUID 且只存在宿主私有存储，外部应用无从获知）。
                registerDynamicReceiver(
                    context,
                    rescueReceiver,
                    rescueFilter,
                    exported = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                )

                record(TAG, "BroadcastReceiver registered")
            } catch (th: Throwable) {
                unregisterBroadcastReceiver(context)
                printStackTrace(TAG, "Register Receiver failed", th)
            }
        }

        /**
         * 注册动态广播接收器。
         *
         * [exported] 为 false 时使用 Android 13 引入的 `RECEIVER_NOT_EXPORTED`；
         * 该能力在 12L 及以下没有等价实现，调用前请确认已了解其限制。
         */
        private fun registerDynamicReceiver(
            context: Context,
            receiver: BroadcastReceiver,
            filter: IntentFilter,
            exported: Boolean
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    receiver,
                    filter,
                    if (exported) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                )
            }
        }

        fun unregisterBroadcastReceiver(context: Context?) {
            if (context == null) return
            var unregistered = false
            for (receiver in listOfNotNull(mCommandReceiver, mRescueReceiver)) {
                try {
                    context.unregisterReceiver(receiver)
                    unregistered = true
                } catch (_: Throwable) {
                    // ignore: receiver not registered
                }
            }
            mCommandReceiver = null
            mRescueReceiver = null
            if (unregistered) record(TAG, "BroadcastReceiver unregistered")
        }
    }
}
