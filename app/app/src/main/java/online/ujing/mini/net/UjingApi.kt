package online.ujing.mini.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * U净云端 API 客户端（OkHttp 手写，不引 Retrofit）。
 *
 * 设计要点：
 *  - token 由外部（TokenStore）注入，401 统一抛 [ApiException]。
 *  - 响应兼容两种形态：带 `{code,data}` 包裹，或裸对象/数组（BLE 接口如此）。
 */
class UjingApi {

    var token: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    class ApiException(val code: Int, message: String) : IOException(message)

    // -----------------------------------------------------------------
    // 基础请求
    // -----------------------------------------------------------------

    private fun urlOf(path: String, query: Map<String, String?>): HttpUrl {
        val b = HttpUrl.Builder()
            .scheme("https")
            .host("phoenix.ujing.online")
            .addPathSegments(path.trimStart('/'))
        query.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v) }
        return b.build()
    }

    private suspend fun raw(
        method: String,
        path: String,
        headers: Map<String, String>,
        query: Map<String, String?> = emptyMap(),
        bodyJson: String? = null
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(urlOf(path, query))
        headers.forEach { (k, v) -> builder.header(k, v) }
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((bodyJson ?: "{}").toRequestBody(jsonMedia))
            "DELETE" -> builder.delete()
            else -> error("unsupported method $method")
        }
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (text.isBlank()) {
                if (resp.isSuccessful) return@withContext "{}"
                throw ApiException(resp.code, "HTTP ${resp.code}")
            }
            val code = try {
                (UjingJson.parseToJsonElement(text) as? JsonObject)
                    ?.get("code")?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            } catch (_: Exception) {
                null
            }
            if (code != null && code != 0 && code != 200) {
                val msg = (UjingJson.parseToJsonElement(text) as? JsonObject)
                    ?.get("message")?.let { (it as? JsonPrimitive)?.content }
                    ?: "code=$code"
                throw ApiException(code, msg)
            }
            // `{}` 是服务端的「静默拒绝」形态（HTTP 200、无 code 字段）。
            // 已知触发场景：`ble/data/report/nordic` 带 orderId + type=2 上报时，
            // 若订单已在运行（status=40）或订单号无效，服务端什么都不返回。
            // 这里把它归一化成 code=200 + data=null，由调用方按业务语义判断，
            // 而不是让整条链路以「接口失败」告终。
            if (code == null && !resp.isSuccessful) {
                throw ApiException(resp.code, "HTTP ${resp.code}")
            }
            text
        }
    }

    /**
     * 解析响应：自动识别是否带 `data` 包裹。
     * 无包裹时把整段 JSON 当作 data（BLE 接口的裸数组/裸对象）。
     *
     * 特殊情况：`{}`（空对象、无 `code` 也无 `data`）是服务端的静默拒绝形态，
     * 直接返回 `Envelope(200, null)`，不尝试反序列化成 T——否则会因字段缺失
     * 而落进默认值分支，让"拒绝"看起来像"成功但内容为空"。
     */
    private fun <T> decode(flexible: String, d: KSerializer<T>): Envelope<T> {
        val root: JsonElement = UjingJson.parseToJsonElement(flexible)
        val obj = root as? JsonObject
        if (obj != null && obj.isEmpty()) {
            return Envelope(code = 200, data = null)
        }
        return if (obj != null && obj.containsKey("data")) {
            UjingJson.decodeFromJsonElement(Envelope.serializer(d), root)
        } else {
            Envelope(code = 200, data = UjingJson.decodeFromJsonElement(d, root))
        }
    }

    private suspend fun <T> call(
        method: String,
        path: String,
        headers: Map<String, String>,
        query: Map<String, String?> = emptyMap(),
        bodyJson: String? = null,
        d: KSerializer<T>
    ): Envelope<T> = decode(raw(method, path, headers, query, bodyJson), d)

    private fun biz(): Map<String, String> =
        Headers.bi(token ?: throw ApiException(401, "未登录：token 为空"))

    // -----------------------------------------------------------------
    // 鉴权
    // -----------------------------------------------------------------

    /** 发短信验证码。 */
    suspend fun sendCaptcha(mobile: String): Envelope<JsonElement> = call(
        method = "GET",
        path = "/api/v1/captcha",
        headers = Headers.ziCode(),
        query = mapOf(
            "mobile" to mobile,
            "sessionId" to "AFS_SWITCH_OFF",
            "sig" to "AFS_SWITCH_OFF",
            "token" to "AFS_SWITCH_OFF",
            "type" to "1"
        ),
        d = JsonElement.serializer()
    )

    /** 登录并写入 [token]。 */
    suspend fun login(mobile: String, captcha: String): Envelope<LoginData> {
        val body = UjingJson.encodeToString(LoginRequest.serializer(), LoginRequest(captcha, mobile))
        val env = call("POST", "/api/v1/login", Headers.ziLogin(), bodyJson = body, d = LoginData.serializer())
        env.data?.token?.takeIf { it.isNotBlank() }?.let { token = it }
        return env
    }

    // -----------------------------------------------------------------
    // 设备 / 门店
    // -----------------------------------------------------------------

    /** 扫洗衣机二维码换取设备信息。 */
    suspend fun scanWasherCode(qrCode: String): Envelope<ScanResultWrap> {
        val body = UjingJson.encodeToString(ScanRequest.serializer(), ScanRequest(qrCode))
        return call("POST", "/api/v1/devices/scanWasherCode", biz(), bodyJson = body, d = ScanResultWrap.serializer())
    }

    /** 设备详情（含洗衣模式列表）。 */
    suspend fun programInfo(deviceId: String): Envelope<ProgramInfo> = call(
        "GET", "/api/v1/app/washer/devices/program/info", biz(),
        query = mapOf("deviceId" to deviceId),
        d = ProgramInfo.serializer()
    )

    /** 按下单门店 + 机型拉模式（无 deviceId 时）。 */
    suspend fun programInfoByType(storeId: String, deviceTypeId: Int): Envelope<ProgramInfo> = call(
        "GET", "/api/v1/app/washer/devices/program/info", biz(),
        query = mapOf("storeId" to storeId, "deviceTypeId" to deviceTypeId.toString()),
        d = ProgramInfo.serializer()
    )

    // -----------------------------------------------------------------
    // 订单
    // -----------------------------------------------------------------

    suspend fun createOrder(req: CreateOrderRequest): Envelope<OrderData> {
        val body = UjingJson.encodeToString(CreateOrderRequest.serializer(), req)
        return call("POST", "/api/v1/orders/create", biz(), bodyJson = body, d = OrderData.serializer())
    }

    suspend fun orderDetail(orderId: String, additional: String = "price"): Envelope<OrderData> = call(
        "GET", "/api/v1/orders/$orderId/detail", biz(),
        query = mapOf("additional" to additional),
        d = OrderData.serializer()
    )

    suspend fun cancelOrder(orderId: String): Envelope<JsonElement> {
        val body = """{"orderId":"$orderId"}"""
        return call("POST", "/api/v1/orders/$orderId/cancel", biz(), bodyJson = body, d = JsonElement.serializer())
    }

    // -----------------------------------------------------------------
    // BLE
    // -----------------------------------------------------------------

    /** Cypress：上报设备回传的随机数/数据包。 */
    suspend fun bleDataReport(macAddress: String, reportData: List<String>): Envelope<JsonElement> {
        val body = UjingJson.encodeToString(
            BleDataReport.serializer(),
            BleDataReport(macAddress, reportData.map { it.uppercase() })
        )
        return call("POST", "/api/v1/ble/data/report", biz(), bodyJson = body, d = JsonElement.serializer())
    }

    /** Cypress：取云端签好名的指令包。 */
    suspend fun bleCommandAcquire(macAddress: String, command: String): List<BleCommandPackage> {
        val body = UjingJson.encodeToString(
            BleCommandAcquireRequest.serializer(),
            BleCommandAcquireRequest(macAddress, command)
        )
        val env = call(
            "POST", "/api/v1/ble/command/acquire", biz(),
            bodyJson = body,
            d = ListSerializer(BleCommandPackage.serializer())
        )
        return env.data.orEmpty()
    }

    /**
     * Nordic：一次性上报读取状态并取回明文指令。
     *
     * 响应兼容带包裹与裸对象两种形态。
     */
    suspend fun nordicReport(report: NordicReport): NordicCommandResponse {
        val body = UjingJson.encodeToString(NordicReport.serializer(), report)
        val env = call(
            "POST", "/api/v1/ble/data/report/nordic", biz(),
            bodyJson = body,
            d = NordicCommandResponse.serializer()
        )
        // `{}` 形态（静默拒绝）会让 env.data 为 null。此时给一个 errorCode=0 的空响应，
        // 由 NordicWasher 按 reportType 判断"该回指令却没回指令"，而不是在这里凭空报错。
        return env.data ?: NordicCommandResponse(errorCode = 0)
    }

    /** 暴露给调试页：原始 JSON 文本。 */
    suspend fun norticReportRaw(report: NordicReport): String {
        val body = UjingJson.encodeToString(NordicReport.serializer(), report)
        return raw("POST", "/api/v1/ble/data/report/nordic", biz(), bodyJson = body)
    }

    // -----------------------------------------------------------------
    // 支付
    // -----------------------------------------------------------------

    /** 可用支付渠道（收银台列表）。[include] 为本机已安装支付 APP 的 channel 逗号串。 */
    suspend fun payMethods(
        orderId: String,
        include: String? = null,
        serviceSubjectId: String? = null,
        deviceId: String? = null,
        creativeNumber: String? = null
    ): Envelope<PayMethodsData> = call(
        "GET", "/api/v1/payment/methods", biz(),
        query = mapOf(
            "orderId" to orderId,
            "include" to include,
            "serviceSubjectId" to serviceSubjectId,
            "deviceId" to deviceId,
            "creativeNumber" to creativeNumber
        ),
        d = PayMethodsData.serializer()
    )

    /**
     * 取支付参数。
     *
     * 注意是 **GET** 而非 POST：`cashierNew.js` 的 `getPayArgumentsReq` 只在
     * `rechargeTypes = ["shower","water"]` 时用 POST，washer 走 GET。
     *
     * 实测（用已完成订单）：已支付订单返回 `100001 订单已支付或已过期，无法支付`，
     * 对已完成订单**无副作用**。
     */
    suspend fun payArguments(
        channel: String,
        orderId: String,
        payVersion: Int = 1,
        chooseSign: Int? = null,
        branchType: Int? = null,
        jumpToAliMini: Boolean? = null
    ): Envelope<PayArgumentsData> = call(
        "GET", "/api/v1/payment/arguments", biz(),
        query = mapOf(
            "channel" to channel,
            "orderId" to orderId,
            "payVersion" to payVersion.toString(),
            "chooseSign" to chooseSign?.toString(),
            "branchType" to branchType?.toString(),
            "jumpToAliMini" to jumpToAliMini?.toString()
        ),
        d = PayArgumentsData.serializer()
    )

    /** 支付成功后向服务端回执。 */
    suspend fun payStatusCommon(orderId: String, channel: String): Envelope<JsonElement> = call(
        "GET", "/api/v1/payment/$orderId/payStatus", biz(),
        query = mapOf("channel" to channel),
        d = JsonElement.serializer()
    )

    /** 轮询支付结果（`status == "3"` 即成功）。 */
    suspend fun lastPayStatus(orderId: String): Envelope<PayStatusData> = call(
        "GET", "/api/v1/app/payment/$orderId/lastPayStatus", biz(),
        d = PayStatusData.serializer()
    )
}
