package online.ujing.mini.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "ujing_mini")

/** 上次扫码得到的设备上下文，免去每次重扫。 */
data class SavedDevice(
    val deviceId: String = "",
    val macAddress: String = "",
    val storeId: String = "",
    val deviceTypeId: Int = 0,
    val deviceNo: String = "",
    val storeName: String = "",
    val moduleType: Int = -1
) {
    val valid: Boolean get() = deviceId.isNotBlank() && macAddress.isNotBlank()
}

/**
 * 本地持久化：登录 token + 设备上下文 + 用户偏好。
 *
 * 只存必要字段，不做任何统计/上报。
 */
class TokenStore(private val context: Context) {

    private object Keys {
        val TOKEN = stringPreferencesKey("token")
        val MOBILE = stringPreferencesKey("mobile")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val MAC = stringPreferencesKey("mac")
        val STORE_ID = stringPreferencesKey("store_id")
        val DEVICE_TYPE_ID = intPreferencesKey("device_type_id")
        val DEVICE_NO = stringPreferencesKey("device_no")
        val STORE_NAME = stringPreferencesKey("store_name")
        val MODULE_TYPE = intPreferencesKey("module_type")
        val LAST_WORK_MODEL = intPreferencesKey("last_work_model")

        /**
         * 待启动订单号。
         *
         * 必须持久化：蓝牙启动时服务端要靠 orderId 才能签发指令。
         * 只放在内存里的 StateFlow 一旦 App 重启就丢，会导致「蓝牙启动」静默空转
         * （服务端回 errorCode=0 但无指令，一个字节都没写进设备）。
         * 来源：scanWasherCode 返回的 orderId，或 createOrder 成功后的 orderId。
         */
        val ORDER_ID = stringPreferencesKey("order_id")

        /**
         * 扫码原文（QR code 内容）。
         *
         * 存下来是为了重放 scanWasherCode 检查设备占用状态（createOrderEnabled），
         * 否则别人占用洗衣机时本 App 无法感知，会一直显示「空闲可下单」。
         */
        val QR_CODE = stringPreferencesKey("qr_code")
    }

    val tokenFlow: Flow<String?> = context.dataStore.data.map { it[Keys.TOKEN] }
    val mobileFlow: Flow<String?> = context.dataStore.data.map { it[Keys.MOBILE] }
    val lastWorkModelFlow: Flow<Int> = context.dataStore.data.map { it[Keys.LAST_WORK_MODEL] ?: 0 }

    val deviceFlow: Flow<SavedDevice> = context.dataStore.data.map { p ->
        SavedDevice(
            deviceId = p[Keys.DEVICE_ID] ?: "",
            macAddress = p[Keys.MAC] ?: "",
            storeId = p[Keys.STORE_ID] ?: "",
            deviceTypeId = p[Keys.DEVICE_TYPE_ID] ?: 0,
            deviceNo = p[Keys.DEVICE_NO] ?: "",
            storeName = p[Keys.STORE_NAME] ?: "",
            moduleType = p[Keys.MODULE_TYPE] ?: -1
        )
    }

    suspend fun currentToken(): String? = tokenFlow.first()
    suspend fun currentDevice(): SavedDevice = deviceFlow.first()

    suspend fun saveSession(token: String, mobile: String) {
        context.dataStore.edit {
            it[Keys.TOKEN] = token
            it[Keys.MOBILE] = mobile
        }
    }

    suspend fun saveDevice(d: SavedDevice) {
        context.dataStore.edit {
            it[Keys.DEVICE_ID] = d.deviceId
            it[Keys.MAC] = d.macAddress
            it[Keys.STORE_ID] = d.storeId
            it[Keys.DEVICE_TYPE_ID] = d.deviceTypeId
            it[Keys.DEVICE_NO] = d.deviceNo
            it[Keys.STORE_NAME] = d.storeName
            it[Keys.MODULE_TYPE] = d.moduleType
        }
    }

    suspend fun saveLastWorkModel(id: Int) {
        context.dataStore.edit { it[Keys.LAST_WORK_MODEL] = id }
    }

    suspend fun currentOrderId(): String = context.dataStore.data.map { it[Keys.ORDER_ID] ?: "" }.first()

    suspend fun saveOrderId(id: String) {
        context.dataStore.edit {
            if (id.isBlank()) it.remove(Keys.ORDER_ID) else it[Keys.ORDER_ID] = id
        }
    }

    suspend fun currentQrCode(): String = context.dataStore.data.map { it[Keys.QR_CODE] ?: "" }.first()

    suspend fun saveQrCode(code: String) {
        context.dataStore.edit {
            if (code.isBlank()) it.remove(Keys.QR_CODE) else it[Keys.QR_CODE] = code
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit {
            it.remove(Keys.TOKEN)
            it.remove(Keys.MOBILE)
            it.remove(Keys.ORDER_ID)
            it.remove(Keys.QR_CODE)
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
