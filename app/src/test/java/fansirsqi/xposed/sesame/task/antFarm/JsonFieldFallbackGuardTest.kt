package fansirsqi.xposed.sesame.task.antFarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「服务端字段缺失」类回归护栏（源码文本断言）。
 *
 * 背景：2026-09-23 ~ 09-26 日志里稳定存在 80 条 `org.json.JSONException: No value for ...`：
 * - `memo`（`AntFarm.useFarmTool` / `listOrnaments` 等 20 处）
 * - `activityNo`、`signInStatus`、`signUpStatus`（`AntMember` 的商家打卡签到 / 报名）
 *
 * 支付宝新版接口已不再返回这些字段，而代码用 `getString()` 硬读 —— 字段一缺就抛异常刷堆栈。
 * `memo` 仅用于日志与「是否 SUCCESS」判定，缺失时 `optString` 返回空串，语义与原逻辑一致
 * （空串 != "SUCCESS"），因此统一改用 `optString` 是安全的。
 *
 * 另外 `kmdkSignUp` 里 `activityNo.split(...)[2]` 在下标越界时也会抛异常，已补长度校验。
 */
class JsonFieldFallbackGuardTest {

    private fun codeOnly(path: String): String =
        File(path).readText().lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")

    private val antFarm by lazy {
        codeOnly("src/main/java/fansirsqi/xposed/sesame/task/antFarm/AntFarm.kt")
    }
    private val antMember by lazy {
        codeOnly("src/main/java/fansirsqi/xposed/sesame/task/antMember/AntMember.kt")
    }

    @Test
    fun `蚂蚁庄园不再硬读 memo 字段`() {
        assertFalse(
            "AntFarm 里不得再用 getString(\"memo\")：服务端已不返回该字段，缺失即抛 JSONException",
            antFarm.contains("getString(\"memo\")")
        )
        assertTrue("应改用 optString(\"memo\") 兜底", antFarm.contains("optString(\"memo\")"))
    }

    @Test
    fun `商家打卡不再硬读签到相关字段`() {
        listOf("signInStatus", "activityNo", "signUpStatus").forEach { field ->
            assertFalse(
                "AntMember 不得再用 getString(\"$field\")",
                antMember.contains("getString(\"$field\")")
            )
        }
    }

    @Test
    fun `商家报名对 activityNo 做了拆分长度校验`() {
        assertTrue(
            "activityNo 拆分后取下标前必须校验长度，否则字段缺失时会越界",
            antMember.contains("activityNoParts.size < 3")
        )
    }
}
