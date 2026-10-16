# -*- coding: utf-8 -*-
"""U净 开源客户端 PoC —— CLI 主程序

用法:
  python main.py captcha <手机号>
  python main.py login <手机号> <验证码>
  python main.py scan <二维码内容>
  python main.py program <deviceId>
  python main.py order <deviceId> <workModelId> <storeId> <deviceTypeId>
  python main.py orderinfo <orderId>
  python main.py cancel <orderId>
  python main.py unlock <mac> <deviceId> [command=startWash]

token 缓存于 poc/.token.json
"""
from __future__ import annotations

import asyncio
import json
import os
import sys

from ujing_api import UjingApi, UjingError
from ujing_ble import CypressWasher

TOKEN_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".token.json")


# ---------------- token 持久化 ----------------
def load_token() -> str:
    if os.path.exists(TOKEN_FILE):
        try:
            with open(TOKEN_FILE, "r", encoding="utf-8") as f:
                return json.load(f).get("token", "")
        except Exception:
            pass
    return ""


def save_token(tok: str):
    with open(TOKEN_FILE, "w", encoding="utf-8") as f:
        json.dump({"token": tok}, f)


def api_from_cache() -> UjingApi:
    api = UjingApi()
    api.token = load_token()
    if not api.token:
        print("[!] 无 token，请先 login")
        sys.exit(1)
    return api


# ---------------- BLE 全流程 ----------------
async def do_unlock(mac: str, device_id: str, command: str = "startWash"):
    api = api_from_cache()
    w = CypressWasher(mac)

    await w.connect()
    try:
        # 1. 触发随机数交换
        print("\n=== 1. 触发随机数 ===")
        await w.trigger_random()
        frames = await w.wait_frames(10.0)
        if not frames:
            print("[!] 未收到设备数据，无法继续")
            return
        print(f"[+] 组包完成: {len(frames)} 包")

        # 2. 上报云端（取签名/指令的前置）
        print("\n=== 2. 上报设备数据 ===")
        report = [f["raw"] for f in frames]
        print(f"[>] POST ble/data/report reportData={report}")
        try:
            r = api.ble_data_report(mac, report)
            print(f"[<] {r}")
        except UjingError as e:
            print(f"[!] 上报失败: {e}")

        # 3. 写 ACK
        print("\n=== 3. 回 ACK ===")
        await w.send_ack(frames)

        # 4. 取云端指令
        print(f"\n=== 4. 取指令 ({command}) ===")
        pkgs = api.ble_command_acquire(mac, command)
        print(f"[<] {pkgs}")

        # 5. 逐包写入
        print("\n=== 5. 写入设备 ===")
        if pkgs:
            for item in pkgs:
                await w.send_packages(item.get("packages", []), gap=1.0)

        # 6. 收设备响应并回 ACK
        print("\n=== 6. 等待设备响应 ===")
        resp = await w.wait_frames(10.0)
        if resp:
            print(f"[+] 收到 {len(resp)} 包")
            await w.send_ack(resp)
        print("\n[✓] 流程结束")
    finally:
        await w.disconnect()


# ---------------- 命令分发 ----------------
def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return

    cmd = args[0]

    if cmd == "captcha":
        api = UjingApi()
        r = api.send_captcha(args[1])
        print(f"[+] 验证码已发送: {r}")

    elif cmd == "login":
        api = UjingApi()
        tok = api.login(args[1], args[2])
        save_token(tok)
        print(f"[+] 登录成功, token 已保存")

    elif cmd == "scan":
        api = api_from_cache()
        r = api.scan_washer_code(args[1])
        print(json.dumps(r, ensure_ascii=False, indent=2))

    elif cmd == "program":
        api = api_from_cache()
        r = api.program_info(device_id=args[1])
        print(json.dumps(r, ensure_ascii=False, indent=2))
        models = r.get("deviceWashModel") or []
        print("\n--- 洗衣模式 ---")
        for m in models:
            print(f"  workModelId={m.get('workModelId')}  {m.get('name') or m.get('workModelName')}  "
                  f"¥{m.get('promotionPrice') or m.get('basePrice')}  {m.get('time')}min")

    elif cmd == "order":
        api = api_from_cache()
        device_id, work_model_id, store_id, device_type_id = args[1:5]
        r = api.create_order(
            type=1,
            deviceId=device_id,
            deviceWashModelId=work_model_id,
            storeId=store_id,
            deviceTypeId=int(device_type_id),
        )
        print(json.dumps(r, ensure_ascii=False, indent=2))

    elif cmd == "orderinfo":
        api = api_from_cache()
        print(json.dumps(api.order_detail(args[1]), ensure_ascii=False, indent=2))

    elif cmd == "cancel":
        api = api_from_cache()
        print(json.dumps(api.cancel_order(args[1]), ensure_ascii=False, indent=2))

    elif cmd == "unlock":
        mac, device_id = args[1], args[2]
        command = args[3] if len(args) > 3 else "startWash"
        asyncio.run(do_unlock(mac, device_id, command))

    else:
        print(__doc__)


if __name__ == "__main__":
    main()
