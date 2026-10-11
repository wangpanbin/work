"""为 JMeter 场景B 生成测试账号池 (users.csv).

用法:
    python test/jmeter/gen_users.py                # 默认 60 个账号
    python test/jmeter/gen_users.py --count 120

输出: test/jmeter/users.csv  (两列: username,password, 无表头 —— 与
scenario-b-lock.jmx 里 CSVDataSet 的 <boolProp name="ignoreFirstLine">false</boolProp> 对应)

为什么要账号池: /api/orders/lock 带 @RateLimit(每用户 + 每场次 5 次/秒)。
单个账号刷不起并发, 只会被 42900 挡住; 压测必须用多账号才能真正打到 Lua 锁座路径。
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def call(base, path, body):
    req = urllib.request.Request(
        base + path, data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw[:200].decode("utf-8", "replace")}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--count", type=int, default=60)
    ap.add_argument("--prefix", default="jm")
    ap.add_argument("--password", default="123456")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "users.csv"))
    args = ap.parse_args()
    base = args.base.rstrip("/")

    # 账号名固定(不带时间戳), 重复执行时已存在的账号直接登录即可 —— 让脚本幂等
    rows, created, reused = [], 0, 0
    for i in range(args.count):
        uname = f"{args.prefix}{i}"
        st, r = call(base, "/api/auth/register",
                     {"username": uname, "password": args.password})
        if r.get("code") == 0:
            created += 1
        else:
            reused += 1
        # 无论注册成功与否都验一次登录, 确保这个账号真的可用
        st, r = call(base, "/api/auth/login",
                     {"username": uname, "password": args.password})
        if r.get("code") != 0 or not (r.get("data") or {}).get("token"):
            print(f"  !! 账号 {uname} 登录失败: {r.get('code')} {r.get('msg')} —— 已跳过")
            continue
        rows.append((uname, args.password))

    if not rows:
        print("!! 没有可用账号, 请确认后端已在 8080 启动")
        return 1

    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        for u, p in rows:
            f.write(f"{u},{p}\n")

    print(f"已写入 {len(rows)} 个账号 → {args.out}  (新建 {created} / 复用 {reused})")
    print(f"现在可以跑: jmeter -n -t test/jmeter/scenario-b-lock.jmx -l result.jtl")
    return 0


if __name__ == "__main__":
    sys.exit(main())
