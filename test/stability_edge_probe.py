"""郑浩(Nova) 稳定性边界探测: 对 /api/orders/lock 的座位索引做非法/越界输入.
只依赖标准库. 用法: python test/stability_edge_probe.py
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
RESULTS = []


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
        with urllib.request.urlopen(req, timeout=25) as resp:
            return resp.status, json.loads(resp.read()), (time.perf_counter() - t0) * 1000
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw), (time.perf_counter() - t0) * 1000
        except Exception:
            return e.code, {"raw": raw[:300].decode("utf-8", "replace")}, (time.perf_counter() - t0) * 1000
    except Exception as e:
        return -1, {"raw": f"{type(e).__name__}: {e}"}, (time.perf_counter() - t0) * 1000


def probe(base, token, sid, n, label, seats, expect="非 0 业务码 (应被拒绝)"):
    st, r, ms = call(base, "/api/orders/lock", method="POST", token=token,
                     body={"sessionId": sid, "seatIndexes": seats})
    code = r.get("code")
    verdict = "OK" if code not in (0, None) else "!! 被接受了"
    if code == 42900:
        verdict = "?? 被限流掩盖(需重新探测)"
    RESULTS.append((label, seats, st, code, r.get("msg"), ms, verdict))
    print(f"  {label:<34} seats={str(seats):<28} http={st} code={code} {ms:6.1f}ms  {r.get('msg')}")
    print(f"      -> {verdict}   (期望: {expect})")
    # @RateLimit 是 per user+session 每秒 5 次; 逐条探测之间留出窗口, 否则后续用例
    # 会被 42900 掩盖, 看起来"被拒绝了"其实根本没走到业务逻辑。
    time.sleep(1.3)
    return code, r


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--user", default="user1")
    ap.add_argument("--cleanup", action="store_true",
                    help="探测结束后删除该场次的 Redis 位图, 避免污染演示数据")
    args = ap.parse_args()
    base = args.base.rstrip("/")

    print("=" * 100)
    print("郑浩(Nova) 稳定性边界探测  " + time.strftime("%Y-%m-%d %H:%M:%S"))
    print("=" * 100)

    st, r, _ = call(base, "/api/auth/login", method="POST",
                    body={"username": args.user, "password": "123456"})
    token = r["data"]["token"]

    # 选一个"位图尚未分配"的干净场次, 这样 [F] 段量到的字节数就是本次探测造成的膨胀
    now = time.strftime("%Y-%m-%dT%H:%M:%S")
    sid = n = None
    for off in (0, 1, 2, 3):
        d = time.strftime("%Y-%m-%d", time.localtime(time.time() + off * 86400))
        st, r, _ = call(base, f"/api/movies/113/sessions?date={d}")
        for s in [x for x in (r.get("data") or []) if (x.get("startTime") or "") > now]:
            st2, r2, _ = call(base, f"/api/sessions/{s['id']}/seat-map", token=token)
            if r2.get("code") == 0 and not any(base64.b64decode(r2["data"]["lockBitmap"])):
                sid = s["id"]
                n = r2["data"]["seatCount"]
                break
        if sid:
            break
    if not sid:
        print("!! 找不到干净的未开场场次, 请先清理 Redis 位图或补种场次")
        return 1
    print(f"探测场次 session={sid} seatCount={n} (合法座位索引区间 0..{n-1})")
    print(f"该场次 lock 位图初始字节数={len(base64.b64decode(r2['data']['lockBitmap']))} "
          f"(seatCount={n} 理论值 {(n + 7) // 8})\n")

    print("[A] 越界座位索引 (DTO 只有 @NotEmpty/@Size(max=4), 没有 @Min/@Max 范围校验)")
    probe(base, token, sid, n, "A1 座位索引 = seatCount (刚好越界)", [n])
    probe(base, token, sid, n, "A2 座位索引 = 100000", [100000])
    probe(base, token, sid, n, "A3 座位索引 = -1 (负数)", [-1])
    probe(base, token, sid, n, "A4 座位索引 = -99999 (大负数)", [-99999])

    print("\n[B] 结构与类型")
    probe(base, token, sid, n, "B1 空数组", [])
    probe(base, token, sid, n, "B2 5 个座位 (超上限)", [0, 1, 2, 3, 4])
    probe(base, token, sid, n, "B3 重复座位", [5, 5, 5, 5])
    probe(base, token, sid, n, "B4 字符串座位", ["abc"])
    probe(base, token, sid, n, "B5 null 座位元素", [None])
    # B6 必须真的换一个不存在的 sessionId, probe() 固定用 sid, 所以这里直接调
    st, r, ms = call(base, "/api/orders/lock", method="POST", token=token,
                     body={"sessionId": 999999999, "seatIndexes": [0]})
    print(f"  {'B6 sessionId 不存在':<34} seats=[0] sessionId=999999999   "
          f"http={st} code={r.get('code')} {r.get('msg')}")
    print(f"      -> {'OK' if r.get('code') != 0 else '!! 被接受了'}   (期望: 非 0)")
    time.sleep(1.3)

    print("\n[C] 认证")
    st, r, _ = call(base, "/api/orders/lock", method="POST", token=None,
                    body={"sessionId": sid, "seatIndexes": [7]})
    print(f"  C1 无 Authorization           http={st} code={r.get('code')} {r.get('msg')}")
    st, r, _ = call(base, "/api/orders/lock", method="POST", token="garbage.token.here",
                    body={"sessionId": sid, "seatIndexes": [7]})
    print(f"  C2 伪造 token                 http={st} code={r.get('code')} {r.get('msg')}")

    print("\n[D] 分页/查询边界")
    for q in ("page=1&size=10", "page=0&size=10", "page=1&size=-5", "page=1&size=100000", "page=-1&size=10"):
        st, r, ms = call(base, f"/api/orders/my?{q}", token=token)
        print(f"  GET /api/orders/my?{q:<20} http={st} code={r.get('code')} {ms:6.1f}ms {r.get('msg')}")

    print("\n[E] 座位图边界")
    for sid2 in (0, -1, 999999999):
        st, r, ms = call(base, f"/api/sessions/{sid2}/seat-map", token=token)
        print(f"  GET /api/sessions/{sid2}/seat-map  http={st} code={r.get('code')} {ms:6.1f}ms {r.get('msg')}")

    print("\n[F] 越界写入后的位图状态 (核心: 越界 SETBIT 后座位图是否还能正常读)")
    st, r, ms = call(base, f"/api/sessions/{sid}/seat-map", token=token)
    if r.get("code") == 0:
        d = r["data"]
        lb = len(base64.b64decode(d["lockBitmap"]))
        print(f"  seat-map 仍可读: code=0 seatCount={d['seatCount']} lockBitmap字节数={lb} "
              f"(理论 {((d['seatCount'])+7)//8} 字节)")
        print(f"  !! 位图已被本次越界探测永久撑大: {lb} 字节 = 理论值的 {lb / (((d['seatCount'])+7)//8):.1f} 倍")
        print("     该场次之后每次 GET seat-map 都会返回这个膨胀后的位图(接口公开、无需登录)")
    else:
        print(f"  !! seat-map 读取失败: code={r.get('code')} msg={r.get('msg')}")

    print("\n" + "=" * 100)
    print("汇总:")
    for label, seats, st, code, msg, ms, verdict in RESULTS:
        flag = "ACCEPTED" if code == 0 else "rejected"
        print(f"  {label:<34} {flag:<9} code={code}  {ms:6.1f}ms  {msg}")
    print("=" * 100)

    if args.cleanup:
        for suffix in ("lock", "sold"):
            key = f"cinema:session:{sid}:{suffix}"
            try:
                subprocess.run([REDIS_CLI, "del", key], capture_output=True, timeout=10)
            except Exception as e:
                print(f"  清理 {key} 失败: {e}")
        print(f"已清理场次 {sid} 的 Redis 位图(下次访问按 seatCount 重新分配)")
    else:
        print(f"提示: 本次探测污染了场次 {sid} 的位图, 用 --cleanup 可自动清理,")
        print(f"      或手动执行: redis-cli del cinema:session:{sid}:lock cinema:session:{sid}:sold")
    return 0


if __name__ == "__main__":
    sys.exit(main())
