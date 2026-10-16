# -*- coding: utf-8 -*-
"""U净 云端 API 客户端 —— 逆向自 com.midea.vm.washer
协议依据: docs/U净协议逆向报告.md §2
"""
from __future__ import annotations

from typing import Any, Optional

import requests

BASE = "https://phoenix.ujing.online"
TIMEOUT = 20
UA_PHONE = "U jing/2.4.3 (iPhone; iOS 17.3; Scale/3.00)"


class UjingError(RuntimeError):
    def __init__(self, code, msg, raw=None):
        super().__init__(f"[{code}] {msg}")
        self.code = code
        self.msg = msg
        self.raw = raw


class UjingApi:
    """云端接口封装。token 过期(code=401) 需重新 login。"""

    def __init__(self, base: str = BASE):
        self.base = base.rstrip("/")
        self.s = requests.Session()
        self.token: Optional[str] = None

    # ---------------- headers ----------------
    def _h_code(self) -> dict:
        return {
            "x-app-code": "ZI",
            "x-app-version": "2.4.3",
            "User-Agent": UA_PHONE,
            "x-mobile-brand": "apple",
            "x-mobile-model": "iPhone14,5",
            "x-user-geo": "-180.000000,-180.000000",
            "Host": "phoenix.ujing.online:443",
        }

    def _h_login(self) -> dict:
        h = self._h_code()
        h["Content-Type"] = "application/json"
        return h

    def _h_reserve(self) -> dict:
        return {
            "x-app-code": "BI",
            "x-app-version": "2.4.2",
            "weex-version": "1.1.30",
            "User-Agent": UA_PHONE,
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.token}",
        }

    # ---------------- core ----------------
    def _req(self, method, path, *, headers, params=None, json_body=None) -> Any:
        url = self.base + path
        r = self.s.request(method, url, headers=headers, params=params,
                           json=json_body, timeout=TIMEOUT)
        try:
            j = r.json()
        except Exception:
            raise UjingError(r.status_code, f"非 JSON 响应: {r.text[:200]}", r.text)
        code = j.get("code")
        if code not in (0, 200):
            raise UjingError(code, j.get("message") or j.get("msg") or "接口失败", j)
        return j

    # ---------------- 鉴权 ----------------
    def send_captcha(self, mobile: str) -> Any:
        return self._req("GET", "/api/v1/captcha", headers=self._h_code(), params={
            "mobile": mobile,
            "sessionId": "AFS_SWITCH_OFF",
            "sig": "AFS_SWITCH_OFF",
            "token": "AFS_SWITCH_OFF",
            "type": 1,
        })

    def login(self, mobile: str, captcha: str) -> str:
        j = self._req("POST", "/api/v1/login", headers=self._h_login(),
                      json_body={"captcha": captcha, "mobile": mobile})
        self.token = j["data"]["token"]
        return self.token

    # ---------------- 设备 ----------------
    def scan_washer_code(self, qr_code: str) -> dict:
        j = self._req("POST", "/api/v1/devices/scanWasherCode",
                      headers=self._h_reserve(), json_body={"qrCode": qr_code})
        return j["data"]["result"]

    def device_detail(self, device_id) -> dict:
        j = self._req("GET", f"/api/v1/devices/{device_id}", headers=self._h_reserve())
        return j["data"]

    def store_info(self, store_id) -> dict:
        j = self._req("GET", "/api/v1/app/washer/store/info", headers=self._h_reserve(),
                      params={"storeId": store_id})
        return j["data"]

    def program_info(self, device_id=None, store_id=None, device_type_id=None) -> dict:
        p = {}
        if device_id is not None:
            p["deviceId"] = device_id
        if store_id is not None:
            p["storeId"] = store_id
        if device_type_id is not None:
            p["deviceTypeId"] = device_type_id
        j = self._req("GET", "/api/v1/app/washer/devices/program/info",
                      headers=self._h_reserve(), params=p)
        return j["data"]

    def wait_time(self, store_id, device_type_id) -> dict:
        j = self._req("GET", "/api/v1/app/washer/store/waitTime", headers=self._h_reserve(),
                      params={"storeId": store_id, "deviceTypeId": device_type_id})
        return j["data"]

    def reserve_devices(self, **params) -> dict:
        j = self._req("GET", "/api/v1/devices/reserve", headers=self._h_reserve(), params=params)
        return j["data"]

    # ---------------- 订单 ----------------
    def create_order(self, **body) -> dict:
        j = self._req("POST", "/api/v1/orders/create", headers=self._h_reserve(), json_body=body)
        return j["data"]

    def order_detail(self, order_id, additional="price") -> dict:
        j = self._req("GET", f"/api/v1/orders/{order_id}/detail", headers=self._h_reserve(),
                      params={"additional": additional})
        return j["data"]

    def cancel_order(self, order_id) -> dict:
        j = self._req("POST", f"/api/v1/orders/{order_id}/cancel", headers=self._h_reserve(),
                      json_body={"orderId": order_id})
        return j["data"]

    # ---------------- BLE ----------------
    def ble_data_report(self, mac_address: str, report_data: list) -> Any:
        j = self._req("POST", "/api/v1/ble/data/report", headers=self._h_reserve(),
                      json_body={"macAddress": mac_address, "reportData": report_data})
        return j["data"]

    def ble_command_acquire(self, mac_address: str, command: str) -> Any:
        j = self._req("POST", "/api/v1/ble/command/acquire", headers=self._h_reserve(),
                      json_body={"macAddress": mac_address, "command": command})
        return j["data"]
