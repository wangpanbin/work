import { getCurrentInstance, onBeforeUnmount, ref, type Ref } from 'vue'

/**
 * 权威重拉(Authoritative Refetch)的调度器。
 *
 * <p>前端座位图是**乐观视图**, 后端才是唯一权威。实时事件之外还有两个补偿点:
 * <ul>
 *   <li>**释放事件之后** —— 释放最容易引发「同座位紧跟一次锁座」, 假可选最容易被察觉</li>
 *   <li>**实时连接重连成功之后** —— 断连期间丢失的事件, 这是唯一的补偿点</li>
 * </ul>
 *
 * <p>**为什么把这段抽成模块**: 补偿策略(防抖 / 退避 / preserveSelection / 剔除统计 /
 * 放弃判定)原本内联在 `SeatSelect.vue` 里, 一条事实都没有归属 ——
 * 两个触发点各自重写一遍就会分裂。抽出来之后, 策略是**一处**说了算。
 *
 * <p>**不动的部分**: `utils/ws.ts` 的 `statusOnConnect` 状态机仍是首连/重连的唯一判别;
 * `stores/seat.ts` 的 `load()` 与 `computeRevalidateSelection` 仍是选座复核的唯一归属。
 * 本模块只负责「什么时候重拉、重拉失败怎么办」。
 */

/** 触发一次权威重拉的原因 —— 仅用于日志与调试 */
export type ReconcileReason = 'release' | 'reconnect'

/** 防抖窗口(毫秒) —— 合并成簇到达的突发事件 */
export const RECONCILE_DEBOUNCE_MS = 500

/** 失败退避表(毫秒) —— 超出这张表就放弃并提示用户 */
export const RECONCILE_BACKOFF_MS: readonly number[] = [300, 1000, 3000]

/** 下一次重试的退避计划 */
export type ReconcilePlan =
  | { kind: 'retry'; delayMs: number }
  | { kind: 'give-up' }

/**
 * 按已连续失败的次数算出下一次重试的退避计划。
 *
 * <p>纯函数 —— 不碰 timer、不碰网络, 所以能在 node 环境直接单测。
 *
 * @param failuresSoFar 已经连续失败了几次(**含本次**)
 * @returns 还能重试 → 下一个延迟; 已超出退避表 → 放弃
 */
export function computeReconcilePlan(failuresSoFar: number): ReconcilePlan {
  const idx = failuresSoFar - 1
  if (idx >= 0 && idx < RECONCILE_BACKOFF_MS.length) {
    return { kind: 'retry', delayMs: RECONCILE_BACKOFF_MS[idx] }
  }
  return { kind: 'give-up' }
}

/** 重拉尝试的计数状态 */
export interface ReconcileState {
  /** 已连续失败次数 */
  failures: number
  /** 是否已放弃(连续失败超出退避表) */
  failed: boolean
}

/** 初始状态 —— 一次都没失败过 */
export const INITIAL_RECONCILE_STATE: ReconcileState = { failures: 0, failed: false }

/**
 * 重拉计数状态机 —— 纯函数。
 *
 * <p>成功 → 计数归零且不再处于失败态(下一次失败重新从第一档退避开始)。
 * <p>失败 → 计数 +1, 并按新计数判断是否已超出退避表。
 */
export function advanceReconcile(
  state: ReconcileState,
  outcome: 'success' | 'failure',
): ReconcileState {
  if (outcome === 'success') {
    return { failures: 0, failed: false }
  }
  const failures = state.failures + 1
  return { failures, failed: computeReconcilePlan(failures).kind === 'give-up' }
}

/** 调度器的依赖 —— 由调用方注入, 本模块不自己创建 */
export interface SeatSyncOptions {
  /**
   * 拉取权威座位图。
   *
   * @param preserveSelection 传 true —— 逐个复核用户已选座位, 只剔除确实被占走的
   * @returns 本次被剔除的已选座位数
   */
  refresh: (preserveSelection: boolean) => Promise<number>
  /** 当前是否忙(正在提交锁座 / 正在刷新) —— 忙时跳过重拉, 避免与锁座响应相互覆盖 */
  isBusy: () => boolean
  /** 有座位被占走了 —— 由调用方决定怎么提示(通常是 toast) */
  onSeatsTaken: (n: number) => void
}

/** 调度器的接口 */
export interface SeatSync {
  /** 请求一次权威重拉 */
  reconcile: (reason: ReconcileReason) => void
  /** 连续失败到放弃时为 true —— UI 用它提示「同步失败」 */
  failed: Ref<boolean>
  /** 清理所有 timer(卸载时自动调用) */
  dispose: () => void
}

/**
 * 创建权威重拉调度器。
 *
 * <p>两个触发点:
 * <ul>
 *   <li>释放事件之后</li>
 *   <li>WS 从 closed 翻到 open 之后(由调用方判定, 本模块不碰 WS 状态机)</li>
 * </ul>
 *
 * <p>防抖合并突发事件; 失败按退避表重试, 连续失败超出退避表就放弃并置 `failed`。
 */
export function useSeatSync(options: SeatSyncOptions): SeatSync {
  const failed = ref(false)
  let state: ReconcileState = INITIAL_RECONCILE_STATE
  let debounceTimer: number | null = null
  let backoffTimer: number | null = null

  function clearTimers() {
    if (debounceTimer != null) {
      window.clearTimeout(debounceTimer)
      debounceTimer = null
    }
    if (backoffTimer != null) {
      window.clearTimeout(backoffTimer)
      backoffTimer = null
    }
  }

  async function run(): Promise<void> {
    debounceTimer = null
    // 提交锁座过程中跳过: 避免与锁座响应相互覆盖
    if (options.isBusy()) return
    try {
      const lost = await options.refresh(true)
      state = advanceReconcile(state, 'success')
      failed.value = state.failed
      if (lost > 0) options.onSeatsTaken(lost)
    } catch {
      state = advanceReconcile(state, 'failure')
      failed.value = state.failed
      const plan = computeReconcilePlan(state.failures)
      if (plan.kind === 'retry') {
        backoffTimer = window.setTimeout(() => {
          backoffTimer = null
          void run()
        }, plan.delayMs)
      }
      // give-up 时 failed 为 true —— 状态条据此提示用户
    }
  }

  function schedule(): void {
    if (debounceTimer != null) window.clearTimeout(debounceTimer)
    debounceTimer = window.setTimeout(() => {
      void run()
    }, RECONCILE_DEBOUNCE_MS)
  }

  function reconcile(_reason: ReconcileReason): void {
    schedule()
  }

  function dispose(): void {
    clearTimers()
  }

  // 组件内调用时自动在卸载时清理; 在组件外(单测)调用则不注册钩子。
  if (getCurrentInstance()) {
    onBeforeUnmount(dispose)
  }

  return { reconcile, failed, dispose }
}
