# U净（com.midea.vm.washer）协议逆向报告

> 目标：为「开源极简替代客户端」提供完整协议依据。
> 对象：美的 / 无锡小净共享网络 —— 校园蓝牙自助洗衣机。
> 方法：APK 静态逆向（Weex JS bundle 未混淆）+ 云端 API 复原 + 实机端到端验证。
> 状态：**协议栈已完整还原并实机验证通过**（启动链路端到端打通，见 §10）；指令帧生成依赖服务端。

---

## 1. 系统架构

```
┌─────────────┐   ①扫码/下单/支付    ┌──────────────────────────┐
│  手机 App   │ ───────────────────► │  phoenix.ujing.online    │
│ (Weex 壳)   │ ◄─────────────────── │  (鉴权/订单/指令计算)     │
└──────┬──────┘   ②云端返回指令帧     └──────────────────────────┘
       │
       │ ③BLE 透传指令帧
       ▼
┌─────────────────────────┐
│  洗衣机 (Cypress CYBLE-20736) │
│  BLE 从机 + 主控          │
└─────────────────────────┘
```

**核心结论：客户端不做任何加密/签名，只是 BLE 透传管道。**
所有控制指令（含开锁）由服务端 `ble/command/acquire` 计算后返回十六进制帧，客户端逐包写入 BLE characteristic。

App 技术栈：**Weex**（Vue2）+ 原生 module 桥。业务逻辑全在 `assets/washer/*.js`（明文 webpack bundle）。
BLE 桥：原生 module `ujingBluetoothle`（方法名同微信小程序 BLE API + 自定义 `createBLEDirectConnection`）。

---

## 2. 云端 API

- 域名：`https://phoenix.ujing.online:443`
- 传输：HTTPS / JSON

### 2.1 请求 Header（按接口分三组）

| 组 | 场景 | Header |
|---|---|---|
| **A. phoneCode** | 取验证码 | `x-app-code: ZI`<br>`x-app-version: 2.4.3`<br>`User-Agent: U jing/2.4.3 (iPhone; iOS 17.3; Scale/3.00)`<br>`x-mobile-brand: apple`<br>`x-mobile-model: iPhone14,5`<br>`x-user-geo: -180.000000,-180.000000` |
| **B. login** | 登录 | 同 A + `Content-Type: application/json` |
| **C. reserve** | 业务请求 | `x-app-code: BI`<br>`weex-version: 1.1.30`<br>`x-app-version: 2.4.2`<br>`Authorization: Bearer {token}` |

> ⚠️ 注意 `x-app-code` 在取码/登录时是 **ZI**，业务请求时是 **BI**。

### 2.2 鉴权链路

```
GET  /api/v1/captcha?mobile={phone}&sessionId=AFS_SWITCH_OFF&sig=AFS_SWITCH_OFF&token=AFS_SWITCH_OFF&type=1
POST /api/v1/login          {"captcha":"<短信码>","mobile":"<手机号>"}
     → res_json.data.token   ← 存为 Bearer token
```

`code==0` 为成功；业务接口 `code==401` 表示 token 失效，需重登。

### 2.3 设备与订单端点

| 用途 | 方法 | 路径 | 参数 |
|---|---|---|---|
| 扫码查设备 | POST | `/api/v1/devices/scanWasherCode` | `{"qrCode":"<二维码原文>"}` → `data.result.deviceId`、`data.result.createOrderEnabled` |
| 设备详情 | GET | `/api/v1/devices/{id}` | — |
| 门店信息 | GET | `/api/v1/app/washer/store/info` | `storeId` |
| 设备程序（洗衣模式） | GET | `/api/v1/app/washer/devices/program/info` | `deviceId` |
| 按类型查程序 | GET | `/api/v1/app/washer/devices/program/info` | `storeId`, `deviceTypeId` |
| 排队时长 | GET | `/api/v1/app/washer/store/waitTime` | `storeId`, `deviceTypeId` |
| 预约设备列表 | GET | `/api/v1/devices/reserve` | — |
| **创建订单** | POST | `/api/v1/orders/create` | 见 §2.4 |
| 订单详情 | GET | `/api/v1/orders/{orderId}/detail` | `additional=price` |
| 取消订单 | POST | `/api/v1/orders/{orderId}/cancel` | `{"orderId":"..."}` |

### 2.4 下单 body（选洗衣模式的核心）

```json
{
  "type":          1,
  "deviceTypeId":  2,
  "deviceId":      "<设备ID>",
  "deviceWashModelId": "<从 program/info 的 deviceWashModel[].workModelId 取>",
  "storeId":       "<门店ID>",
  "wp_detergentGearId":  1,
  "wp_disinfectantGearId": 4,
  "washTemperatureId": 1,
  "dryTime": 3
}
```

字段来源：

| 字段 | 取值 |
|---|---|
| `type` | 设备 `type`（服务端返回） |
| `deviceTypeId` | 见 §4.2 WASHER_DEVICE_TYPE |
| `deviceWashModelId` | **服务端下发的 `deviceWashModel[].workModelId`**，非硬编码 |
| `storeId` | `program/info` 返回 |
| `wp_detergentGearId` | 洗涤剂档位（LiquidType.Detergent） |
| `wp_disinfectantGearId` | 消毒液档位（LiquidType.Disinfectant） |
| `washTemperatureId` | 1=常温 2=30℃ 3=40℃ 4=60℃ |
| `dryTime` | 仅干衣机，= time/10 |

**洗衣模式列表来自服务端**：`program/info` → `data.deviceWashModel[]`，每项：
`workModelId` / `name`(`workModelName`) / `basePrice` / `promotionPrice` / `isPromotion` / `isDefault` / `hide` / `time` / `additionDevices` / `additionParams`。

> 早期资料里「1=普通洗 2=小件洗 3=超强洗 4=单脱水」在客户端代码中**不存在**，属服务端动态配置，不要写死。

`program/info` 完整响应字段：
`deviceId, deviceNo, deviceTypeId, deviceTypeName, moduleType, customDeviceType, type, warmTip, showMore, isOpenDoubleExposure, storeId, storeName, serviceSubjectId, serviceSubjectName, isForceDetergent, isForceDisinfectant, storeMobile, skuConfig, isWashTemperatureEnable, deviceWashModel[], notification`

### 2.5 BLE 专用端点（决定性）

| 用途 | 方法 | 路径 | Body |
|---|---|---|---|
| **上报设备随机数/状态** | POST | `/api/v1/ble/data/report` | `{"macAddress":"...","reportData":["HEX",...]}` |
| **取云端计算好的指令帧** | POST | `/api/v1/ble/command/acquire` | `{"macAddress":"...","command":"<queryOrder\|queryDevice\|控制指令>"}` |
| Nordic 上报 | POST | `/api/v1/ble/data/report/nordic` | 见 §5.2 |
| 历史记录上报 | POST | `/api/v1/ble/history/report` | — |

`ble/command/acquire` 响应：数组，每项含 `packages:["HEX1","HEX2",...]`
```json
[ { "packages": ["...", "..."] }, { "packages": ["..."] } ]
```

---

## 3. 蓝牙协议（Cypress CYBLE-20736）

**洗衣机硬编码 `bleType = "bleCypress20736"`**（见 §6 调用点）。

### 3.1 GATT 定义

```
Service UUID     = 6569A8E8-1C4D-A184-5C4B-3D365C6DF1F3

DEVICE_APP_DATA  = 27BF7B55-19B2-44B3-414B-4E37809D959E   设备→App  notify
APP_DEVICE_DATA  = 195FB501-E2ED-14B0-984C-041AE69F1A39   App→设备  write
DEVICE_APP_ACK   = 239B91E2-66A0-18BF-8D44-05515BC685C4   设备→App  notify
APP_DEVICE_ACK   = 6E2C0961-AB91-2481-BC4E-3D0DB126FDD7   App→设备  write

READ_RANDOM      = "00010100FFFFFFFF"   触发随机数交换的固定帧
```

### 3.2 数据帧格式

```
[frameNum:2hex][total:2hex][pkgNum:2hex][pkgLen:2hex][payload...]
```
- `frameNum`：帧序号（分帧组包用）
- `total`：该帧总分包数
- `pkgNum`：当前包序号
- `pkgLen`：payload 长度
- 全部 `parseInt(x, 16)`

组包：`frameNum` 相同 → 按 `pkgNum` 去重 → 排序 → 数量达 `total` 即完整。

### 3.3 ACK 帧格式

```js
createAckFrame(frames):
  frameNum = frames[0].packageFrameNum
  total    = frames[0].packageTotal
  xorSum   = XOR(所有 packageNum)
  lenSum   = SUM(所有 packageLength)
  last     = (frames.length < total) ? "01" : "00"
  return hex2(frameNum)+hex2(total)+hex2(xorSum)+hex2(lenSum)+last
```

### 3.4 状态机

```
PROCESS_STATE = { Init:0, RandomListening:1, RandomOver:2, DataListening:3, DataOver:4 }
ACK_STATE     = { Init:0, RandomAck:1, OrderStatusAck:2, DeviceStatusAck:3, OrderNoAndStartAck:4, AckOver:5 }
OPERATION     = { READ_RANDOM_NUM:0, SYNC_HISTORY_ORDER:1, SYNC_DEVICE_STATUS:2, CONTROL_DEVICE:3 }
```

### 3.5 完整连接流程

```
1. createBLEDirectConnection({macAddress, signValue:"", isReverse, localName:"", timeout:10000})
   → connectableDevice  (存 storage "connectableDevice")
2. getBLEDeviceServices({deviceId})
3. getBLEDeviceCharacteristics({deviceId, serviceId: 6569A8E8-...})
4. notifyBLECharacteristicValueChange × 2  (DEVICE_APP_DATA, DEVICE_APP_ACK, state:true)
5. onBLECharacteristicValueChange(cb)       注册收包
6. 写 APP_DEVICE_DATA = "00010100FFFFFFFF"   触发设备回随机数
7. 设备回包 → 组包 → 上报云端：
   POST ble/data/report {macAddress, reportData:[payload_hex,...]}
8. eventBus.$emit("BLE_RANDOM_OVER")
   → 按 operation 分支：1→P("queryOrder")  2→P("queryDevice")  3→P(控制指令)
9. P(cmd): POST ble/command/acquire {macAddress, command:cmd}
   → [{packages:[...]}, ...]
   → 逐包写 APP_DEVICE_DATA，包间隔 1000ms
10. 设备回包 → createAckFrame → 写 APP_DEVICE_ACK
11. eventBus.$emit("BLE_PROCESS_OVER", {errorCode:0})
```

### 3.6 关键函数（bluetoothConnect.js 原文）

```js
// 取指令并写设备
P = async function(command){
  var n = L;                                  // L={operation,macAddress,deviceId,virtualId,command,orderId}
  var body = {macAddress: n.macAddress, command: command};
  var c = await api.getCommandAcquire(body);  // POST ble/command/acquire
  if (c) {
    while (c.length > 0) {
      await R(n.deviceId, c[0].packages);
      await delay(1000);
      c.splice(0,1);
    }
  } else {
    eventBus.$emit("BLE_PROCESS_OVER", {errorCode:-1});
  }
}

// 逐包写 APP_DEVICE_DATA
R = function(deviceId, packages){
  return new Promise(async (resolve,reject)=>{
    try {
      while (packages.length > 0) {
        await ble.writeBLECharacteristicValue({
          deviceId, serviceId: BLE_CYPRESS_20736.UUID,
          characteristicId: BLE_CYPRESS_20736.APP_DEVICE_DATA,
          value: packages[0].toUpperCase()
        });
        packages.splice(0,1);
      }
      resolve({errorCode:0});
    } catch(e){ reject(e) }
  })
}
```

---

## 4. 枚举表

### 4.1 DEVICE_MODULE_TYPE（通信模块，决定蓝牙通道）

```
TYPE_2G         = 0
TYPE_BLE_NORDIC = 1     ← Nordic 直写
TYPE_NB         = 2
TYPE_2G_BLE     = 3
TYPE_NB_BLE     = 4
TYPE_BLE_CYPRESS= 5     ← Cypress 透传（洗衣机）
TYPE_WIFI_MESH  = 6
TYPE_4G         = 7
```

### 4.2 WASHER_DEVICE_TYPE

```
PULSATOR      = 1    波轮
DRUM          = 2    滚筒
DRYER         = 3    烘干机
SHOE_WASHER   = 4    洗鞋机
DRYER_LARGE   = 6
PULSATOR_OTT  = 8
DRUM_10KG     = 9
DRYER_9KG     = 10
PULSATOR_65KG = 11
DRUM_10KG_NEW = 12
DRYER_10KG    = 13
```

### 4.3 订单状态 WASHER_DEVICE_STATUS

```
RESERVED=10  PAYING=17  PAID=20  STARTING=21  SELF_CLEANING_STARTING=22
DETERGENT_DISPENSING=24  ORDER_PROTECTION=29  SELF_CLEANING=30
SELF_CLEANING_COMPLETED=35  RUNNING=40  COMPLETED=50  PAYMENT_TIMEOUT=51
START_FAILED=52  CANCELED=53  START_TIMEOUT=...
```

### 4.4 支付状态 WASHER_ORDER_PAY_FLAG

```
UNPAY=0  PAYED=1  REFUND=2  PAYING=3  PAY_FAILED=4  REFUNDING=5  REFUND_FAILED=6
```

### 4.5 设备在线状态 / 模块工作状态

```
WASHER_DEVICE_ONLINE_STATUS = { OFFLINE:0, ONLINE:1 }
WASHER_DEVICE_MODULE_STATUS = { IDLE:0, WORK:1, FAULT:2, DISABLE:3 }
```

### 4.6 ORDER_CATEGORY

```
WASHER=1  CONTROL_BOX=2  WATER=3  SHOWER=4  SOCKET=5  CHARGER_PILE=6
WASHING_PARTNER=7  SIM_CARD=201  TICKET=101  ROB_VOUCHERS=102  BUY_COUPONS=103
```

---

## 5. Nordic nRF51822 分支（**本机实际通道**；亦覆盖吹风机 / 淋浴 / controlBox）

### 5.1 GATT

```
FILTER_UUID  = 0x180A            (Device Information Service)
Service UUID = 4A323000-79A1-44DE-A67E-11071F138630

BLE_VERSION = 4A320001-...   read
POWER       = 4A320002-...   write   (commandType=switch)
WASH_MODE   = 4A320003-...   write   (commandType=wash_mode)
START       = 4A320004-...   write   (commandType=wash)
RANDOM      = 4A320006-...   read
STATUS      = 4A320009-...   read
WASH_COUNT  = 4A32000A-...   read
ORDER_NO    = 4A32000B-...   write
```

commandType → characteristic：`switch→POWER, wash_mode→WASH_MODE, wash→START`

### 5.2 上报结构（POST ble/data/report/nordic）

```js
{
  type: reportType || 1,
  deviceId: id,
  command:   COMMAND[cmd].command,       // 如 "startWash"
  commandType: COMMAND[cmd].commandType, // 如 "wash_mode"
  originStr,      // 读 STATUS
  deviceInfoStr,  // 读 WASH_COUNT
  bleVersionStr,  // 读 BLE_VERSION
  random,         // 读 RANDOM
  orderNoStr,     // 读 ORDER_NO
  orderId         // reportType===2 时
}
```
返回云端指令 `{command:"<hex>", orderNo:"..."}`，再写 ORDER_NO / 对应 characteristic。

> 此分支同样**依赖云端算指令**，客户端只读特征 + 写明文回包。

---

## 6. 业务调用链（洗衣机）

```
扫码
 └─ scanWasherCode(qrCode) → deviceId
 └─ program/info?deviceId= → deviceWashModel[] / storeId / deviceTypeId / macAddress
      │
      ├─ 用户选模式
      └─ orders/create {deviceId, deviceWashModelId, storeId, deviceTypeId, type, ...}
           │
           └─ 支付 → 订单 PAID
                │
                └─ orderDetail.js: startByCypressBLE(command)
                     navigator.push("bluetoothConnect", {
                       orderNo, orderId, deviceId, macAddress,
                       bleType: "bleCypress20736",   // 硬编码
                       reportType: 2,
                       operation: 3,                  // CONTROL_DEVICE
                       command: <控制指令>,
                       startConnect: 1
                     })
                     │
                     └─ bluetoothConnect.js 页面 → 蓝牙调度器：
                          switch(bleType){
                            "bleCypress20736"          → cypress20736BuleTooth()
                            "bleCypressShower20736"    → cypressShower20736BuleTooth()
                            "bleCypressHairdryer20736" → cypressHairdryer20736BuleTooth()
                            "bleNordic51822"           → connectProcess()
                            "bleNordic51822_2"         → connectProcess_2()
                            "bleNordic52832"           → ...
                          }
```

**另一入口**（订单状态查询，operation=2）：
```js
navigator.push("bluetoothConnect", {
  orderNo, orderId, deviceId, macAddress,
  bleType: "bleCypress20736", reportType: 3,
  operation: 2, command: "queryDevice", startConnect: 0
})
```

事件：`bleCypress20736_connect` / `bleCypress20736_connect_success` / `bleCypress20736_connect_fail`

---

## 7. 错误码

```
BleError:
  -1     连接失败
  0      正常
  404 / 10002   未搜索到设备
  10000  未初始化蓝牙适配器
  10001  蓝牙未打开
  10003  连接失败
  10004  没有找到指定服务

Nordic 上报:
  100007 → {errorCode:"20001"}
  100014 → toast

BLE_PROCESS_OVER.errorCode:
  20002 / 20003  已有订单
```

权限：`checkSelfPermissions(["bluetoothle","bluetoothleOpen","location","locationService"])`

---

## 8. 原生 module 清单

`ujingNetwork` `modal` `ujingSystem` `ujingUser` `ujingLocation` `ujingScanCode`
`storage` `ujingWeexView` `ujingAlipay` `ujingMqtt` `ujingPay` `globalEvent`
`ujingModel` `ujingPermission` `ujingLog` `ujingBluetoothle`

`ujingBluetoothle` 方法：`openBluetoothAdapter` `closeBluetoothAdapter` `getBluetoothAdapterState`
`startBluetoothDevicesDiscovery` `stopBluetoothDevicesDiscovery` `onBluetoothDeviceFound`
`offBluetoothDeviceFound` `onBluetoothAdapterStateChange` `offBluetoothAdapterStateChange`
`getBluetoothDevices` `getConnectedBluetoothDevices` `createBLEConnection` `closeBLEConnection`
`onBLEConnectionStateChange` `getBLEDeviceServices` `getBLEDeviceCharacteristics`
`writeBLECharacteristicValue` `readBLECharacteristicValue` `notifyBLECharacteristicValueChange`
`onBLECharacteristicValueChange` `offBLECharacteristicValueChange`
**`createBLEDirectConnection`** **`offBLEDirectConnection`**（自定义，传 macAddress 直连）

---

## 9. 实机验证结果

> **实测环境**：vivo V2309A（Android 16，MediaTek，无 root）
> **目标设备**：美的蓝牙波轮洗衣机 `deviceId=REPLACE_WITH_PLATFORM_DEVICE_ID`、`macAddress=E2:60:5A:XX:XX:XX`、`moduleType=1`（TYPE_BLE_NORDIC）、`deviceTypeId=1`（PULSATOR）
> **工具**：本仓库自研客户端 `online.ujing.mini`（`app/`）
> **日期**：2026-09-22

### 9.1 结论表

| # | 待验证项 | 结果 | 证据 / 说明 |
|---|---|---|---|
| 1 | 原生 `createBLEDirectConnection` 的实现方式 | ✅ 已还原 | 官方必然内部先扫描：本机 BLE 地址 `E2:60:5A:XX:XX:XX` 是 **Random Static Address**（首字节 `0xE2` = `1110 0010`，最高两 bit = `11`）。`BluetoothAdapter.getRemoteDevice(MAC)` 会按 *public* 类型握手，对随机地址**必然超时**；改为 `BluetoothLeScanner` 取 `ScanResult.device` 后首连即通 |
| 2 | `ble/command/acquire` 真实响应体 | ✅ 已确认 | 见 §10：真实付费订单下成功取到指令包并写入设备，机器启动 |
| 3 | `ble/data/report` 随机数长度与语义 | ✅ 已确认 | Nordic `RANDOM` 特征读出 **8 字节**明文，每次连接不同（实测两轮：`4CC142E9CAF78272`、`C2D5A902A443FEC5`） |
| 4 | 二维码原文格式 | ✅ 已确认 | `http://app.littleswan.com/u_download.html?type=Ujing&uuid=0000000000000A0007604202408030001835`；**不含 MAC / deviceNo**，纯 uuid 载体 |
| 5 | 设备是否校验 ACK 超时 | ⚪ 不适用 | ACK 帧机制属 Cypress CYBLE-20736 通道；本机是 Nordic，协议里无 ACK |
| 6 | 指令帧是否可重放 | ❌ 未验证 | 需在同一订单的**可启动窗口**内二次下发才能判定；本次实测订单已进入 RUNNING，无法重放 |
| 7 | `macAddress` 来源字段 | ✅ 已确认 | `POST /devices/scanWasherCode` → `data.result.macAddress` = `AABBCCDDEEFF`；`GET /app/washer/devices/program/info` 亦返回同值 |
| 8 | 是否需订单处于 PAID/STARTING 才允许 BLE 操作 | ✅ 已确认 | **启动必须绑定订单**：带 `orderId` + `type=2` 上报，服务端才签发指令；无订单号时返回空指令。只读同步（`type=3`）不需要订单 |
| 9 | `ble/data/report/nordic` 的 `deviceId` 该填什么 | ✅ 已确认 | **必须填平台 ObjectId**（`33f6d2ff...`），不是 MAC。填 MAC 时服务端直接返回 `{"code":1603,"message":"未找到该设备"}` |
| 10 | 订单已被重复启动时服务端行为 | ✅ 已确认 | 返回 `{}`（空对象、HTTP 200、**无 `code` 字段**）——静默拒绝，不是错误码。见 §10.4 |

### 9.2 Nordic 连接时序（实测，全程约 2.3 s）

```
17:50:31.023  开始扫描 E2:60:5A:XX:XX:XX（最多 10s）
17:50:31.121  扫描命中 rssi=-79 name=MideaF664        ← 0.1s 内命中，设备广播名 MideaF664
17:50:31.894  已连接，发起服务发现                      ← stopScan + 400ms 收敛后连接，首连成功
17:50:32.853  服务发现完成，特征数=19
17:50:32.954  快照（依次读 STATUS / WASH_COUNT / BLE_VERSION / RANDOM / ORDER_NO）
17:50:33.329  云端回传 errorCode=0
```

### 9.3 Nordic 特征初值（实测）

| 特征 | UUID 尾段 | 初值（hex） | 解读 |
|---|---|---|---|
| STATUS | `4A320009` | `30393231323134373536330023001F00E4A0830F` | 首段 ASCII 可读：`09212147563` |
| WASH_COUNT | `4A32000A` | `AD010000000000008AA25548` | 前 2 字节小端 `0x01AD` = **429**（累计洗涤次数） |
| BLE_VERSION | `4A320001` | `05600201180309` | 固件版本串 |
| RANDOM | `4A320006` | `4CC142E9CAF78272` / `C2D5A902A443FEC5` | 8 字节，每次连接刷新 |
| ORDER_NO | `4A32000B` | *(空)* | 无订单时为空 |

### 9.4 首连 `status=62` 问题与修复

**现象**：扫描命中后立即 `connectGatt`，回调返回 `status=62`（`GATT_CONN_FAIL_ESTABLISH`），连接被断开。
**根因**：扫描与连接**并发**占用同一个 BLE 控制器资源（不是设备拒绝）。
**修复**：`stopScan` 之后 `delay(400ms)` 让控制器状态收敛，再发起连接；并加 1 次重试（间隔 600ms）。
**复测结果**：首连一次成功，不再出现 status=62。

---

## 10. 端到端启动验证（真实付费订单，2026-09-22）

> 本节是整份报告的**收敛点**：它同时验证了云端订单链路、BLE 上报契约、服务端指令签发、特征写入四条链路。

### 10.1 操作序列

| 时间（UTC+8） | 动作 | 主体 |
|---|---|---|
| 18:05:51 | 扫码下单并支付 ¥2.78（普通洗） | 搭档，**支付宝小程序** |
| 18:06:05 | 订单锁定 | 服务端 |
| **18:07:06** | **点「蓝牙启动」→ 指令写入设备 → 机器开始运转** | **本仓库客户端 `online.ujing.mini`** |
| 18:17:08 | 再次点击「蓝牙启动」→ 返回空指令（订单已在运行） | 同上 |

### 10.2 服务端订单详情（铁证）

`GET /api/v1/orders/REPLACE_WITH_ORDER_ID/detail?additional=price` 关键字段：

```json
{
  "orderId": REPLACE_WITH_ORDER_ID,
  "orderNo": "20260922180550739848",
  "deviceWashModelId": 5,
  "status": "40",
  "statusRemark": "运行中",
  "payFlag": 1,
  "payPrice": 2.78,
  "payType": 315,
  "createAt": "2026-09-22T10:05:51Z",
  "lockTime": "2026-09-22T10:06:05Z",
  "userClickWashStartTime": "2026-09-22T10:07:06Z",
  "washStartTime": "2026-09-22T10:07:06Z",
  "remainTime": 1550,
  "workTime": 35,
  "cycle": "普通洗 | 筒自洁",
  "deviceSn": "0000DA1113803443024A152103180000",
  "macAddress": "AABBCCDDEEFF",
  "moduleType": 1,
  "online": 1
}
```

**`userClickWashStartTime == washStartTime == 10:07:06Z`** —— 服务端记录的「用户点击启动」与「实际开始洗涤」是同一时刻，且落在客户端侧点击「蓝牙启动」的时间点上。

**结论：BLE 写指令路径端到端打通。** 这是实机实测，非推断。

### 10.3 上报契约（探针实验结果，决定性）

用 12 个变体（`poc/probe_nordic.py`、`poc/probe_nordic2.py`）对 `POST /api/v1/ble/data/report/nordic` 做了字段枚举：

| 变体 | `deviceId` | `orderId` | `type` | 服务端响应 |
|---|---|---|---|---|
| A | 平台 ID | 数字 | 2 | `{}` 空对象 |
| B | 平台 ID | 字符串 | 2 | `{}` 空对象 |
| **C** | 平台 ID | **不带** | 2 | `{"code":0,"data":{"deviceStatus":"1","errorCode":0,"orderStatus":"40","status":""}}` |
| **D** | 平台 ID | 数字 | **1** | `{"code":0,"data":{"orderId":REPLACE_WITH_ORDER_ID,"storeMobile":null}}` |
| **E** | 平台 ID | 不带 | **3** | 同 C |
| F | 平台 ID | 数字 + `orderNoStr` | 2 | `{}` 空对象 |
| **G/H/I/J/L** | **MAC** | 有 / 无 | 2 或 1 | **`{"code":1603,"message":"未找到该设备"}`** |
| **K** | 平台 ID | 数字 | 2 | **`{}`（HTTP 200、无 `code` 字段）** |

**三条硬结论**：

1. **`deviceId` 必须是平台 ObjectId**，填 MAC 一律 `1603 未找到该设备`。
2. **`type=2`（请求启动）必须带 `orderId`**，服务端才可能签发指令。
3. **`{}` 是服务端的「静默拒绝」**，不是错误码 —— 客户端不能把它当解析失败处理。

### 10.4 静默拒绝的语义

`{}` 的触发条件是**订单已进入 RUNNING**（`status=40`）。此时服务端认为无指令可签发，返回空对象。

对照变体 C（不带 orderId、type=2）仍返回正常包裹 —— 说明**判据是"该订单是否还需要启动"，而不是"请求本身是否合法"**。

**对客户端的含义**：启动前必须先看订单状态。本仓库客户端的处理是：
- 扫码后调 `orderDetail` 取 `status` / `statusRemark` / `remainTime`
- 订单 `status ∈ {30, 40}` 时**直接禁用启动按钮**，不发起 BLE 连接
- 底层 `NordicWasher` 在 `reportType=2` 收到空指令时返回 `Failed(NO_COMMAND)`，**不再假报成功**

### 10.5 修复的假成功缺陷

早期版本在 `reportType=2` 收到空指令时走 `Outcome.Started("", resp)` 分支，UI 提示「启动成功」而实际**一个字节都没写进设备**。这是最危险的一类缺陷：用户以为机器要转了，实际什么都没有发生。

现已改为显式失败：`Failed(BleError.NO_COMMAND, "服务端未签发指令：订单号缺失或订单已结束，请重新扫码取订单号")`。

### 10.6 订单号必须跨进程存活

实测发现：APP 重启后内存态 `orderId` 丢失 → 上报时 `orderId` 为空 → 服务端无从签发指令 → 静默空转。

**修复**：`orderId` 持久化到 DataStore（`Keys.ORDER_ID`），`init` 时恢复，`logout` 时清除。

### 10.7 仍待实测

- `ble/command/acquire`（Cypress 通道）的真实响应结构与分包策略 —— 本机是 Nordic，未触发
- 指令帧是否可重放（需在同一订单的可启动窗口内做二次下发）
- 设备侧是否校验指令帧与当前 RANDOM 的绑定关系

---

## 11. 对「开源极简客户端」的影响

**已实机验证可用**（客户端本就只是透传管道）：
- ✅ 扫码 → 查设备 → 查模式列表 → 下单
- ✅ BLE 扫描 + 连接 + 读特征快照 + 上报云端
- ✅ 取云端指令帧 + 写入设备 → **启动洗衣机**（§10 铁证）
- ✅ 订单状态查询与展示（防重复启动）
- ✅ 去掉广告、埋点、定位、推送

**必须服务端配合的部分**：
- ❌ 指令帧无法本地生成（签名在服务端）→ 必须调 `ble/data/report/nordic`
- ❌ 支付环节若走微信/支付宝 SDK，仍需官方支付渠道（可用自有支付/充值，或保留原支付）

**纯离线开锁**：不可行。服务端签发指令时依赖订单状态，且 §10.3 证实 `deviceId` 必须是平台 ObjectId，无网络时连设备身份都无法构造。仅在能抓包重放且设备无防重放时才有一线可能，而这一点**尚未验证**（§10.7）。

**远程开锁**：BLE 有效距离约 10m。若设备为「NB/4G + 蓝牙」双模，可云端下发；若为**纯蓝牙机**（本机 `moduleType=1` 即是），物理上必须人在现场。跨距离控制需自建 BLE→网络 网关（如 ESP32 桥）。

**订单状态是启动的前置条件**：§10.4 证实服务端对 RUNNING 订单返回静默拒绝。任何替代客户端都必须在启动前查 `orderDetail`，否则用户会在"看起来正常"的界面里反复点一个永远不会生效的按钮。
