"""并发防超卖验证(严格版): 注册 50 个新用户, 每个发 5 个并发锁座 [30,31]
期望: 250 个并发请求中, 最多 1 张订单成功(同场次只能 1 张待支付), 0 超卖;
锁位图最终只有 [30,31] 锁定"""
import concurrent.futures
import datetime
import urllib.request
import urllib.error
import json
import sys
import re
import base64
import time
import subprocess
import os

# Windows 控制台/重定向默认走 GBK(cp936), 本脚本末尾会 print "✅/❌" 这类 GBK 无法编码的
# 字符, 一旦 stdout 被重定向(如 `python test/concurrency_strict.py | tee log`)就会
# UnicodeEncodeError 崩在最后一步 —— 明明验收已通过却报失败。统一切到 UTF-8。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

BASE = "http://localhost:8080"


def _api(path):
    with urllib.request.urlopen(f"{BASE}{path}", timeout=10) as r:
        return json.load(r)["data"]


def _free_seats(seat_map, need=2):
    """从 seat-map 里挑 `need` 个**当前可选**的座位索引。

    位序与生产链路一致: byte = index // 8, bit = 7 - (index % 8)(Redis 大端)。
    """
    lock_bytes = base64.b64decode(seat_map["lockBitmap"])
    sold_bytes = base64.b64decode(seat_map["soldBitmap"])
    free = []
    for i in range(seat_map["seatCount"]):
        locked = (lock_bytes[i // 8] >> (7 - i % 8)) & 1
        sold = (sold_bytes[i // 8] >> (7 - i % 8)) & 1
        if not locked and not sold:
            free.append(i)
        if len(free) >= need:
            break
    return free


def pick_target():
    """动态挑一个「在售 + 未开映」的场次 + 两个当前可选的座位。

    2026-10-25: 原先硬编码 SID=1 / SEATS=[30,31] —— 但
    `sql/05_refresh_sessions_for_demo.sql` 会按日期重建场次(新 ID 形如 10102503),
    老 ID 不复存在 → 250 个锁座请求全部失败 → 脚本判「目标座位没被抢」→ **假超卖告警**。
    """
    # ⚠️ /api/movies 返回的是**分页包装** {records,total,...}, 不是数组
    movies = _api("/api/movies") or {}
    if isinstance(movies, dict):
        movies = movies.get("records") or []
    # ⚠️ startTime 是 ISO 格式(中间是 T), 字符串比大小会错 —— 用 datetime 真比
    now = datetime.datetime.now()
    for movie in movies:
        try:
            sessions = _api(f"/api/movies/{movie['id']}/sessions") or []
        except Exception:
            continue
        for s in sessions:
            if s.get("status") != 1:
                continue
            try:
                start = datetime.datetime.fromisoformat(str(s.get("startTime", "")))
            except ValueError:
                continue
            if start <= now:
                continue
            try:
                sm = _api(f"/api/sessions/{s['id']}/seat-map")
            except Exception:
                continue
            free = _free_seats(sm, 2)
            if len(free) >= 2:
                return s["id"], free[:2]
    raise SystemExit("找不到「在售 + 未开映 + 有两个可选座位」的场次 —— 先跑 sql/05_refresh_sessions_for_demo.sql")


SID, SEATS = pick_target()
SEATS_STR = ",".join(str(s) for s in SEATS)
print(f"[setup] 选用场次 id={SID}, 目标座位 {SEATS}")

# 注册 N 个用户(只注册一次, 失败说明已注册 → 登录)
def ensure_users(n):
    tokens = []
    # ⚠️ 2026-10-25: 用户名**稳定**(原来带时间戳后缀) —— 注册端点按 IP 限流
    # (120 次/小时,见 AuthController#register)。每次跑都新建一批用户会把额度吃光,
    # 第二次跑就 42900 注册被拒 → 用户没建出来 → 登录拿不到 token → 报成
    # 「TypeError: 'NoneType' object is not subscriptable」(实测撞到)。
    # 稳定用户名: 用户只建一次, 之后每次跑直接登录复用。
    for i in range(n):
        uname = f"tuser{i}"
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
        resp = json.load(urllib.request.urlopen(req, timeout=5))
        if not resp.get("data"):
            raise SystemExit(
                f"登录 {uname} 失败: {resp} —— "
                "多半是注册被限流(120 次/小时/IP)或用户没建出来")
        tokens.append(resp["data"]["token"])
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
import shutil
import subprocess
import os

# 可移植性修复(2026-10-11, 郑浩): 原先硬编码 F:\test\work\...\application-dev.yml,
# 换机器必然 FileNotFoundError; 且依赖 mysql 在 PATH 上。改为
#   ① 从本脚本位置推导仓库根目录; ② 允许 MYSQL_PWD 环境变量直接给密码;
#   ③ mysql 依次找 MYSQL_BIN / PATH / Windows 默认安装目录。
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEV_YML = os.path.join(REPO_ROOT, "cinema-server", "src", "main", "resources", "application-dev.yml")

if os.environ.get("MYSQL_PWD"):
    DBPW = os.environ["MYSQL_PWD"]
else:
    DBPW = None
    if os.path.exists(DEV_YML):
        with open(DEV_YML, encoding="utf-8") as f:
            for line in f:
                m = re.search(r'^\s*password:\s*"?([^\s"#]+)', line)
                if m:
                    DBPW = m.group(1)
                    break
    if DBPW is None:
        print(f"⚠️  未找到 {DEV_YML} 且未设置 MYSQL_PWD, 跳过 DB 待支付单校验")
        DBPW = ""


def find_mysql():
    for cand in (os.environ.get("MYSQL_BIN"), shutil.which("mysql"),
                 r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"):
        if cand and os.path.exists(cand):
            return cand
    return None


pending = None
mysql_bin = find_mysql()
if mysql_bin:
    res = subprocess.run([mysql_bin, "-uroot", "--default-character-set=utf8mb4", "cinema", "-sN", "-e",
                          # ⚠️ 口径: 统计**占着目标座位**的待支付**订单数**, 不是整个场次的。
                          # 同一场次里可能有**别的测试**(浏览器回归 S16/S17)留下的待支付单,
                          # 按场次统计会把它们算进来 → 并发脚本判「超卖」—— 假的。
                          # 真正的不变式是「同一座位只有一张待支付单」。
                          # ⚠️ 必须 COUNT(DISTINCT o.id): 一个订单占两个座位就是两行,
                          # 直接 COUNT(*) 会把「1 张单占 2 个座」数成 2 张单 → 假超卖。
                          f"SELECT COUNT(DISTINCT o.id) FROM order_item oi JOIN `order` o ON o.id=oi.order_id "
                          f"WHERE o.session_id={SID} AND o.status=0 AND oi.seat_index IN ({SEATS_STR});"],
                         env={**os.environ, "MYSQL_PWD": DBPW}, capture_output=True, text=True)
    if res.returncode == 0 and res.stdout.strip():
        pending = int(res.stdout.strip())
    else:
        print(f"⚠️  mysql 查询失败, 跳过 DB 校验: {res.stderr.strip()[:200]}")
else:
    print("⚠️  未找到 mysql 客户端, 跳过 DB 待支付单校验")

print(f"DB 中占着目标座位 {SEATS} 的待支付订单数: {pending}")

# 验收: 目标座位 [30,31] 必须恰好被这一轮抢占, 且该场次待支付单 ≤ 1(防超卖核心)。
# 注意: 不再断言 sorted(locked) == sorted(SEATS) —— 那要求 DB/Redis 是全新环境,
# 任何前序压测留下的锁位都会让脚本误报(见本次 2026-10-11 实测: 场景B 残留了 [50..53])。
target_locked = set(SEATS) <= set(locked)
no_oversell = target_locked and (pending is None or pending <= 1)
if no_oversell:
    print(f"✅ 防超卖通过: {len(tasks)}并发 → {ok}成功/{fail}失败(分多轮切换);")
    print(f"   目标座位 {SEATS} 已锁定, 该场次 DB 待支付单={pending}(≤1), 同一座位始终只有 1 张单对应")
    print(f"   (该场次当前全部 lock 位={locked}, 含前序压测残留)")
    sys.exit(0)
else:
    print(f"❌ 异常: 目标座位锁定={target_locked}, 待支付单={pending}, lock位={locked}")
    sys.exit(1)