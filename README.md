# UjingMini — U净洗衣机开源极简客户端

替代 U净官方客户端的第三方 Android 客户端：扫机身二维码识机、选洗衣模式、**蓝牙直连启动**洗衣机。
零广告、零埋点、零推送、零统计 SDK。

本版本增加附近设备发现：扫描名称符合 `Midea数字` 的 BLE 洗衣机，按信号强度排序并允许手动选择。设备选择用于现场确认目标机器；**首次绑定仍需机身二维码，启动仍需有效订单及网络连接**。

---

## ⚠️ 使用前提（先读）

- 仅用于**设备使用者本人**在**自己有权使用**的洗衣机上操作。
- **不绕过支付**：启动所需的订单与支付仍走官方云端，本工程不提供免费洗衣能力。
- 不修改服务端、不攻击他人设备、不做批量自动化。
- 逆向产物仅供学习研究，请勿商用。

---

## 功能

| 功能 | 状态 |
|---|---|
| 手机号 + 短信验证码登录 | ✅ |
| 扫机身二维码 → deviceId / macAddress / moduleType / 模式列表 | ✅ |
| 洗衣模式选择（服务端动态下发） | ✅ |
| 蓝牙直连启动（Nordic nRF51822 通道，`moduleType=1`） | ✅ 实机验证通过 |
| 蓝牙直连启动（Cypress CYBLE-20736 通道，`moduleType=5`） | ✅ 代码就绪（无实机） |
| 订单状态查询（运行中自动禁用启动按钮） | ✅ |
| 一键开洗（选模式 → 下单 → 拉起支付宝 → 付款后切启动按钮） | ✅ |
| APP 内支付宝支付（官方 SDK，订单串由服务端签发，App 不经手资金） | ✅ |
| 机身时间同步（读 Nordic STATUS 特征，与机身显示屏一致） | ✅ 实机验证通过 |
| 运行中 15 秒轮询 + 本地每秒倒计时，洗完自动收尾 | ✅ |
| 无广告 / 无埋点 / 无推送 | ✅ |

### 不提供（有意为之）

- **免密代扣**：U净 仅在打水业务开通此能力，洗衣机业务服务端未开通；且支付宝规定免密代扣必须跳转支付宝签约一次。
- **远程启动**：本机为纯蓝牙机型（`moduleType=1`，无 NB/4G 通道），蓝牙物理距离约 10 m。
- **2G / NB / 4G 机型**：那类机型走云端启动，不经蓝牙。

---

## 架构

```
app/
├── app/src/main/java/online/ujing/mini/
│   ├── UjingApp.kt               Application，无任何第三方 SDK 初始化
│   ├── MainActivity.kt           权限申请（BLUETOOTH_SCAN / BLUETOOTH_CONNECT 或 ACCESS_FINE_LOCATION）
│   ├── data/TokenStore.kt        DataStore：token / 手机号 / 设备上下文 / 上次模式
│   ├── net/
│   │   ├── Headers.kt            两组请求头（ZI 组鉴权前 / BI 组鉴权后）
│   │   ├── Models.kt             全部数据类与枚举（moduleType / deviceType / 订单状态 / 投放档位）
│   │   └── UjingApi.kt           OkHttp 手写客户端（无 Retrofit）
│   ├── ble/
│   │   ├── BleUuids.kt           Nordic + Cypress 全 UUID
│   │   ├── FrameCodec.kt         Cypress 帧解析 / ACK 构造 / 组包
│   │   ├── DeviceResolver.kt     MAC 归一化 + BluetoothDevice 解析
│   │   ├── NordicWasher.kt       ★本机主路径：GATT 连接 + 读状态 + 上报云端 + 写指令
│   │   └── CypressWasher.kt      Cypress 通道：notify + 组包/ACK + 上报 + 取指令逐包写入
│   └── ui/
│       ├── MainViewModel.kt      单 ViewModel + StateFlow
│       ├── AppRoot.kt            Compose 单栈界面
│       └── Theme.kt              Material3 动态取色
└── app/src/main/res/             图标 / 主题 / 字符串
```

**依赖**：Compose BOM、Material3、OkHttp、kotlinx-serialization、DataStore、zxing-android-embedded。
无 Hilt、无 Retrofit、无任何统计/推送/广告/崩溃上报 SDK。

---

## 构建

### 环境要求

- JDK 17+（实测 JDK 21）
- Android SDK：`compileSdk 36`、`build-tools 36.0.0`
- Gradle 8.14.3（wrapper 已内置，无需另装）

### 步骤

```bash
# 1. 指定 SDK 路径（Windows 需转义）
#    编辑 app/local.properties：
sdk.dir=C\:\\Users\\<你的用户名>\\AppData\\Local\\Android\\Sdk

# 2. 构建
cd app
.\gradlew.bat assembleDebug          # Windows
./gradlew assembleDebug              # macOS / Linux
```

产物：`app/app/build/outputs/apk/debug/app-debug.apk`

### 已知构建陷阱

- **工程路径含非 ASCII 字符**（如中文目录）时，`gradle.properties` 必须保留
  `android.overridePathCheck=true`，否则 AGP 直接拒绝。
- zxing 的 `CaptureActivity` 与本工程主题冲突，已在 manifest 用 `tools:replace` 覆盖
  （`android:screenOrientation,android:theme,android:windowSoftInputMode,android:stateNotNeeded`）。
- 需显式依赖 `androidx.appcompat:appcompat`（主题继承 `Theme.AppCompat.DayNight.NoActionBar`）。
- `material-icons-core` **不含** `Icons.Filled.Bluetooth`，本工程用自绘矢量
  `res/drawable/ic_bluetooth.xml`。

---

## 使用

1. 安装 `app-debug.apk`
2. 授予权限：Android 12+ → 附近设备（蓝牙）；Android 11- → 定位
3. 手机号登录（收验证码）
4. 「扫码」对准洗衣机机身二维码 → 读出设备信息与洗衣模式
5. 选模式 → 点「蓝牙启动」（**需站在机器旁，约 10 m 内**）
6. 订单已存在但需重发指令时，点「同步」刷新状态

### 附近设备选择

登录后点「搜索附近 Midea 洗衣机」，从扫描结果选择现场机器。若选择的设备与已绑定的订单设备不一致，客户端会阻止启动，避免将订单指令发到另一台机器。当前协议报告显示启动指令由服务端签发，因此本项目不支持纯离线启动，也不会重放抓取的启动包。

---

## 技术要点

### 云端

```
BASE: https://phoenix.ujing.online
鉴权前头: x-app-code: ZI, x-app-version: 2.4.3, User-Agent: U jing/2.4.3 (iPhone; iOS 17.3; Scale/3.00)
鉴权后头: x-app-code: BI, x-app-version: 2.4.2, weex-version: 1.1.30, Authorization: Bearer <token>
```

关键端点：`captcha` → `login` → `devices/scanWasherCode` → `app/washer/devices/program/info`
→ `orders/create` → `ble/data/report/nordic` → `ble/command/acquire`。

### 蓝牙（Nordic nRF51822）

```
Service     4A323000-79A1-44DE-A67E-11071F138630
POWER       4A320002-…   write   switch
WASH_MODE   4A320003-…   write   wash_mode
START       4A320004-…   write   wash
STATUS      4A320009-…   read
ORDER_NO    4A32000B-…   write
```

流程：连接 → 读状态特征 → 上报 `ble/data/report/nordic` → **云端返回待写指令** → 写 `ORDER_NO` + 目标特征。

### 蓝牙（Cypress CYBLE-20736，`moduleType == 5`）

```
Service          6569A8E8-1C4D-A184-5C4B-3D365C6DF1F3
DEVICE_APP_DATA  27BF7B55-…   notify  设备→App（数据帧）
APP_DEVICE_DATA  195FB501-…   write   App→设备（触发随机数 / 下发指令包）
DEVICE_APP_ACK   239B91E2-…   notify  设备→App
APP_DEVICE_ACK   6E2C0961-…   write   App→设备（回 ACK）
```

帧格式：`[frameNum:2][total:2][pkgNum:2][pkgLen:2][payload]`（全 hex）；
ACK = `frameNum + total + XOR(pkgNum) + SUM(pkgLen) + "00"/"01"`。

流程：连接 → 开两路 notify → 写 `00010100FFFFFFFF` 触发随机数交换 → 收包组帧 → 回 ACK
→ 上报 `ble/data/report` → `ble/command/acquire` 取云端指令包 → 逐包写入（间隔 1 s）。

> 结论：**客户端只是透传管道**，指令帧由服务端生成。纯离线启动不可行。

---

## 实机验证结果

> **实测环境**：vivo V2309A（Android 16，MediaTek，无 root）
> **目标设备**：美的蓝牙波轮洗衣机，`macAddress=E2:60:5A:XX:XX:XX`，`moduleType=1`（TYPE_BLE_NORDIC）
> **日期**：2026-09-22

| 项 | 结果 |
|---|---|
| 扫码绑定设备 | ✅ `scanWasherCode` 返回 deviceId / macAddress / moduleType / deviceTypeId |
| 洗衣模式列表 | ✅ 名称、描述、价格、时长全部正确 |
| 蓝牙扫描 → 连接 | ✅ 首连一次成功（设备广播名 `MideaF664`，RSSI ≈ -79 dBm） |
| GATT 服务发现 | ✅ 19 个特征 |
| 读设备状态 | ✅ STATUS / WASH_COUNT / BLE_VERSION / RANDOM / ORDER_NO 五特征读值成功 |
| 云端状态上报 | ✅ `ble/data/report/nordic` 返回 `errorCode=0` |
| **写指令启动洗衣机** | ✅ **端到端验证通过**（真实付费订单，见下） |
| 订单状态查询 | ✅ `orders/{id}/detail` 取回 status / statusRemark / remainTime |

实测连接全程约 **2.3 s**（扫描命中 0.1 s → 连接 0.8 s → 服务发现 1.0 s → 读特征 0.1 s → 上报 0.4 s）。

### 端到端启动（铁证）

服务端订单记录：

```
18:05:51  支付宝小程序下单支付 ¥2.78（普通洗）
18:06:05  订单锁定
18:07:06  userClickWashStartTime == washStartTime   ← 本客户端点「蓝牙启动」，机器开始运转
```

`userClickWashStartTime` 与 `washStartTime` 同刻，且落在客户端点击时间点上 ——
**BLE 写指令路径端到端打通**。

### 三个反直觉的坑（已修）

1. **BLE 随机地址必须扫描**
   设备地址 `E2:60:5A:XX:XX:XX` 首字节 `0xE2` 二进制是 `1110 0010`，最高两 bit = `11`
   → 这是 **Random Static Address**。用 `BluetoothAdapter.getRemoteDevice(mac)` 直连会按 *public* 类型握手，
   **必然超时**。必须先用 `BluetoothLeScanner` 扫到 `ScanResult.device`（携带正确地址类型）再连接。

2. **首连返回 `status=62`（`GATT_CONN_FAIL_ESTABLISH`）**
   扫描与连接**并发**争抢同一个 BLE 控制器。
   修法：`stopScan` 后 `delay(400ms)` 让控制器状态收敛，再加一次重试。

3. **服务端会「静默拒绝」，不是报错**
   对已进入 RUNNING 的订单，`ble/data/report/nordic` 返回 `{}`（HTTP 200、**连 `code` 字段都没有**）。
   早期代码把它当解析失败 → 抛异常 → UI 报「接口失败」。
   现在归化为业务信号：**启动前先查订单状态，运行中直接禁用按钮**。

> 完整实测数据（时序、特征初值、上报契约探针、修复过程）见 `docs/U净协议逆向报告.md` §9 与 §10。

---

## 已知限制

1. **指令依赖服务端**：`phoenix.ujing.online` 不可达时无法启动。
2. **BLE 距离**约 10 m。官方「远程启动」面向 NB/4G 机型，蓝牙机无法真远程；
   如需远程，须在机器旁自建网关（如 ESP32 走 BLE 连机器 + WiFi 回传手机）。
3. **蓝牙通道已实现 Nordic（`moduleType=1`）与 Cypress（`moduleType=5`）**；
   `2G_BLE(3)` / `NB_BLE(4)` 需另行实现，`2G/NB/4G` 走云端启动。
4. **免密代扣未实现**：付款在 APP 内唤起支付宝完成（官方 SDK），指纹确认后回到 App 自动切启动按钮。

---

## 免责声明

本项目为安全研究 / 逆向工程学习产物，与无锡小净共享网络科技有限公司及美的集团无关，
未获其授权或认可。使用者须自行确保其使用行为合法合规，并在使用前确认对目标设备拥有使用权。
作者不对任何滥用行为或由此产生的后果负责。

---

## 许可

[MIT](LICENSE)。「U净」及相关商标归其权利人所有，本项目仅为协议研究用途。

APK 可从本仓库 [Releases](../../releases) 页面下载，或自行从源码构建。
