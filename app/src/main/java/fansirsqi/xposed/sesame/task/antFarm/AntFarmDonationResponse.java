package fansirsqi.xposed.sesame.task.antFarm;

import org.json.JSONObject;

final class AntFarmDonationResponse {
    static String failureMessage(JSONObject response) {
        for (String key : new String[]{"memo", "resultDesc", "resultCode"}) {
            String value = response.optString(key, "");
            if (!value.trim().isEmpty()) return value;
        }
        return "庄园接口返回失败，未提供原因";
    }

    /**
     * 响应是否要求人工完成安全验证。
     *
     * 只认风控专用码。通用业务拒绝码 1009（访问被拒绝）曾在此被判为验证，
     * 导致普通业务失败也会暂停自动任务，而支付宝并不会弹出验证页，用户无从操作。
     */
    static boolean requiresVerification(JSONObject response) {
        return "RPC_VERIFICATION_REQUIRED".equals(response.optString("resultCode"));
    }

    static boolean supportsAutomaticDonation(JSONObject activity) {
        return !"SOLDBY".equals(activity.optString("projectType"));
    }
}
