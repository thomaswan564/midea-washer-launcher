package online.ujing.mini.pay

import android.app.Activity
import android.content.Context
import com.alipay.sdk.app.PayTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 支付宝支付客户端封装（官方 SDK，非复用 U净 的）。
 *
 * U净 的支付链路是「服务端签 orderStr → 客户端唤起支付宝」：
 * ```
 * GET /api/v1/payment/arguments?channel=alipay&orderId=...&payVersion=1
 *   → { payInfo: { orderInfo: "app_id=...&biz_content=...&sign=..." } }
 *   → PayTask.pay(orderInfo)
 * ```
 *
 * 关键事实：orderInfo 自带 U净 商户的 `app_id` 与 RSA2 签名，
 * 支付宝客户端只认订单串本身、不校验发起方 APP —— 所以本 APP 能直接唤起收银台。
 * 本 APP 不经手任何资金，钱始终进 U净 商户账户。
 */
object AlipayLauncher {

    const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

    /** 支付宝 SDK 标准结果码。 */
    object ResultStatus {
        const val SUCCESS = "9000"
        const val PAYING = "8000"                 // 支付中，需再查服务端确认
        const val USER_CANCELED = "6001"          // 用户中途取消
        const val NETWORK_ERROR = "6002"
        const val REPEAT_REQUEST = "5000"
        const val UNKNOWN = "6004"
    }

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(ALIPAY_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /**
     * 唤起支付宝收银台。
     *
     * 官方要求 `PayTask.pay*` 在**非 UI 线程**执行（内部为同步阻塞调用），
     * 故整体切到 [Dispatchers.IO]。
     *
     * @return `resultStatus to memo`；异常时给 [ResultStatus.UNKNOWN]。
     */
    suspend fun pay(activity: Activity, orderInfo: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val task = PayTask(activity)
                val result: Map<String, String> = task.payV2(orderInfo, true)
                result["resultStatus"].orEmpty() to result["memo"].orEmpty()
            }.getOrElse { e ->
                ResultStatus.UNKNOWN to (e.message ?: "唤起支付宝失败")
            }
        }
}
