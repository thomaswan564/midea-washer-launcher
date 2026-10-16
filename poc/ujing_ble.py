# -*- coding: utf-8 -*-
"""U净 蓝牙透传 (Cypress CYBLE-20736) —— 逆向自 com.midea.vm.washer
协议依据: docs/U净协议逆向报告.md §3
"""
from __future__ import annotations

import asyncio
from typing import Callable, Optional

from bleak import BleakClient

# ---------------- GATT ----------------
SERVICE_UUID    = "6569a8e8-1c4d-a184-5c4b-3d365c6df1f3"
DEVICE_APP_DATA = "27bf7b55-19b2-44b3-414b-4e37809d959e"   # 设备→App notify
APP_DEVICE_DATA = "195fb501-e2ed-14b0-984c-041ae69f1a39"   # App→设备 write
DEVICE_APP_ACK  = "239b91e2-66a0-18bf-8d44-05515bc685c4"   # 设备→App notify
APP_DEVICE_ACK  = "6e2c0961-ab91-2481-bc4e-3d0db126fdd7"   # App→设备 write

READ_RANDOM = "00010100FFFFFFFF"    # 触发随机数交换的固定帧


def parse_frame(hexstr: str) -> Optional[dict]:
    """[frameNum:2][total:2][pkgNum:2][pkgLen:2][payload...]"""
    h = hexstr.replace(" ", "").upper()
    if len(h) < 8:
        return None
    return {
        "frameNum": int(h[0:2], 16),
        "total":    int(h[2:4], 16),
        "pkgNum":   int(h[4:6], 16),
        "pkgLen":   int(h[6:8], 16),
        "payload":  h[8:],
        "raw":      h,
    }


def build_ack(frames: list) -> str:
    """ACK = frameNum + total + xor(pkgNum) + sum(pkgLen) + ('00'|'01')"""
    if not frames:
        return ""
    frame_num = frames[0]["frameNum"]
    total     = frames[0]["total"]
    xor_sum   = 0
    len_sum   = 0
    for f in frames:
        xor_sum ^= f["pkgNum"]
        len_sum += f["pkgLen"]
    last = "01" if len(frames) < total else "00"
    return f"{frame_num:02X}{total:02X}{xor_sum:02X}{len_sum:02X}{last}"


class CypressWasher:
    def __init__(self, address: str, on_log: Callable[[str], None] = print):
        self.address = address
        self.log = on_log
        self.client: Optional[BleakClient] = None
        self._frames: list = []
        self._cur_frame: Optional[int] = None
        self._done = asyncio.Event()

    # ---------------- 连接 ----------------
    async def connect(self, timeout=20.0):
        self.log(f"[BLE] 连接 {self.address} ...")
        self.client = BleakClient(self.address, timeout=timeout)
        await self.client.connect()
        self.log(f"[BLE] 已连接: {self.client.is_connected}")
        await self.client.start_notify(DEVICE_APP_DATA, self._on_data)
        await self.client.start_notify(DEVICE_APP_ACK, self._on_ack)
        self.log("[BLE] notify 已订阅 (DEVICE_APP_DATA + DEVICE_APP_ACK)")

    async def disconnect(self):
        if self.client and self.client.is_connected:
            try:
                await self.client.stop_notify(DEVICE_APP_DATA)
                await self.client.stop_notify(DEVICE_APP_ACK)
            except Exception:
                pass
            await self.client.disconnect()
            self.log("[BLE] 已断开")

    # ---------------- 收包 ----------------
    def _on_data(self, _char, data: bytearray):
        h = bytes(data).hex().upper()
        self.log(f"[RX data] {h}")
        f = parse_frame(h)
        if not f:
            return
        if f["pkgNum"] == 1:
            self._cur_frame = f["frameNum"]
            self._frames = []
        if f["frameNum"] != self._cur_frame:
            return
        if any(x["pkgNum"] == f["pkgNum"] for x in self._frames):
            return
        self._frames.append(f)
        if len(self._frames) >= f["total"]:
            self._frames.sort(key=lambda x: x["pkgNum"])
            self._done.set()

    def _on_ack(self, _char, data: bytearray):
        self.log(f"[RX ack] {bytes(data).hex().upper()}")

    # ---------------- 发包 ----------------
    async def write(self, char: str, hexstr: str, resp=True):
        raw = bytes.fromhex(hexstr)
        await self.client.write_gatt_char(char, raw, response=resp)
        self.log(f"[TX] {char[:8]} {hexstr.upper()}")

    async def trigger_random(self):
        self._frames = []
        self._cur_frame = None
        self._done.clear()
        await self.write(APP_DEVICE_DATA, READ_RANDOM)

    async def wait_frames(self, timeout=10.0) -> list:
        try:
            await asyncio.wait_for(self._done.wait(), timeout)
        except asyncio.TimeoutError:
            self.log("[BLE] 等待组包超时")
        return list(self._frames)

    async def send_packages(self, packages: list, gap=1.0):
        for p in packages:
            await self.write(APP_DEVICE_DATA, p.upper())
            await asyncio.sleep(gap)

    async def send_ack(self, frames: list):
        ack = build_ack(frames)
        if ack:
            await self.write(APP_DEVICE_ACK, ack)
