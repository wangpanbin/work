import { AUTH_TOKEN_KEY, AUTH_USER_KEY } from '../constants/auth'

/**
 * 登录态失效的**广播口** —— 登录态有**两份**, 这里负责让它们同时失效。
 *
 * <p>两份状态: localStorage 里的 token/user 与 Pinia store 里的
 * `token` / `user` ref。只清前者的话, store 还以为用户已登录 →
 * 顶栏照显示「我的订单 / 管理端」, 路由守卫照放行 →
 * 后续请求继续 401 → 死循环(2026-10-26 实测发现的坑)。
 *
 * <p>⚠️ 拦截器**不能**直接 import `stores/user`:
 * `request.ts` → `stores/user.ts` → `api/user.ts` → `request.ts` 会成环
 * (现在 `request.ts → router → stores/user → api/user → request` 已经是环)。
 * 所以这里做成**叶子模块**: 清 localStorage + 通知订阅者,
 * 由 store 自己订阅 —— 依赖环断开, 两侧状态仍然同步。
 *
 * <p>用模块内的订阅表而不是 DOM 事件: 本仓 vitest 跑在 `environment: 'node'`,
 * 那里没有 `globalThis.addEventListener`, 用 DOM 事件会让这个模块在测试里
 * 变成哑的(订阅静默失效, 断言还是绿的 —— 那就是假绿灯)。
 */
const handlers = new Set<() => void>()

/**
 * 让登录态失效 —— 清掉 localStorage 那份, 并通知所有订阅者。
 *
 * <p>幂等: 重复调用只是把同样的两个 key 再删一次、再通知一次,
 * 不会互相打架(并发多个请求同时 401 时会走到这里)。
 */
export function invalidateAuthSession(): void {
  try {
    globalThis.localStorage?.removeItem(AUTH_TOKEN_KEY)
    globalThis.localStorage?.removeItem(AUTH_USER_KEY)
  } catch {
    // 隐私模式禁用了 storage —— 状态本来就无处可清, 不抛错
  }
  // 复制一份再遍历: 订阅者可能在回调里退订, 直接遍历 Set 会跳过元素。
  for (const handler of [...handlers]) {
    try {
      handler()
    } catch {
      // 单个订阅者出错不该影响其他人(尤其不该让拦截器的 reject 链断掉)
    }
  }
}

/**
 * 订阅「登录态失效」通知。返回退订函数。
 *
 * <p>用在 `stores/user.ts`: 收到通知就把 `token` / `user` 置空,
 * 让顶栏与路由守卫立刻知道「已降级为匿名」。
 */
export function onAuthSessionInvalidated(handler: () => void): () => void {
  handlers.add(handler)
  return () => {
    handlers.delete(handler)
  }
}
