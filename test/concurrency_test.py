"""并发防超卖验证: 200 个请求同时锁座 [30,31], 期望恰好 1 个成功(0 超卖)"""
import concurrent.futures
import urllib.request
import json
import sys

BASE = "http://localhost:8080"

def login():
    req = urllib.request.Request(
        f"{BASE}/api/auth/login",
        data=json.dumps({"username": "user1", "password": "123456"}).encode(),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.load(r)["data"]["token"]

TOK = login()
SID = 1
SEATS = [30, 31]
N = 200

def lock(_):
    try:
        req = urllib.request.Request(
            f"{BASE}/api/orders/lock",
            data=json.dumps({"sessionId": SID, "seatIndexes": SEATS}).encode(),
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOK}"})
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())

with concurrent.futures.ThreadPoolExecutor(max_workers=80) as ex:
    results = list(ex.map(lock, range(N)))

ok = sum(1 for code, body in results if code == 200 and body.get("code") == 0)
fail = sum(1 for code, body in results if code == 200 and body.get("code") != 0)
print(f"成功={ok}, 失败(冲突/业务错)={fail}")

# 校验锁位图: 30,31 应被锁定
sm = urllib.request.urlopen(f"{BASE}/api/sessions/{SID}/seat-map", timeout=10)
d = json.load(sm)["data"]
import base64
lock_bytes = base64.b64decode(d["lockBitmap"])
locked = [i for i in range(d["seatCount"]) if (lock_bytes[i//8] >> (7-i%8)) & 1]
print(f"lock位: {locked}")
print(f"sold位: {sorted([i for i in range(d['seatCount']) if (base64.b64decode(d['soldBitmap'])[i//8]>>(7-i%8))&1])}")

if ok == 1 and 30 in locked and 31 in locked:
    print("✅ 防超卖通过: 200并发抢同一场次同一座位, 仅1人成功, 0超卖")
    sys.exit(0)
else:
    print("❌ 异常: 超卖或全失败")
    sys.exit(1)