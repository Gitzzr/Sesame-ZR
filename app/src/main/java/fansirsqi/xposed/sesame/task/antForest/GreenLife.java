package fansirsqi.xposed.sesame.task.antForest;

import fansirsqi.xposed.sesame.task.DailyTaskLogPolicy;
import fansirsqi.xposed.sesame.util.CoroutineUtils;
import org.json.JSONObject;
import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.util.ResChecker;
public class GreenLife {
    public static final String TAG = GreenLife.class.getSimpleName();
    /** 森林集市 */
    public static void ForestMarket(String sourceType) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.consultForSendEnergyByAction(sourceType));
            if (ResChecker.checkRes(TAG,jo)) {
                JSONObject data = jo.getJSONObject("data");
                if (data.optBoolean("canSendEnergy", false)) {
                    CoroutineUtils.sleepCompat(1000);
                    jo = new JSONObject(AntForestRpcCall.sendEnergyByAction(sourceType));
                    if (ResChecker.checkRes(TAG,jo)) {
                        data = jo.getJSONObject("data");
                        if (data.optBoolean("canSendEnergy", false)) {
                            int receivedEnergyAmount = data.getInt("receivedEnergyAmount");
                            Log.forest("集市逛街🛍[获得:能量" + receivedEnergyAmount + "g]");
                            AntForest.recordForestAction(
                                    DailyTaskLogPolicy.ACTION_TASK, "森林集市",
                                    receivedEnergyAmount + "g", true, null);
                        }
                    } else {
                        // 领取响应没有可读原因时只记失败，不另写文案
                        AntForest.recordForestAction(
                                DailyTaskLogPolicy.ACTION_TASK, "森林集市", sourceType, false, "失败");
                    }
                }
            } else {
                Log.record(TAG, jo.getJSONObject("data").getString("resultCode"));
                AntForest.recordForestAction(
                        DailyTaskLogPolicy.ACTION_TASK, "森林集市", sourceType, false, "失败");
                CoroutineUtils.sleepCompat(300);
            }
        } catch (Throwable t) {
            Log.record(TAG, "sendEnergyByAction err:");
            Log.printStackTrace(TAG, t);
        }
    }
}