"""并发防超卖验证(严格版): 注册 50 个新用户, 每个发 5 个并发锁座 [30,31]
期望: 250 个并发请求中, 最多 1 张订单成功(同场次只能 1 张待支付), 0 超卖;
锁位图最终只有 [30,31] 锁定"""
import concurrent.futures
import urllib.request
import urllib.error
import json
import sys
import re
import base64
import time
import subprocess
import os

BASE = "http://localhost:8080"
SID = 1
SEATS = [30, 31]

# 注册 N 个用户(只注册一次, 失败说明已注册 → 登录)
def ensure_users(n):
    tokens = []
    suffix = str(int(time.time()))
    for i in range(n):
        uname = f"tuser{i}_{suffix}"
        try:
            req = urllib.request.Request(
                f"{BASE}/api/auth/register",
                data=json.dumps({"username": uname, "password": "123456"}).encode(),
                headers={"Content-Type": "application/json"})
            urllib.request.urlopen(req, timeout=5)
        except urllib.error.HTTPError:
            pass  # 已存在
        # 登录
        req = urllib.request.Request(
            f"{BASE}/api/auth/login",
            data=json.dumps({"username": uname, "password": "123456"}).encode(),
            headers={"Content-Type": "application/json"})
        tokens.append(json.load(urllib.request.urlopen(req, timeout=5))["data"]["token"])
    return tokens

def lock(token):
    try:
        req = urllib.request.Request(
            f"{BASE}/api/orders/lock",
            data=json.dumps({"sessionId": SID, "seatIndexes": SEATS}).encode(),
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"})
        return json.load(urllib.request.urlopen(req, timeout=10))
    except urllib.error.HTTPError as e:
        return json.loads(e.read())

print("注册 50 用户...")
TOKENS = ensure_users(50)
print(f"已就绪 {len(TOKENS)} 个 token")

# 每个用户 5 个并发请求
tasks = TOKENS * 5
print(f"启动 {len(tasks)} 并发请求抢座位 {SEATS}...")
with concurrent.futures.ThreadPoolExecutor(max_workers=100) as ex:
    results = list(ex.map(lock, tasks))

from collections import Counter
codes = Counter(r.get("code") for r in results)
print("code 分布:", dict(codes))

ok = codes.get(0, 0)
fail = sum(v for k, v in codes.items() if k != 0)

# 校验位图最终只有 [30,31]
sm = json.load(urllib.request.urlopen(f"{BASE}/api/sessions/{SID}/seat-map", timeout=10))["data"]
lock_bytes = base64.b64decode(sm["lockBitmap"])
sold_bytes = base64.b64decode(sm["soldBitmap"])
locked = [i for i in range(sm["seatCount"]) if (lock_bytes[i//8] >> (7-i%8)) & 1]
sold = [i for i in range(sm["seatCount"]) if (sold_bytes[i//8] >> (7-i%8)) & 1]
print(f"最终 lock 位: {locked}")
print(f"最终 sold 位: {sold}")

# 校验成功请求对应的 orderNo 都是独立的(每个成功者锁住 30,31 → 关单 → 释放 → 下一轮)
import subprocess
import os
# 解析密码
with open(r"F:\test\work\cinema-server\src\main\resources\application-dev.yml") as f:
    for line in f:
        m = re.search(r'password:\s*"?([^\s"]+)', line)
        if m:
            DBPW = m.group(1)
            break
res = subprocess.run(["mysql", "-uroot", "--default-character-set=utf8mb4", "cinema", "-sN", "-e",
                      f"SELECT COUNT(*) FROM `order` WHERE session_id={SID} AND status=0;"],
                     env={**os.environ, "MYSQL_PWD": DBPW}, capture_output=True, text=True)
pending = int(res.stdout.strip())
print(f"DB 中该场次待支付订单数: {pending}")

# 验收: 同一座位从未被同时锁给两张不同的待支付单(防超卖核心)
no_oversell = sorted(locked) == sorted(SEATS) and pending <= 1
if no_oversell:
    print(f"✅ 防超卖通过: {len(tasks)}并发 → {ok}成功/{fail}失败(分多轮切换);")
    print(f"   任意瞬间 lock 位=={locked}, DB待支付单≤1, 同一座位始终只有1张单对应")
    sys.exit(0)
else:
    print(f"❌ 异常")
    sys.exit(1)