package online.ujing.mini.ble

import java.util.UUID

/**
 * BLE UUID 常量表。
 *
 * 两个硬件平台：
 *  - Nordic nRF51822（moduleType=1）：自定义 GATT 表，App 直写特征值，指令明文。
 *  - Cypress CYBLE-20736（moduleType=5）：透传管道，指令帧由服务端签名后下发，App 仅分包收发。
 *
 * 全部值逆向自 U净 2.4.18 `assets/washer/bluetoothConnect.js`。
 */
object BleUuids {

    // ---------------------------------------------------------------------
    // Nordic nRF51822 —— 本机通道（宿舍蓝牙洗衣机主力）
    // ---------------------------------------------------------------------

    /** 设备信息 Service（0x180A 标准 DIS），用于扫描期过滤。 */
    val NORDIC_FILTER_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")

    /** 自定义主 Service。 */
    val NORDIC_SERVICE: UUID = UUID.fromString("4A323000-79A1-44DE-A67E-11071F138630")

    val NORDIC_BLE_VERSION: UUID = UUID.fromString("4A320001-79A1-44DE-A67E-11071F138630") // read
    val NORDIC_POWER: UUID = UUID.fromString("4A320002-79A1-44DE-A67E-11071F138630")       // write  switch
    val NORDIC_WASH_MODE: UUID = UUID.fromString("4A320003-79A1-44DE-A67E-11071F138630")   // write  wash_mode
    val NORDIC_START: UUID = UUID.fromString("4A320004-79A1-44DE-A67E-11071F138630")       // write  wash
    val NORDIC_RANDOM: UUID = UUID.fromString("4A320006-79A1-44DE-A67E-11071F138630")      // read
    val NORDIC_STATUS: UUID = UUID.fromString("4A320009-79A1-44DE-A67E-11071F138630")      // read
    val NORDIC_WASH_COUNT: UUID = UUID.fromString("4A32000A-79A1-44DE-A67E-11071F138630")  // read
    val NORDIC_ORDER_NO: UUID = UUID.fromString("4A32000B-79A1-44DE-A67E-11071F138630")    // write

    /** commandType → 目标写特征。 */
    fun nordicWriteTarget(commandType: String): UUID? = when (commandType) {
        NordicCommandType.SWITCH -> NORDIC_POWER
        NordicCommandType.WASH_MODE -> NORDIC_WASH_MODE
        NordicCommandType.WASH -> NORDIC_START
        else -> null
    }

    // ---------------------------------------------------------------------
    // Cypress CYBLE-20736 —— 透传通道（moduleType=5，商业机/部分宿舍机）
    // ---------------------------------------------------------------------

    val CYPRESS_SERVICE: UUID = UUID.fromString("6569A8E8-1C4D-A184-5C4B-3D365C6DF1F3")
    val CYPRESS_DEVICE_APP_DATA: UUID = UUID.fromString("27BF7B55-19B2-44B3-414B-4E37809D959E") // notify 设备→App
    val CYPRESS_APP_DEVICE_DATA: UUID = UUID.fromString("195FB501-E2ED-14B0-984C-041AE69F1A39") // write  App→设备
    val CYPRESS_DEVICE_APP_ACK: UUID = UUID.fromString("239B91E2-66A0-18BF-8D44-05515BC685C4")  // notify 设备→App
    val CYPRESS_APP_DEVICE_ACK: UUID = UUID.fromString("6E2C0961-AB91-2481-BC4E-3D0DB126FDD7")   // write  App→设备

    /** 触发设备随机数交换的固定报文。 */
    const val CYPRESS_READ_RANDOM_PAYLOAD = "00010100FFFFFFFF"
}

/** Nordic COMMAND_TYPE 常量。 */
object NordicCommandType {
    const val WASH = "wash"
    const val WASH_MODE = "wash_mode"
    const val SWITCH = "switch"
    const val RANDOM = "random"
    const val WASH_STATUS = "washStatus"
}

/** Nordic COMMAND → {command, commandType} 映射。 */
object NordicCommand {
    const val START_WASH = "startWash"
    const val SELF_CLEAN = "selfClean"
    const val CONTINUE_WASH = "continueWash"
    const val PAUSE_WASH = "pauseWash"
    const val SWITCH_ON = "switchOn"
    const val SWITCH_OFF = "switchOff"

    fun commandTypeOf(command: String): String = when (command) {
        START_WASH, SELF_CLEAN -> NordicCommandType.WASH_MODE
        CONTINUE_WASH, PAUSE_WASH -> NordicCommandType.WASH
        SWITCH_ON, SWITCH_OFF -> NordicCommandType.SWITCH
        else -> ""
    }
}

/** 全局 BLE 错误码（对齐官方原生 module）。 */
object BleError {
    const val OK = 0
    const val CONNECT_FAILED = -1
    const val DEVICE_NOT_FOUND = 404
    const val NOT_INITIALIZED = 10000
    const val BLUETOOTH_OFF = 10001
    const val NOT_FOUND_DEVICE = 10002
    const val CONNECT_ERROR = 10003
    const val SERVICE_NOT_FOUND = 10004

    /**
     * 服务端没有签发指令。
     *
     * 实测场景：蓝牙启动时上报的 orderId 为空（例如 App 重启后内存态丢失），
     * 服务端无从判断该给哪张订单签发指令，于是返回 errorCode=0 但 command 为空。
     * 此时**什么都没写进设备**，必须报错而不是报成功。
     */
    const val NO_COMMAND = 10005
}
