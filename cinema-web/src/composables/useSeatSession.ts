/**
 * 选座页「装载一个场次」的编排(2026-10-25 交互流程审查 P0-1)。
 *
 * <p>**为什么要有这个模块**: 选座页原先只在 `onMounted` 里拉一次座位图。
 * Vue Router 4 对**同一路由记录只换 params** 会复用组件实例(`RouterView`
 * 渲染时不加 key), `onMounted` 不再触发 —— 于是「URL 变成 /seat/B, 页面仍是
 * A 场的座位图, WS 还连着 A」, 用户在 A 的图上选座、点确认, 发出的却是
 * B 的 sessionId → 锁错场次的钱付了。
 *
 * <p>把这段编排抽成模块有两个理由:
 * <ul>
 *   <li>**可测**: 本仓 vitest 跑在 `environment: 'node'`, 挂不了 SFC ——
 *       编排留在 `onMounted` 里就只能靠真浏览器验证;</li>
 *   <li>**一处说了算**: 首拉与「同路由换场次」走同一条路径, 不会再分裂成
 *       两套顺序(两套顺序迟早分叉)。</li>
 * </ul>
 *
 * <p>**依赖注入**: 本模块不碰 Pinia / vue-router / WebSocket, 那些都由调用方
 * 注入。这样编排本身是纯逻辑(node 环境可测), 视图侧只负责「把依赖接上去」。
 */

/** 装载一个场次所需要的依赖 —— 全部由调用方注入 */
export interface SeatSessionDeps {
  /** 关掉当前 WS(换场次时必须先关, 否则旧场次的事件会流进新场次) */
  closeWs(): void
  /** 清空上一场次的选座(以及旧座位图) */
  resetSelection(): void
  /** 拉取并装载某个场次的座位图(带轮次号, 让实现方能判断「回来的这份是否已过时」) */
  loadMap(sessionId: string, generation: number): Promise<void>
  /** 应用来自对话助手的预选座位 */
  applyPreselect(indexes: number[]): void
  /** 建立该场次的实时连接 */
  connectWs(sessionId: string): void
  /**
   * 这一轮编排是否仍是「**当前那一轮**」。
   *
   * <p>为什么用「轮次」而不是「场次 ID」: `A → B → A` 的快返场景下, 第一轮 A
   * 回来时**场次 ID 又是 A 了** —— 只比 ID 会把**上一轮 A 的预选**套到当前 A 场次上,
   * 把「上一场的座位」选到这一场。轮次单调递增, 只有最新那一轮才通过。
   *
   * <p>被它挡掉的东西都不会报错, 用户只看到「座位莫名其妙被选中了」
   * 与「实时同步不更新」。
   */
  isCurrent(generation: number): boolean
}

/**
 * 装载一个场次 —— 首拉与「同路由换场次」共用的唯一编排。
 *
 * <p>调用顺序固定为:
 * {@code closeWs → resetSelection → loadMap → applyPreselect → connectWs}。
 *
 * <p>⚠️ **本仓既有不变量: 拉座位图失败也必须连 WS。**
 * 首拉失败若把 WS 建连一起中断, 用户看到的是一张「没有实时更新」的过期座位图,
 * 而且毫无察觉(连状态条都不会出现)。所以 `loadMap` 的失败在本函数内部兜住,
 * `connectWs` 照常执行。
 *
 * <p>⚠️ 本函数**永不 reject**: 失败信息由 `loadMap` 的实现方负责呈现
 * (选座页把它记进 `loadError` 渲染错误态)。这样调用方不必再写一遍 catch,
 * 也不会产生未处理的 rejection。
 *
 * @param deps     依赖(见 {@link SeatSessionDeps})
 * @param sessionId 要装载的场次 ID
 * @param preselect 预选座位索引; 空数组表示没有预选
 */
export async function loadSeatSession(
  deps: SeatSessionDeps,
  sessionId: string,
  preselect: number[],
  generation: number,
): Promise<void> {
  deps.closeWs()
  deps.resetSelection()
  let loaded = false
  try {
    await deps.loadMap(sessionId, generation)
    loaded = true
  } catch {
    // 错误已由 loadMap 的实现方记进错误态; 这里不打断 WS 建连。
  }

  // ⚠️ **这一轮已经过时 → 彻底中止**(2026-10-25 评审发现)。
  // 座位图在视图侧已挡住(落库前核对场次), 但**预选与 WS 是另外两路**:
  // 旧编排回来后套用旧预选 / 用旧场次覆盖新场次的 WS, 都不会报错,
  // 用户只看到「座位莫名其妙被选中了」与「实时同步不更新」。
  if (!deps.isCurrent(generation)) return

  // 预选要在位图到手之后才能应用(冲突判断依赖位图)。
  // 拉图失败时位图未知, 此时应用预选只会得到错误的冲突判断 → 跳过。
  if (loaded && preselect.length > 0) deps.applyPreselect(preselect)

  deps.connectWs(sessionId)
}

/**
 * 场次是否还能锁座(2026-10-25 交互流程审查 P3)。
 *
 * <p>后端在锁座那一刻才拒「场次已开场」, 前端提前拦一道 —— 纯函数, 便于单测。
 *
 * <p>⚠️ **与后端不是逐毫秒等价**: 后端判据是「开演时间严格晚于当前时间」
 * (`isAfter(now)`), 本函数按**整秒**判定。因此在开演前的最后一秒里
 * 前端会拦而后端会放行 —— 这是亚秒级差异, 可接受, 但不要把它当成完全等价。
 *
 * @param remainingSeconds 距离开演的剩余秒数(<= 0 表示已开场)
 */
export function canLockSession(remainingSeconds: number): boolean {
  return remainingSeconds > 0
}
