# -*- coding: utf-8 -*-
"""支付链路探针（只读，无副作用）。

目标：
  1. GET payment/methods  —— 支付渠道列表（纯读）
  2. 观察响应结构，确认 include/creativeNumber 的实际语义

不调用 payment/arguments（可能创建/锁定支付参数，有副作用风险）。
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import main as m  # noqa: E402
from ujing_api import UjingError  # noqa: E402

api = m.api_from_cache()
print("token:", (api.token or "")[:32], "...")
print()

DEVICE_ID = "REPLACE_WITH_PLATFORM_DEVICE_ID"
ORDER_ID = REPLACE_WITH_ORDER_ID
SUBJECT_ID = 63241
MAC = "AABBCCDDEEFF"


def call(label, path, params=None, headers=None):
    print("=" * 72)
    print(f"### {label}")
    print(f"GET {path}")
    if params:
        print("params:", json.dumps(params, ensure_ascii=False))
    try:
        r = api._req("GET", path,
                     headers=headers or api._h_reserve(),
                     params=params)
        print("OK:", json.dumps(r, ensure_ascii=False)[:3000])
    except UjingError as e:
        print(f"UjingError code={e.code} msg={e.msg}")
    except Exception as e:
        print(f"{type(e).__name__}: {e}")
    print()


# A 最小参数
call("A payment/methods 仅 orderId", "/api/v1/payment/methods",
     params={"orderId": ORDER_ID})

# B 全参数
call("B payment/methods 全参", "/api/v1/payment/methods",
     params={
         "orderId": ORDER_ID,
         "deviceId": DEVICE_ID,
         "serviceSubjectId": SUBJECT_ID,
         "include": "alipay,wechatPay",
         "creativeNumber": "",
     })

# C 带 include（模拟已装支付宝+微信）
call("C payment/methods include=alipay", "/api/v1/payment/methods",
     params={
         "orderId": ORDER_ID,
         "deviceId": DEVICE_ID,
         "serviceSubjectId": SUBJECT_ID,
         "include": "alipay",
     })

# D 无 include
call("D payment/methods 无 include", "/api/v1/payment/methods",
     params={
         "orderId": ORDER_ID,
         "deviceId": DEVICE_ID,
         "serviceSubjectId": SUBJECT_ID,
     })
