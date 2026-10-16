# U净 开源客户端 PoC

替代 U净官方 APP（`com.midea.vm.washer`）的协议层实现。
**无广告、无埋点、无定位采集。**

## 依赖

```powershell
& "D:\python\python.exe" -m pip install -r requirements.txt
```

- `requests` — 云端 API
- `bleak` — BLE 透传（跨平台）

## 文件

| 文件 | 作用 |
|---|---|
| `ujing_api.py` | 云端 API 客户端（鉴权/设备/订单/BLE 上报与取令） |
| `ujing_ble.py` | Cypress CYBLE-20736 透传（GATT/组包/ACK） |
| `main.py` | CLI 入口 |

## 用法

```powershell
# 1. 登录（验证码）
& "D:\python\python.exe" main.py captcha 13800138000
& "D:\python\python.exe" main.py login 13800138000 123456
# → token 存入 .token.json，后续命令自动复用

# 2. 扫码查设备（二维码原文）
& "D:\python\python.exe" main.py scan "<二维码内容>"

# 3. 查洗衣模式列表（deviceWashModel）
& "D:\python\python.exe" main.py program <deviceId>

# 4. 下单
& "D:\python\python.exe" main.py order <deviceId> <workModelId> <storeId> <deviceTypeId>

# 5. 查订单详情（拿 macAddress / orderNo）
& "D:\python\python.exe" main.py orderinfo <orderId>

# 6. BLE 启动洗衣机
& "D:\python\python.exe" main.py unlock <macAddress> <deviceId> [command]
#    command 默认 startWash，可选 switchOn / selfClean 等
```

## 蓝牙启动流程（`unlock` 内部）

```
1. 连接 macAddress
2. start_notify(DEVICE_APP_DATA) + start_notify(DEVICE_APP_ACK)
3. 写 APP_DEVICE_DATA = "00010100FFFFFFFF"  触发设备回随机数
4. 收包 → 组包（frameNum/total/pkgNum/pkgLen/payload）
5. POST ble/data/report  {macAddress, reportData:[...]}   上报云端
6. 写 APP_DEVICE_ACK = build_ack(frames)                  回 ACK
7. POST ble/command/acquire {macAddress, command}          取云端指令帧
8. 逐包写 APP_DEVICE_DATA（间隔 1s）
9. 收设备响应 → 回 ACK
```

## 自检

```powershell
& "D:\python\python.exe" -X utf8 -c "from ujing_ble import parse_frame, build_ack; print(parse_frame('0A0301FF4142')); print(build_ack([{'frameNum':10,'total':3,'pkgNum':1,'pkgLen':2},{'frameNum':10,'total':3,'pkgNum':2,'pkgLen':5}]))"
# 期望: ack: 0A03030701
```

## 云端连通性验证（已实测）

```
GET  /api/v1/app/washer/devices/program/info   (header ZI, 无 token)
     → 401 {"code":401,"reason":"UNAUTHORIZED","message":"JWT token is missing"}

POST /api/v1/devices/scanWasherCode            (header BI, 坏 token)
     → 401 {"code":401,"reason":"UNAUTHORIZED","message":"Token is invalid"}
```

## 已知限制

1. **指令帧无法本地生成** —— 签名在服务端，必须调 `ble/command/acquire`。
2. **纯蓝牙机需现场操作** —— BLE 有效距离约 10m，远程需自建 BLE→网络 网关。
3. **支付** —— 官方走微信/支付宝 SDK；自建客户端需接自有支付或保留原支付跳转。
4. **`macAddress` 来源待实机确认** —— 推测在 `orders/{id}/detail` 响应中。
