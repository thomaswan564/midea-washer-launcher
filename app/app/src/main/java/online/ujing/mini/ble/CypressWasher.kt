package online.ujing.mini.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import online.ujing.mini.net.UjingApi
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Cypress CYBLE-20736 透传通道（moduleType = 5）。
 *
 * 与 Nordic 的本质差别：本通道客户端**不做任何加解密**。
 * 它只做两件事——
 *   1. 把设备回传的数据帧组包后原样上报云端 POST /api/v1/ble/data/report；
 *   2. 把云端算好的指令包逐条写回 APP_DEVICE_DATA。
 *
 * 签名、随机数校验、指令帧生成全在服务端。参考官方 bluetoothConnect.js 的
 * cypress20736BuleTooth / P(command) / R(deviceId, packages) 三段实现。
 */
class CypressWasher(
    private val context: Context,
    private val api: UjingApi,
    private val onLog: (String) -> Unit = {}
) {

    sealed class Outcome {
        /** 云端指令已下发（packageCount 为实际写入的包数，0 表示云端无指令）。 */
        data class Started(val command: String, val packageCount: Int) : Outcome()

        data class Failed(val code: Int, val message: String) : Outcome()
    }

    companion object {
        /** 对应官方 BLE_CYPRESS_OPERATION。 */
        const val OP_SYNC_HISTORY = 1
        const val OP_SYNC_DEVICE_STATUS = 2
        const val OP_CONTROL_DEVICE = 3

        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val FRAME_WAIT_MS = 12_000L
        private const val PACKAGE_GAP_MS = 1_000L

        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim().replace(" ", "").replace(":", "")
            val padded = if (clean.length % 2 == 1) "0$clean" else clean
            return ByteArray(padded.length / 2) { i ->
                padded.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        fun bytesToHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02X".format(it) }
    }

    private var gatt: BluetoothGatt? = null
    private val writeWaiters = ConcurrentHashMap<UUID, CompletableDeferred<Boolean>>()
    private var connected: CompletableDeferred<Boolean>? = null
    private var servicesReady: CompletableDeferred<Boolean>? = null
    private var descriptorWaiter: CompletableDeferred<Boolean>? = null
    private var framesReady: CompletableDeferred<List<FrameCodec.Frame>>? = null
    private val assembler = FrameCodec.Assembler()

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                onLog("已连接，发起服务发现")
                g.discoverServices()
                connected?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onLog("连接断开 (status=$status)")
                connected?.complete(false)
                servicesReady?.complete(false)
                descriptorWaiter?.complete(false)
                writeWaiters.values.forEach { it.complete(false) }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val found = g.services.sumOf { it.characteristics.size }
                onLog("服务发现完成，特征数=$found")
                g.requestMtu(247)
                servicesReady?.complete(true)
            } else {
                onLog("服务发现失败 status=$status")
                servicesReady?.complete(false)
            }
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) onLog("写失败 ${c.uuid} status=$status")
            writeWaiters[c.uuid]?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            d: BluetoothGattDescriptor,
            status: Int
        ) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            if (!ok) onLog("描述符写入失败 status=$status")
            descriptorWaiter?.complete(ok)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (c.uuid) {
                BleUuids.CYPRESS_DEVICE_APP_DATA -> handleData(value)
                BleUuids.CYPRESS_DEVICE_APP_ACK -> onLog("ACK ← ${bytesToHex(value)}")
                else -> onLog("notify ${c.uuid} → ${bytesToHex(value)}")
            }
        }
    }

    private fun handleData(value: ByteArray) {
        val hex = bytesToHex(value)
        onLog("recv $hex")
        val frame = FrameCodec.parseFrame(hex) ?: run {
            onLog("帧头不足 8 字节，忽略")
            return
        }
        var done = false
        var snapshot: List<FrameCodec.Frame> = emptyList()
        synchronized(assembler) {
            if (assembler.offer(frame)) {
                done = true
                snapshot = assembler.collected()
            }
        }
        if (done) framesReady?.complete(snapshot)
    }

    // -----------------------------------------------------------------
    // 主流程
    // -----------------------------------------------------------------

    /**
     * 跑完一轮 Cypress 交互。
     *
     * @param command    operation = OP_CONTROL_DEVICE 时下发的业务指令（如 startWash）
     * @param operation  OP_SYNC_HISTORY / OP_SYNC_DEVICE_STATUS / OP_CONTROL_DEVICE
     */
    suspend fun run(
        macAddress: String,
        deviceId: String,
        command: String,
        operation: Int = OP_CONTROL_DEVICE,
        connectTimeoutMs: Long = 60_000L
    ): Outcome = withTimeout(connectTimeoutMs) {
        // 必须先扫描：BLE MAC 是 Random Static Address，直接 getRemoteDevice 会一直连不上
        val device = DeviceResolver.scanFor(context, macAddress, onLog = onLog)
            ?: DeviceResolver.resolve(context, macAddress)
            ?: return@withTimeout Outcome.Failed(BleError.NOT_FOUND_DEVICE, "未找到设备 $macAddress")

        try {
            // 实测首连常被设备以 status=62 (GATT_CONN_FAIL_ESTABLISH) 拒绝，重试一次即可
            var connected = false
            for (attempt in 1..2) {
                if (connect(device)) {
                    connected = true
                    break
                }
                onLog("第 $attempt 次连接失败，稍后重试")
                delay(600)
            }
            if (!connected) {
                return@withTimeout Outcome.Failed(BleError.CONNECT_ERROR, "GATT 连接失败")
            }
            if (!awaitServices()) {
                return@withTimeout Outcome.Failed(BleError.SERVICE_NOT_FOUND, "未发现服务")
            }
            if (!enableNotify(BleUuids.CYPRESS_DEVICE_APP_DATA)) {
                return@withTimeout Outcome.Failed(BleError.SERVICE_NOT_FOUND, "数据通道 notify 未开启")
            }
            if (!enableNotify(BleUuids.CYPRESS_DEVICE_APP_ACK)) {
                return@withTimeout Outcome.Failed(BleError.SERVICE_NOT_FOUND, "ACK 通道 notify 未开启")
            }

            // 1) 触发随机数交换并收齐设备数据帧
            val frames = collectFrames {
                write(
                    BleUuids.CYPRESS_APP_DEVICE_DATA,
                    hexToBytes(BleUuids.CYPRESS_READ_RANDOM_PAYLOAD)
                )
            }
            if (frames.isEmpty()) {
                onLog("未收到设备帧，放弃本轮交互")
                return@withTimeout Outcome.Failed(BleError.CONNECT_ERROR, "设备无响应（未收到任何帧）")
            }
            onLog("组包完成：${frames.size} 帧 / 共 ${frames.first().total} 包")

            // 2) 回 ACK
            FrameCodec.buildAck(frames)?.let { ack ->
                onLog("ACK → $ack")
                runCatching { write(BleUuids.CYPRESS_APP_DEVICE_ACK, hexToBytes(ack)) }
                    .onFailure { onLog("ACK 写入异常：${it.message}") }
            }

            // 3) 原样上报云端
            runCatching { api.bleDataReport(macAddress, frames.map { it.raw }) }
                .onFailure { onLog("上报异常：${it.message}") }

            // 4) 取云端算好的指令
            val cmd = when (operation) {
                OP_SYNC_HISTORY -> "queryOrder"
                OP_SYNC_DEVICE_STATUS -> "queryDevice"
                else -> command
            }
            if (cmd.isBlank()) return@withTimeout Outcome.Started("", 0)

            val acquired = runCatching { api.bleCommandAcquire(macAddress, cmd) }.getOrElse {
                onLog("取指令异常：${it.message}")
                return@withTimeout Outcome.Failed(
                    BleError.CONNECT_FAILED,
                    it.message ?: "取指令失败"
                )
            }
            if (acquired.isEmpty()) {
                // 控制类操作拿不到指令包 = 服务端没签发，一个字节都没写进设备 —— 不能报成功
                onLog("云端未返回指令包（operation=$operation cmd=$cmd）")
                return@withTimeout if (operation == OP_CONTROL_DEVICE) {
                    Outcome.Failed(
                        BleError.NO_COMMAND,
                        "服务端未签发指令：订单号缺失或订单已结束，请重新扫码取订单号"
                    )
                } else {
                    Outcome.Started(cmd, 0) // 同步类操作本就无指令，属正常
                }
            }

            // 5) 逐包写回
            var written = 0
            for (bundle in acquired) {
                for (pkg in bundle.packages) {
                    val bytes = hexToBytes(pkg)
                    val preview = if (pkg.length > 32) "${pkg.take(32)}…" else pkg
                    onLog("写 ${bytes.size}B → $preview")
                    val ok = runCatching {
                        write(BleUuids.CYPRESS_APP_DEVICE_DATA, bytes)
                    }.getOrDefault(false)
                    if (!ok) onLog("包写入失败")
                    written++
                    delay(PACKAGE_GAP_MS)
                }
            }
            onLog("指令下发完成，共 $written 包")
            Outcome.Started(cmd, written)

        } catch (e: TimeoutCancellationException) {
            Outcome.Failed(BleError.CONNECT_FAILED, "蓝牙超时")
        } catch (e: Exception) {
            Outcome.Failed(BleError.CONNECT_FAILED, e.message ?: "未知异常")
        } finally {
            close()
        }
    }

    /** 同步设备状态（operation = 2, command = queryDevice）。 */
    suspend fun sync(macAddress: String, deviceId: String): Outcome =
        run(macAddress, deviceId, command = "queryDevice", operation = OP_SYNC_DEVICE_STATUS)

    // -----------------------------------------------------------------
    // GATT 原语
    // -----------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun connect(device: BluetoothDevice): Boolean {
        val waiter = CompletableDeferred<Boolean>()
        connected = waiter
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val ok = withTimeoutOrNull(15_000) { waiter.await() } ?: false
        connected = null
        return ok
    }

    private suspend fun awaitServices(): Boolean {
        val waiter = CompletableDeferred<Boolean>()
        servicesReady = waiter
        val ok = withTimeoutOrNull(8_000) { waiter.await() } ?: false
        servicesReady = null
        return ok
    }

    @Suppress("DEPRECATION")
    private suspend fun enableNotify(uuid: UUID): Boolean {
        val g = gatt ?: return false
        val ch = g.getService(BleUuids.CYPRESS_SERVICE)?.getCharacteristic(uuid) ?: run {
            onLog("未找到特征 $uuid")
            return false
        }
        if (!g.setCharacteristicNotification(ch, true)) {
            onLog("setCharacteristicNotification 失败 $uuid")
            return false
        }
        // 部分固件不带 CCCD，此时 setCharacteristicNotification 已足够
        val cccd = ch.getDescriptor(CCCD) ?: return true
        val waiter = CompletableDeferred<Boolean>()
        descriptorWaiter = waiter
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!g.writeDescriptor(cccd)) {
            descriptorWaiter = null
            onLog("writeDescriptor 调用失败 $uuid")
            return false
        }
        val ok = withTimeoutOrNull(3_000) { waiter.await() } ?: false
        descriptorWaiter = null
        return ok
    }

    private suspend fun collectFrames(trigger: suspend () -> Unit): List<FrameCodec.Frame> {
        val waiter = CompletableDeferred<List<FrameCodec.Frame>>()
        synchronized(assembler) { assembler.reset() }
        framesReady = waiter
        trigger()
        val result = withTimeoutOrNull(FRAME_WAIT_MS) { waiter.await() }
        framesReady = null
        return result ?: emptyList()
    }

    @Suppress("DEPRECATION")
    private suspend fun write(uuid: UUID, value: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = g.getService(BleUuids.CYPRESS_SERVICE)?.getCharacteristic(uuid) ?: return false
        val supportsResponse =
            (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val waiter = CompletableDeferred<Boolean>()
        writeWaiters[ch.uuid] = waiter
        ch.writeType = if (supportsResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        ch.value = value
        if (!g.writeCharacteristic(ch)) {
            writeWaiters.remove(ch.uuid)
            return false
        }
        val ok = withTimeoutOrNull(3_000) { waiter.await() } ?: false
        writeWaiters.remove(ch.uuid)
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeWaiters.clear()
        framesReady = null
    }
}
