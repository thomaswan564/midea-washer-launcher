# -*- coding: utf-8 -*-
"""
探针 v2：直连 requests，打印 HTTP 状态与原始响应体，定位 `ble/data/report/nordic` 的字段契约。

核心怀疑：
 官方 JS `connectProcess` 里，上报 body 的 `deviceId` 字段值来自 `paramsAll.id`，
 而 `paramsAll.id` 在调用链上是 BLE 连接对象标识（createBLEDirectConnection 返回的
 connectableDevice.deviceId，微信小程序语义下即 MAC）。官方上报 body 里**没有** macAddress。

 我们的 Kotlin 实现把 `deviceId` 填成了平台 deviceId（32 位 hex ObjectId），
 怀疑服务端据此查不到设备 -> 不签发指令；而带上 orderId 时还直接抛错。
"""
import json
import sys

import requests

sys.path.insert(0, ".")
from main import load_token  # noqa: E402

BASE = "https://phoenix.ujing.online"
MAC = "AABBCCDDEEFF"
PLATFORM_DEVICE_ID = "REPLACE_WITH_PLATFORM_DEVICE_ID"
ORDER_ID = REPLACE_WITH_ORDER_ID

SNAP = {
    "originStr": "3039323231383035353031001A001F006E8E9B1E",
    "deviceInfoStr": "AD010000000000008AA25548",
    "bleVersionStr": "05600201180309",
    "random": "A11FBC59092C046E",
    "orderNoStr": "",
}

HEADERS = {
    "x-app-code": "BI",
    "weex-version": "1.1.30",
    "x-app-version": "2.4.2",
    "Content-Type": "application/json",
}


def call(label, body):
    h = dict(HEADERS)
    h["Authorization"] = f"Bearer {load_token()}"
    print("=" * 78)
    print(f"[{label}]")
    print("  body:", json.dumps(body, ensure_ascii=False))
    try:
        r = requests.post(f"{BASE}/api/v1/ble/data/report/nordic",
                          headers=h, json=body, timeout=20)
        print(f"  HTTP {r.status_code}")
        text = r.text
        print("  resp:", text[:600])
    except Exception as e:  # noqa: BLE001
        print(f"  EXC {type(e).__name__}: {e}")


def main():
    base = {"type": 2, "command": "startWash", "commandType": "wash_mode", **SNAP}

    # G: deviceId=MAC + orderId 数字
    call("G deviceId=MAC + orderId=数字", {**base, "deviceId": MAC, "orderId": ORDER_ID})
    # H: deviceId=MAC + orderId 字符串
    call("H deviceId=MAC + orderId=字符串", {**base, "deviceId": MAC, "orderId": str(ORDER_ID)})
    # I: deviceId=MAC 无 orderId
    call("I deviceId=MAC 无 orderId", {**base, "deviceId": MAC})
    # J: deviceId=MAC + macAddress 也带上 + orderId 数字
    call("J deviceId=MAC + macAddress=MAC + orderId=数字",
         {**base, "deviceId": MAC, "macAddress": MAC, "orderId": ORDER_ID})
    # K: 对照 —— 平台 deviceId + orderId 数字（复现 A）
    call("K deviceId=平台ID + orderId=数字",
         {**base, "deviceId": PLATFORM_DEVICE_ID, "orderId": ORDER_ID})
    # L: type=1 + deviceId=MAC + orderId 数字
    call("L type=1 deviceId=MAC + orderId=数字",
         {**base, "type": 1, "deviceId": MAC, "orderId": ORDER_ID})


if __name__ == "__main__":
    main()
