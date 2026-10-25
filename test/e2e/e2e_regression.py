# -*- coding: utf-8 -*-
"""
>>> 已迁移到新套件: F:/test/work/tests/ (Pytest + POM + Playwright + API 契约, 33 用例)
>>> 新套件使用:
>>>   cd F:/test/work/tests
>>>   pytest -q            # 全套 (33 用例 ~50s)
>>>   pytest -m api -q     # 仅 API (21 用例)
>>>   pytest -m web -q     # 仅 Web (12 用例, 含本文件 S01~S12 等价用例)
>>>   pytest -q web/test_seat.py::test_seat_lock_ws_sync   # 单场景
>>>   # 报告: test-report.html, reports/junit.xml, test-results/*/trace.zip
>>> 旧文件保留用于对照回归, 不再主动维护。详见 F:/test/work/tests/README.md。

影院抢票选座系统 E2E 回归测试 (Python Playwright)
覆盖 S01~S12 十二个场景, 重点回归上次修复的三个 Bug:
  Bug A: WebSocket 实时推送 (S03)
  Bug B: seat-map myLockedSeats 自锁高亮 (S03)
  Bug C: snowflake ID 精度丢失 (S01/S09)

运行方式(由 with_server.py 托管前后端):
  python with_server.py --server "cd cinema-server && mvn spring-boot:run" --port 8080 --timeout 240
                        --server "cd cinema-web && pnpm dev" --port 5173 --timeout 120
                        -- python test/e2e/e2e_regression.py
"""
import argparse
import base64
import json
import os
import re
import subprocess
import sys
import time
import traceback
from datetime import datetime, timedelta
from pathlib import Path

import requests
from playwright.sync_api import sync_playwright

FRONT = os.environ.get("CINEMA_E2E_FRONT", "http://localhost:5173")
API = os.environ.get("CINEMA_E2E_API", "http://localhost:8080/api")
#: 后端基址(去掉 /api)—— /actuator/health 与 /swagger-ui.html 不在 /api 下面
BACKEND = API[:-4] if API.endswith("/api") else API
ROOT = Path(r"F:\test\work")
SHOT = ROOT / "test" / "e2e" / "screenshots"
SHOT.mkdir(parents=True, exist_ok=True)

S = {}  # 场景间共享状态
RESULTS = []  # (name, PASS/FAIL/SKIP, detail)


class Skipped(Exception):
    """这条场景在当前环境里没法验证(不是失败)。记 SKIP, 不许记 PASS。"""


def record(name, ok, detail="", skipped=False):
    """记一条场景结果。

    <p>三态: PASS / FAIL / **SKIP**。
    SKIP 不是「通过」—— 它表示**这条场景在当前环境里没法验证**
    (典型: 助手出不出卡取决于 LLM), 必须带上原因, 不能算作通过。
    """
    status = "SKIP" if skipped else ("PASS" if ok else "FAIL")
    RESULTS.append((name, status, detail))
    print(f"[{status}] {name}" + (f" — {detail}" if detail else ""), flush=True)


def api(method, path, token=None, json_body=None, params=None, base=API):
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    r = requests.request(method, base + path, headers=headers, json=json_body,
                         params=params, timeout=15)
    return r.status_code, r.json()


def api_ok(method, path, token=None, json_body=None, params=None):
    sc, body = api(method, path, token, json_body, params)
    if sc != 200 or body.get("code") != 0:
        raise RuntimeError(f"API {method} {path} -> HTTP {sc} body={body}")
    return body["data"]


def login_api(username, password="123456"):
    return api_ok("POST", "/auth/login", json_body={"username": username, "password": password})["token"]


def register_throwaway_user(prefix="e2e"):
    """注册一个一次性用户并返回它的 token。

    <p>**为什么需要它**:锁座端点带 `@Idempotent`(幂等键 = 用户 + 场次, TTL 8 秒),
    同一个用户在 8 秒内对同一场次再锁一次会返 40900「锁座请求处理中」。S17 刚用 user2
    锁过座位, S20 紧接着又用 user2 锁 → 撞幂等窗口。换一个一次性用户就绕开了,
    而且不用 sleep 8 秒拖慢套件。
    """
    username = f"{prefix}{datetime.now().strftime('%H%M%S%f')}"
    try:
        api_ok("POST", "/auth/register", json_body={"username": username, "password": "123456"})
    except RuntimeError as e:
        if "已存在" not in str(e) and "40001" not in str(e):
            # 其它失败(最常见: 注册被限流)必须**明确**报出来 ——
            # 吞掉它的话, 紧接着的登录会报「用户名或密码错误」, 把人指向错误的根因。
            raise SystemExit(f"注册 {username} 失败: {e} —— 注册按 IP 限流 120 次/小时")
    return login_api(username)


def db_exec(sql):
    """通过 mysql CLI 执行 SQL(密码从 application-dev.yml 读取)"""
    yml = (ROOT / "cinema-server" / "src" / "main" / "resources" / "application-dev.yml").read_text(encoding="utf-8")
    m = re.search(r"password:\s*(\S+)", yml)
    out = subprocess.run(["mysql", "-uroot", f"-p{m.group(1)}", "-N", "-B", "cinema", "-e", sql],
                         capture_output=True, text=True, shell=False)
    if out.returncode != 0:
        raise RuntimeError(f"mysql 执行失败: {out.stderr.strip()}")
    return out.stdout.strip()


def redis_cli(*args):
    out = subprocess.run(["redis-cli", *args], capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"redis-cli 失败: {out.stderr.strip()}")
    return out.stdout.strip()


def decode_bitmap(b64: str, count: int):
    """seat-index 位图: byte=i>>3, bit=7-(i&7) (与前端 utils/bitmap.ts 一致)"""
    raw = base64.b64decode(b64) if b64 else b""
    bits = []
    for i in range(count):
        byte = raw[i >> 3] if i >> 3 < len(raw) else 0
        bits.append(1 if (byte >> (7 - (i & 7))) & 1 else 0)
    return bits


def lock_bits(token, sid=None):
    sid = sid or S["sid"]
    sm = api_ok("GET", f"/sessions/{sid}/seat-map", token=token)
    return decode_bitmap(sm["lockBitmap"], sm["seatCount"])


# ---------------- Playwright 通用 ----------------

def shot(page, name):
    try:
        page.screenshot(path=str(SHOT / f"{name}.png"), full_page=True)
    except Exception:
        pass


def wait_toast(page, text, timeout=6000):
    try:
        page.locator(f".el-message", has_text=text).first.wait_for(state="visible", timeout=timeout)
        return True
    except Exception:
        return False


def ui_login(page, username, password="123456"):
    page.goto(FRONT + "/login")
    page.wait_for_load_state("networkidle")
    page.locator("input[placeholder='用户名']").fill(username)
    page.locator("input[placeholder='密码']").fill(password)
    page.get_by_role("button", name="立即登录").click()
    ok = wait_toast(page, "登录成功")
    page.wait_for_load_state("networkidle")
    return ok


#: 座位元素选择器 —— **两种布局都要覆盖**:
#: 常规影厅(cols ≤ 16)走 `.seats-grid`,大影厅(cols > 16)走 `.seats`。
#: 2026-10-25: 旧脚本只找 `.seats .seat`,而演示库 1 号厅是 20×12(cols=12)→ 落在
#: `.seats-grid` 里, 于是「等 15s 看不到座位」—— 探针实测 240 个座位全都渲染了。
SEAT_ITEM_SEL = ".seats-grid .seat, .seats .seat"

def goto_seat_map(page, sid=None):
    sid = sid or S["sid"]
    page.goto(f"{FRONT}/seat/{sid}")
    page.wait_for_selector(SEAT_ITEM_SEL, timeout=15000)
    page.wait_for_function(
        "() => document.querySelectorAll('.seats-grid .seat.available, .seats .seat.available').length > 0",
        timeout=15000)




def available_indexes(page):
    return page.evaluate(
        "() => [...document.querySelectorAll('%s')]"
        ".map((el,i)=>el.classList.contains('available')?i:-1).filter(i=>i>=0)" % SEAT_ITEM_SEL)


def wait_stable_count(page, selector, timeout=6000):
    prev, end = -1, time.time() + timeout / 1000
    while time.time() < end:
        cur = page.locator(selector).count()
        loading = page.locator(".el-loading-mask:visible").count()
        if cur == prev and loading == 0:
            return cur
        prev = cur
        page.wait_for_timeout(300)
    return page.locator(selector).count()


def dialog_form_item(page, label):
    return page.locator(f".el-dialog .el-form-item:has(.el-form-item__label:text('{label}'))")


def confirm_pending_order_dialog(page, lock_sent, timeout=12000):
    """点掉「同场次已有待支付单」的确认框; 返回 (是否弹过这个框, 锁座请求是否已发出)。

    <p>**为什么需要它**(2026-10-25 交互流程审查 P0-2): 选座页在发出锁座请求**之前**
    会弹一个确认框 ——「你在本场次还有一笔待支付订单 XXX, 重新选座将取消它」。
    不点它就**根本不会发请求**, 于是脚本等不到「锁座成功」toast。

    <p>**为什么它是夹具漂移而不是产品缺陷**: 上一轮场景 S16(对话一键下单)会留下一笔
    待支付单, 支付窗口 900s。**15 分钟内连跑两遍套件**, 第二遍的 S03 就会撞上这个框
    —— 第一遍跑不到(自己的 S16 还没执行)。所以场景必须能处理这个框, 否则套件不可重入。

    <p>⚠️ **不能死等这个框**(2026-10-25 第三轮评审后实测踩到): 选座页现在锁座前会
    **先权威重拉一次座位图**, 之后才可能弹框。**没有**待支付单时, 点完按钮就直接
    发锁座请求并弹「锁座成功」toast —— 而 Element Plus 的 message **3 秒就自动消失**。
    死等一个永远不会出现的框(上一版等 4 秒), 那个 toast 就被等没了, 后面
    {@code wait_toast} 必然误判失败。**那是测试脚本自己的时序问题, 不是产品缺陷** ——
    服务端日志里那次锁座是成功的。

    <p>所以这里同时盯两件事, 哪个先来按哪个走:
    <ol>
      <li>确认框出现 → 点它的主按钮(语言无关: {@code .el-message-box__btns .el-button--primary},
          不点中文按钮文案 —— S19 会把界面切成英文);</li>
      <li>锁座请求已发出({@code lock_sent} 非空)→ 说明没有框, **立刻返回**,
          好让调用方去抓那个还在寿命期的 toast。</li>
    </ol>

    @param lock_sent 列表, 调用方注册的请求监听器往里塞「已发出的锁座请求」
    @return (是否弹过确认框, 是否已发出锁座请求)
    """
    deadline = time.time() + timeout / 1000
    saw_dialog = False
    while time.time() < deadline:
        box = page.locator(".el-message-box")
        if box.count() and box.first.is_visible():
            if not saw_dialog:
                saw_dialog = True
                page.locator(".el-message-box__btns .el-button--primary").first.click(timeout=8000)
        elif lock_sent:
            break
        page.wait_for_timeout(150)
    return saw_dialog, bool(lock_sent)


# ---------------- 座位图工具(S23 用)----------------

def seat_map_of(sid, token=None):
    """某个场次的座位图。

    <p>**必须带 token 拿**: 自己的待支付单锁住的座位在位图里也是锁位,
    不带 token 看到的「可选数」跟用户实际看到的不是一回事。
    """
    return api_ok("GET", f"/sessions/{sid}/seat-map", token=token or S.get("token1"))


def available_bits(sm):
    """座位图里「可选」的位(既没被锁也没被卖)—— 与 SeatAvailability.isAvailable 同口径。"""
    lock = decode_bitmap(sm["lockBitmap"], sm["seatCount"])
    sold = decode_bitmap(sm["soldBitmap"], sm["seatCount"])
    return [not lock[i] and not sold[i] for i in range(sm["seatCount"])]


def longest_row_run(sm):
    """最长的一段**同行连续**可选座位(与 SeatAvailability.RunPolicy.FIRST_ROW 同口径)。

    <p>跨行的相邻索引不算 —— 服务端找连座就是按行扫的, 这里必须同口径,
    否则会挑出一个「服务端根本找不到连座」的场次, 场景就变成测运气了。
    """
    bits = available_bits(sm)
    cols = sm["cols"]
    best = run = 0
    for i, ok in enumerate(bits):
        if not ok:
            run = 0
            continue
        run = run + 1 if (i > 0 and i % cols != 0) else 1
        best = max(best, run)
    return best


# ---------------- 场景 ----------------

def s12_health(page):
    """健康检查 / Swagger 静态端点"""
    r1 = requests.get(f"{BACKEND}/actuator/health", timeout=10)
    r2 = requests.get(f"{BACKEND}/swagger-ui.html", timeout=10, allow_redirects=False)
    assert r1.status_code == 200 and r1.json().get("status") == "UP", f"health={r1.status_code} {r1.text[:100]}"
    assert r2.status_code in (200, 302), f"swagger={r2.status_code}"
    return f"/actuator/health=200 UP; /swagger-ui.html={r2.status_code}"


def s01_browse(page):
    """公共浏览: 首页影片卡 → 详情页场次; snowflake ID 详情直达 (Bug C 回归)"""
    page.goto(FRONT + "/")
    page.wait_for_load_state("networkidle")
    n_cards = wait_stable_count(page, ".movie-card")
    assert n_cards >= 3, f"首页影片卡数量 {n_cards} < 3"
    shot(page, "s01-home")

    # 逐卡进入详情, 直到找到带场次的影片
    detail_ok, used_idx = False, -1
    for i in range(min(n_cards, 4)):
        page.goto(FRONT + "/")
        page.wait_for_load_state("networkidle")
        page.locator(".movie-card").nth(i).click()
        page.wait_for_url(re.compile(r"/movie/"), timeout=8000)
        page.wait_for_load_state("networkidle")
        try:
            page.wait_for_selector(".buy-btn", timeout=4000)
            detail_ok, used_idx = True, i
            break
        except Exception:
            continue
    assert detail_ok, "前 4 部影片详情页均无场次(选座购票按钮)"
    session_cnt = page.locator(".buy-btn").count()
    shot(page, "s01-detail")

    # Bug C 回归: snowflake ID 影片详情直达 + 接口返回 id 必须是字符串
    snow_id = "2093944289341255682"
    sc, body = api("GET", f"/movies/{snow_id}")
    snow_note = "snowflake影片不存在(跳过)"
    if sc == 200 and body.get("code") == 0:
        assert isinstance(body["data"]["id"], str), f"接口 snowflake id 类型={type(body['data']['id'])}"
        page.goto(f"{FRONT}/movie/{snow_id}")
        page.wait_for_load_state("networkidle")
        page.wait_for_selector("text=测试新片", timeout=8000)
        snow_note = f"snowflake id 直达详情 OK(接口 id 为 string)"
    return f"首页 {n_cards} 张影片卡; 卡{used_idx}详情含 {session_cnt} 个场次; {snow_note}"


def s02_login(page):
    """登录: 错误密码提示 + 正确登录写 token"""
    page.goto(FRONT + "/login")
    page.wait_for_load_state("networkidle")
    # 错误密码
    page.locator("input[placeholder='用户名']").fill("user1")
    page.locator("input[placeholder='密码']").fill("wrong-password")
    page.get_by_role("button", name="立即登录").click()
    ok_err = wait_toast(page, "密码", timeout=5000) or wait_toast(page, "错误", timeout=2000) \
        or page.locator(".el-message--error").first.is_visible()
    shot(page, "s02-wrong-pwd")
    # 正确登录
    page.locator("input[placeholder='密码']").fill("123456")
    page.get_by_role("button", name="立即登录").click()
    assert wait_toast(page, "登录成功"), "未出现 '登录成功' toast"
    page.wait_for_load_state("networkidle")
    token = page.evaluate("localStorage.getItem('cinema_token')")
    assert token, "登录后 localStorage 无 cinema_token"
    S["token1"] = token
    shot(page, "s02-logged-in")
    return f"错密提示={'有' if ok_err else '无(仅校验正确路径)'}; user1 token 已写入 localStorage"


def s03_seat_ws(page, page_b):
    """核心: 选座 + 锁座 + myLockedSeats 高亮(Bug B) + WS 实时推送(Bug A)"""
    goto_seat_map(page)
    avail = available_indexes(page)
    assert len(avail) >= 5, f"可用座位不足: {len(avail)}"
    seat_a, seats_bc = avail[0], avail[1:3]

    # user1 UI 锁座 A
    page.locator(SEAT_ITEM_SEL).nth(seat_a).click()
    page.wait_for_timeout(200)
    # ⚠️ 这里**不能**写 `SEAT_ITEM_SEL + ".selected"` —— 那是逗号选择器,
    # 前半段 `.seats-grid .seat` 会匹配全部 240 个座位, count() 恒等于座位总数。
    # `.seat` 只有座位子组件有, 直接用它选状态类。
    assert page.locator(".seat.selected").count() == 1, "点击后未出现 selected 座位"
    # 盯住锁座请求: 「有没有待支付单确认框」只能靠它判定(见 helper 注释)。
    # 监听器在**点击之前**挂上 —— 点了之后请求可能几毫秒就发出去了。
    lock_sent = []

    def _on_lock_request(req):
        if req.method == "POST" and "/orders/lock" in req.url:
            lock_sent.append(req)

    page.on("request", _on_lock_request)
    page.get_by_role("button", name="确认锁座下单").click()
    # 同场次有旧待支付单时, 前端会先弹确认框, 不点它就不发锁座请求(见 helper 注释)
    had_pending, lock_requested = confirm_pending_order_dialog(page, lock_sent)
    page.remove_listener("request", _on_lock_request)
    assert lock_requested, ("点「确认锁座下单」后既没弹待支付单确认框, 也没发出锁座请求 —— "
                             "查浏览器控制台与「你已选的 N 个座位被他人锁定」提示(重拉后发现选座变了会中止)")
    assert wait_toast(page, "锁座成功"), "未出现 '锁座成功' toast"
    page.wait_for_url(re.compile("/payment"), timeout=8000)
    page.wait_for_load_state("networkidle")
    m = re.search(r"orderNo=([^&]+)", page.url)
    assert m, f"支付页 URL 无 orderNo: {page.url}"
    S["order_no1"] = m.group(1)
    shot(page, "s03-locked-goto-payment")

    # Bug B 回归: 回到选座页, 自己锁的座位应为 locked_mine(绿色)
    goto_seat_map(page)
    cls = page.locator(SEAT_ITEM_SEL).nth(seat_a).get_attribute("class")
    assert "locked_mine" in cls, f"自锁座位 class={cls}, 缺 locked_mine (Bug B 回归失败)"
    locked_other_before = page.locator(".seat.locked_other").count()
    shot(page, "s03-mine")

    # user2 UI 登录后通过 API 锁 B/C 两座
    assert ui_login(page_b, "user2"), "user2 登录失败"
    S["token2"] = page_b.evaluate("localStorage.getItem('cinema_token')")
    assert S["token2"], "user2 token 缺失"
    api_ok("POST", "/orders/lock", token=S["token2"],
           json_body={"sessionId": S["sid"], "seatIndexes": seats_bc})

    # Bug A 回归: user1 页面在无刷新情况下通过 WS 收到 LOCKED 推送
    #
    # ⚠️ 判据盯**被锁的那几个座位本身**, 不盯「locked_other 总数」。
    # 总数法在两种情况下会假红(2026-10-25 实测踩到):
    #   ① 同场次有别人的旧待支付单 → 新锁座会**顶掉**旧单并释放旧座位,
    #      于是「新增 2 个他人锁定」与「释放 2 个他人锁定」互相抵消, 总数不变;
    #   ② 别的场景在同一时间改动了同一场次。
    # 盯具体座位才是在验「WS 推送到了没有」, 也才是这条场景本来要验的东西。
    deadline, ws_ok, locked_other_after = time.time() + 8, False, -1
    while time.time() < deadline:
        locked_other_after = page.locator(".seat.locked_other").count()
        if all("locked_other" in (page.locator(SEAT_ITEM_SEL).nth(i).get_attribute("class") or "")
               for i in seats_bc):
            ws_ok = True
            break
        page.wait_for_timeout(300)
    shot(page, "s03-ws-sync")
    assert ws_ok, (f"8s 内 WS 未把 user2 锁的座位 {seats_bc} 推成 locked_other "
                   f"(locked_other {locked_other_before}→{locked_other_after})")
    return (f"user1 UI锁座{seat_a}→支付页(orderNo={S['order_no1'][:12]}…); 自锁座位显示 locked_mine ✓; "
            f"user2 API锁{seats_bc} → user1 无刷新 WS 收到推送, 这几个座位变 locked_other ✓ "
            f"(locked_other 总数 {locked_other_before}→{locked_other_after})"
            + ("; 撞到同场次旧待支付单确认框, 已确认" if had_pending else ""))


def s04_pay_cancel(page, page_b):
    """支付 user1 订单 + 取消 user2 订单, 校验状态机与座位释放"""
    page.goto(f"{FRONT}/payment?orderNo={S['order_no1']}")
    page.wait_for_load_state("networkidle")
    # ⚠️ 2026-10-25: 支付按钮文案从「确认支付」改成了 i18n key `order.actionPay` = 「去支付」
    page.get_by_role("button", name="去支付").click()
    assert wait_toast(page, "支付成功"), "未出现 '支付成功' toast"
    page.wait_for_selector("text=祝您观影愉快", timeout=8000)
    shot(page, "s04-paid")
    d = api_ok("GET", f"/orders/{S['order_no1']}", token=S["token1"])
    assert d["status"] == 1 and d["paidAt"], f"支付后状态异常: status={d['status']} paidAt={d['paidAt']}"

    # user2 再锁 2 座 → 支付页取消
    goto_seat_map(page)
    avail = available_indexes(page)
    data = api_ok("POST", "/orders/lock", token=S["token2"],
                  json_body={"sessionId": S["sid"], "seatIndexes": avail[0:2]})
    order_no2, seats_de = data["orderNo"], data["seatIndexes"]
    page_b.goto(f"{FRONT}/payment?orderNo={order_no2}")
    page_b.wait_for_load_state("networkidle")
    page_b.get_by_role("button", name="取消订单").click()
    # ⚠️ 2026-10-25: 取消也加了二次确认(ElMessageBox.confirm), 确认按钮文案「确定取消」。
    # 旧脚本点完「取消订单」就等 toast —— 它一直卡在那个确认框上。
    page_b.get_by_role("button", name="确定取消").click(timeout=8000)
    assert wait_toast(page_b, "订单已取消"), "未出现 '订单已取消' toast"
    shot(page_b, "s04-cancelled")
    d2 = api_ok("GET", f"/orders/{order_no2}", token=S["token2"])
    assert d2["status"] == 2 and not d2["paidAt"], f"取消后状态异常: {d2['status']}"

    # 座位释放: seat-map lockBitmap 中 D/E 位应清零
    bits = lock_bits(S["token1"])
    assert all(bits[i] == 0 for i in seats_de), f"取消后座位未释放: {seats_de} lockBits={[bits[i] for i in seats_de]}"
    return (f"订单{S['order_no1'][-8:]} 支付→status=1 paidAt={d['paidAt']}; "
            f"订单{order_no2[-8:]} 取消→status=2; 座位{seats_de}已释放(lockBits清零)")


def s05_timeout_close(page):
    """超时关单(快速路径): DB expire_at + ZSet score 同步改过去 → 5s 扫描器关单释放座位"""
    goto_seat_map(page)
    avail = available_indexes(page)
    seat_idx = avail[0]
    data = api_ok("POST", "/orders/lock", token=S["token2"],
                  json_body={"sessionId": S["sid"], "seatIndexes": [seat_idx]})
    order_no3 = data["orderNo"]

    now_ms = int(time.time() * 1000)
    db_exec(f"UPDATE `order` SET expire_at=DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE order_no='{order_no3}'")
    redis_cli("ZADD", "cinema:order:timeout", str(now_ms - 60000), order_no3)

    status = None
    deadline = time.time() + 20
    while time.time() < deadline:
        status = api_ok("GET", f"/orders/{order_no3}", token=S["token2"])["status"]
        if status == 2:
            break
        page.wait_for_timeout(1000)
    shot(page, "s05-timeout")
    assert status == 2, f"20s 内订单未被超时关闭, status={status}"
    # 座位释放: lockBitmap 该位清零
    bits = lock_bits(S["token1"])
    assert bits[seat_idx] == 0, f"超时关单后座位未释放: seat {seat_idx} lockBit={bits[seat_idx]}"
    return f"订单{order_no3[-8:]} DB+ZSet 置过期 → {20 - (deadline - time.time()):.0f}s 内 status=2, 座位{seat_idx}已释放(lockBits清零)"


def s06_order_tabs(page):
    """订单列表: 全部 = 待支付 + 已支付 + 已取消"""
    page.goto(FRONT + "/orders")
    page.wait_for_load_state("networkidle")
    wait_stable_count(page, ".order-card")

    def count_tab(label):
        page.locator(".filter-tabs .el-radio-button", has_text=label).click()
        page.wait_for_timeout(400)
        return wait_stable_count(page, ".order-card")

    total, pend, paid, canc = (count_tab("全部"), count_tab("待支付"),
                               count_tab("已支付"), count_tab("已取消"))
    shot(page, "s06-orders")
    # ⚠️ 2026-10-25: v0.3.0 给订单列表上了分页(每页 20 条), 且重复跑回归会在库里
    # 攒下大量订单(实测已取消 3129 张)。于是每个 tab 都显示「一整页 20 条」,
    # 旧的「全部 = 待支付+已支付+已取消」关系只在**没有 tab 超过一页**时成立。
    # 改成分页口径: 全部 = min(各 tab 之和, 每页条数), 且每个 tab 都不超过一页。
    page_size = 20
    for name, v in (("待支付", pend), ("已支付", paid), ("已取消", canc)):
        assert v <= page_size, f"{name} tab 显示 {v} 条, 超过一页({page_size}) —— 分页没生效?"
    assert total == min(pend + paid + canc, page_size), (
        f"Tab 数不一致(分页口径): 全部{total} != min(待支付{pend}+已支付{paid}+已取消{canc}, {page_size})")
    return (f"全部={total}, 待支付={pend}, 已支付={paid}, 已取消={canc}, "
            f"分页口径一致 ✓(每页 {page_size} 条)")


def s08_permission(page, page_c):
    """权限边界: user1 调管理接口 40301; admin 正常(API+UI)"""
    sc, body = api("GET", "/admin/movies", token=S["token1"], params={"page": 1, "size": 5})
    assert body.get("code") == 40301, f"user1 访问管理接口应 40301, 实际 {body}"
    # UI: user1 直访 /admin/movies (可能被前端跳登录页或展示错误 toast, 记录现象)
    page.goto(FRONT + "/admin/movies")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(1200)
    # 2026-10-25: 文案是 i18n key `app.adminDenied` = 「该页面仅限管理员访问」
    toast = wait_toast(page, "仅限管理员", timeout=1500)
    url_after = page.url
    shot(page, "s08-user1-denied")
    # admin: API token + UI 登录(供 S09~S11 使用)
    S["token_admin"] = login_api("admin")
    sc2, body2 = api("GET", "/admin/movies", token=S["token_admin"], params={"page": 1, "size": 5})
    assert body2.get("code") == 0, f"admin 访问失败: {body2}"
    assert ui_login(page_c, "admin"), "admin UI 登录失败"
    page_c.goto(FRONT + "/admin/movies")
    page_c.wait_for_load_state("networkidle")
    page_c.wait_for_selector(".el-table__row", timeout=10000)
    shot(page_c, "s08-admin-dashboard")
    return f"user1 → code=40301 (UI 落点 {url_after}, toast={'有' if toast else '无'}); admin → API 200 + UI 影片表格可见"


def s09_movie_crud(page_c):
    """管理端影片 CRUD (Bug C 回归: 新建 snowflake ID 影片可编辑/删除)"""
    # 清理历史运行残留的同名前缀影片
    leftovers = api_ok("GET", "/admin/movies", token=S["token_admin"],
                       params={"page": 1, "size": 50, "keyword": "E2E回归测试片"})
    for m in leftovers["records"]:
        requests.delete(f"{API}/admin/movies/{m['id']}",
                        headers={"Authorization": f"Bearer {S['token_admin']}"}, timeout=10)

    title = f"E2E回归测试片{datetime.now().strftime('%H%M%S')}"
    page_c.goto(FRONT + "/admin/movies")
    page_c.wait_for_load_state("networkidle")
    page_c.wait_for_selector(".el-table__row", timeout=10000)
    rows0 = page_c.locator(".el-table__row").count()

    # 新增
    page_c.get_by_role("button", name="新增影片").click()
    dlg = page_c.locator(".el-dialog", has_text="新增影片")
    dialog_form_item(page_c, "片名").locator("input").fill(title)
    dialog_form_item(page_c, "时长").locator("input").fill("99")
    dialog_form_item(page_c, "简介").locator("textarea").fill("自动化回归创建")
    dlg.get_by_role("button", name="保存").click()
    dlg.wait_for(state="hidden", timeout=8000)
    page_c.wait_for_timeout(500)

    found = api_ok("GET", "/admin/movies", token=S["token_admin"],
                   params={"page": 1, "size": 10, "keyword": title})
    assert found["total"] >= 1, f"新建影片未在列表中: {found}"
    mv = found["records"][0]
    mid = mv["id"]
    assert isinstance(mid, str) and len(str(mid)) >= 18, f"snowflake id 应为长字符串, 实际 {mid!r} (Bug C 回归失败)"

    # UI 搜索(回车)
    page_c.locator("input[placeholder='搜索片名']").fill(title)
    page_c.keyboard.press("Enter")
    page_c.wait_for_timeout(800)
    wait_stable_count(page_c, ".el-table__row")
    assert page_c.locator(".el-table__row", has_text=title).count() == 1, "搜索结果未显示新影片"
    shot(page_c, "s09-created")

    # 编辑: 99 → 105
    page_c.locator(".el-table__row", has_text=title).get_by_role("button", name="编辑").click()
    dlg2 = page_c.locator(".el-dialog", has_text="编辑影片")
    dialog_form_item(page_c, "时长").locator("input").fill("105")
    dlg2.get_by_role("button", name="保存").click()
    dlg2.wait_for(state="hidden", timeout=8000)
    cur = api_ok("GET", "/admin/movies", token=S["token_admin"],
                 params={"page": 1, "size": 10, "keyword": title})["records"][0]
    assert int(cur["duration"]) == 105, f"编辑未生效 duration={cur['duration']}"

    # 删除(popconfirm 确认)
    # ⚠️ 2026-10-25: Element Plus 的 zh-cn locale 早已接上(App.vue 的 el-config-provider,
    # 见 utils/elementPlusLocale.ts)—— 确认按钮现在是「确定」而不是「Yes」。
    # 2026-08-31 报告里那条「补充发现 1: 缺 zh-cn locale」就是被那次修复掉的, 旧断言没跟上。
    page_c.locator(".el-table__row", has_text=title).get_by_role("button", name="删除").click()
    page_c.locator(".el-popper:visible button", has_text="确定").first.click(timeout=5000)
    page_c.wait_for_timeout(800)
    gone = api_ok("GET", "/admin/movies", token=S["token_admin"],
                  params={"page": 1, "size": 10, "keyword": title})
    shot(page_c, "s09-deleted")
    assert gone["total"] == 0, f"删除后仍能搜到: {gone}"
    # 取一部现有影片 id 供 S11 建场次
    listing = api_ok("GET", "/admin/movies", token=S["token_admin"], params={"page": 1, "size": 1})
    S["movie_id_for_session"] = listing["records"][0]["id"]
    return f"rows {rows0}→新增({title}, id={mid[:10]}…, str)→搜索→编辑时长99→105→删除, snowflake ID 全链路 ✓"


def s10_hall_crud(page_c):
    """管理端影厅新增 + 座位自动生成(DB 校验)"""
    hall_name = f"E2E回归厅{datetime.now().strftime('%H%M%S')}"
    S["hall_name"] = hall_name
    page_c.goto(FRONT + "/admin/halls")
    page_c.wait_for_load_state("networkidle")
    page_c.wait_for_selector(".el-table__row", timeout=10000)
    rows0 = page_c.locator(".el-table__row").count()

    page_c.get_by_role("button", name="新增影厅").click()
    dlg = page_c.locator(".el-dialog", has_text="新增影厅")
    dialog_form_item(page_c, "影厅名").locator("input").fill(hall_name)
    dialog_form_item(page_c, "行数").locator("input").fill("5")
    dialog_form_item(page_c, "列数").locator("input").fill("10")
    dialog_form_item(page_c, "VIP 起始行").locator("input").fill("4")
    dlg.get_by_role("button", name="保存").click()
    assert wait_toast(page_c, "已创建,座位已生成"), "未出现 '已创建,座位已生成' toast"
    dlg.wait_for(state="hidden", timeout=8000)
    shot(page_c, "s10-hall")

    halls = api_ok("GET", "/admin/halls", token=S["token_admin"])
    hall = next((h for h in halls if h["name"] == hall_name), None)
    assert hall, f"新影厅不在列表: {[h['name'] for h in halls]}"
    cnt = db_exec(f"SELECT COUNT(*) FROM seat WHERE hall_id={hall['id']}")
    assert int(cnt) == 50, f"座位自动生成应为 50, 实际 {cnt}"
    S["hall_id_for_session"] = hall["id"]
    return f"影厅列表 {rows0}→+1 ({hall_name}, id={hall['id']}); DB seat 表自动生成 5×10=50 条 ✓"


def s11_session(page_c):
    """管理端场次: UI 列表/新增对话框 + API 建场次 + 新场次座位图"""
    page_c.goto(FRONT + "/admin/sessions")
    page_c.wait_for_load_state("networkidle")
    page_c.wait_for_selector(".el-table__row", timeout=10000)
    rows0 = page_c.locator(".el-table__row").count()

    page_c.get_by_role("button", name="新增场次").click()
    dlg = page_c.locator(".el-dialog", has_text="新增场次")
    assert dlg.is_visible(), "新增场次对话框未打开"
    # 「开映时间」在 2026-10-25 前后改成了 i18n key admin.sessionsStartLabel = 「开始时间」
    for label in ("影片", "影厅", "开始时间", "票价"):
        assert dlg.locator(f".el-form-item:has(.el-form-item__label:text('{label}'))").count() >= 1, f"缺少字段 {label}"
    shot(page_c, "s11-session-dialog")
    dlg.get_by_role("button", name="取消").click()

    if "movie_id_for_session" not in S:
        listing = api_ok("GET", "/admin/movies", token=S["token_admin"], params={"page": 1, "size": 1})
        S["movie_id_for_session"] = listing["records"][0]["id"]
    start = (datetime.now() + timedelta(days=1)).strftime("%Y-%m-%dT10:00:00")
    sess = api_ok("POST", "/admin/sessions", token=S["token_admin"],
                  json_body={"movieId": S["movie_id_for_session"], "hallId": S["hall_id_for_session"],
                             "startTime": start, "price": 45.5})
    sm = api_ok("GET", f"/sessions/{sess['id']}/seat-map", token=S["token2"])
    assert sm["seatCount"] == 50 and sm["cols"] == 10, f"新场次座位图异常: {sm['seatCount']}x{sm['cols']}"
    # 清理: 删除测试场次与测试影厅(级联座位), 保持库内无残留
    cleaned = "是"
    try:
        requests.delete(f"{API}/admin/sessions/{sess['id']}", headers={"Authorization": f"Bearer {S['token_admin']}"}, timeout=10).raise_for_status()
        requests.delete(f"{API}/admin/halls/{S['hall_id_for_session']}", headers={"Authorization": f"Bearer {S['token_admin']}"}, timeout=10).raise_for_status()
    except Exception:
        cleaned = "否(残留)"
    return f"场次列表 {rows0} 行; 对话框字段完整; API 建场次 id={sess['id']} → seat-map 50 座 10 列 ✓; 测试数据清理={cleaned}"


def s07_logout(page):
    """登出 + 未登录访问受保护路由"""
    page.goto(FRONT + "/")
    page.wait_for_load_state("networkidle")
    # ⚠️ 用类名而不是文案选: 这个场景排在 S19(中英切换)**之后**, 界面已经是英文,
    # 「退出」这个文案不存在了(英文是 Logout)。文案随语言变, 类名不变。
    page.locator("button.header-chip.chip-danger").first.click()
    # ⚠️ 2026-10-25: 登出现在先弹二次确认(ElMessageBox.confirm), 旧脚本点完 chip 就等跳转
    # → 它一直卡在那个确认框上, 8s 超时。
    # 确认按钮同样用 primary 类选, 不绑文案(中英文分别是「确定退出」/「Sign out」)。
    page.locator(".el-message-box__btns .el-button--primary").first.click(timeout=8000)
    page.wait_for_url(re.compile(r"/login"), timeout=8000)
    token = page.evaluate("localStorage.getItem('cinema_token')")
    assert token in (None, ""), "登出后 token 未清除"
    shot(page, "s07-logout")
    # 未登录访问 /orders → 被 40101 拦截器带回登录页
    page.goto(FRONT + "/orders")
    page.wait_for_url(re.compile(r"/login"), timeout=8000)
    return "退出→跳 /login 且 token 清除; 未登录访问 /orders 被重定向回 /login ✓"




# ===================== v0.4.0 新增场景(S13~S20) =====================

def s13_seat_load_error(page):
    """选座页首拉失败 → 错误态 + 重试; 撤销拦截后点重试 → 座位出现; WS 仍然连着

    ⚠️ 必须用**全新 context**:座位图缓存在 Pinia store 里, 跨路由导航不丢 ——
    同一个 context 里已经有 map 时, 错误态的条件 `loadError && !seatStore.map`
    不成立, 错误态根本不渲染(2026-10-25 实测踩到)。
    """
    ctx13 = page.context.browser.new_context(viewport={"width": 1440, "height": 900})
    p13 = ctx13.new_page()
    # 真的建了 WS 连接? —— 这是「首拉失败不再中断 onMounted → createSeatWs 照样被调用」
    # 的直接证据(座位状态条渲染在 v-if="seatStore.map" 里, 首拉失败时它整个不渲染)。
    ws_urls = []
    p13.on("websocket", lambda ws: ws_urls.append(ws.url))
    try:
        assert ui_login(p13, "user1"), "S13 里 user1 登录失败"
        # 让 seat-map 接口失败(模拟后端 500 / 网络中断)
        p13.route("**/api/sessions/*/seat-map", lambda route: route.abort())
        p13.goto(f"{FRONT}/seat/{S['sid']}")
        p13.wait_for_selector(".load-error", timeout=15000)
        assert p13.locator(".load-error-text").count() >= 1, "错误态没有文案"
        # v0.3.0 的修复: 首拉失败**不再中断 onMounted** → createSeatWs 照样被调用
        p13.wait_for_timeout(1500)
        assert any("/ws/seat/" in u for u in ws_urls), (
            f"首拉失败后没有建立座位 WS 连接(实时同步被静默废掉): {ws_urls}")
        shot(p13, "s13-load-error")
        # 撤销拦截 → 点重试
        p13.unroute("**/api/sessions/*/seat-map")
        p13.get_by_role("button", name="重试").click()
        p13.wait_for_selector(SEAT_ITEM_SEL, timeout=15000)
        n = p13.locator(SEAT_ITEM_SEL).count()
        return f"首拉失败 → 错误态 + 文案 + 重试按钮 + WS 仍在连; 撤销拦截后点重试 → {n} 个座位出现 ✓"
    finally:
        ctx13.close()


def s14_admin_session_pagination(page_c):
    """管理端场次分页: 分页器在位 + 分页端点返回分页结构"""
    page_c.goto(FRONT + "/admin/sessions")
    page_c.wait_for_load_state("networkidle")
    page_c.wait_for_selector(".el-table__row", timeout=10000)
    assert page_c.locator(".el-pagination").count() >= 1, "管理端场次列表没有分页器"
    d = api_ok("GET", "/admin/sessions/page", token=S["token_admin"], params={"page": 1, "size": 5})
    assert d["total"] > 5, f"分页端点 total 异常: {d.get('total')}"
    assert len(d["records"]) == 5, f"分页端点首页应返 5 条, 实际 {len(d['records'])}"
    shot(page_c, "s14-session-pagination")
    return f"分页器在位; 分页端点 total={d['total']}, 首页 {len(d['records'])} 条 ✓"


def s15_recover_bitmap_button(page_c):
    """管理端「恢复位图」按钮: 能点、点了有反馈(这是 PRODUCT.md 承诺的能力)"""
    page_c.goto(FRONT + "/admin/sessions")
    page_c.wait_for_load_state("networkidle")
    page_c.wait_for_selector(".el-table__row", timeout=10000)
    btn = page_c.get_by_role("button", name="恢复位图")
    assert btn.count() >= 1, "场次表里没有「恢复位图」按钮"
    btn.first.click()
    assert wait_toast(page_c, "座位位图已从订单重建"), "未出现「座位位图已从订单重建」toast"
    shot(page_c, "s15-recover-bitmap")
    return "「恢复位图」按钮在位、点击有反馈 ✓"


def s16_chat_order_and_audit(page):
    """对话一键下单的写路径(ADR-0004) + 写审计(IT-05): 提案 → 确认 → 订单建成 → 审计留痕"""
    d = api_ok("POST", "/chat/orders/proposals", token=S["token1"],
               json_body={"sessionId": S["sid"], "count": 2})
    pid = d["proposalId"]
    r = api_ok("POST", f"/chat/orders/{pid}/confirm", token=S["token1"])
    assert r.get("orderNo"), f"确认后没有订单号: {r}"
    # 审计: 这一笔必须在 chat_assistant_log 里留一条 WRITE_CONFIRM 且 confirmed_by_user=1
    cnt = db_exec("SELECT COUNT(*) FROM chat_assistant_log "
                  "WHERE kind='WRITE_CONFIRM' AND confirmed_by_user=1 AND proposal_id='%s'" % pid)
    assert int(cnt) == 1, f"写审计缺行: 期望 1 条, 实际 {cnt}"
    # 红线: 未经用户确认的写操作数必须恒为 0
    bad = db_exec("SELECT COUNT(*) FROM chat_assistant_log "
                "WHERE kind='WRITE_CONFIRM' AND confirmed_by_user=0")
    assert int(bad) == 0, f"红线指标不为 0: {bad}(未经用户确认的写操作)"
    return f"提案→确认→订单 {r['orderNo'][-8:]} 建成; 审计留痕 1 条(confirmed_by_user=1); 红线未确认写数=0 ✓"


def s17_seat_watch(page, page_b):
    """座位关注 + 释放提醒: 关注他人占用的座位 → 徽标出现 → 对方取消后弹出释放提醒"""
    goto_seat_map(page)
    avail = available_indexes(page)
    assert len(avail) >= 2, f"可用座位不足: {len(avail)}"
    # user2 占一个座位
    watch_seat = avail[0]
    data = api_ok("POST", "/orders/lock", token=S["token2"],
                  json_body={"sessionId": S["sid"], "seatIndexes": [watch_seat]})
    order_no = data["orderNo"]
    # user1 页面刷新 → 那个座位是 locked_other
    goto_seat_map(page)
    # 打开关注模式
    page.locator("button.watch-toggle").click()
    page.wait_for_selector("button.watch-toggle.active", timeout=5000)
    # 点那个被占用的座位 → 关注
    page.locator(SEAT_ITEM_SEL).nth(watch_seat).click()
    page.wait_for_timeout(500)
    cnt = page.locator(".watch-count").count()
    assert cnt == 1, f"关注徽标没出现(count={cnt})"
    shot(page, "s17-watch")
    # user2 取消订单 → 座位释放 → 释放提醒
    api_ok("POST", f"/orders/{order_no}/cancel", token=S["token2"])
    released = False
    deadline = time.time() + 15
    while time.time() < deadline:
        if wait_toast(page, "刚刚释放", timeout=800):
            released = True
            break
        page.wait_for_timeout(500)
    shot(page, "s17-watch-released")
    assert released, "座位释放后 15s 内没有出现释放提醒 toast"
    return f"关注徽标出现 ✓; 对方取消 → 释放提醒 toast 出现 ✓"


def s18_dark_theme(page):
    """暗色主题: 切到 dark → html[data-theme='dark'] + 偏好存进 localStorage"""
    page.goto(FRONT + "/")
    page.wait_for_load_state("networkidle")
    before = page.evaluate("() => document.documentElement.getAttribute('data-theme')")
    # 三态开关(auto/light/dark): 点到 dark 为止(最多 3 次)
    for _ in range(3):
        cur = page.evaluate("() => document.documentElement.getAttribute('data-theme')")
        if cur == "dark":
            break
        # 桌面头部与移动端菜单各有一个主题 chip → 严格模式下要 .first
        page.locator(".header-chip.chip-theme").first.click()
        page.wait_for_timeout(400)
    after = page.evaluate("() => document.documentElement.getAttribute('data-theme')")
    assert after == "dark", f"没切到暗色: {before} → {after}"
    stored = page.evaluate("() => localStorage.getItem('cinema_theme')")
    assert stored, "主题偏好没写进 localStorage"
    shot(page, "s18-dark-theme")
    return f"data-theme {before} → {after}; 偏好已存 localStorage={stored} ✓"


def s19_locale_switch(page):
    """中英双语: 切到 en-US → html[lang] 变 + 关键文案变英文"""
    page.goto(FRONT + "/")
    page.wait_for_load_state("networkidle")
    lang_before = page.evaluate("() => document.documentElement.getAttribute('lang')")
    page.locator(".header-chip.chip-locale").click()
    page.wait_for_timeout(800)
    lang_after = page.evaluate("() => document.documentElement.getAttribute('lang')")
    assert lang_after != lang_before, f"语言没切: {lang_before} → {lang_after}"
    # 英文界面不能夹中文枚举/裸 key
    body = page.inner_text("body")
    assert "订单" not in body[:400], "切到英文后页头仍夹中文"
    shot(page, "s19-locale-en")
    return f"lang {lang_before} → {lang_after}; 英文界面无中文残留 ✓"


def s21_admin_usage_dashboard(page_c):
    """管理端「助手用量」看板: 四项指标 + 红线指标都渲染出来(AC-19)

    2026-10-25 补: 此前**没有任何场景打开过 /admin/chat-stats** ——
    IT-11 的验收标准 AC-19 正是「打开看板看到四项指标」, 却没有浏览器覆盖。
    """
    page_c.goto(FRONT + "/admin/chat-stats")
    page_c.wait_for_load_state("networkidle")
    page_c.wait_for_selector(".stat-card", timeout=10000)
    for label in ("调用量", "失败率", "P95 耗时", "估算成本"):
        assert page_c.locator(".stat-label", has_text=label).count() >= 1, (
            f"看板上没有「{label}」这一项")
    # 红线指标也要在 —— 让「助手没越权写」在管理端是**看得见**的
    assert page_c.locator(".stat-label", has_text="红线指标").count() >= 1, (
        "看板上没有红线指标")
    # 标题在还不够: 四项指标的**数值**也得渲染出来(否则可能全是空壳)
    values = [v.strip() for v in page_c.locator(".stat-value").all_text_contents()]
    assert len(values) >= 5, f"看板上的指标值不够: {values}"
    assert all(v for v in values), f"有指标值是空的: {values}"
    shot(page_c, "s21-usage-dashboard")
    return f"四项指标(调用量/失败率/P95/估算成本) + 红线指标都在看板上, 数值非空 ✓ ({len(values)} 个值)"


def chat_provider():
    """后端当前跑的是真 LLM 还是确定性 stub(GET /api/chat/status)。"""
    try:
        d = api_ok("GET", "/chat/status")
        return (d or {}).get("provider") or "unknown"
    except Exception as e:
        print(f"[setup] 读 /api/chat/status 失败: {e}", flush=True)
        return "unknown"


def my_order_nos(token):
    """当前用户的订单号集合 —— 拿来判「有没有新订单」(语言无关, 不依赖界面文案)。"""
    d = api_ok("GET", "/orders/my?page=1&size=100", token=token) or {}
    return {r.get("orderNo") for r in (d.get("records") or []) if r.get("orderNo")}


def s22_chat_widget_flow(page):
    """对话助手浮窗(浏览器): 开浮窗 → 发消息 → 助手回复 → 出卡则点「确认下单」→ 订单建成

    ⚠️ **不伪造通过**: 真实 LLM 下助手出不下下单卡取决于模型自由发挥。
    出卡了 → 点「确认下单」→ 验订单建成(这才是 AC-20 那条链路的真覆盖);
    没出卡 → 记 **SKIP**(附原因), 不许记 PASS —— 记 PASS 就是把「没验」说成「验过了」。

    ⚠️ 但后端以 `cinema.chat.provider=stub` 启动时(确定性夹具), **必须**出卡 ——
    没出卡/没回复就是真回归, 这时记 FAIL 而不是 SKIP。stub 让这条验收可回归。
    """
    provider = chat_provider()
    print(f"[setup] 对话模型来源 = {provider}", flush=True)
    page.goto(FRONT + "/")
    page.wait_for_load_state("networkidle")
    # 打开聊天浮窗
    page.locator("button.chat-fab").first.click(timeout=8000)
    page.wait_for_selector(".chat-panel", timeout=8000)
    page.wait_for_selector(".chat-input textarea", timeout=8000)

    # 发一条会诱发推荐的消息
    page.locator(".chat-input textarea").fill("帮我推荐今晚的场次,要两个连座")
    page.locator(".chat-send-btn").click()

    # 等助手回复(LLM 可能要几秒; 超时按 SKIP 记, 不算失败)
    deadline, replied = time.time() + 45, False
    while time.time() < deadline:
        if page.locator(".chat-msg.assistant").count() > 0:
            replied = True
            break
        page.wait_for_timeout(500)
    if not replied:
        if provider == "stub":
            raise AssertionError("stub 模式下 45s 内助手必须回复 —— 没回复就是回归")
        raise Skipped("45s 内助手没回复(LLM 不可用或被限流) —— 本次没法验证这条链路")

    # 卡片与回复同批返回, 但前端可能分两拍渲染 → 给它一点时间
    try:
        page.locator(".order-card").first.wait_for(state="visible", timeout=8000)
    except Exception:
        pass

    # 出卡了吗? 没出卡就是 LLM 没给下单建议 —— 按 provider 分开定性
    card = page.locator(".order-card")
    if card.count() == 0:
        shot(page, "s22-chat-no-card")
        if provider == "stub":
            raise AssertionError("stub 模式下助手必须给出下单卡 —— 没出卡就是回归"
                                 "(查 /api/chat/status 与后端启动参数 CINEMA_CHAT_PROVIDER)")
        raise Skipped("助手这次没给下单卡(LLM 自由发挥) —— 卡片链路本次没法验证")

    shot(page, "s22-chat-card")
    # 判据用**服务端事实**(user1 的订单列表里冒出新订单) + **语言无关的选择器**(.pending-card),
    # 不用「界面上出现『订单号』这几个字」: S19 把界面切成了英文, 拿中文文案当判据
    # = 测试与场景顺序耦合(2026-10-25 实测踩到: 订单早建成了, 却报「没看到订单号」)。
    before = my_order_nos(S["token1"])
    page.locator(".order-card-btn").first.click(timeout=8000)

    deadline, order_no = time.time() + 20, None
    while time.time() < deadline:
        new_nos = my_order_nos(S["token1"]) - before
        if new_nos:
            order_no = sorted(new_nos)[0]
            break
        page.wait_for_timeout(500)
    assert order_no, "点了「确认下单」但服务端没有出现新订单"

    # 前端应追加一张「待支付」卡(.pending-card —— 与语言无关)
    try:
        page.locator(".pending-card").first.wait_for(state="visible", timeout=8000)
    except Exception:
        pass
    assert page.locator(".pending-card").count() > 0, "确认成功后前端没有渲染待支付卡"

    # 服务端对账: 这笔单真的存在
    d = api_ok("GET", f"/orders/{order_no}", token=S["token1"])
    assert d and d.get("orderNo"), f"订单 {order_no} 查不到"
    return f"聊天浮窗 → 发消息 → 助手给出下单卡 → 点「确认下单」→ 订单 {order_no} 建成 ✓"


def s23_seat_session_switch(page):
    """选座页换场次必须重载(P0-1): /seat/A → 点助手座位建议卡 → /seat/B, 页面必须变成 B 场

    <p>Vue Router 4 对**同一路由记录只换 params** 会复用组件实例(`onMounted` 不再触发)。
    修复前: URL 变成 /seat/B, 页面仍是 A 场的座位图, WS 还连着 A, preselect 也没生效 ——
    用户在 A 的图上选座、点确认 → 发出的是 B 的 sessionId + A 的座位索引 → 锁错场次的钱付了。

    <p>判据全部**与语言无关**(S19 会把界面切成英文, 拿中文文案当判据 = 测试与场景顺序耦合):
      ① 选座页 header 的影片名 == B 场的 movieTitle;
      ② 渲染出的座位数 == B 场的 seatCount;
      ③ preselect 生效: URL 上的 preselect 索引在页面上确实是 selected 座位;
      ④ WS 重连到了 B: 浏览器真的新建了一条指向 B 场的 WS 连接。

    <p>⚠️ A、B 必须**可区分**(影片名 / 座位数至少有一个不同)——
    否则 ①② 两条判据在 A、B 相同的情况下也成立, 那是假绿灯。
    找不到可区分的一对就 SKIP, 不拿一条恒真的断言充数。
    """
    provider = chat_provider()
    print(f"[setup] 对话模型来源 = {provider}", flush=True)

    # ---------- 1. 挑两个「在售 + 未开场 + 有连座」且可区分的场次 ----------
    raw = db_exec("SELECT id FROM `session` WHERE status=1 AND start_time > NOW() "
                  "ORDER BY start_time, id LIMIT 40")
    cands = []
    for raw_id in raw.split():
        if not raw_id.isdigit():
            continue
        try:
            sm = seat_map_of(int(raw_id))
        except Exception:
            continue
        # 至少要有两个**同行连续**的可选座位, 否则服务端找不到连座 → stub 不会出卡
        if longest_row_run(sm) < 2:
            continue
        cands.append((int(raw_id), sm))
        if len(cands) >= 12:
            break
    if len(cands) < 2:
        raise Skipped(f"库里「在售 + 未开场 + 有连座」的场次只有 {len(cands)} 个 —— 本次没法验证")

    pair = None
    for sid_a, sm_a in cands:
        for sid_b, sm_b in cands:
            if sid_a == sid_b:
                continue
            dims = []
            if sm_a["movieTitle"] != sm_b["movieTitle"]:
                dims.append("movieTitle")
            if sm_a["seatCount"] != sm_b["seatCount"]:
                dims.append("seatCount")
            if dims:
                pair = (sid_a, sm_a, sid_b, sm_b, dims)
                break
        if pair:
            break
    if not pair:
        raise Skipped("演示库里找不到两个可区分的场次(影片名与座位数都相同) —— 本次没法验证")
    sid_a, sm_a, sid_b, sm_b, dims = pair
    print(f"[setup] S23 场次 A={sid_a}({sm_a['movieTitle']}/{sm_a['seatCount']}座) "
          f"B={sid_b}({sm_b['movieTitle']}/{sm_b['seatCount']}座) 可区分维度={dims}", flush=True)

    # ---------- 2. 记住 WS 连接(判据④: WS 确实重连到了 B) ----------
    ws_urls = []
    page.on("websocket", lambda w: ws_urls.append(w.url))

    goto_seat_map(page, sid_a)
    title_a = page.locator(".header-card .title").inner_text().strip()
    seats_a = page.locator(SEAT_ITEM_SEL).count()
    assert seats_a == sm_a["seatCount"], (
        f"A 场渲染出 {seats_a} 个座位, 库里 seatCount={sm_a['seatCount']} —— 前置数据对不上")
    shot(page, "s23-seat-a")

    # ---------- 3. 聊天浮窗: 发一条「带 B 的场次 ID 且在问座位」的消息 ----------
    if page.locator(".chat-panel").count() == 0:
        page.locator("button.chat-fab").first.click(timeout=8000)
        page.wait_for_selector(".chat-panel", timeout=8000)
    page.wait_for_selector(".chat-input textarea", timeout=8000)
    page.locator(".chat-input textarea").fill(f"场次 {sid_b} 的座位有连座吗")
    page.locator(".chat-send-btn").click()

    # 座位建议卡渲染成 .action-card(下单卡是 .order-card, 两者不串)
    deadline, got_card = time.time() + 45, False
    while time.time() < deadline:
        if page.locator(".action-card").count() > 0:
            got_card = True
            break
        page.wait_for_timeout(500)
    if not got_card:
        shot(page, "s23-chat-no-card")
        if provider == "stub":
            raise AssertionError("stub 模式下助手必须给出座位建议卡 —— 没出卡就是回归"
                                 "(查 /api/chat/status 与后端启动参数 CINEMA_CHAT_PROVIDER)")
        raise Skipped("助手这次没给座位建议卡(LLM 自由发挥) —— 本次没法验证这条链路")
    shot(page, "s23-chat-card")

    # ---------- 4. 点卡片 → URL 变成 /seat/B ----------
    ws_before = len(ws_urls)
    page.locator(".action-card-btn").first.click(timeout=8000)
    page.wait_for_url(re.compile(rf"/seat/{sid_b}(?:\?|$)"), timeout=10000)
    # 座位图要重新拉 —— 等 header 换成 B 场的身份
    page.wait_for_function(
        "(title) => { const el = document.querySelector('.header-card .title');"
        " return !!el && el.textContent.trim() === title; }",
        arg=sm_b["movieTitle"], timeout=15000)
    page.wait_for_function(
        "(n) => document.querySelectorAll('.seats-grid .seat, .seats .seat').length === n",
        arg=sm_b["seatCount"], timeout=15000)

    # ---------- 5. 核心断言: 页面显示的是 B 场, 不是 A 场 ----------
    title_b = page.locator(".header-card .title").inner_text().strip()
    seats_b = page.locator(SEAT_ITEM_SEL).count()
    assert title_b == sm_b["movieTitle"], (
        f"选座页显示的是「{title_b}」, 但 B 场是「{sm_b['movieTitle']}」 —— 页面没换场次")
    assert seats_b == sm_b["seatCount"], (
        f"选座页渲染了 {seats_b} 个座位, B 场应是 {sm_b['seatCount']} 个 —— 座位图没换场次")
    if "movieTitle" in dims:
        assert title_b != title_a, (
            f"A、B 两场影片名不同, 但页面仍显示 A 场的「{title_a}」 —— 换场次没生效")
    if "seatCount" in dims:
        assert seats_b != seats_a, (
            f"A、B 两场座位数不同, 但页面仍渲染着 A 场的 {seats_a} 个座位 —— 换场次没生效")
    shot(page, "s23-seat-b")

    # ---------- 6. preselect 生效(卡片带来的预选座位被选中) ----------
    m = re.search(r"preselect=([0-9,]+)", page.url)
    assert m, f"URL 上没有 preselect 参数: {page.url}"
    preselect = [int(x) for x in m.group(1).split(",") if x != ""]
    assert preselect, f"preselect 参数是空的: {page.url}"
    selected_count = page.locator(".seat.selected").count()
    assert selected_count == len(preselect), (
        f"preselect 带了 {len(preselect)} 个座位, 页面上只有 {selected_count} 个 selected —— 预选没生效")
    for idx in preselect:
        cls = page.locator(SEAT_ITEM_SEL).nth(idx).get_attribute("class")
        assert "selected" in (cls or ""), f"座位 {idx} 没被预选(class={cls})"

    # ---------- 7. WS 确实重连到了 B ----------
    new_ws = ws_urls[ws_before:]
    ws_b_re = re.compile(rf"/ws/seat/{sid_b}(?:$|\?)")
    assert any(ws_b_re.search(u) for u in new_ws), (
        f"点击卡片后没有新建指向 B 场({sid_b})的 WS 连接, 新建的连接是: {new_ws}")

    return (f"A={sid_a}({sm_a['movieTitle']}/{sm_a['seatCount']}座) → 座位建议卡 → "
            f"B={sid_b}({sm_b['movieTitle']}/{sm_b['seatCount']}座); "
            f"URL 已换且页面显示 B 场(影片名+座位数都对上, 可区分维度={dims}); "
            f"preselect {preselect} 生效(座位数与 selected 数一致); "
            f"WS 已重连到 /ws/seat/{sid_b} ✓")


def s20_rebuild_keeps_lock(page, page_c):
    """位图合并语义(v0.4.0 超卖修复): 重建位图**不清掉**已锁座位"""
    goto_seat_map(page)
    avail = available_indexes(page)
    assert len(avail) >= 1, "没有可用座位"
    keep = avail[0]
    # ⚠️ 用 user1: 锁座端点的幂等键 = 用户 + 场次(TTL 8s)。S17 刚用 user2 锁过同一场次,
    # 复用 user2 会撞 40900; user1 的上一次锁座(S03)早已过了 8 秒窗口。
    # (曾经试过「注册一个一次性用户」—— 但注册端点按 IP 限流 120 次/小时,
    #  额度用完时注册被拒, 而失败被吞掉后登录报的是「用户名或密码错误」, 指向错误的根因。)
    # user2 锁住这个座位
    data = api_ok("POST", "/orders/lock", token=S["token1"],
                  json_body={"sessionId": S["sid"], "seatIndexes": [keep]})
    assert data.get("orderNo"), "user2 锁座失败"
    # 调管理端「恢复位图」(v0.4.0 起是「合并」: 正式位图 OR DB 快照, 只加不减)
    api_ok("POST", f"/admin/sessions/bitmaps/recover?sessionId={S['sid']}", token=S["token_admin"])
    # 关键: 那个座位**仍然**是锁着的 —— 不能被重建抹掉
    bits = lock_bits(S["token1"])
    assert bits[keep] == 1, f"重建后座位 {keep} 的锁位被抹掉了(lockBit={bits[keep]}) —— 这就是超卖窗口"
    # ⚠️ 本场景**不区分**「合并」与旧的「替换」实现: 锁座走公开 API, DB 里有
    # PENDING_PAY 单, 快照里就有那个座位, 旧实现同样会把它锁上。
    # 真正能区分新旧实现的是那条 JUnit 回归用例
    # (SeatLuaServiceBitmapRoundTripTest#mergeSeats_doesNotWipeLockCreatedAfterSnapshot:
    #  它直接在位图上锁一个**DB 里没有单**的座位, 合并后必须还在)。
    return f"重建后座位 {keep} 仍被锁定(lockBit=1) ✓ —— DB 有单的座位不会被重建抹掉"

# ---------------- 主流程 ----------------

def run(only=None):
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx_a = browser.new_context(viewport={"width": 1440, "height": 900})  # user1
        ctx_b = browser.new_context(viewport={"width": 1440, "height": 900})  # user2
        ctx_c = browser.new_context(viewport={"width": 1440, "height": 900})  # admin
        page = ctx_a.new_page()
        page_b = ctx_b.new_page()
        page_c = ctx_c.new_page()

        pageerrors = []
        for pg in (page, page_b, page_c):
            pg.on("pageerror", lambda e: pageerrors.append(str(e)))

        # 动态挑选一个在售且未开映的场次(种子场次 1 已被置为停售)
        S["sid"] = int(db_exec("SELECT id FROM `session` WHERE status=1 AND start_time > NOW() ORDER BY id LIMIT 1"))
        print(f"[setup] 选用场次 id={S['sid']}", flush=True)

        scenarios = [
            ("S12 健康/静态端点", lambda: s12_health(page)),
            ("S01 公共浏览+snowflake直达", lambda: s01_browse(page)),
            ("S02 登录(错密+成功)", lambda: s02_login(page)),
            ("S03 选座+myLocked+WS实时推送", lambda: s03_seat_ws(page, page_b)),
            ("S04 支付+取消+座位释放", lambda: s04_pay_cancel(page, page_b)),
            ("S05 超时关单(5s扫描器)", lambda: s05_timeout_close(page)),
            ("S06 订单列表筛选", lambda: s06_order_tabs(page)),
            ("S08 权限边界(40301/admin)", lambda: s08_permission(page, page_c)),
            ("S09 影片CRUD(snowflake回归)", lambda: s09_movie_crud(page_c)),
            ("S10 影厅CRUD+座位自动生成", lambda: s10_hall_crud(page_c)),
            ("S11 场次管理+新场次座位图", lambda: s11_session(page_c)),
            ("S13 选座首拉失败+重试", lambda: s13_seat_load_error(page)),
            ("S14 管理端场次分页", lambda: s14_admin_session_pagination(page_c)),
            ("S15 管理端恢复位图按钮", lambda: s15_recover_bitmap_button(page_c)),
            ("S16 对话一键下单+写审计", lambda: s16_chat_order_and_audit(page)),
            ("S17 座位关注+释放提醒", lambda: s17_seat_watch(page, page_b)),
            ("S18 暗色主题切换", lambda: s18_dark_theme(page)),
            ("S19 中英双语切换", lambda: s19_locale_switch(page)),
            ("S20 位图合并不抹掉已锁座位", lambda: s20_rebuild_keeps_lock(page, page_c)),
            ("S21 管理端助手用量看板", lambda: s21_admin_usage_dashboard(page_c)),
            ("S22 对话浮窗→下单卡→确认下单", lambda: s22_chat_widget_flow(page)),
            ("S23 选座页换场次必须重载", lambda: s23_seat_session_switch(page)),
            # ⚠️ 登出**必须排最后** —— 它会清掉 user1 的 token,
            # 排在前面会让后面所有需要登录的场景被路由守卫踢回 /login。
            ("S07 登出+路由守卫", lambda: s07_logout(page)),
        ]
        if only:
            wanted = {w.strip().upper() for w in only.split(",") if w.strip()}

            def _keep(s):
                code = s[0].split()[0].upper()
                # S02(登录)始终保留 —— 它是后面场景的前置(token 存在 S["token1"])。
                return code in wanted or code == "S02"

            scenarios = [s for s in scenarios if _keep(s)]
            print(f"[setup] --only {only} → 跑: {[s[0].split()[0] for s in scenarios]}", flush=True)
        for name, fn in scenarios:
            try:
                detail = fn()
                record(name, True, detail)
            except Skipped as e:
                record(name, True, f"SKIP: {e}", skipped=True)
            except Exception as e:
                record(name, False, f"{type(e).__name__}: {e}")
                traceback.print_exc()

        if pageerrors:
            print("\n[页面 JS 错误]", *pageerrors[:10], sep="\n  ")
        browser.close()

    # 汇总
    n_pass = sum(1 for _, s, _ in RESULTS if s == "PASS")
    n_fail = sum(1 for _, s, _ in RESULTS if s == "FAIL")
    n_skip = sum(1 for _, s, _ in RESULTS if s == "SKIP")
    print(f"\n===== 汇总: {n_pass} PASS / {n_fail} FAIL / {n_skip} SKIP "
          f"(共 {len(RESULTS)} 个场景) =====")
    for name, s, d in RESULTS:
        print(f"  [{s}] {name}" + (f" — {d}" if d else ""))

    lines = [
        "# E2E 回归测试报告 (Python Playwright)",
        "",
        f"> 执行时间: {datetime.now().strftime('%Y-%m-%d %H:%M')} · Chromium headless · 前端 {FRONT} / 后端 {BACKEND}",
        "> 目的: 上轮修复 Bug A(WS 推送)/Bug B(myLockedSeats)/Bug C(snowflake ID) 后的全场景回归",
        "",
        "| 场景 | 状态 | 证据 |",
        "| --- | :-: | --- |",
    ]
    for name, s, d in RESULTS:
        icon = {"PASS": "✅", "FAIL": "❌", "SKIP": "⏭"}.get(s, "?")
        lines.append(f"| {name} | {icon} {s} | {d} |")
    lines += ["", f"**统计**: ✅ {n_pass} PASS / ❌ {n_fail} FAIL / ⏭ {n_skip} SKIP", ""]
    if n_skip:
        lines += ["> ⏭ = **跳过**, 不是通过: 当前环境不具备验证条件(例如助手出不出卡取决于 LLM)。",
                  "> 跳过项不代表功能有问题, 但也**不算验过了**。", ""]
    (ROOT / "test" / "e2e" / "RESULTS.md").write_text("\n".join(lines), encoding="utf-8")
    print("报告已写入 test/e2e/RESULTS.md")
    # 退出码只看 FAIL —— SKIP 是「环境不具备验证条件」, 不是缺陷(但会在报告里单列)。
    return 1 if n_fail else 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="E2E 回归套件(Playwright)")
    ap.add_argument("--only", help="只跑指定场景(编号逗号分隔, 如 S22 / S21,S22); S02 登录始终保留")
    args = ap.parse_args()
    sys.exit(run(args.only))
