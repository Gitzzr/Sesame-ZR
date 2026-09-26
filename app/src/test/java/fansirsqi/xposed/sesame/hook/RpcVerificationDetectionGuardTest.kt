package fansirsqi.xposed.sesame.hook

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RPC 桥「风控漏判」修复的源码级护栏。
 *
 * 背景：`NewRpcBridge` 只在响应**既没有 `success` 也没有 `isSuccess` 键**时才 `setError()`，
 * 而 `success:false` 属于预期内的业务失败、不会置 error —— 于是
 * `if (!rpcEntity.getHasError()) return rpcEntity;` 直接提前返回，
 * **风控判定整段被跳过**。
 *
 * 而部分接口恰恰把风控信息放在这类响应里：
 * ```json
 * {"success":false,"resultCode":"RPC_VERIFICATION_REQUIRED","resultDesc":"触发安全验证，请人工验证后继续"}
 * ```
 * 实测后果：4 天内 `alipay.antforest.forest.h5.queryPropList` 被拒 **4941 次**却从未触发统一熔断，
 * 神奇海洋的任务领奖也是同一形态（`AntOcean ... RPC_VERIFICATION_REQUIRED` 连续重试）。
 *
 * 这里把「失败响应也要查风控」钉死，防止回退。
 */
class RpcVerificationDetectionGuardTest {

    private fun source(path: String) = File(path).readText()

    private val newBridge by lazy {
        source("src/main/java/fansirsqi/xposed/sesame/hook/rpc/bridge/NewRpcBridge.java")
    }
    private val oldBridge by lazy {
        source("src/main/java/fansirsqi/xposed/sesame/hook/rpc/bridge/OldRpcBridge.java")
    }

    @Test
    fun `新版桥在业务失败响应上也会检查风控`() {
        assertTrue(
            "success:false 的分支必须调用风控检查，否则整段判定会被 hasError 提前返回跳过",
            newBridge.contains("checkVerificationInFailedResponse")
        )
        assertTrue(
            "失败判定的入口应挂在「存在 success 键但值不是成功」的分支上",
            newBridge.contains("else if (!isSuccessResponse(obj))")
        )
    }

    @Test
    fun `新版桥同时认 error 与 resultCode 两套字段`() {
        assertTrue(newBridge.contains("requiresVerificationIn"))
        listOf("error", "errorMessage", "resultCode", "resultDesc").forEach { field ->
            assertTrue(
                "必须读取 $field 字段：只认 error/errorMessage 会漏掉 resultCode 形态的风控响应",
                newBridge.contains("readStringField(responseObject, \"$field\")")
            )
        }
    }

    @Test
    fun `字段读取对缺失与异常是安全的`() {
        assertTrue(
            "读字段前应先 containsKey，缺失时返回 null 而不是抛异常",
            newBridge.contains("private static String readStringField")
        )
        assertTrue(
            "无法判定为失败时应视为成功，避免把正常响应误判成需要熔断",
            newBridge.contains("private static boolean isSuccessResponse")
        )
    }

    @Test
    fun `旧版桥同样兼容 resultCode 字段`() {
        assertTrue(
            "旧版桥（newRpc=false 时启用）也应兼容 resultCode/resultDesc",
            oldBridge.contains("optString(\"resultCode\"")
        )
        assertTrue(oldBridge.contains("optString(\"resultDesc\""))
    }
}
