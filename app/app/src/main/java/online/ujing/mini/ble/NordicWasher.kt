package online.ujing.mini.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import online.ujing.mini.net.NordicCommandResponse
import online.ujing.mini.net.NordicReport
import online.ujing.mini.net.UjingApi
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Nordic nRF51822 洗衣机直连通道（moduleType=1）。
 *
 * 完整复刻官方 `connectProcess`：
 *   1. 连接 + 发现服务 + 请求 MTU
 *   2. 读取 STATUS / WASH_COUNT / BLE_VERSION / RANDOM / ORDER_NO（存在才读）
 *   3. 一次性上报 `POST /ble/data/report/nordic`，云端回传**明文指令**
 *   4. 若回传 orderId 非空 → 该设备已有订单（20002）
 *   5. 写 ORDER_NO（若有）+ 写目标特征（switch→POWER / wash_mode→WASH_MODE / wash→START）
 *   6. 延时 2s 后以 reportType=3 再上报一次收尾
 *
 * 与 Cypress 通道的本质差别：指令由云端以**明文**返回，App 不做签名计算。
 */
class NordicWasher(
    private val context: Context,
    private val api: UjingApi,
    private val onLog: (String) -> Unit = {}
) {

    sealed class Outcome {
        /** 指令已成功写入设备。 */
        data class Started(
            val command: String,
            val raw: NordicCommandResponse,
            /**
             * 设备侧剩余分钟（STATUS 特征第 13 字节）。
             *
             * 这是机身显示屏上那个数字，比服务端的 `remainTime` 权威 ——
             * 服务端在「启动中」阶段压根不回写剩余时间。null = 本次没读到。
             */
            val remainMinutes: Int? = null,
            /** STATUS 特征里的时间戳（epoch 毫秒），用于过滤待机值。0 = 解析失败。 */
            val statusTimeMs: Long = 0L
        ) : Outcome()

        /** 设备侧已有订单（官方 errorCode 20002/20003）。 */
        data class Pending(val orderId: String) : Outcome()

        data class Failed(val code: Int, val message: String) : Outcome()
    }

    /** 设备读回的快照。 */
    data class Snapshot(
        val status: String = "",
        val washCount: String = "",
        val bleVersion: String = "",
        val random: String = "",
        val orderNo: String = ""
    ) {
        /**
         * 设备侧剩余分钟 —— 就是机身显示屏上那个数。
         *
         * STATUS 特征（`4A320009`）是 20 字节，前 11 字节是 ASCII 的「最后操作时间」
         * （如 `09221805501` = 09月22日 18:05:50），第 13 字节（下标 12）是剩余分钟。
         * 实测：洗中快照 `...00 1A 00 ...` → `0x1A` = 26 分钟，与机身显示一致；
         * 空闲时该字节是上一次洗程的总时长。
         */
        val remainMinutes: Int?
            get() {
                val b = NordicWasher.hexToBytes(status)
                if (b.size < 13) return null
                val m = b[12].toInt() and 0xFF
                return if (m in 0..600) m else null
            }

        /**
         * STATUS 特征里的「最后操作时间」（epoch 毫秒），0 = 解析失败。
         *
         * 前 10 字节是 ASCII 的 MMDDHHmmSS（如 `0922180550` = 09月22日 18:05:50）。
         * 用来和订单开始时间比对：若机身时间戳早于订单开始，说明读到的是待机值
         * （上次洗程总时长），不应作为倒计时基准。
         */
        val statusTimeMs: Long
            get() {
                val b = NordicWasher.hexToBytes(status)
                if (b.size < 10) return 0
                val s = String(b, 0, 10, Charsets.US_ASCII)
                val mm = s.substring(0, 2).toIntOrNull() ?: return 0
                val dd = s.substring(2, 4).toIntOrNull() ?: return 0
                val hh = s.substring(4, 6).toIntOrNull() ?: return 0
                val mi = s.substring(6, 8).toIntOrNull() ?: return 0
                val ss = s.substring(8, 10).toIntOrNull() ?: return 0
                if (mm !in 1..12 || dd !in 1..31 || hh !in 0..23 || mi !in 0..59 || ss !in 0..59) return 0
                return try {
                    java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.MONTH, mm - 1)
                        set(java.util.Calendar.DAY_OF_MONTH, dd)
                        set(java.util.Calendar.HOUR_OF_DAY, hh)
                        set(java.util.Calendar.MINUTE, mi)
                        set(java.util.Calendar.SECOND, ss)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }.timeInMillis
                } catch (_: Exception) { 0L }
            }
    }

    // -----------------------------------------------------------------
    // GATT 会话状态
    // -----------------------------------------------------------------

    private var gatt: BluetoothGatt? = null
    private val available = HashSet<UUID>()
    private val readWaiters = ConcurrentHashMap<UUID, CompletableDeferred<ByteArray?>>()
    private val writeWaiters = ConcurrentHashMap<UUID, CompletableDeferred<Boolean>>()
    private var connected: CompletableDeferred<Boolean>? = null
    private var servicesReady: CompletableDeferred<Boolean>? = null
    private val main = Handler(Looper.getMainLooper())

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
                readWaiters.values.forEach { it.complete(null) }
                writeWaiters.values.forEach { it.complete(false) }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                available.clear()
                g.services.forEach { s -> s.characteristics.forEach { c -> available.add(c.uuid) } }
                onLog("服务发现完成，特征数=${available.size}")
                // 提升 MTU，保证长指令单包可写
                g.requestMtu(247)
                servicesReady?.complete(true)
            } else {
                onLog("服务发现失败 status=$status")
                servicesReady?.complete(false)
            }
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handleRead(c.uuid, status, if (status == BluetoothGatt.GATT_SUCCESS) c.value else null)
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int
        ) {
            handleRead(c.uuid, status, value)
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            if (!ok) onLog("写失败 ${c.uuid} status=$status")
            writeWaiters[c.uuid]?.complete(ok)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) {
            onLog("notify ${c.uuid} → ${bytesToHex(value)}")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { /* Nordic 无需 */ }
    }

    private fun handleRead(uuid: UUID, status: Int, value: ByteArray?) {
        if (status != BluetoothGatt.GATT_SUCCESS) onLog("读失败 $uuid status=$status")
        readWaiters[uuid]?.complete(value)
    }

    // -----------------------------------------------------------------
    // 公开入口
    // -----------------------------------------------------------------

    /**
     * 执行一次 Nordic 控制流程。
     *
     * @param command   startWash / selfClean / continueWash / pauseWash / switchOn / switchOff
     * @param reportType 2 = 下发控制指令；3 = 仅同步状态
     */
    suspend fun run(
        macAddress: String,
        deviceId: String,
        orderId: String?,
        command: String,
        reportType: Int = 2,
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
            if (!connected) return@withTimeout Outcome.Failed(BleError.CONNECT_ERROR, "GATT 连接失败")
            if (!awaitServices()) return@withTimeout Outcome.Failed(BleError.SERVICE_NOT_FOUND, "未发现服务")

            val snapshot = readSnapshot()
            // 把 STATUS 解析出的剩余分钟一起打出来，方便对照机身显示屏核对
            onLog("快照 $snapshot remainMin=${snapshot.remainMinutes}")

            val commandType = NordicCommand.commandTypeOf(command)
            val report = NordicReport(
                type = reportType,
                deviceId = deviceId,
                command = command,
                commandType = commandType,
                originStr = snapshot.status,
                deviceInfoStr = snapshot.washCount,
                bleVersionStr = snapshot.bleVersion,
                random = snapshot.random,
                orderNoStr = snapshot.orderNo,
                orderId = if (reportType == 2) orderId else null
            )

            val resp = api.nordicReport(report)
            onLog(
                "云端回传 errorCode=${resp.errorCode} cmd=${resp.command ?: "-"} " +
                    "orderNo=${resp.orderNo ?: "-"} orderStatus=${resp.orderStatus ?: "-"}"
            )

            if (!resp.orderId.isNullOrBlank()) {
                return@withTimeout Outcome.Pending(resp.orderId)
            }
            if (resp.errorCode != 0 && resp.errorCode != 200) {
                return@withTimeout Outcome.Failed(BleError.CONNECT_ERROR, "服务端错误 ${resp.errorCode}")
            }

            val plain = resp.command
            if (reportType == 2) {
                // reportType=2 是「请求启动」：服务端必须回一条指令。
                // 回空说明它不认为存在可启动的订单（典型原因：上报的 orderId 为空，
                // 例如 App 重启后内存态丢失），此时一个字节都没写进设备 —— 绝不能报成功。
                if (plain.isNullOrBlank()) {
                    onLog("服务端未签发指令（orderId=${orderId ?: "空"}），本次未写入设备")
                    return@withTimeout Outcome.Failed(
                        BleError.NO_COMMAND,
                        "服务端未签发指令：订单号缺失或订单已结束，请重新扫码取订单号"
                    )
                }

                val target = BleUuids.nordicWriteTarget(commandType)
                    ?: return@withTimeout Outcome.Failed(BleError.SERVICE_NOT_FOUND, "无对应特征: $commandType")

                if (BleUuids.NORDIC_ORDER_NO in available && !resp.orderNo.isNullOrBlank()) {
                    write(BleUuids.NORDIC_ORDER_NO, hexToBytes(resp.orderNo))
                    onLog("写 ORDER_NO = ${resp.orderNo}")
                }

                write(target, hexToBytes(plain))
                onLog("写指令 → $target = $plain")

                delay(2000)

                // 收尾：以 reportType=3 再上报一次，让云端刷新设备状态
                api.nordicReport(
                    report.copy(type = 3, command = "", commandType = "", orderId = null)
                )
                onLog("收尾上报完成")
                return@withTimeout Outcome.Started(plain, resp, snapshot.remainMinutes, snapshot.statusTimeMs)
            }

            // reportType=3（同步）没有指令是正常的
            Outcome.Started("", resp, snapshot.remainMinutes, snapshot.statusTimeMs)
        } catch (e: TimeoutCancellationException) {
            Outcome.Failed(BleError.CONNECT_FAILED, "蓝牙超时")
        } catch (e: Exception) {
            Outcome.Failed(BleError.CONNECT_FAILED, e.message ?: "未知异常")
        } finally {
            close()
        }
    }

    /** 仅同步状态（reportType=3），供界面轮询。 */
    suspend fun sync(macAddress: String, deviceId: String): Outcome =
        run(macAddress, deviceId, orderId = null, command = "", reportType = 3)

    // -----------------------------------------------------------------
    // GATT 原语
    // -----------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun connect(device: BluetoothDevice): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        connected = deferred
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        return try {
            withTimeout(15_000L) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            onLog("连接超时")
            false
        }
    }

    private suspend fun awaitServices(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        servicesReady = deferred
        return try {
            withTimeout(8_000L) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun readSnapshot(): Snapshot {
        return Snapshot(
            status = readChar(BleUuids.NORDIC_STATUS)?.let { bytesToHex(it) } ?: "",
            washCount = readChar(BleUuids.NORDIC_WASH_COUNT)?.let { bytesToHex(it) } ?: "",
            bleVersion = readChar(BleUuids.NORDIC_BLE_VERSION)?.let { bytesToHex(it) } ?: "",
            random = readChar(BleUuids.NORDIC_RANDOM)?.let { bytesToHex(it) } ?: "",
            orderNo = readChar(BleUuids.NORDIC_ORDER_NO)?.let { bytesToHex(it) } ?: ""
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun readChar(uuid: UUID): ByteArray? {
        if (uuid !in available) return null
        val g = gatt ?: return null
        val ch = g.getService(BleUuids.NORDIC_SERVICE)?.getCharacteristic(uuid) ?: return null
        val waiter = CompletableDeferred<ByteArray?>()
        readWaiters[uuid] = waiter
        val started = try {
            @Suppress("DEPRECATION")
            g.readCharacteristic(ch)
        } catch (e: SecurityException) {
            onLog("读权限被拒 $uuid")
            false
        }
        if (!started) {
            readWaiters.remove(uuid)
            return null
        }
        return try {
            withTimeout(3_000L) { waiter.await() }
        } catch (e: TimeoutCancellationException) {
            onLog("读超时 $uuid")
            null
        } finally {
            readWaiters.remove(uuid)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun write(uuid: UUID, value: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = g.getService(BleUuids.NORDIC_SERVICE)?.getCharacteristic(uuid) ?: return false
        val supportsResp = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        @Suppress("DEPRECATION")
        ch.writeType = if (supportsResp) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val waiter = CompletableDeferred<Boolean>()
        if (supportsResp) writeWaiters[uuid] = waiter
        val started = try {
            @Suppress("DEPRECATION")
            run { ch.value = value; g.writeCharacteristic(ch) }
        } catch (e: SecurityException) {
            onLog("写权限被拒 $uuid")
            false
        }
        if (!started) {
            writeWaiters.remove(uuid)
            return false
        }
        if (!supportsResp) return true
        return try {
            withTimeout(3_000L) { waiter.await() }
        } catch (e: TimeoutCancellationException) {
            onLog("写超时 $uuid")
            false
        } finally {
            writeWaiters.remove(uuid)
        }
    }

    @SuppressLint("MissingPermission")
    private fun close() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        readWaiters.clear()
        writeWaiters.clear()
    }

    // -----------------------------------------------------------------
    // 工具
    // -----------------------------------------------------------------

    companion object {
        fun hexToBytes(hex: String): ByteArray {
            var h = hex.trim().replace(" ", "").replace(":", "")
            if (h.length % 2 != 0) h = "0$h"
            val out = ByteArray(h.length / 2)
            for (i in out.indices) {
                out[i] = ((Character.digit(h[i * 2], 16) shl 4) or Character.digit(h[i * 2 + 1], 16)).toByte()
            }
            return out
        }

        fun bytesToHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02X".format(it) }
    }
}
