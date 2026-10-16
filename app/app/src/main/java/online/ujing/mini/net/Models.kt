package online.ujing.mini.net

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * 兼容「布尔字段收到数字」的服务端。
 *
 * U净 服务端的 `deviceWashModel[]` 把 `isPromotion` / `isDefault` / `hide`
 * 以 **数字 0/1** 返回，而非 JSON 布尔；kotlinx.serialization 严格模式会抛
 * `Failed to parse literal '0' as a boolean value`，导致整个 ProgramInfo 解析失败、
 * 洗衣模式列表为空。
 */
object FlexibleBoolSerializer : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("FlexibleBool", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val input = decoder as? JsonDecoder ?: return false
        val prim = input.decodeJsonElement() as? JsonPrimitive ?: return false
        val raw = prim.content.trim()
        return when (raw.lowercase()) {
            "1", "true" -> true
            "0", "0.0", "", "false", "null" -> false
            else -> raw.toDoubleOrNull()?.let { it != 0.0 } ?: false
        }
    }

    override fun serialize(encoder: Encoder, value: Boolean) {
        encoder.encodeBoolean(value)
    }
}

/**
 * 数字字段的宽容反序列化器。
 *
 * 服务端同一字段在不同接口/版本下可能是 JSON 数字（`40`）、字符串（`"40"`）
 * 或浮点（`40.0`）。`isLenient` 只解决"字符串里是合法 JSON"的场景，
 * 不解决 `Int` 字段收到 `"40"`，因此需要它。
 */
object FlexibleIntSerializer : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Int {
        val input = decoder as? JsonDecoder ?: return 0
        val prim = input.decodeJsonElement() as? JsonPrimitive ?: return 0
        val raw = prim.content.trim()
        if (raw.isEmpty() || raw.equals("null", ignoreCase = true)) return 0
        return raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt() ?: 0
    }

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(value)
    }
}

/**
 * 云端响应模型。
 *
 * 统一包裹：`{ code, reason, message, data, metadata }`，code ∈ {0, 200} 视为成功。
 * 字段全部可空 + 默认值，兼容服务端灰度期字段增删。
 */
val UjingJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class Envelope<T>(
    val code: Int = 0,
    val reason: String? = null,
    val message: String? = null,
    val data: T? = null
) {
    val isOk: Boolean get() = code == 0 || code == 200
}

// ---------------------------------------------------------------------------
// 鉴权
// ---------------------------------------------------------------------------

@Serializable
data class LoginRequest(
    val captcha: String,
    val mobile: String
)

@Serializable
data class LoginData(
    val token: String = ""
)

// ---------------------------------------------------------------------------
// 扫码 / 设备
// ---------------------------------------------------------------------------

@Serializable
data class ScanRequest(
    val qrCode: String
)

@Serializable
data class ScanResultWrap(
    val result: ScanResult? = null
)

@Serializable
data class ScanResult(
    val orderId: Long = 0,
    val deviceId: String = "",
    val macAddress: String = "",
    val isSlotMachine: Boolean = false,
    /** 见 ModuleType：1=NORDIC 5=CYPRESS … */
    val moduleType: Int = -1,
    val status: Int = 0,
    val mobile: String = "",
    /** true = 当前空闲可下单 */
    val createOrderEnabled: Boolean = false,
    val reason: String? = null,
    val needSync: Boolean = false,
    val needSyncBLE: Boolean = false,
    val deviceTypeId: Int = 0
)

@Serializable
data class ProgramInfo(
    val customDeviceType: Int = 0,
    val deviceId: String = "",
    val deviceNo: String = "",
    val deviceTypeId: Int = 0,
    val deviceTypeName: String = "",
    val deviceWashModel: List<DeviceWashModel> = emptyList(),
    val isOpenDoubleExposure: Boolean = false,
    val isWashTemperatureEnable: Boolean = false,
    val macAddress: String = "",
    val moduleType: Int = -1,
    val serviceSubjectId: Long = 0,
    val serviceSubjectName: String = "",
    @Serializable(with = FlexibleBoolSerializer::class)
    val showMore: Boolean = false,
    val storeId: String = "",
    val storeMobile: String = "",
    val storeName: String = "",
    val type: Int = 0,
    val warmTip: String = ""
)

@Serializable
data class DeviceWashModel(
    val workModelId: Int = 0,
    val workModelName: String = "",
    /** 单位：分 */
    val basePrice: Int = 0,
    val promotionPrice: Int? = null,
    @Serializable(with = FlexibleBoolSerializer::class)
    val isPromotion: Boolean = false,
    /** 默认选中项 */
    @Serializable(with = FlexibleBoolSerializer::class)
    val isDefault: Boolean = false,
    @Serializable(with = FlexibleBoolSerializer::class)
    val hide: Boolean = false,
    /** 单位：分钟 */
    val time: Int = 0,
    val description: String = "",
    val tags: List<String> = emptyList()
) {
    /** 展示价（分）：服务端无促销时 promotionPrice 显式返回 0（不是 null），须判 >0 才采信。 */
    val displayPriceCents: Int get() = (promotionPrice ?: 0).takeIf { it > 0 } ?: basePrice
}

// ---------------------------------------------------------------------------
// 下单
// ---------------------------------------------------------------------------

@Serializable
data class CreateOrderRequest(
    val type: Int = 1,
    val deviceTypeId: Int,
    val deviceId: String,
    val deviceWashModelId: Int,
    val storeId: String,
    val wp_detergentGearId: Int? = null,
    val wp_disinfectantGearId: Int? = null,
    val washTemperatureId: Int? = null,
    val dryTime: Int? = null,
    @SerialName("additionParams") val additionParams: Map<String, String> = emptyMap()
)

/**
 * 订单数据。
 *
 * 注意：`orderId` / `status` 服务端有时返回数字、有时返回字符串。
 * `orderId` 声明为 String 是因为数字/字符串都能被 kotlinx 的 lenient 模式读入 String；
 * `status` 声明为 Int 则在收到 `"40"` 这类字符串时会失败，因此用 [FlexibleIntSerializer]。
 */
@Serializable
data class OrderData(
    val orderId: String = "",
    val deviceId: String = "",
    val macAddress: String = "",
    val orderNo: String = "",
    @Serializable(with = FlexibleIntSerializer::class) val status: Int = 0,
    val statusRemark: String? = null,
    @Serializable(with = FlexibleIntSerializer::class) val deviceWashModelId: Int = 0,
    val deviceWashModel: DeviceWashModel? = null,
    val workTime: Int? = null,
    val remainTime: Int? = null,
    val cycle: String? = null,
    val createAt: String? = null,
    val userClickWashStartTime: String? = null,
    val washStartTime: String? = null,
    val payFlag: Int? = null,
    val payPrice: Double? = null,
    val deviceNo: String? = null,
    val storeName: String? = null,
    val storeMobile: String? = null
) {
    /** 订单是否处于「已启动但未结束」——此时不可重复启动。 */
    val running: Boolean get() = status in WasherStatus.IN_PROGRESS

    /**
     * 服务端给的剩余秒数，负数按 0 处理。
     *
     * UI 侧拿它做本地倒计时（每秒递减），避免只靠轮询导致计时几分钟才跳一次。
     */
    val remainSeconds: Int get() = (remainTime ?: 0).coerceAtLeast(0)

    /** 剩余时间文本，如「剩余 26 分钟」。 */
    val remainText: String
        get() {
            val sec = remainSeconds
            if (sec <= 0) return ""
            val min = sec / 60
            return if (min <= 0) "剩余不足 1 分钟" else "剩余约 $min 分钟"
        }

    /**
     * 订单真正的开始时刻（epoch 毫秒），0 表示服务端还没给。
     *
     * 服务端在「启动中」(status=21) 阶段 `remainTime` 还是 0，光靠它无从倒计时；
     * 用这个时刻 + [totalSeconds] 自己推，重开 App 也能接着走，不会每次从总时长从头开始。
     * 取值的优先级：`washStartTime`（实际开始洗）→ `userClickWashStartTime`（用户点启动）→ `createAt`（下单）。
     * 三者都是 UTC ISO-8601（形如 `2026-09-22T10:07:06Z`）。
     */
    val startMs: Long
        get() {
            val raw = (washStartTime ?: userClickWashStartTime ?: createAt)?.trim().orEmpty()
            if (raw.isBlank()) return 0
            return runCatching { java.time.Instant.parse(raw).toEpochMilli() }
                .recoverCatching {
                    java.time.LocalDateTime.parse(raw)
                        .toInstant(java.time.ZoneOffset.UTC)
                        .toEpochMilli()
                }
                .getOrDefault(0)
        }

    /** 该模式的整套时长（秒）。服务端 `workTime` 单位是分钟。 */
    val totalSeconds: Int get() = (workTime ?: 0) * 60
}

// ---------------------------------------------------------------------------
// BLE 专用
// ---------------------------------------------------------------------------

/** `POST /ble/data/report` 请求体。 */
@Serializable
data class BleDataReport(
    val macAddress: String,
    val reportData: List<String>
)

/** `POST /ble/command/acquire` 请求体。 */
@Serializable
data class BleCommandAcquireRequest(
    val macAddress: String,
    val command: String
)

/** acquire 响应：数组，每项含若干 hex 包。 */
@Serializable
data class BleCommandPackage(
    val packages: List<String> = emptyList()
)

/**
 * `POST /ble/data/report/nordic` 上报体。
 *
 * 对齐官方：
 * ```
 * {type, deviceId, command, commandType, originStr, deviceInfoStr,
 *  bleVersionStr, random, orderNoStr, orderId?}
 * ```
 */
@Serializable
data class NordicReport(
    val type: Int,
    val deviceId: String,
    val command: String = "",
    val commandType: String = "",
    val originStr: String = "",
    val deviceInfoStr: String = "",
    val bleVersionStr: String = "",
    val random: String = "",
    val orderNoStr: String = "",
    val orderId: String? = null
)

/**
 * Nordic 上报响应：云端回传的明文指令。
 *
 * 实测响应形态（探针实验变体 C/E，type=2 无 orderId 或 type=3）：
 * ```json
 * {"code":0,"data":{"deviceStatus":"1","errorCode":0,"orderStatus":"40","status":""}}
 * ```
 * 带 orderId + type=2 且订单已在运行时，服务端回 `{}` → 解析后全部字段为 null。
 */
@Serializable
data class NordicCommandResponse(
    val orderId: String? = null,
    val orderNo: String? = null,
    /** 明文指令，直接写目标特征 */
    val command: String? = null,
    @Serializable(with = FlexibleIntSerializer::class) val errorCode: Int = 0,
    /** 设备模块状态："1" = WORK。 */
    val deviceStatus: String? = null,
    /** 订单状态："40" = 运行中。 */
    val orderStatus: String? = null,
    val status: String? = null
)

// ---------------------------------------------------------------------------
// 枚举
// ---------------------------------------------------------------------------

object ModuleType {
    const val TYPE_2G = 0
    const val TYPE_BLE_NORDIC = 1
    const val TYPE_NB = 2
    const val TYPE_2G_BLE = 3
    const val TYPE_NB_BLE = 4
    const val TYPE_BLE_CYPRESS = 5
    const val TYPE_WIFI_MESH = 6
    const val TYPE_4G = 7

    /** 支持 App 直连蓝牙的模块类型。 */
    fun supportsBle(m: Int): Boolean = m in setOf(TYPE_BLE_NORDIC, TYPE_2G_BLE, TYPE_NB_BLE, TYPE_BLE_CYPRESS)

    fun bleTypeOf(m: Int): String? = when (m) {
        TYPE_BLE_NORDIC -> "bleNordic51822"
        TYPE_BLE_CYPRESS -> "bleCypress20736"
        else -> null
    }
}

object WasherDeviceType {
    const val PULSATOR = 1
    const val DRUM = 2
    const val DRYER = 3
    const val SHOE_WASHER = 4
    const val DRYER_LARGE = 6
    const val PULSATOR_OTT = 8
    const val DRUM_10KG = 9
    const val DRYER_9KG = 10
    const val PULSATOR_65KG = 11
    const val DRUM_10KG_NEW = 12
    const val DRYER_10KG = 13

    val DRYER_TYPES = setOf(DRYER, DRYER_LARGE, DRYER_9KG, DRYER_10KG)

    fun displayName(id: Int): String = when (id) {
        PULSATOR -> "波轮机"
        DRUM -> "滚筒机"
        DRYER -> "烘干机"
        SHOE_WASHER -> "洗鞋机"
        DRYER_LARGE -> "大型烘干机"
        PULSATOR_OTT -> "波轮机(OTT)"
        DRUM_10KG -> "10KG滚筒"
        DRYER_9KG -> "9KG烘干机"
        PULSATOR_65KG -> "6.5KG波轮"
        DRUM_10KG_NEW -> "10KG滚筒(新)"
        DRYER_10KG -> "10KG烘干机"
        else -> "设备#$id"
    }
}

object WasherStatus {
    const val RESERVED = 10
    const val PAYING = 17
    const val PAID = 20
    const val STARTING = 21
    const val SELF_CLEANING_STARTING = 22
    const val DETERGENT_DISPENSING = 24
    const val ORDER_PROTECTION = 29
    const val SELF_CLEANING = 30
    const val SELF_CLEANING_COMPLETED = 35
    const val RUNNING = 40
    const val COMPLETED = 50
    const val PAYMENT_TIMEOUT = 51
    const val START_FAILED = 52
    const val CANCELED = 53

    val BLE_STARTABLE = setOf(SELF_CLEANING, RUNNING)

    /**
     * 「已启动但没结束」的全部中间态。
     *
     * 启动后服务端可能先给 21（启动中）/ 22 / 24，过一会儿才落到 40（运行中）。
     * 这几个状态都要当成「运行中」处理，否则界面会又把「启动洗衣机」按钮亮出来。
     * 排除：29 订单保护（异常态，需人工介入）、50 完成、51 支付超时、52 启动失败、53 取消。
     */
    val IN_PROGRESS = setOf(
        STARTING,
        SELF_CLEANING_STARTING,
        DETERGENT_DISPENSING,
        SELF_CLEANING,
        RUNNING
    )

    fun displayName(s: Int): String = when (s) {
        RESERVED -> "已预约"
        PAYING -> "支付中"
        PAID -> "已支付"
        STARTING -> "启动中"
        SELF_CLEANING_STARTING -> "筒自洁启动中"
        DETERGENT_DISPENSING -> "投放洗涤剂"
        ORDER_PROTECTION -> "订单保护中"
        SELF_CLEANING -> "筒自洁中"
        SELF_CLEANING_COMPLETED -> "筒自洁完成"
        RUNNING -> "运行中"
        COMPLETED -> "已完成"
        PAYMENT_TIMEOUT -> "支付超时"
        START_FAILED -> "启动失败"
        CANCELED -> "已取消"
        else -> "状态#$s"
    }
}

/** 洗涤剂/消毒剂档位。 */
object Gear {
    const val DETERGENT_STANDARD = 1
    const val DETERGENT_HEAVY = 3
    const val DISINFECTANT_STANDARD = 4
    const val DISINFECTANT_HEAVY = 6
}

/** 水温档位。 */
object WashTemperature {
    const val NORMAL = 1
    const val C30 = 2
    const val C40 = 3
    const val C60 = 4
}

// ---------------------------------------------------------------------------
// 支付
// ---------------------------------------------------------------------------

/**
 * `GET /payment/methods` 的渠道项。
 *
 * 实测返回 60 余字段，这里只声明用得到的；其余由 `ignoreUnknownKeys` 丢弃。
 * 渠道 channel 取值：`alipay` / `wechatPay` / `unionPay` / `abcPay` / `cmbPayH5` /
 * `ccbPay` / `bocPay` / `mobilePay` / `ticketPay` / `alipayF2F` / `dyPay`。
 */
@Serializable
data class PayChannel(
    val channel: String = "",
    val name: String = "",
    @Serializable(with = FlexibleBoolSerializer::class) val isDefault: Boolean = false,
    @Serializable(with = FlexibleBoolSerializer::class) val isRecommended: Boolean = false,
    @Serializable(with = FlexibleBoolSerializer::class) val needUpgrade: Boolean = false,
    val upgradeNotes: String? = null,
    val upgradeVersion: String? = null,
    @Serializable(with = FlexibleIntSerializer::class) val branchType: Int = 0,
    /** 银联委托支付是否已签约。 */
    @Serializable(with = FlexibleBoolSerializer::class) val paymentSigned: Boolean = false,
    /** 免密协议是否已同意（支付宝侧同名语义）。 */
    @Serializable(with = FlexibleBoolSerializer::class) val isPaymentSignAgreen: Boolean = false,
    @Serializable(with = FlexibleBoolSerializer::class) val jumpToAliMini: Boolean = false,
    @Serializable(with = FlexibleBoolSerializer::class) val needOAuth: Boolean = false,
    val description: String? = null,
    @Serializable(with = FlexibleIntSerializer::class) val weight4Display: Int = 0
) {
    /** 该渠道是否已具备免密签约（true 时服务端可能直接返 skipPay）。 */
    val signCapable: Boolean get() = paymentSigned || isPaymentSignAgreen

    val displayName: String
        get() = name.ifBlank {
            when (channel) {
                "alipay", "alipayF2F" -> "支付宝"
                "wechatPay", "wechatPayMidea" -> "微信支付"
                "unionPay" -> "云闪付"
                "cmbPayH5" -> "招行一网通"
                else -> channel
            }
        }
}

@Serializable
data class PayMethodsData(
    val channels: List<PayChannel> = emptyList(),
    val unionPayDirectLogo: String? = null
)

/** `payment/arguments` 响应里的支付参数。 */
@Serializable
data class PayInfo(
    /** 支付宝订单串：`app_id=...&biz_content=...&sign=...`，直接喂给 PayTask。 */
    val orderInfo: String = ""
)

/**
 * `GET /payment/arguments` 响应。
 *
 * 兼容两种形态：带 `payInfo` 包裹（官方主路径），或 orderInfo 直接挂顶层
 * （官方代码里写作 `res.payInfo || res`）。
 *
 * - [skipPay] = true → 服务端已免密扣款完成，客户端**不要**再唤起收银台；
 * - [jumpToQuery] = true → 跳支付状态页轮询。
 */
@Serializable
data class PayArgumentsData(
    val payInfo: PayInfo? = null,
    val orderInfo: String? = null,
    @Serializable(with = FlexibleBoolSerializer::class) val skipPay: Boolean = false,
    @Serializable(with = FlexibleBoolSerializer::class) val jumpToQuery: Boolean = false,
    val orderNo: String? = null,
    val channel: String? = null
) {
    /** 取支付宝订单串（包裹 / 裸两种形态）。 */
    val orderStr: String
        get() = payInfo?.orderInfo?.takeIf { it.isNotBlank() } ?: orderInfo.orEmpty()
}

/** `GET /app/payment/{id}/lastPayStatus` 响应。status == "3" 为支付成功。 */
@Serializable
data class PayStatusData(
    val status: String = "",
    @Serializable(with = FlexibleIntSerializer::class) val payFlag: Int = 0
) {
    val success: Boolean get() = status == "3"
}
