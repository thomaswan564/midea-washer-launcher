package online.ujing.mini.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE 设备解析。
 *
 * **为什么必须扫描、不能直接 getRemoteDevice**
 *
 * 校园宿舍洗衣机的 BLE 地址是 **Random Static Address**（最高两 bit 为 `11`），
 * 例如 `E2:60:5A:XX:XX:XX`——首字节 0xE2 = `1110 0010`，最高两 bit 正是 `11`。
 *
 * [BluetoothAdapter.getRemoteDevice] 只能按字符串构造设备，系统会以 *public*
 * 地址类型发起连接；对随机地址设备，链路层永远握不上手，表现为一直停在
 * connecting，最后超时（实测界面日志「连接超时」）。
 *
 * 只有从 [ScanCallback] 拿到的 [ScanResult.device] 才携带正确的地址类型，
 * 所以连接前必须先扫描。
 */
object DeviceResolver {

    data class NearbyWasher(
        val device: BluetoothDevice,
        val name: String,
        val address: String,
        val rssi: Int
    )

    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isReady(context: Context): Boolean = adapter(context)?.isEnabled == true

    @SuppressLint("MissingPermission")
    suspend fun discoverNearby(
        context: Context,
        timeoutMs: Long = 8_000L,
        onLog: (String) -> Unit = {}
    ): List<NearbyWasher> {
        val adapter = adapter(context) ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        val scanner = adapter.bluetoothLeScanner ?: return emptyList()
        val found = ConcurrentHashMap<String, NearbyWasher>()
        val finished = CompletableDeferred<Unit>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull() ?: return
                if (!name.matches(Regex("Midea\\d+", RegexOption.IGNORE_CASE))) return
                val address = normalize(device.address) ?: return
                found[address] = NearbyWasher(device, name, address, result.rssi)
            }

            override fun onScanFailed(errorCode: Int) {
                onLog("蓝牙扫描失败，错误码 $errorCode")
                finished.complete(Unit)
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        return try {
            scanner.startScan(null, settings, callback)
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { finished.await() }
            found.values.sortedByDescending { it.rssi }
        } catch (e: SecurityException) {
            onLog("缺少附近设备权限")
            emptyList()
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    /**
     * 扫描 [timeoutMs] 毫秒，返回地址等于 [macAddress] 的设备；找不到返回 null。
     *
     * 需要 `BLUETOOTH_SCAN` 权限（Manifest 已声明 `neverForLocation`）。
     */
    @SuppressLint("MissingPermission")
    suspend fun scanFor(
        context: Context,
        macAddress: String,
        timeoutMs: Long = 10_000L,
        onLog: (String) -> Unit = {}
    ): BluetoothDevice? {
        val adapter = adapter(context) ?: return null
        if (!adapter.isEnabled) return null
        val normalized = normalize(macAddress) ?: return null
        val scanner = adapter.bluetoothLeScanner ?: return null

        val hit = CompletableDeferred<BluetoothDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                if (!device.address.equals(normalized, ignoreCase = true)) return
                val name = try {
                    device.name
                } catch (_: SecurityException) {
                    null
                }
                onLog("扫描命中 ${device.address} rssi=${result.rssi} name=${name ?: "-"}")
                if (!hit.isCompleted) hit.complete(device)
            }

            override fun onScanFailed(errorCode: Int) {
                onLog("扫描失败 errorCode=$errorCode")
                if (!hit.isCompleted) {
                    hit.completeExceptionally(IllegalStateException("scan failed code=$errorCode"))
                }
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        onLog("开始扫描 $normalized（最多 ${timeoutMs / 1000}s）")
        return try {
            scanner.startScan(null, settings, cb)
            val device = withTimeout(timeoutMs) { hit.await() }
            // 扫描与连接不能并发：先停扫描，留 400ms 让控制器状态收敛，否则首连易出 status=62
            runCatching { scanner.stopScan(cb) }
            delay(400)
            device
        } catch (e: TimeoutCancellationException) {
            onLog("扫描超时，未发现 $normalized")
            null
        } catch (e: Exception) {
            onLog("扫描异常：${e.message}")
            null
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
    }

    /** 兜底路径：不扫描，直接按 MAC 构造设备（只对 public 地址可靠）。 */
    @SuppressLint("MissingPermission")
    fun resolve(context: Context, macAddress: String): BluetoothDevice? {
        val a = adapter(context) ?: return null
        if (!a.isEnabled) return null
        val normalized = normalize(macAddress) ?: return null
        return try {
            a.getRemoteDevice(normalized)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /** `AABBCCDDEEFF` / `E2-60-...` → `E2:60:5A:XX:XX:XX`；非法返回 null。 */
    fun normalize(mac: String): String? {
        val h = mac.trim().replace(":", "").replace("-", "").replace(" ", "").uppercase()
        if (h.length != 12) return null
        if (!h.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return h.chunked(2).joinToString(":")
    }
}
