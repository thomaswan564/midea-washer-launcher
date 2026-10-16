package online.ujing.mini.ui

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import online.ujing.mini.R
import online.ujing.mini.net.DeviceWashModel
import online.ujing.mini.net.ModuleType
import online.ujing.mini.net.OrderData
import online.ujing.mini.net.ScanResult
import online.ujing.mini.net.WasherDeviceType
import online.ujing.mini.net.WasherStatus
import online.ujing.mini.pay.AlipayLauncher

/**
 * 整个 App 只有一条主栈：登录 → 设备（默认折叠）→ 选模式 → 一个主按钮。
 *
 * 刻意不放的东西：运行日志、支付渠道选择、同步按钮、埋点、广告位。
 * 设备一旦扫码绑定就永久保存在本机，之后打开即用，不需要再扫。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel) {
    val loggedIn by vm.loggedIn.collectAsState()
    val busy by vm.busy.collectAsState()
    val toast by vm.toast.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { vm.onScan(it) }
    }
    val scanOptions = remember {
        ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("对准洗衣机机身上的二维码")
            setBeepEnabled(false)
            setOrientationLocked(true)
        }
    }

    // 服务端签发的支付宝订单串到手后，由 UI 层唤起收银台。
    // PayTask 构造需要 Activity，而 ViewModel 拿不到，故经 pendingOrderInfo 中转。
    val activity = LocalActivity.current
    val pendingOrderInfo by vm.pendingOrderInfo.collectAsState()
    LaunchedEffect(pendingOrderInfo) {
        val info = pendingOrderInfo ?: return@LaunchedEffect
        val act = activity ?: return@LaunchedEffect
        vm.consumePendingOrderInfo()
        val (status, memo) = AlipayLauncher.pay(act, info)
        vm.onPayResult(status, memo)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("洗衣机", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (loggedIn) {
                        // 这一条就是唯一的绑定入口：第一次扫码，或以后换洗衣机。
                        // 已绑定的设备存在本机，日常使用不需要再扫。
                        TextButton(onClick = { scanLauncher.launch(scanOptions) }) { Text("扫码") }
                        IconButton(onClick = { vm.refreshAll() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                        TextButton(onClick = { vm.logout() }) { Text("退出") }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (busy != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(18.dp).height(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(busy.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (!loggedIn) {
                LoginCard(vm)
            } else {
                DeviceCard(vm) { scanLauncher.launch(scanOptions) }
                ModeSection(vm)
                MainAction(vm)
            }
        }
    }
}

@Composable
private fun LoginCard(vm: MainViewModel) {
    val mobile by vm.mobile.collectAsState()
    val codeSent by vm.codeSent.collectAsState()
    var captcha by remember { mutableStateOf("") }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("登录", style = MaterialTheme.typography.titleMedium)
            Text("用你的 U净 账号登录，本机直接与洗衣机服务端通信。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = mobile,
                onValueChange = vm::onMobileChange,
                label = { Text("手机号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (codeSent) {
                OutlinedTextField(
                    value = captcha,
                    onValueChange = { captcha = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("短信验证码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = vm::sendCode, enabled = mobile.length == 11) {
                    Text(if (codeSent) "重新发送" else "发送验证码")
                }
                Button(onClick = { vm.login(captcha) }, enabled = codeSent && captcha.isNotBlank()) {
                    Text("登录")
                }
            }
        }
    }
}

/**
 * 设备卡。默认只占一行：洗衣机 + 编号 + 门店 + 状态。
 * 点一下才展开 MAC / 通道 / 订单号 / 温馨提示这些细节。
 */
@Composable
private fun DeviceCard(vm: MainViewModel, onScan: () -> Unit) {
    val device by vm.device.collectAsState()
    val nearbyWashers by vm.nearbyWashers.collectAsState()
    val selectedNearbyAddress by vm.selectedNearbyAddress.collectAsState()
    val program by vm.program.collectAsState()
    val scan by vm.scan.collectAsState()
    val orderId by vm.orderId.collectAsState()
    val order by vm.order.collectAsState()
    val tick by vm.tick.collectAsState()
    val fetchedAt by vm.orderFetchedAt.collectAsState()
    val startedAt by vm.startedAt.collectAsState()
    val deviceRemain by vm.deviceRemain.collectAsState()
    val deviceAt by vm.deviceRemainAt.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = device.valid) { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (device.valid) "洗衣机 · ${device.deviceNo.ifBlank { "-" }} 号"
                        else "还没绑定洗衣机",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (device.valid) device.storeName.ifBlank { device.storeId }
                        else "扫一次码，以后打开就直接用",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (device.valid) {
                    StatusBadge(order, scan, liveRemain(order, tick, fetchedAt, startedAt, deviceRemain, deviceAt))
                    Spacer(Modifier.width(6.dp))
                    Text(if (expanded) "▴" else "▾", style = MaterialTheme.typography.titleMedium)
                }
            }

            if (!device.valid) {
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Text("扫码绑定洗衣机")
                }
            } else if (expanded) {
                HorizontalDivider()
                InfoLine("编号", device.deviceNo.ifBlank { "-" })
                InfoLine("机型", WasherDeviceType.displayName(device.deviceTypeId))
                InfoLine("门店", device.storeName.ifBlank { device.storeId })
                InfoLine("MAC", device.macAddress)
                InfoLine("通道", channelName(device.moduleType))
                scan?.let {
                    InfoLine("可下单", if (it.createOrderEnabled) "是" else "否（${it.reason ?: "-"}）")
                }
                if (orderId.isNotBlank()) InfoLine("订单号", orderId)
                program?.warmTip?.takeIf { it.isNotBlank() }?.let {
                    HorizontalDivider()
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Text("换一台洗衣机（扫码）")
                }
            }

            OutlinedButton(
                onClick = vm::discoverNearbyWashers,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("搜索附近 Midea 洗衣机")
            }
            nearbyWashers.forEach { washer ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.selectNearbyWasher(washer.address) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedNearbyAddress.equals(washer.address, ignoreCase = true),
                        onClick = null
                    )
                    Column(Modifier.weight(1f)) {
                        Text(washer.name, style = MaterialTheme.typography.bodyLarge)
                        Text(washer.address, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${washer.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * 倒计时：把「订单还剩多少秒」推进到此刻。
 *
 * 四级来源，逐级降级：
 * 0. 蓝牙连上洗衣机读到的机身剩余（STATUS 特征第 13 字节）—— 与机身显示屏一致，最权威。
 * 1. 服务端给了 `remainTime` —— 以最近一次拉取的时刻为锚，本地每秒减（能反映暂停等真实变化）。
 * 2. 服务端还没算（「启动中」status=21 阶段 `remainTime` 为 0）—— 用订单开始时刻 + 模式时长倒推。
 * 3. 前面都没有 —— 从用户点「启动洗衣机」那刻起算。
 */
private fun liveRemain(
    order: OrderData?, tick: Long, fetchedAt: Long, startedAt: Long,
    deviceRemain: Int?, deviceAt: Long,
): Int {
    if (order == null) return 0
    val total = order.totalSeconds

    // 0. 机身 STATUS 读到的剩余（与显示屏一致）
    if (deviceRemain != null && deviceRemain > 0 && deviceAt > 0 && tick > deviceAt) {
        return (deviceRemain - ((tick - deviceAt) / 1000).toInt()).coerceAtLeast(0)
    }

    // 1. 服务端剩余时间 + 本地走秒
    val server = order.remainSeconds
    val anchor = if (fetchedAt > 0) fetchedAt else startedAt
    if (server > 0 && anchor > 0 && tick > anchor) {
        return (server - ((tick - anchor) / 1000).toInt()).coerceAtLeast(0)
    }

    // 2. 按订单开始时刻倒推（结合模式时长）
    val startMs = order.startMs
    if (startMs > 0 && total > 0) {
        if (tick <= startMs) return total
        return (total - ((tick - startMs) / 1000).toInt()).coerceAtLeast(0)
    }

    // 3. 本地兜底：从点启动那刻起算
    if (startedAt > 0 && total > 0 && tick > startedAt) {
        return (total - ((tick - startedAt) / 1000).toInt()).coerceAtLeast(0)
    }

    return 0
}

/** 秒 → `M:SS`。 */
private fun clock(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

/** 折叠态右侧的状态角标：使用中 / 空闲 / 待付款 / 待启动 / 运行中（剩余时间）/ 已完成。 */
@Composable
private fun StatusBadge(order: OrderData?, scan: ScanResult?, remain: Int) {
    val (text, color) = when {
        order == null && scan?.createOrderEnabled == false -> "使用中" to MaterialTheme.colorScheme.error
        order == null -> "空闲" to MaterialTheme.colorScheme.outline
        order.status == WasherStatus.COMPLETED -> "已完成" to MaterialTheme.colorScheme.tertiary
        order.running -> (if (remain > 0) clock(remain) else "运行中") to MaterialTheme.colorScheme.primary
        order.payFlag == 1 -> "待启动" to MaterialTheme.colorScheme.secondary
        else -> order.statusRemark.orEmpty().ifBlank { "待付款" } to MaterialTheme.colorScheme.secondary
    }
    Text(text, color = color, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(56.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun channelName(moduleType: Int): String = when (moduleType) {
    ModuleType.TYPE_BLE_NORDIC -> "蓝牙直连 (Nordic)"
    ModuleType.TYPE_BLE_CYPRESS -> "蓝牙直连 (Cypress)"
    ModuleType.TYPE_2G_BLE -> "2G + 蓝牙"
    ModuleType.TYPE_NB_BLE -> "NB + 蓝牙"
    ModuleType.TYPE_NB -> "NB-IoT（需官方云端）"
    ModuleType.TYPE_4G -> "4G（需官方云端）"
    else -> "未知 ($moduleType)"
}

@Composable
private fun ModeSection(vm: MainViewModel) {
    val program by vm.program.collectAsState()
    val selectedId by vm.selectedModelId.collectAsState()
    val models = program?.deviceWashModel.orEmpty()

    if (models.isEmpty()) return

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("洗衣模式", style = MaterialTheme.typography.titleMedium)
            models.forEach { m ->
                ModeRow(
                    model = m,
                    selected = m.workModelId == selectedId,
                    onSelect = { vm.selectModel(m.workModelId) }
                )
            }
        }
    }
}

@Composable
private fun ModeRow(model: DeviceWashModel, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(model.workModelName, style = MaterialTheme.typography.bodyLarge)
            if (model.description.isNotBlank()) {
                Text(model.description, style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "¥" + "%.2f".format(model.displayPriceCents / 100.0),
                style = MaterialTheme.typography.bodyLarge
            )
            Text("${model.time} 分钟", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * 唯一的主操作区。按订单状态只给一个按钮，文案随状态变：
 *   空闲 → 「支付 ¥x.xx · 开洗」→ 付款完成 →「启动洗衣机」→ 运行中 →（洗完后自动收尾，回到空闲）
 *
 * 付款走支付宝，App 只转发服务端签发的订单串；启动走蓝牙，需要站在机器旁。
 */
@Composable
private fun MainAction(vm: MainViewModel) {
    val device by vm.device.collectAsState()
    val program by vm.program.collectAsState()
    val scan by vm.scan.collectAsState()
    val selectedId by vm.selectedModelId.collectAsState()
    val orderId by vm.orderId.collectAsState()
    val order by vm.order.collectAsState()
    val paid by vm.paid.collectAsState()
    val starting by vm.starting.collectAsState()
    val tick by vm.tick.collectAsState()
    val fetchedAt by vm.orderFetchedAt.collectAsState()
    val startedAt by vm.startedAt.collectAsState()
    val deviceRemain by vm.deviceRemain.collectAsState()
    val deviceAt by vm.deviceRemainAt.collectAsState()

    if (!device.valid) return

    val model = program?.deviceWashModel?.firstOrNull { it.workModelId == selectedId }
    val running = order?.running == true
    // 刚点完启动、服务端还没把状态推到「运行中」的这段窗口，界面也要显示成在跑，
    // 否则按钮会闪回「启动洗衣机」，让人以为没点上。
    val justStarted = startedAt > 0 && tick - startedAt < 180_000
    val inProgress = running || justStarted
    val remain = liveRemain(order, tick, fetchedAt, startedAt, deviceRemain, deviceAt)
    val hasOrder = orderId.isNotBlank()

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                starting -> {
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Icon(painterResource(R.drawable.ic_bluetooth), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("启动中…")
                    }
                    Hint("正在连接洗衣机并下发启动指令，请站在机器旁稍候（约 5 秒）。")
                }

                inProgress -> {
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Text(if (remain > 0) "运行中 · ${clock(remain)}" else "运行中")
                    }
                    Hint("洗衣机正在工作，不需要操作。洗完后会自动收尾，这里回到可以再洗的状态。")
                }

                paid || (hasOrder && order?.payFlag == 1) -> {
                    Button(onClick = { vm.startWash() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(painterResource(R.drawable.ic_bluetooth), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("启动洗衣机")
                    }
                    Hint("已付款。站到机器旁（10 米内）点上面按钮，洗衣机就开始洗。")
                }

                hasOrder -> {
                    // 订单存在但未付款（例如以前在官方渠道下单没付）
                    Button(onClick = { vm.startPay() }, modifier = Modifier.fillMaxWidth()) {
                        val amount = order?.payPrice?.takeIf { it > 0 }
                        Text(if (amount != null) "支付 ¥%.2f · 开洗".format(amount) else "支付 · 开洗")
                    }
                    Hint("订单 $orderId 还没付款。")
                }

                scan?.createOrderEnabled == false && !hasOrder -> {
                    // 服务端标记设备不可下单，且本地没有进行中的订单 → 被别人占用了
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Text("洗衣机使用中")
                    }
                    Hint(scan?.reason ?: "其他人正在使用，请稍后再试")
                    OutlinedButton(onClick = { vm.forceSync() }, modifier = Modifier.fillMaxWidth()) {
                        Text("强制同步（推动服务端结算）")
                    }
                }

                else -> {
                    Button(
                        onClick = { vm.payAndWash() },
                        enabled = model != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (model == null) "请先选择洗衣模式"
                            else "支付 ¥%.2f · 开洗".format(model.displayPriceCents / 100.0)
                        )
                    }
                    Hint("付款由支付宝完成，钱直接进洗衣机运营方，App 不经手任何资金。")
                }
            }

            if (hasOrder && !inProgress && !starting) {
                OutlinedButton(onClick = { vm.cancelOrder() }, modifier = Modifier.fillMaxWidth()) {
                    Text("取消订单")
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}
