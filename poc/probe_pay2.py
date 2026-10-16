# -*- coding: utf-8 -*-
"""支付渠道列表精简探针（只读）。"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import main as m  # noqa: E402
from ujing_api import UjingError  # noqa: E402

api = m.api_from_cache()

ORDER_ID = REPLACE_WITH_ORDER_ID
DEVICE_ID = "REPLACE_WITH_PLATFORM_DEVICE_ID"
SUBJECT_ID = 63241

INTEREST = [
    "channel", "name", "isDefault", "isRecommended", "weight4Display",
    "paymentSigned", "isPaymentSignAgreen", "paymentSignGuide",
    "paymentSignedShowWay", "paymentSignedContent",
    "jumpToAliMini", "needOAuth", "branchType", "bindCardStatus",
    "balance", "appKey", "schoolCode", "payModelFlag",
    "needUpgrade", "upgradeVersion", "isShowCanBuy",
]

try:
    r = api._req("GET", "/api/v1/payment/methods",
                 headers=api._h_reserve(),
                 params={"orderId": ORDER_ID, "deviceId": DEVICE_ID, "serviceSubjectId": SUBJECT_ID})
except UjingError as e:
    print(f"UjingError code={e.code} msg={e.msg}"); raise SystemExit(1)

data = r.get("data") or {}
chs = data.get("channels") or []
print(f"code={r.get('code')} message={r.get('message')} channels={len(chs)}")
print(f"data 其他键: {[k for k in data.keys() if k != 'channels']}")
print()

for i, c in enumerate(chs):
    print(f"--- [{i}] {c.get('channel')} / {c.get('name')} ---")
    for k in INTEREST:
        if k in c:
            v = c[k]
            if v not in ("", 0, False, None, []):
                print(f"    {k} = {json.dumps(v, ensure_ascii=False)}")
    print()

# 打印完整原始 JSON 到文件，便于后续查阅
out = Path(__file__).parent / "probe_pay_methods.json"
out.write_text(json.dumps(r, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"完整原始响应已写入: {out}")
