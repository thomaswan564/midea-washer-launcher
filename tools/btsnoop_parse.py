#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
btsnoop (HCI UART H4) 解析器 —— U净 洗衣机 BLE 抓包分析

用法:
    python btsnoop_parse.py <file.cfa> [--mac E2:60:5A:XX:XX:XX] [--evt] [--raw]

输出:
    1) 容器信息
    2) LE 连接/广播事件
    3) ATT 操作序列（按 connection handle 分组）
"""
import sys, os, struct, binascii, datetime
from collections import defaultdict

H4_ACL, H4_EVT = 0x02, 0x04

ATT_OPS = {
    0x01: 'ErrorRsp', 0x02: 'MTUReq', 0x03: 'MTURsp',
    0x04: 'FindInfoReq', 0x05: 'FindInfoRsp',
    0x06: 'FindByTypeValReq', 0x07: 'FindByTypeValRsp',
    0x08: 'ReadByTypeReq', 0x09: 'ReadByTypeRsp',
    0x0A: 'ReadReq', 0x0B: 'ReadRsp',
    0x0C: 'ReadBlobReq', 0x0D: 'ReadBlobRsp',
    0x10: 'ReadByGroupReq', 0x11: 'ReadByGroupRsp',
    0x12: 'WriteReq', 0x13: 'WriteRsp',
    0x16: 'PrepWriteReq', 0x17: 'PrepWriteRsp',
    0x18: 'ExecWriteReq', 0x19: 'ExecWriteRsp',
    0x1B: 'Notify', 0x1D: 'Indicate', 0x1E: 'Confirm',
    0x52: 'WriteCmd', 0x53: 'SignedWriteCmd',
}

LE_SUBEV = {
    0x01: 'LE_ConnComplete', 0x02: 'LE_AdvReport', 0x03: 'LE_ConnUpdateComplete',
    0x04: 'LE_ReadRemoteFeatComplete', 0x05: 'LE_LTKRequest',
    0x06: 'LE_RemoteConnParamReq', 0x07: 'LE_DataLengthChange',
    0x0A: 'LE_EnhancedConnComplete', 0x0B: 'LE_DirectedAdvReport',
    0x0C: 'LE_PHYUpdateComplete', 0x0D: 'LE_ExtAdvReport',
    0x10: 'LE_ExtConnComplete', 0x11: 'LE_ScanTimeout',
    0x12: 'LE_AdvSetTerminated', 0x13: 'LE_ScanReqReceived',
    0x1A: 'LE_ChanSelAlgo', 0x1B: 'LE_TransPowerReport',
}

EVT_NAMES = {
    0x05: 'DisconnComplete', 0x08: 'EncChange', 0x0E: 'CmdComplete',
    0x0F: 'CmdStatus', 0x13: 'NumComplPkts', 0x30: 'EncKeyRefresh',
    0x3E: 'LE_Meta',
}


def mac_le(b):
    return ':'.join('%02X' % x for x in reversed(b[:6]))


def mac_uuid(b):
    """128-bit UUID LE 字节序 -> 标准字符串"""
    h = binascii.hexlify(b[:16]).decode().upper()
    return '-'.join([h[0:8], h[8:12], h[12:16], h[16:20], h[20:32]])


def ts_str(ts_us):
    try:
        dt = datetime.datetime.fromtimestamp(ts_us / 1e6, datetime.timezone.utc).astimezone()
        return dt.strftime('%H:%M:%S.%f')[:-3]
    except Exception:
        return str(ts_us)


def parse_btsnoop(path):
    data = open(path, 'rb').read()
    if data[:8] != b'btsnoop\x00':
        raise ValueError('非 btsnoop: ' + binascii.hexlify(data[:8]).decode())
    version, datalink = struct.unpack('>II', data[8:16])
    off, recs = 16, []
    while off + 24 <= len(data):
        orig, incl, flags, drops, ts = struct.unpack('>IIIIQ', data[off:off + 24])
        off += 24
        recs.append((ts, flags, data[off:off + incl]))
        off += incl
    return version, datalink, recs


def main():
    argv = sys.argv[1:]
    if not argv:
        print(__doc__)
        return
    path = argv[0]
    want_mac = None
    show_evt = '--evt' in argv
    if '--mac' in argv:
        want_mac = argv[argv.index('--mac') + 1].replace(':', '').replace('-', '').upper()

    ver, datalink, recs = parse_btsnoop(path)
    print('=' * 78)
    print('文件  : %s' % os.path.basename(path))
    print('大小  : %d B' % os.path.getsize(path))
    print('容器  : btsnoop ver=%d datalink=%d %s' % (
        ver, datalink, '(HCI_UART_H4)' if datalink == 1002 else '(? )'))
    print('记录数: %d' % len(recs))
    if recs:
        print('时间  : %s ~ %s' % (ts_str(recs[0][0]), ts_str(recs[-1][0])))

    counts = defaultdict(int)
    conns = []
    handle_mac = {}
    att = defaultdict(list)
    evt_log = []

    for ts, flags, pkt in recs:
        if not pkt:
            continue
        counts['h4_%02x' % pkt[0]] += 1
        sent = (flags & 0x01) == 0
        arrow = '→' if sent else '←'

        if pkt[0] == H4_EVT and len(pkt) >= 4:
            ev = pkt[1]
            if ev == 0x3E:
                sub = pkt[3]
                p = pkt[4:]
                evt_log.append((ts, arrow, LE_SUBEV.get(sub, 'LE_0x%02X' % sub), p))
                if sub in (0x01, 0x0A) and len(p) >= 11:
                    handle = struct.unpack('<H', p[1:3])[0] & 0x0FFF
                    mac = mac_le(p[5:11])
                    handle_mac[handle] = mac
                    conns.append((ts, handle, mac, p[3], p[4], p[0], 'Enhanced' if sub == 0x0A else 'Legacy'))
            else:
                evt_log.append((ts, arrow, EVT_NAMES.get(ev, 'Evt_0x%02X' % ev), pkt[3:]))
                # Disconnect Complete: status(1) handle(2) reason(1)
                if ev == 0x05 and len(pkt) >= 7:
                    h = struct.unpack('<H', pkt[4:6])[0] & 0x0FFF
                    evt_log[-1] = (ts, arrow, 'DisconnComplete(h=0x%03X,reason=0x%02X)' % (h, pkt[6]), b'')

        elif pkt[0] == H4_ACL and len(pkt) >= 5:
            handle = struct.unpack('<H', pkt[1:3])[0] & 0x0FFF
            dlen = struct.unpack('<H', pkt[3:5])[0]
            body = pkt[5:5 + dlen]
            if len(body) >= 4:
                l2len = struct.unpack('<H', body[0:2])[0]
                cid = struct.unpack('<H', body[2:4])[0]
                if cid == 0x0004:
                    a = body[4:4 + l2len] if l2len else body[4:]
                    if a:
                        op = a[0]
                        h = struct.unpack('<H', a[1:3])[0] if len(a) >= 3 else -1
                        att[handle].append((ts, arrow, op, h, a[3:]))

    print('H4 类型统计: ' + ' '.join('%s=%d' % (k, v) for k, v in sorted(counts.items())))

    print('\n=== LE 连接事件 (%d) ===' % len(conns))
    for ts, handle, mac, role, atype, status, kind in conns:
        print('  %s  handle=0x%03X  mac=%s  role=%d  addrType=%d  %s  status=%d'
              % (ts_str(ts), handle, mac, role, atype, kind, status))

    if show_evt:
        print('\n=== 事件流 (%d) ===' % len(evt_log))
        for ts, arrow, name, p in evt_log:
            extra = binascii.hexlify(p[:24]).decode() if p else ''
            print('  %s %s %-28s %s' % (ts_str(ts), arrow, name, extra))

    print('\n=== ATT 操作 ===')
    for handle in sorted(att):
        mac = handle_mac.get(handle, '?')
        ops = att[handle]
        if want_mac and mac.replace(':', '') != want_mac:
            continue
        print('\n--- handle=0x%03X  mac=%s  %d ops ---' % (handle, mac, len(ops)))
        for ts, arrow, op, h, val in ops:
            name = ATT_OPS.get(op, 'ATT_0x%02X' % op)
            v = binascii.hexlify(val).decode().upper()
            if len(v) > 120:
                v = v[:120] + '...(+%d)' % (len(v) - 120)
            print('  %s %s %-14s h=0x%04X len=%-3d %s' % (ts_str(ts), arrow, name, h, len(val), v))


if __name__ == '__main__':
    main()
