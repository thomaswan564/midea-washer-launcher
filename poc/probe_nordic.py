# -*- coding: utf-8 -*-
"""
探针：`ble/data/report/nordic` 到底要什么 body 才会签发指令。

背景（实机观测）：
    Nordic 上报后服务端返回 errorCode=0 但 command 为空 -> App 拿不到指令 -> 空转。
    官方 JS（assets/washer/bluetoothConnect.js）里 orderId 是数字，
    而我们的 Kotlin 实现把 orderId 序列化成了字符串。怀疑是类型不匹配。

本脚本不碰蓝牙，只打云端接口（服务端只看 body），用变体对照实验定位差异。
"""
import json
import sys

sys.path.insert(0, ".")
from ujing_api import UjingError  # noqa: E402
from main import api_from_cache  # noqa: E402

MAC = "AABBCCDDEEFF"
DEVICE_ID = "REPLACE_WITH_PLATFORM_DEVICE_ID"
ORDER_ID = REPLACE_WITH_ORDER_ID

# 实机采集到的特征快照（最后一次蓝牙启动时设备真实回读值）
SNAP = {
    "originStr": "3039323231383035353031001A001F006E8E9B1E",
    "deviceInfoStr": "AD010000000000008AA25548",
    "bleVersionStr": "05600201180309",
    "random": "A11FBC59092C046E",
    "orderNoStr": "",
}


def build(type_, command, command_type, order_id, **over):
    body = {
        "type": type_,
        "deviceId": DEVICE_ID,
        "command": command,
        "commandType": command_type,
        **SNAP,
    }
    if order_id is not None:
        body["orderId"] = order_id
    body.update(over)
    return body


def probe(api, label, body):
    print("=" * 78)
    print(f"[{label}]")
    print("  body:", json.dumps(body, ensure_ascii=False))
    try:
        raw = api._req("POST", "/api/v1/ble/data/report/nordic",
                       headers=api._h_reserve(), json_body=body)
        print("  resp:", json.dumps(raw, ensure_ascii=False))
    except UjingError as e:
        print(f"  ERROR code={e.code} msg={e}")
    except Exception as e:  # noqa: BLE001
        print(f"  EXC {type(e).__name__}: {e}")


def main():
    api = api_from_cache()
    if not api.token:
        print("!! 没有 token，先跑 main.py login")
        return

    # 变体 A：完全对齐官方字段名与类型（orderId 数字）
    probe(api, "A 官方形态 type=2 startWash/wash_mode orderId=数字",
          build(2, "startWash", "wash_mode", ORDER_ID))

    # 变体 B：我们的现状（orderId 字符串）
    probe(api, "B 现状 type=2 startWash/wash_mode orderId=字符串",
          build(2, "startWash", "wash_mode", str(ORDER_ID)))

    # 变体 C：不带 orderId
    probe(api, "C type=2 无 orderId",
          build(2, "startWash", "wash_mode", None))

    # 变体 D：type=1（官方 `reportType||1` 的默认分支）
    probe(api, "D type=1 startWash/wash_mode orderId=数字",
          build(1, "startWash", "wash_mode", ORDER_ID))

    # 变体 E：先查订单状态（官方 reportType=3 语义）
    probe(api, "E type=3 同步（无 command）",
          build(3, "", "", None))

    # 变体 F：orderNo 非空（官方先写 ORDER_NO 再上报，orderNoStr 应有值）
    probe(api, "F type=2 + orderNoStr=订单号字符串",
          build(2, "startWash", "wash_mode", ORDER_ID, orderNoStr=str(ORDER_ID)))


if __name__ == "__main__":
    main()
