"""压测工具集: 提供 QPS/P99/错误率统计, 支持三个场景
- scenario A: 座位图读
- scenario B: 锁座抢购(同时支持两种模式: 抢同一座位 / 抢不同座位)
- scenario C: 混合(80% 读座位图 + 15% 锁座 + 5% 我的订单)
"""
import concurrent.futures as cf
import urllib.request
import urllib.error
import json
import statistics
import time
import argparse
import sys
import random
import base64

BASE = "http://localhost:8080"

def login(username, password):
    req = urllib.request.Request(
        f"{BASE}/api/auth/login",
        data=json.dumps({"username": username, "password": password}).encode(),
        headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=10))["data"]["token"]

def ensure_users(n, prefix="pt"):
    """确保有 n 个测试用户(密码统一为 123456)"""
    tokens = []
    suffix = str(int(time.time()))
    for i in range(n):
        uname = f"{prefix}{i}_{suffix}"
        try:
            req = urllib.request.Request(
                f"{BASE}/api/auth/register",
                data=json.dumps({"username": uname, "password": "123456"}).encode(),
                headers={"Content-Type": "application/json"})
            urllib.request.urlopen(req, timeout=5)
        except urllib.error.HTTPError:
            pass
        tokens.append(login(uname, "123456"))
    return tokens

def timed(url, *, method="GET", token=None, body=None):
    """返回 (latency_ms, status_code, parsed_body_or_None)"""
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    else:
        data = None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            payload = json.load(resp)
            latency_ms = (time.perf_counter() - t0) * 1000
            return latency_ms, payload.get("code", resp.status), payload
    except urllib.error.HTTPError as e:
        latency_ms = (time.perf_counter() - t0) * 1000
        try:
            payload = json.loads(e.read())
            return latency_ms, payload.get("code", e.code), payload
        except Exception:
            return latency_ms, e.code, None
    except Exception as e:
        latency_ms = (time.perf_counter() - t0) * 1000
        return latency_ms, -1, None

def percentile(latencies, p):
    if not latencies:
        return 0
    return statistics.quantiles(latencies, n=100, method="inclusive")[p - 1] if len(latencies) > 1 else latencies[0]

def report(name, latencies, results, duration_s):
    if not latencies:
        print(f"[{name}] 无样本")
        return
    sorted_l = sorted(latencies)
    p50 = percentile(sorted_l, 50)
    p90 = percentile(sorted_l, 90)
    p99 = percentile(sorted_l, 99)
    ok = sum(1 for _, _, payload in results if isinstance(payload, dict) and payload.get("code") == 0)
    biz_err = sum(1 for _, _, payload in results if isinstance(payload, dict) and 40000 <= payload.get("code", 0) < 50000)
    sys_err = sum(1 for _, _, payload in results if not (isinstance(payload, dict) and "code" in payload))
    qps = len(results) / duration_s if duration_s > 0 else 0
    print(f"=== {name} ===")
    print(f"  总请求数: {len(results)}")
    print(f"  耗时:     {duration_s:.2f}s")
    print(f"  QPS:      {qps:.1f}")
    print(f"  成功率:   {ok}/{len(results)} = {ok/len(results)*100:.2f}%")
    print(f"  业务错:   {biz_err} (4xx) ; 系统错: {sys_err}")
    print(f"  P50/P90/P99: {p50:.1f}/{p90:.1f}/{p99:.1f} ms")
    print(f"  Min/Max: {min(sorted_l):.1f}/{max(sorted_l):.1f} ms")

# ---------- 场景 ----------
def scenario_a_read(session_id, concurrency, duration_s):
    """座位图读: GET /api/sessions/{sid}/seat-map"""
    url = f"{BASE}/api/sessions/{session_id}/seat-map"
    print(f"启动场景A(座位图读): session={session_id}, 并发={concurrency}, 时长={duration_s}s")
    deadline = time.time() + duration_s
    results = []
    latencies = []
    request_id = [0]
    def task(_):
        if time.time() >= deadline:
            return None
        request_id[0] += 1
        return timed(url)
    with cf.ThreadPoolExecutor(max_workers=concurrency) as ex:
        # 持续投递直到截止
        while time.time() < deadline:
            batch = [ex.submit(task, i) for i in range(concurrency * 2)]
            for f in batch:
                r = f.result()
                if r is None:
                    continue
                lat, _, payload = r
                results.append(r)
                latencies.append(lat)
    report("场景A 座位图读", latencies, results, duration_s)
    return results

def scenario_b_lock(tokens, session_id, target_seats, concurrency, duration_s):
    """锁座抢购: 多用户争抢 target_seats
    - tokens: 已就绪的用户 token 列表(每个用户同一场次限一单, 用户数必须 >= 抢单数)
    - target_seats: 抢的座位索引列表"""
    print(f"启动场景B(锁座抢购): 用户={len(tokens)}, 目标座位={target_seats}, 并发={concurrency}, 时长={duration_s}s")
    deadline = time.time() + duration_s
    results = []
    latencies = []
    def task(_):
        if time.time() >= deadline:
            return None
        token = random.choice(tokens)
        body = {"sessionId": session_id, "seatIndexes": target_seats}
        return timed(f"{BASE}/api/orders/lock", method="POST", token=token, body=body)
    with cf.ThreadPoolExecutor(max_workers=concurrency) as ex:
        while time.time() < deadline:
            batch = [ex.submit(task, i) for i in range(concurrency * 2)]
            for f in batch:
                r = f.result()
                if r is None:
                    continue
                lat, code, payload = r
                results.append(r)
                latencies.append(lat)
    report("场景B 锁座抢购", latencies, results, duration_s)
    return results

def scenario_c_mix(tokens, session_id, concurrency, duration_s):
    """混合: 80% 读座位图 + 15% 锁座(随机座) + 5% 我的订单"""
    print(f"启动场景C(混合): 并发={concurrency}, 时长={duration_s}s")
    deadline = time.time() + duration_s
    results = []
    latencies = []
    def task(_):
        if time.time() >= deadline:
            return None
        r = random.random()
        token = random.choice(tokens)
        if r < 0.80:
            return timed(f"{BASE}/api/sessions/{session_id}/seat-map")
        elif r < 0.95:
            # 随机选 1~2 个未大概率未占用的座位
            seat = random.randint(0, 139)
            return timed(f"{BASE}/api/orders/lock", method="POST", token=token,
                         body={"sessionId": session_id, "seatIndexes": [seat]})
        else:
            return timed(f"{BASE}/api/orders/my?page=1&size=10", token=token)
    with cf.ThreadPoolExecutor(max_workers=concurrency) as ex:
        while time.time() < deadline:
            batch = [ex.submit(task, i) for i in range(concurrency * 2)]
            for f in batch:
                r = f.result()
                if r is None:
                    continue
                lat, _, payload = r
                results.append(r)
                latencies.append(lat)
    report("场景C 混合", latencies, results, duration_s)
    return results

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--scenario", required=True, choices=["a", "b", "c", "all"])
    parser.add_argument("--session", type=int, default=1, help="场次ID")
    parser.add_argument("--concurrency", type=int, default=200)
    parser.add_argument("--duration", type=int, default=30, help="持续秒数")
    parser.add_argument("--users", type=int, default=100, help="场景B/C 预备用户数")
    args = parser.parse_args()

    if args.scenario in ("b", "c", "all"):
        print(f"准备 {args.users} 个测试用户...")
        tokens = ensure_users(args.users)
        print(f"就绪 {len(tokens)} token")

    if args.scenario == "a" or args.scenario == "all":
        scenario_a_read(args.session, args.concurrency, args.duration)
    if args.scenario == "b" or args.scenario == "all":
        scenario_b_lock(tokens, args.session, [50, 51, 52, 53], args.concurrency, args.duration)
    if args.scenario == "c" or args.scenario == "all":
        scenario_c_mix(tokens, args.session, args.concurrency, args.duration)

if __name__ == "__main__":
    main()