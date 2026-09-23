package fansirsqi.xposed.sesame.model

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [AccountPreset] 的写入前置条件测试。
 *
 * 背景：本功能的入口在**主界面**（`MainActivity` → `SettingsContent`）的设置页里，
 * 而 `Model.initAllModel()` 只被 `ApplicationHook`（支付宝进程）与三个设置 Activity 调用过 ——
 * 模块自己的进程从主界面进入时，模型注册表是空的。
 * 此时若直接走 `Config.load` / `Config.save`，最坏情况是
 * `Config.setModelFieldsMap` 拿到空注册表，把**空配置覆盖到账号的 `config_v2.json`** 上，
 * 等于清空用户配置。因此「先初始化注册表、且初始化失败就中止」是硬性前提。
 *
 * 沿用仓库既有惯例（读源码文本做断言）——`AccountPreset` 依赖 Android 运行时，
 * 无法在 JVM 单测里直接执行 `apply()`。
 */
class AccountPresetGuardTest {

    private val sourceText: String by lazy {
        File("src/main/java/fansirsqi/xposed/sesame/model/AccountPreset.kt").readText()
    }

    @Test
    fun `应用档位前必须初始化模型注册表`() {
        assertTrue(
            "AccountPreset 必须调用 Model.initAllModel()，否则主界面进程里注册表为空",
            sourceText.contains("Model.initAllModel()"),
        )
        assertTrue(
            "必须存在注册表就绪检查",
            sourceText.contains("Model.getModelConfigMap().isNotEmpty()"),
        )
    }

    @Test
    fun `注册表为空时必须中止且不落盘`() {
        assertTrue(
            "必须存在「注册表为空则中止」的守卫",
            sourceText.contains("Model.getModelConfigMap().isEmpty()"),
        )

        val guard = sourceText.indexOf("Model.getModelConfigMap().isEmpty()")
        val initCall = sourceText.indexOf("ensureModelRegistry()")
        val load = sourceText.indexOf("Config.load(targetUid)")
        val save = sourceText.indexOf("Config.save(targetUid, true)")

        assertTrue("ensureModelRegistry 的调用点必须存在", initCall >= 0)
        assertTrue("Config.load(targetUid) 的调用点必须存在", load >= 0)
        assertTrue("Config.save(targetUid, true) 的调用点必须存在", save >= 0)

        assertTrue("必须先初始化注册表，再载入配置", initCall < load)
        assertTrue("必须先做空注册表检查，再落盘", guard < save)
    }

    @Test
    fun `大号档必须把小号从排除名单里移除`() {
        assertTrue(
            "必须存在「当前排除名单 - 小号名单」的清理逻辑",
            sourceText.contains("val remain = current - subList"),
        )
        assertTrue(
            "只有指定了小号名单才清理",
            sourceText.contains("tier == PresetTier.MAIN && subList.isNotEmpty()"),
        )
    }

    @Test
    fun `小号档白名单制必须开开关与收窄名单成对`() {
        assertTrue(
            "必须应用白名单开关",
            sourceText.contains("AccountFriendListPolicy.ALT_WHITELIST_SWITCHES"),
        )
        assertTrue(
            "必须把服务类名单收窄到大号名单",
            sourceText.contains("AccountFriendListPolicy.altListsFilledWithMain()"),
        )
        assertTrue(
            "未指定大号名单时不得开白名单（保守）",
            sourceText.contains("tier == PresetTier.ALT && mainList.isNotEmpty()"),
        )
    }

    @Test
    fun `不再登记主源码中未注册的设置项`() {
        // AntFarm.giftFamilyDrawFragment 的 addField 在 AntFarm.kt 里是注释状态，
        // AntMember.annualReview 整块被注释且功能已下线 —— 登记它们只会让每次切换
        // 都多出一条「跳过」噪声，掩盖真正的字段缺失。
        val policySource = File("src/main/java/fansirsqi/xposed/sesame/model/AccountPresetPolicy.kt").readText()
        assertTrue(
            "giftFamilyDrawFragment 未注册，不应登记到预设表",
            !policySource.contains("\"giftFamilyDrawFragment\""),
        )
        assertTrue(
            "annualReview 已下线，不应登记到预设表",
            !policySource.contains("\"annualReview\""),
        )
    }
}
