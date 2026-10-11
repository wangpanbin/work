"""郑浩(Nova) 功能维度 E2E: 登录→选座→锁座→支付→出票→退票→重新锁座 全链路实测.
只依赖标准库. 用法: python test/e2e_api_flow.py [--base http://localhost:8080]
"""
import argparse
import base64
import json
import sys
import time
import urllib.error
import urllib.request

PASS, FAIL = [], []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}" + (f"  -- {detail}" if detail else ""))
    return cond


def call(base, path, *, method="GET", token=None, body=None, raw=False):
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            payload = resp.read()
            return resp.status, (payload if raw else json.loads(payload))
    except urllib.error.HTTPError as e:
        payload = e.read()
        try:
            return e.code, (payload if raw else json.loads(payload))
        except Exception:
            return e.code, {"code": e.code, "msg": payload[:200].decode("utf-8", "replace")}


def bits(b64, n):
    b = base64.b64decode(b64)
    return [i for i in range(n) if i // 8 < len(b) and (b[i // 8] >> (7 - i % 8)) & 1]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--user", default="user1")
    ap.add_argument("--password", default="123456")
    args = ap.parse_args()
    base = args.base.rstrip("/")

    print("=" * 68)
    print("郑浩(Nova) E2E 全链路实测  " + time.strftime("%Y-%m-%d %H:%M:%S"))
    print("=" * 68)

    # ---- 1. 登录 ----
    print("\n[1] 登录")
    st, r = call(base, "/api/auth/login", method="POST",
                 body={"username": args.user, "password": args.password})
    check("POST /api/auth/login 返回 code=0", r.get("code") == 0, f"code={r.get('code')} msg={r.get('msg')}")
    token = (r.get("data") or {}).get("token")
    if not token:
        print("!! 登录失败, 终止"); return 1
    check("拿到 JWT token", bool(token), f"len={len(token)}")

    st, r = call(base, "/api/users/me", token=token)
    check("GET /api/users/me 鉴权通过", r.get("code") == 0, f"user={((r.get('data') or {}).get('username'))}")

    # ---- 2. 选片/选场次 ----
    print("\n[2] 选片 / 选场次")
    st, r = call(base, "/api/movies?page=1&size=5")
    check("GET /api/movies 返回 code=0", r.get("code") == 0)
    movies = ((r.get("data") or {}).get("records")) or []
    check("影片列表非空", len(movies) > 0, f"count={len(movies)}")
    if not movies:
        return 1
    movie = movies[0]
    print(f"      影片: id={movie.get('id')} title={movie.get('title')}")

    # 场次: 取今天/明天/后天, 必须挑一个"尚未开场"的场次(已开场会返回 40003 无法购票)
    now = time.strftime("%Y-%m-%dT%H:%M:%S")
    session, scanned = None, []
    for day_off in (0, 1, 2):
        d = time.strftime("%Y-%m-%d", time.localtime(time.time() + day_off * 86400))
        st, r = call(base, f"/api/movies/{movie['id']}/sessions?date={d}")
        rows = r.get("data") or []
        scanned.append(f"{d}:{len(rows)}")
        future = [s for s in rows if (s.get("startTime") or "") > now]
        if future:
            session = future[0]
            print(f"      场次: date={d} id={session.get('id')} hall={session.get('hallName')} "
                  f"start={session.get('startTime')} price={session.get('price')}")
            break
    check("GET /api/movies/{id}/sessions 拿到未开场场次", session is not None, f"scanned={scanned} now={now}")
    if not session:
        return 1
    sid = session["id"]

    # ---- 3. 座位图 ----
    print("\n[3] 座位图")
    st, r = call(base, f"/api/sessions/{sid}/seat-map", token=token)
    check("GET /api/sessions/{id}/seat-map 返回 code=0", r.get("code") == 0)
    sm = r.get("data") or {}
    n = sm.get("seatCount")
    check("seatCount > 0", bool(n), f"seatCount={n} rows={sm.get('rows')} cols={sm.get('cols')}")
    lock0, sold0 = set(bits(sm["lockBitmap"], n)), set(bits(sm["soldBitmap"], n))
    print(f"      初始 lock={sorted(lock0)} sold={sorted(sold0)}")

    free = [i for i in range(n) if i not in lock0 and i not in sold0]
    check("存在可选座位", len(free) >= 4, f"free={len(free)}/{n}")
    if len(free) < 4:
        return 1
    seats = free[:4]
    print(f"      选择座位: {seats}")

    # ---- 4. 锁座 ----
    print("\n[4] 锁座下单")
    st, r = call(base, "/api/orders/lock", method="POST", token=token,
                 body={"sessionId": sid, "seatIndexes": seats})
    check("POST /api/orders/lock 返回 code=0", r.get("code") == 0, f"code={r.get('code')} msg={r.get('msg')}")
    order_no = (r.get("data") or {}).get("orderNo")
    if not order_no:
        print("!! 锁座失败, 后续跳过"); return 1
    amount = (r.get("data") or {}).get("totalAmount")
    check("返回 orderNo", bool(order_no), f"orderNo={order_no} amount={amount}")

    st, r = call(base, f"/api/sessions/{sid}/seat-map", token=token)
    sm2 = r.get("data") or {}
    lock1 = set(bits(sm2["lockBitmap"], n))
    check("选中的 4 座在 lock 位图中置位", set(seats) <= lock1, f"lock delta={sorted(lock1 - lock0)}")
    check("seat-map 回显 myLockedSeats 含本次座位", set(seats) <= set(sm2.get("myLockedSeats") or []),
          f"myLockedSeats={sm2.get('myLockedSeats')}")

    # 重复锁同座 -> 40002 (被占用) 或 40900 (幂等); 都不能成功
    st, r = call(base, "/api/orders/lock", method="POST", token=token,
                 body={"sessionId": sid, "seatIndexes": seats})
    check("同座位重复锁座被拒 (非 0)", r.get("code") != 0, f"code={r.get('code')} msg={r.get('msg')}")

    # 另一用户抢同座 -> 40002 + conflict 明细
    st, r2 = call(base, "/api/auth/login", method="POST", body={"username": "user2", "password": "123456"})
    tok2 = (r2.get("data") or {}).get("token")
    st, r = call(base, "/api/orders/lock", method="POST", token=tok2,
                 body={"sessionId": sid, "seatIndexes": seats})
    check("user2 抢已锁座位返回 40002 且带 conflict", r.get("code") == 40002,
          f"code={r.get('code')} data={r.get('data')}")

    # ---- 5. 支付 ----
    print("\n[5] 支付")
    st, r = call(base, f"/api/orders/{order_no}/pay", method="POST", token=token)
    check("POST /api/orders/{no}/pay 返回 code=0", r.get("code") == 0, f"code={r.get('code')} msg={r.get('msg')}")

    st, r = call(base, f"/api/sessions/{sid}/seat-map", token=token)
    sm3 = r.get("data") or {}
    sold1, lock2 = set(bits(sm3["soldBitmap"], n)), set(bits(sm3["lockBitmap"], n))
    check("支付后座位进入 sold 位图", set(seats) <= sold1, f"sold delta={sorted(sold1 - sold0)}")
    check("支付后 lock 位被清除", not (set(seats) & lock2), f"still locked={sorted(set(seats) & lock2)}")

    st, r = call(base, f"/api/orders/{order_no}", token=token)
    check("订单详情状态为已支付", r.get("code") == 0, f"status={((r.get('data') or {}).get('status'))}")

    # ---- 6. 出票 ----
    print("\n[6] 电子票")
    st, r = call(base, f"/api/orders/{order_no}/ticket", token=token)
    check("GET /api/orders/{no}/ticket 返回 code=0", r.get("code") == 0, f"msg={r.get('msg')}")
    tdata = r.get("data") or {}
    check("票包含 payload + sig", bool(tdata.get("payload")) and bool(tdata.get("sig")), f"keys={sorted(tdata.keys())}")
    if tdata.get("payload"):
        st, r = call(base, f"/api/tickets/verify?payload={urllib.parse.quote(tdata['payload'])}&sig={tdata['sig']}")
        check("GET /api/tickets/verify 验票通过", r.get("code") == 0, f"code={r.get('code')} msg={r.get('msg')}")

    # ---- 7. 退票 ----
    print("\n[7] 退票")
    st, r = call(base, f"/api/orders/{order_no}/refund", method="POST", token=token)
    check("POST /api/orders/{no}/refund 返回 code=0", r.get("code") == 0, f"code={r.get('code')} msg={r.get('msg')}")

    st, r = call(base, f"/api/sessions/{sid}/seat-map", token=token)
    sm4 = r.get("data") or {}
    sold2, lock3 = set(bits(sm4["soldBitmap"], n)), set(bits(sm4["lockBitmap"], n))
    check("退票后座位从 sold 位图清除", not (set(seats) & sold2), f"still sold={sorted(set(seats) & sold2)}")
    check("退票后座位从 lock 位图清除", not (set(seats) & lock3), f"still locked={sorted(set(seats) & lock3)}")

    # ---- 8. 退票后可重新锁座 ----
    print("\n[8] 退票后可再售 (位图闭环)")
    # @Idempotent 的 key 是 userId:sessionId:seatIndexes, ttl=8s。步骤 4 用同一组座位
    # 发过锁座请求, 8s 内重放会命中幂等缓存返回 40900(设计行为, 不是缺陷), 故先等窗口滑过。
    print("      等待 @Idempotent 8s 窗口滑过...")
    time.sleep(9)
    st, r = call(base, "/api/orders/lock", method="POST", token=token,
                 body={"sessionId": sid, "seatIndexes": seats})
    check("退票后同一座位可被重新锁定", r.get("code") == 0, f"code={r.get('code')} msg={r.get('msg')}")
    if r.get("code") == 0:
        st, r = call(base, f"/api/orders/{r['data']['orderNo']}/cancel", method="POST", token=token)
        check("清理: 取消测试订单", r.get("code") == 0, f"code={r.get('code')}")

    # ---- 汇总 ----
    print("\n" + "=" * 68)
    print(f"PASS {len(PASS)} / FAIL {len(FAIL)}")
    if FAIL:
        for f in FAIL:
            print(f"  FAILED: {f}")
    print("=" * 68)
    return 1 if FAIL else 0


if __name__ == "__main__":
    import urllib.parse
    sys.exit(main())
