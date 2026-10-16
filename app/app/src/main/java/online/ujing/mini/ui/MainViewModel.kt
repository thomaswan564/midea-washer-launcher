package online.ujing.mini.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import online.ujing.mini.ble.BleError
import online.ujing.mini.ble.CypressWasher
import online.ujing.mini.ble.DeviceResolver
import online.ujing.mini.ble.NordicCommand
import online.ujing.mini.ble.NordicWasher
import online.ujing.mini.data.SavedDevice
import online.ujing.mini.data.TokenStore
import online.ujing.mini.net.CreateOrderRequest
import online.ujing.mini.net.DeviceWashModel
import online.ujing.mini.net.ModuleType
import online.ujing.mini.net.OrderData
import online.ujing.mini.net.PayChannel
import online.ujing.mini.net.ProgramInfo
import online.ujing.mini.net.ScanResult
import online.ujing.mini.net.UjingApi
import online.ujing.mini.net.WasherStatus

/**
 * 全流程编排：登录 → 扫码 → 选模式 → 蓝牙启动。
 *
 * 刻意保持单 ViewModel + 纯 StateFlow，不引 DI 框架。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val TAG = "UjingMini"
    private val store = TokenStore(app)
    val api = UjingApi()

    private val _mobile = MutableStateFlow("")
    val mobile = _mobile.asStateFlow()

    private val _codeSent = MutableStateFlow(false)
    val codeSent = _codeSent.asStateFlow()

    private val _loggedIn = MutableStateFlow(false)
    val loggedIn = _loggedIn.asStateFlow()

    private val _device = MutableStateFlow(SavedDevice())
    val device = _device.asStateFlow()

    private val _nearbyWashers = MutableStateFlow<List<DeviceResolver.NearbyWasher>>(emptyList())
    val nearbyWashers = _nearbyWashers.asStateFlow()

    private val _selectedNearbyAddress = MutableStateFlow("")
    val selectedNearbyAddress = _selectedNearbyAddress.asStateFlow()

    private val _scan = MutableStateFlow<ScanResult?>(null)
    val scan = _scan.asStateFlow()

    /** 当前待启动的订单号。来源：扫洗衣机码返回的 orderId（已在官方渠道下单支付的订单）。 */
    private val _orderId = MutableStateFlow("")
    val orderId = _orderId.asStateFlow()

    /**
     * 订单详情。扫到 orderId 后拉一次，用 `status` 判断订单是否已在运行。
     *
     * 背景：服务端对已 RUNNING 的订单会**静默拒绝**重复启动
     * （`ble/data/report/nordic` 返回 `{}`），所以必须在点「蓝牙启动」之前
     * 就知道订单状态，否则用户白点一次、还以为是 App 坏了。
     */
    private val _order = MutableStateFlow<OrderData?>(null)
    val order = _order.asStateFlow()

    /**
     * 收银台渠道列表（`GET /payment/methods`）。下单成功后自动拉取。
     *
     * 实测本账号 5 个渠道：unionPay(默认) / abcPay / alipay / wechatPay / cmbPayH5。
     */
    private val _payChannels = MutableStateFlow<List<PayChannel>>(emptyList())
    val payChannels = _payChannels.asStateFlow()

    /** 当前选中的支付渠道 channel，默认 alipay。 */
    private val _selectedChannel = MutableStateFlow("alipay")
    val selectedChannel = _selectedChannel.asStateFlow()

    /**
     * 待唤起的支付宝订单串。
     *
     * 为什么要绕一圈：`PayTask` 构造需要 `Activity`，而 ViewModel 拿不到 Activity。
     * 因此 ViewModel 只负责从服务端取 `orderInfo` 放到这里，
     * 由 UI 层 collect 到之后调 `AlipayLauncher.pay(activity, ...)`，结果再回传 [onPayResult]。
     */
    private val _pendingOrderInfo = MutableStateFlow<String?>(null)
    val pendingOrderInfo = _pendingOrderInfo.asStateFlow()

    /**
     * 订单已付款、但还没启动洗衣机。
     *
     * 付款成功后置 true，UI 据此把主按钮切成「启动洗衣机」；
     * 启动成功或订单结束置 false。重启 App 时由 [refreshOrder] 按 `payFlag` 还原。
     */
    private val _paid = MutableStateFlow(false)
    val paid = _paid.asStateFlow()

    /**
     * 启动指令已下发、但服务端还没把订单推进到「运行中」。
     *
     * 点「启动洗衣机」时立即置 true，UI 主按钮切成「启动中…」并禁用（防重复点击）；
     * 等 orderDetail 的 status 变成 40 才清掉，让按钮接上「运行中 · 剩余 N 分钟」。
     */
    private val _starting = MutableStateFlow(false)
    val starting = _starting.asStateFlow()

    /** 每秒心跳。服务端的 remainTime 不保证持续回写，界面靠它做本地倒计时。 */
    private val _tick = MutableStateFlow(0L)
    val tick = _tick.asStateFlow()

    /** 最近一次成功拉到订单状态的时刻，配合 [tick] 推算剩余时间。 */
    private val _orderFetchedAt = MutableStateFlow(0L)
    val orderFetchedAt = _orderFetchedAt.asStateFlow()

    /**
     * 本地「刚下发启动指令」的时刻，0 表示没有。
     *
     * 服务端把订单从「已付款」推到「运行中」有延迟（实测几秒到十几秒），
     * 这段时间界面不能退回「启动洗衣机」，用这个时间戳兜一段；
     * 服务端确认 running（或订单结束）后清零。
     */
    private val _startedAt = MutableStateFlow(0L)
    val startedAt = _startedAt.asStateFlow()

    /**
     * 直接从洗衣机机身读到的剩余时间（秒），null = 还没读到过。
     *
     * STATUS 特征（`4A320009`）第 13 字节就是机身显示屏上那个分钟数，
     * 比服务端的 `remainTime` 权威 —— 服务端在「启动中」阶段根本不回写。
     * 蓝牙连接时（约 10 米内）读一次，记下时刻，由界面每秒递减。
     */
    private val _deviceRemain = MutableStateFlow<Int?>(null)
    val deviceRemain = _deviceRemain.asStateFlow()

    /** 读到 [deviceRemain] 的时刻，界面用它推算已经过去多少秒。 */
    private val _deviceRemainAt = MutableStateFlow(0L)
    val deviceRemainAt = _deviceRemainAt.asStateFlow()

    private val _program = MutableStateFlow<ProgramInfo?>(null)
    val program = _program.asStateFlow()

    private val _selectedModelId = MutableStateFlow(0)
    val selectedModelId = _selectedModelId.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy = _busy.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    init {
        runCatching { logFile()?.writeText("") }
        viewModelScope.launch {
            val saved = store.currentDevice()
            if (saved.valid) _device.value = saved
            val token = store.currentToken()
            if (!token.isNullOrBlank()) {
                api.token = token
                _loggedIn.value = true
                _mobile.value = store.mobileFlow.first().orEmpty()
                if (saved.valid) {
                    refreshProgram(silent = true)
                    scheduleIdlePoll()  // 启动设备占用状态轮询
                }
            }
            val last = store.lastWorkModelFlow.first()
            if (last != 0) _selectedModelId.value = last
            // 订单号必须跨重启存活：蓝牙启动时服务端靠它签发指令，丢了就静默空转
            val savedOrderId = store.currentOrderId()
            if (savedOrderId.isNotBlank()) {
                _orderId.value = savedOrderId
                log("恢复待启动订单 orderId=$savedOrderId")
                // 顺带把订单状态拉回来：折叠卡的角标与主按钮都靠它判断
                refreshOrder(savedOrderId)
            }
        }
    }

    fun log(line: String) {
        // vivo 只放行 E 级日志，Log.d 会被系统静默丢弃（实测 adb logcat 为空）
        Log.e(TAG, line)
        _logs.value = (_logs.value + line).takeLast(200)
        // vivo 的 logcat 环形缓冲很小，常被系统日志冲掉；同时落盘一份，便于 adb pull 取证
        runCatching { logFile()?.appendText(line + "\n") }
    }

    private fun logFile(): java.io.File? =
        java.io.File(getApplication<Application>().getExternalFilesDir(null), "ujing.log")

    fun consumeToast() { _toast.value = null }

    private fun error(e: Throwable) {
        val msg = when (e) {
            is UjingApi.ApiException -> "接口错误 ${e.code}：${e.message}"
            else -> e.message ?: "未知错误"
        }
        _toast.value = msg
        log("ERROR $msg")
    }

    // -----------------------------------------------------------------
    // 登录
    // -----------------------------------------------------------------

    fun onMobileChange(v: String) { _mobile.value = v.filter { it.isDigit() }.take(11) }

    fun sendCode() {
        val m = _mobile.value
        if (m.length != 11) { _toast.value = "手机号需 11 位"; return }
        viewModelScope.launch {
            _busy.value = "正在发送验证码"
            runCatching { api.sendCaptcha(m) }
                .onSuccess { _codeSent.value = true; _toast.value = "验证码已发送" }
                .onFailure { error(it) }
            _busy.value = null
        }
    }

    fun login(captcha: String) {
        val m = _mobile.value
        if (captcha.isBlank()) { _toast.value = "请输入验证码"; return }
        viewModelScope.launch {
            _busy.value = "正在登录"
            runCatching { api.login(m, captcha) }
                .onSuccess { env ->
                    val t = env.data?.token.orEmpty()
                    if (t.isBlank()) { _toast.value = "登录返回 token 为空"; return@onSuccess }
                    store.saveSession(t, m)
                    _loggedIn.value = true
                    _toast.value = "登录成功"
                    val d = _device.value
                    if (d.valid) {
                        refreshProgram(silent = true)
                        scheduleIdlePoll()
                    }
                }
                .onFailure { error(it) }
            _busy.value = null
        }
    }

    fun logout() {
        viewModelScope.launch {
            store.clearSession()
            api.token = null
            _loggedIn.value = false
            _codeSent.value = false
            _program.value = null
            cancelOrderPoll()
            cancelIdlePoll()
            stopTick()
            _scan.value = null
            _orderId.value = ""
            _order.value = null
            _paid.value = false
            _starting.value = false
            _startedAt.value = 0
            _deviceRemain.value = null
            _deviceRemainAt.value = 0
            _orderFetchedAt.value = 0
            _payChannels.value = emptyList()
            _pendingOrderInfo.value = null  // 防止退出后残留支付串触发支付宝
            store.saveOrderId("")
        }
    }

    // -----------------------------------------------------------------
    // 扫码 / 设备
    // -----------------------------------------------------------------

    fun onScan(raw: String) {
        if (raw.isBlank()) return
        viewModelScope.launch {
            _busy.value = "正在识别设备"
            runCatching { api.scanWasherCode(raw) }
                .onSuccess { env ->
                    val r = env.data?.result
                    if (r == null || r.deviceId.isBlank()) {
                        _toast.value = "二维码无效"
                    } else {
                        _scan.value = r
                        store.saveQrCode(raw)  // 存扫码原文，后续重放 scanWasherCode 检查设备占用
                        // 换设备时清理旧设备的全部订单/倒计时残留，防止旧状态污染新设备
                        _paid.value = false
                        _startedAt.value = 0
                        _deviceRemain.value = null
                        _deviceRemainAt.value = 0
                        _orderFetchedAt.value = 0
                        if (r.orderId > 0) {
                            _orderId.value = r.orderId.toString()
                            store.saveOrderId(r.orderId.toString())
                            log("扫到未完成订单 orderId=${r.orderId}")
                        } else {
                            _orderId.value = ""
                            _order.value = null
                            store.saveOrderId("")
                            log("该设备上没有属于你的未完成订单")
                        }
                        val d = SavedDevice(
                            deviceId = r.deviceId,
                            macAddress = r.macAddress,
                            deviceTypeId = r.deviceTypeId,
                            moduleType = r.moduleType,
                            storeName = ""
                        )
                        _device.value = d
                        store.saveDevice(d)
                        if (!r.createOrderEnabled) {
                            _toast.value = r.reason ?: "该设备当前不可下单"
                        }
                        refreshProgram(silent = false)
                        // 有关联订单就顺手把它的状态拉回来：运行中 / 待支付 / 待启动
                        if (r.orderId > 0) refreshOrder(r.orderId.toString())
                    }
                }
                .onFailure { error(it) }
            _busy.value = null
        }
    }

    fun discoverNearbyWashers() {
        viewModelScope.launch {
            _busy.value = "正在搜索附近洗衣机"
            _nearbyWashers.value = emptyList()
            _selectedNearbyAddress.value = ""
            try {
                if (!DeviceResolver.isReady(getApplication())) {
                    _toast.value = "请先打开手机蓝牙"
                    return@launch
                }
                _nearbyWashers.value = DeviceResolver.discoverNearby(getApplication()) { log(it) }
                if (_nearbyWashers.value.isEmpty()) _toast.value = "没有发现附近的 Midea 数字设备"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error(e)
            } finally {
                _busy.value = null
            }
        }
    }

    fun selectNearbyWasher(address: String) {
        _selectedNearbyAddress.value = address
        val boundAddress = DeviceResolver.normalize(_device.value.macAddress)
        if (boundAddress != null && boundAddress == DeviceResolver.normalize(address)) {
            _toast.value = "已选中与当前订单匹配的洗衣机"
        } else {
            _toast.value = "已选择设备；启动前需先扫码绑定这台机器并取得有效订单"
        }
    }

    /**
     * 拉订单详情，刷新 [order] 状态。
     *
     * 关键用途：判断订单是否已在运行。服务端对已 RUNNING 的订单会静默拒绝
     * 重复启动（`ble/data/report/nordic` 返回 `{}`），所以启动前必须先看状态。
     */
    fun refreshOrder(id: String = _orderId.value) {
        if (id.isBlank()) { _order.value = null; return }
        viewModelScope.launch {
            runCatching { api.orderDetail(id) }
                .onSuccess { env ->
                    val o = env.data
                    if (o == null) {
                        log("订单 $id 详情为空（订单不存在或已结束）")
                        _order.value = null
                        return@onSuccess
                    }
                    // 订单已结束：本地收尾，回到「可以开新的一单」的状态。
                    // 刻意不让「已完成」常驻在界面上，只在结束的那一刻提示一次。
                    if (o.status == WasherStatus.COMPLETED) {
                        log("订单 $id 已完成，清除本地订单号")
                        _orderId.value = ""
                        _order.value = null
                        _paid.value = false
                        _startedAt.value = 0
                        _deviceRemain.value = null
                        store.saveOrderId("")
                        cancelOrderPoll()
                        stopTick()
                        _toast.value = "上一单已完成"
                        refreshProgram(silent = true)
                        scheduleIdlePoll()  // 订单结束，回到 idle 轮询
                        return@onSuccess
                    }
                    _order.value = o
                    // 记下这次拉取的时刻：界面用 remainTime 减掉「已过去的秒数」做本地倒计时
                    _orderFetchedAt.value = System.currentTimeMillis()
                    // 已付款、未运行 → 处于「等待启动」态。
                    // 重启 App 后 _paid 内存态会丢，这里按服务端 payFlag 还原。
                    // 单向赋值：服务端 payFlag 回写有延迟，已确认付过款就别再打回未付，
                    // 否则用户刚付完会看到按钮又变回「支付」（实测踩过）。
                    if (o.payFlag == 1 && !o.running) _paid.value = true
                    log(
                        "订单 $id 状态=${o.status} ${o.statusRemark.orEmpty()} " +
                            "模式=${o.deviceWashModelId} ${o.remainText} paid=${_paid.value}"
                    )
                    if (o.running) {
                        // 服务端已确认在跑，本地兜底标记可以撤了
                        _startedAt.value = 0
                        startTick()
                        cancelIdlePoll()
                        scheduleOrderPoll(id)
                    } else {
                        cancelOrderPoll()
                        scheduleIdlePoll()
                    }
                }
                .onFailure { error(it) }
        }
    }

    private var orderPollJob: Job? = null

    /**
     * 订单运行中时的定时刷新（15 秒一次）。
     *
     * 洗衣机完成后服务端不会主动推送，只能轮询 `orders/{id}/detail`，
     * 靠 `status` 从 40 变 50 来切「已完成」。订单一旦不再运行就自动停掉。
     * 秒级倒计时不靠它：界面用 [tick] 本地走秒，这里只负责校准与收尾。
     */
    private fun scheduleOrderPoll(id: String) {
        if (orderPollJob?.isActive == true) return
        orderPollJob = viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                if (_orderId.value != id) return@launch
                refreshOrder(id)
                val o = _order.value
                if (o == null || !o.running) return@launch
            }
        }
    }

    private fun cancelOrderPoll() {
        orderPollJob?.cancel()
        orderPollJob = null
    }

    // -----------------------------------------------------------------
    // 设备占用状态轮询（Bug 1+2：别人下单后本 App 感知不到）
    // -----------------------------------------------------------------

    private var idlePollJob: Job? = null

    /**
     * 重放 scanWasherCode 检查设备是否可下单。
     *
     * 别人占用洗衣机时，服务端 scanWasherCode 会返回 createOrderEnabled=false + reason，
     * 这是唯一能感知「机器被别人用了」的 API。30 秒轮一次，idle 时自动运行。
     */
    fun checkDeviceStatus() {
        viewModelScope.launch { checkDeviceStatusSuspend() }
    }

    /** [checkDeviceStatus] 的 suspend 版本，供轮询协程和 forceSync 等待完成。 */
    private suspend fun checkDeviceStatusSuspend() {
        val qr = store.currentQrCode()
        if (qr.isBlank()) return
        runCatching { api.scanWasherCode(qr) }
            .onSuccess { env ->
                val r = env.data?.result ?: return@onSuccess
                _scan.value = r
                log("设备状态检查：createOrderEnabled=${r.createOrderEnabled} reason=${r.reason ?: "-"} orderId=${r.orderId}")
                if (r.orderId > 0) {
                    // 服务端发现了未完成订单（自己的或别处下的），同步过来
                    if (r.orderId.toString() != _orderId.value) {
                        _orderId.value = r.orderId.toString()
                        store.saveOrderId(r.orderId.toString())
                        refreshOrder(r.orderId.toString())
                    }
                } else if (_orderId.value.isNotBlank() && _order.value?.running != true) {
                    // 服务端说没有未完成订单，但本地还挂着一个旧的（且不在运行中）→ 清掉
                    log("服务端无未完成订单，清理本地残留 orderId=${_orderId.value}")
                    _orderId.value = ""
                    _order.value = null
                    _paid.value = false
                    _startedAt.value = 0
                    _deviceRemain.value = null
                    store.saveOrderId("")
                }
            }
            .onFailure { log("设备状态检查失败：${it.message}") }
    }

    /**
     * idle 时的定时设备状态检查（30 秒一次）。
     * 订单运行中时自动退出（由 scheduleOrderPoll 接管），订单结束后重启。
     */
    private fun scheduleIdlePoll() {
        if (idlePollJob?.isActive == true) return
        idlePollJob = viewModelScope.launch {
            while (isActive) {
                checkDeviceStatusSuspend()
                delay(30_000)
                if (_order.value?.running == true) return@launch
            }
        }
    }

    private fun cancelIdlePoll() {
        idlePollJob?.cancel()
        idlePollJob = null
    }

    private var tickJob: Job? = null

    /**
     * 每秒推一次 [tick]。
     *
     * 服务端的 `remainTime` 只在轮询时才回写（15 秒一次），直接显示会一顿一顿的；
     * 界面拿 `tick - orderFetchedAt` 算出已经过去多少秒，从服务端给的剩余时间里减掉，
     * 看起来就是每秒都在走。订单不再运行后由 [stopTick] 停掉，不空转。
     */
    private fun startTick() {
        if (tickJob?.isActive == true) return
        tickJob = viewModelScope.launch {
            while (isActive) {
                _tick.value = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }

    private fun stopTick() {
        tickJob?.cancel()
        tickJob = null
    }

    fun refreshProgram(silent: Boolean = false) {
        val d = _device.value
        if (!d.valid) { if (!silent) _toast.value = "尚未绑定设备"; return }
        viewModelScope.launch {
            if (!silent) _busy.value = "正在加载洗衣模式"
            runCatching { api.programInfo(d.deviceId) }
                .onSuccess { env ->
                    val p = env.data
                    if (p == null) { if (!silent) _toast.value = "未取到设备信息"; return@onSuccess }
                    _program.value = p
                    val merged = d.copy(
                        storeId = p.storeId.ifBlank { d.storeId },
                        macAddress = p.macAddress.ifBlank { d.macAddress },
                        deviceTypeId = if (p.deviceTypeId != 0) p.deviceTypeId else d.deviceTypeId,
                        deviceNo = p.deviceNo,
                        storeName = p.storeName,
                        moduleType = if (p.moduleType >= 0) p.moduleType else d.moduleType
                    )
                    _device.value = merged
                    store.saveDevice(merged)
                    val current = _selectedModelId.value
                    val exists = p.deviceWashModel.any { it.workModelId == current }
                    if (!exists) {
                        _selectedModelId.value =
                            p.deviceWashModel.firstOrNull { it.isDefault }?.workModelId
                                ?: p.deviceWashModel.firstOrNull()?.workModelId
                                ?: 0
                    }
                }
                .onFailure { if (!silent) error(it) }
            if (!silent) _busy.value = null
        }
    }

    /**
     * 右上角刷新：拉设备信息 + 拉订单状态；订单在跑时顺带连一次蓝牙，
     * 把机身显示屏上的剩余时间读回来（这是唯一能拿到真实剩余时间的途径）。
     */
    fun refreshAll() {
        refreshProgram(silent = true)
        refreshOrder()
        if (_order.value?.running == true) syncDevice()
    }

    /**
     * 连一次洗衣机，读回机身侧剩余时间。
     *
     * 只在订单运行中才有意义；失败不打扰用户（机器不在身边时必然失败）。
     */
    fun syncDevice() {
        val d = _device.value
        if (!d.valid || !ModuleType.supportsBle(d.moduleType)) return
        viewModelScope.launch {
            val outcome = runCatching {
                when (d.moduleType) {
                    ModuleType.TYPE_BLE_NORDIC -> {
                        val w = NordicWasher(getApplication(), api) { log(it) }
                        w.sync(d.macAddress, d.deviceId) as? NordicWasher.Outcome.Started
                    }

                    ModuleType.TYPE_BLE_CYPRESS -> {
                        val w = CypressWasher(getApplication(), api) { log(it) }
                        w.sync(d.macAddress, d.deviceId)
                        null // Cypress 是透传通道，没有可读的机身状态特征
                    }

                    else -> null
                }
            }.getOrElse { log("读机身状态失败：${it.message}"); null }

            val minutes = outcome?.remainMinutes
            val statusTime = outcome?.statusTimeMs ?: 0L

            if (minutes == null) return@launch

            // 机身显示 0 分钟 = 洗程已结束，推动服务端结算
            if (minutes == 0) {
                if (_order.value?.running == true) {
                    log("机身显示 0 分钟，洗程已结束，推动服务端结算")
                    refreshOrder()
                }
                return@launch
            }

            // 时间戳比对：机身时间戳早于订单开始 → 待机值（上次洗程总时长），不作倒计时基准
            val orderStart = _order.value?.startMs ?: 0L
            if (statusTime > 0 && orderStart > 0 && statusTime < orderStart - 60_000) {
                log("机身时间戳早于订单开始（待机值），不作为倒计时基准")
                return@launch
            }

            // 只有当前订单确实在跑才记录
            if (_order.value?.running != true) {
                log("机身显示 $minutes 分钟，但订单已不在运行，不作为倒计时基准")
                return@launch
            }
            _deviceRemain.value = minutes * 60
            _deviceRemainAt.value = System.currentTimeMillis()
            log("机身剩余 $minutes 分钟（已与显示屏对齐）")
        }
    }

    fun selectModel(id: Int) {
        _selectedModelId.value = id
        viewModelScope.launch { store.saveLastWorkModel(id) }
    }

    fun selectedModel(): DeviceWashModel? =
        _program.value?.deviceWashModel?.firstOrNull { it.workModelId == _selectedModelId.value }

    // -----------------------------------------------------------------
    // 下单
    // -----------------------------------------------------------------

    /**
     * 下单。
     *
     * @param thenPay 下单成功后是否立刻接着发起支付 —— 「一键开洗」走这条路。
     */
    fun createOrder(thenPay: Boolean = false) {
        val d = _device.value
        val p = _program.value
        val m = selectedModel()
        if (!d.valid || p == null || m == null) { _toast.value = "请先扫码并选择模式"; return }
        viewModelScope.launch {
            _busy.value = "正在下单"
            runCatching {
                api.createOrder(
                    CreateOrderRequest(
                        deviceTypeId = d.deviceTypeId,
                        deviceId = d.deviceId,
                        deviceWashModelId = m.workModelId,
                        storeId = d.storeId
                    )
                )
            }
                .onSuccess { env ->
                    val order = env.data
                    if (order == null || order.orderId.isBlank()) {
                        _toast.value = "下单失败：无订单号"
                    } else {
                        _orderId.value = order.orderId
                        store.saveOrderId(order.orderId)
                        _toast.value = "下单成功 ${order.orderId}（待支付）"
                        val merged = d.copy(macAddress = order.macAddress.ifBlank { d.macAddress })
                        _device.value = merged
                        store.saveDevice(merged)
                        log("orderId=${order.orderId} status=${order.status}")
                        // 下单成功即拉收银台渠道，用户可以直接在本 APP 内付掉
                        loadPayMethods(thenPay)
                    }
                }
                .onFailure { error(it) }
            _busy.value = null
        }
    }

    /**
     * 取消当前订单。
     *
     * 主要用途：清掉「卡在服务端」的旧订单。未支付的订单会立即释放设备；
     * 已支付的订单按服务端退款规则处理。成功后本机不再持有待启动订单号。
     */
    fun cancelOrder() {
        val oid = _orderId.value
        if (oid.isBlank()) { _toast.value = "没有可取消的订单"; return }
        viewModelScope.launch {
            _busy.value = "正在取消订单"
            runCatching { api.cancelOrder(oid) }
                .onSuccess {
                    log("订单 $oid 已取消")
                    _toast.value = "订单已取消"
                    _orderId.value = ""
                    _order.value = null
                    _paid.value = false
                    _startedAt.value = 0
                    _payChannels.value = emptyList()
                    _deviceRemain.value = null
                    store.saveOrderId("")
                    cancelOrderPoll()
                    stopTick()
                    refreshProgram(silent = true)
                }
                .onFailure { e ->
                    log("取消失败：${e.message}")
                    _toast.value = "取消失败：${e.message?.take(80)}"
                }
            _busy.value = null
        }
    }

    // -----------------------------------------------------------------
    // 支付（APP 内唤起支付宝）
    //
    // 链路对齐 cashierNew.js：
    //   orders/create → orderId
    //   GET payment/methods          → 渠道列表
    //   GET payment/arguments        → payInfo.orderInfo（U净 商户签名串）
    //   PayTask.pay(orderInfo)       → 支付宝收银台（用户指纹/面容确认）
    //   GET payment/{id}/payStatus   → 回执
    //   GET app/payment/{id}/lastPayStatus → 轮询（status == "3" 成功）
    //
    // 我们不经手任何资金：orderInfo 由 U净 服务端签发，钱进 U净 商户账户。
    // -----------------------------------------------------------------

    fun selectChannel(channel: String) { _selectedChannel.value = channel }

    /**
     * 拉收银台渠道列表。
     *
     * @param thenPay 拉完渠道后是否立刻发起支付（「一键开洗」）。
     */
    fun loadPayMethods(thenPay: Boolean = false) {
        val oid = _orderId.value
        if (oid.isBlank()) return
        viewModelScope.launch {
            runCatching {
                api.payMethods(
                    orderId = oid,
                    serviceSubjectId = _program.value?.serviceSubjectId?.toString(),
                    deviceId = _device.value.deviceId
                )
            }
                .onSuccess { env ->
                    val list = env.data?.channels.orEmpty()
                        .filter { !it.needUpgrade }
                        .sortedByDescending { it.weight4Display }
                    _payChannels.value = list
                    if (list.isNotEmpty() && list.none { it.channel == _selectedChannel.value }) {
                        _selectedChannel.value =
                            list.firstOrNull { it.isDefault }?.channel ?: list.first().channel
                    }
                    log("支付渠道 ${list.size} 个：" + list.joinToString { "${it.channel}(${it.displayName})" })
                    if (thenPay && list.isNotEmpty()) startPay()
                }
                .onFailure { log("拉支付渠道失败：${it.message}") }
        }
    }

    /**
     * 一键开洗：没有订单就先下单，有订单就直接付款。
     *
     * 对应 UI 主按钮「支付 ¥x.xx · 开洗」。付款成功后由 [onPayResult]
     * 把按钮切成「启动洗衣机」—— 启动这一步留给用户手动点，避免误触开机。
     */
    fun payAndWash() {
        if (_orderId.value.isNotBlank()) startPay() else createOrder(thenPay = true)
    }

    /**
     * 取支付参数。成功时把 `orderInfo` 放进 [pendingOrderInfo]，由 UI 层唤起支付宝。
     *
     * 三种响应分支（对齐官方 `getPayArguments`）：
     *  - `skipPay`     → 服务端已免密扣款完成，无需唤起收银台；
     *  - `jumpToQuery` → 跳支付状态轮询；
     *  - 有 orderStr   → 交给 PayTask。
     */
    fun startPay() {
        val oid = _orderId.value
        if (oid.isBlank()) { _toast.value = "请先下单"; return }
        val channel = _selectedChannel.value
        if (channel.isBlank()) { _toast.value = "请选择支付方式"; return }
        viewModelScope.launch {
            _busy.value = "正在准备支付"
            val ch = _payChannels.value.firstOrNull { it.channel == channel }
            runCatching {
                api.payArguments(
                    channel = channel,
                    orderId = oid,
                    payVersion = 1,
                    // 只有已签约的渠道才带 chooseSign：带上才可能触发服务端免密直付
                    chooseSign = if (ch?.signCapable == true) 1 else null,
                    branchType = ch?.branchType
                )
            }
                .onSuccess { env ->
                    val data = env.data
                    when {
                        data == null ->
                            _toast.value = "服务端未返回支付参数"

                        data.skipPay -> {
                            log("服务端免密直付成功（skipPay=true），无需唤起收银台")
                            _toast.value = "已免密扣款，正在刷新订单"
                            refreshOrder(oid)
                        }

                        data.jumpToQuery -> {
                            _toast.value = "正在确认支付结果"
                            pollPayStatus(oid)
                        }

                        data.orderStr.isNotBlank() -> {
                            log("取到支付串（${data.orderStr.length} 字符），准备唤起支付宝")
                            _pendingOrderInfo.value = data.orderStr
                        }

                        else -> _toast.value = "服务端未签发支付串"
                    }
                }
                .onFailure { error(it) }
            _busy.value = null
        }
    }

    /** UI 层唤起支付宝后的结果回传。 */
    fun onPayResult(resultStatus: String, memo: String) {
        val oid = _orderId.value
        log("支付宝返回 resultStatus=$resultStatus memo=$memo")
        viewModelScope.launch {
            when (resultStatus) {
                "9000" -> {
                    _toast.value = "支付成功"
                    // 付款成功 → 立刻切「启动洗衣机」，不等 payStatusCommon 网络回执（可能耗时数秒）。
                    // refreshOrder 里的 _paid 是单向赋值，不会把这里打回未付。
                    _paid.value = true
                    runCatching { api.payStatusCommon(oid, _selectedChannel.value) }
                        .onFailure { log("支付回执失败：${it.message}") }
                    refreshOrder(oid)
                }
                "8000" -> {
                    _toast.value = "支付处理中，正在确认结果"
                    pollPayStatus(oid)
                }
                "6001" -> _toast.value = "已取消支付"
                else -> _toast.value = "支付未完成（$resultStatus ${memo.ifBlank { "" }}）"
            }
        }
    }

    fun consumePendingOrderInfo() { _pendingOrderInfo.value = null }

    /** 轮询支付结果，最多 5 次 × 1.5s。 */
    private suspend fun pollPayStatus(orderId: String) {
        repeat(5) { i ->
            val ok = runCatching { api.lastPayStatus(orderId) }
                .onFailure { log("查支付状态失败：${it.message}") }
                .getOrNull()?.data?.success == true
            if (ok) {
                log("支付状态确认为成功（第 ${i + 1} 次查询）")
                _toast.value = "支付成功"
                refreshOrder(orderId)
                return
            }
            kotlinx.coroutines.delay(1500)
        }
        _toast.value = "支付结果未确认，可稍后扫一次码刷新"
        refreshOrder(orderId)
    }

    // -----------------------------------------------------------------
    // 蓝牙启动
    // -----------------------------------------------------------------

    fun startWash(command: String = NordicCommand.START_WASH) {
        val d = _device.value
        if (!d.valid) { _toast.value = "尚未绑定设备"; return }
        val selected = _selectedNearbyAddress.value
        if (selected.isNotBlank() && DeviceResolver.normalize(selected) != DeviceResolver.normalize(d.macAddress)) {
            _toast.value = "所选机器与当前订单不匹配，请先扫码绑定这台机器并取得有效订单"
            return
        }
        if (!ModuleType.supportsBle(d.moduleType)) {
            _toast.value = "该设备模块(${d.moduleType})不支持蓝牙直连"
            return
        }
        if (_orderId.value.isBlank()) {
            // 没有订单号就别连蓝牙了：服务端不会签发指令，连了也是白跑一趟（实测会静默空转）
            _toast.value = "没有待启动的订单。请先用支付宝小程序/U净扫码下单并支付，回到本页重新扫码"
            log("拒绝启动：当前无待启动订单（orderId 为空）")
            return
        }
        val o = _order.value
        if (o != null && o.running) {
            // 订单已经在跑。服务端对这种订单会静默拒绝再次签发指令（返回 `{}`），
            // 再连一次蓝牙也只会拿到空指令，纯属浪费用户时间。
            val tail = o.remainText.ifBlank { o.statusRemark.orEmpty() }
            _toast.value = "订单已在运行中${if (tail.isBlank()) "" else "（$tail）"}"
            log("拒绝启动：订单 ${o.orderId} 已在运行（status=${o.status} $tail）")
            return
        }
        viewModelScope.launch {
            // 「启动中…」过渡态：蓝牙连接 + 上报 + 写指令通常要几秒，
            // 这段时间按钮必须锁住，否则用户会连点、重复上报（服务端会静默拒绝）。
            _starting.value = true
            _busy.value = "蓝牙连接中"
            try {
                when (d.moduleType) {
                    ModuleType.TYPE_BLE_NORDIC -> runNordicStart(d, command)
                    ModuleType.TYPE_BLE_CYPRESS -> runCypressStart(d, command)
                    else -> {
                        _toast.value = "模块 ${d.moduleType} 暂不支持蓝牙直连"
                        log("不支持的模块类型 ${d.moduleType}")
                    }
                }
            } finally {
                _starting.value = false
                _busy.value = null
            }
        }
    }

    private suspend fun runNordicStart(d: SavedDevice, command: String) {
        val washer = NordicWasher(getApplication(), api) { log(it) }
        val result = runCatching {
            washer.run(
                macAddress = d.macAddress,
                deviceId = d.deviceId,
                orderId = _orderId.value.ifBlank { null },
                command = command,
                reportType = 2
            )
        }.getOrElse { NordicWasher.Outcome.Failed(BleError.CONNECT_FAILED, it.message ?: "异常") }
        when (result) {
            is NordicWasher.Outcome.Started -> {
                _toast.value = "已启动，洗衣机开始工作"
                // 指令已下发。这里刻意不清 _paid：服务端可能还停在下单时的状态，
                // 由 refreshOrder 按真实 status 接管（21/40 会走 running 分支）。
                // 在服务端反应过来之前，用本地时间戳先兜一段「运行中」。
                _orderFetchedAt.value = System.currentTimeMillis()
                _startedAt.value = System.currentTimeMillis()
                // 顺手记下机身侧剩余时间：这是显示屏上的数，后面倒计时以它为准
                val statusTime = result.statusTimeMs
                result.remainMinutes?.takeIf { it > 0 }?.let { m ->
                    // 时间戳比对：机身时间戳早于订单开始 → 待机值，不作倒计时基准
                    val orderStart = _order.value?.startMs ?: 0L
                    if (statusTime > 0 && orderStart > 0 && statusTime < orderStart - 60_000) {
                        log("机身时间戳早于订单开始（待机值），不作为倒计时基准")
                    } else {
                        _deviceRemain.value = m * 60
                        _deviceRemainAt.value = System.currentTimeMillis()
                        log("机身剩余 $m 分钟（读自 STATUS 特征）")
                    }
                }
                if (result.remainMinutes == 0) {
                    log("机身显示 0 分钟（刚启动或待机值），不作为倒计时基准")
                }
                log("Nordic 启动成功")
                // 给服务端一点时间推进状态，然后拉一次校准
                delay(3_000)
                refreshOrder()
            }
            is NordicWasher.Outcome.Pending -> {
                _toast.value = "设备已有订单：${result.orderId}"
                log("已有订单 ${result.orderId}")
            }
            is NordicWasher.Outcome.Failed -> {
                _toast.value = "启动失败[${result.code}] ${result.message}"
                log("启动失败 ${result.code} ${result.message}")
            }
        }
    }

    private suspend fun runCypressStart(d: SavedDevice, command: String) {
        val washer = CypressWasher(getApplication(), api) { log(it) }
        val result = runCatching {
            washer.run(
                macAddress = d.macAddress,
                deviceId = d.deviceId,
                command = command,
                operation = CypressWasher.OP_CONTROL_DEVICE
            )
        }.getOrElse { CypressWasher.Outcome.Failed(BleError.CONNECT_FAILED, it.message ?: "异常") }
        when (result) {
            is CypressWasher.Outcome.Started -> {
                _toast.value = "已启动，洗衣机开始工作"
                // 同 Nordic：不清 _paid，交给 refreshOrder 按真实 status 接管
                _orderFetchedAt.value = System.currentTimeMillis()
                _startedAt.value = System.currentTimeMillis()
                log("Cypress 启动成功，写入 ${result.packageCount} 包")
                delay(3_000)
                refreshOrder()
            }
            is CypressWasher.Outcome.Failed -> {
                _toast.value = "启动失败[${result.code}] ${result.message}"
                log("启动失败 ${result.code} ${result.message}")
            }
        }
    }

    fun syncStatus() {
        viewModelScope.launch { syncStatusSuspend() }
    }

    /** [syncStatus] 的 suspend 版本，供 forceSync 等待 BLE 上报完成。 */
    private suspend fun syncStatusSuspend() {
        val d = _device.value
        if (!d.valid) return
        if (!ModuleType.supportsBle(d.moduleType)) {
            _toast.value = "该设备模块(${d.moduleType})不支持蓝牙直连"
            return
        }
        _busy.value = "同步设备状态"
        runCatching {
            when (d.moduleType) {
                ModuleType.TYPE_BLE_NORDIC ->
                    NordicWasher(getApplication(), api) { log(it) }.sync(d.macAddress, d.deviceId)
                ModuleType.TYPE_BLE_CYPRESS ->
                    CypressWasher(getApplication(), api) { log(it) }.sync(d.macAddress, d.deviceId)
                else -> {
                    _toast.value = "模块 ${d.moduleType} 暂不支持蓝牙直连"
                    log("不支持的模块类型 ${d.moduleType}")
                }
            }
        }.onFailure { error(it) }
        refreshProgram(silent = true)
        // 同步顺带把订单状态刷一遍：type=3 上报会让服务端回写最新 orderStatus
        if (_orderId.value.isNotBlank()) refreshOrder()
        _busy.value = null
    }

    /**
     * 强制同步（Bug 4：服务端状态卡死导致误报「洗衣机在使用中」）。
     *
     * 连一次蓝牙上报（reportType=3）可推动服务端结算卡住的订单，
     * 然后重新检查设备占用状态。室友首次扫码遇到「使用中」但实际空闲时点这个。
     */
    fun forceSync() {
        viewModelScope.launch {
            _busy.value = "正在强制同步"
            syncStatusSuspend()   // 等 BLE 连接+上报完成（约 3-5 秒）
            delay(3_000)          // 等服务端结算
            checkDeviceStatusSuspend()  // 等状态检查完成
            _busy.value = null
        }
    }
}
