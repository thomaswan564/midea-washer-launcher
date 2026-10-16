# -*- coding: utf-8 -*-
"""payment/arguments 结构探针。

安全边界：
  - 用【已完成】的历史订单 REPLACE_WITH_ORDER_ID（已于 18:07 支付并洗完全程），服务端不会再对它扣款
  - 只调 payment/arguments（生成支付参数），**不唤起支付宝**，因此不会产生任何真实扣款
  - 目的是确认响应结构（payInfo.orderInfo 字段名与形态）与 skipPay 语义
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import main as m  # noqa: E402
from ujing_api import UjingError  # noqa: E402

api = m.api_from_cache()

ORDER_ID = REPLACE_WITH_ORDER_ID
DEVICE_ID = "REPLACE_WITH_PLATFORM_DEVICE_ID"


def call(label, path, params):
    print("=" * 74)
    print(f"### {label}")
    print("GET", path)
    print("params:", json.dumps(params, ensure_ascii=False))
    try:
        r = api._req("GET", path, headers=api._h_reserve(), params=params)
        print("OK:", json.dumps(r, ensure_ascii=False)[:2500])
    except UjingError as e:
        print(f"UjingError code={e.code} msg={e.msg}")
    except Exception as e:
        print(f"{type(e).__name__}: {e}")
    print()


BASE = "/api/v1/payment/arguments"

call("A alipay 最小", BASE, {"channel": "alipay", "orderId": ORDER_ID, "payVersion": 1})

call("B alipay + chooseSign", BASE,
     {"channel": "alipay", "orderId": ORDER_ID, "payVersion": 1, "chooseSign": 1})

call("C alipay 完整入参", BASE, {
    "channel": "alipay", "orderId": ORDER_ID, "payVersion": 1,
    "couponId": "", "isUseRedPacket": False, "redPacketId": "",
    "alipayF2FNoAds": False, "branchType": 0, "jumpToAliMini": False,
})

call("D unionPay", BASE, {"channel": "unionPay", "orderId": ORDER_ID, "payVersion": 1})
