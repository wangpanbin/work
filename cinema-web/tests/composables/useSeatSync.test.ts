import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'
import {
  INITIAL_RECONCILE_STATE,
  RECONCILE_BACKOFF_MS,
  RECONCILE_DEBOUNCE_MS,
  advanceReconcile,
  computeReconcilePlan,
} from '../../src/composables/useSeatSync'

/**
 * 权威重拉调度器的退避策略契约。
 *
 * <p>**为什么把这块做成纯函数**: 本仓 vitest 跑在 `environment: 'node'`,
 * 没装 jsdom / `@vue/test-utils` —— 挂不上 SFC, 也没有 `window`。
 * 调度器本身依赖 `window.setTimeout`, 只能在真浏览器里验证;
 * 但**「失败几次之后放弃」「成功后计数归零」是纯逻辑**, 应该能在 node 环境锁死。
 * 这正是 2026-10-19 那个 bug 暴露的缺口: 当时只测了状态机谓词,
 * 补偿策略层一直裸奔, 只能靠浏览器验收发现「状态恢复了但一条 seat-map 都没有」。
 *
 * <p>接线本身(哪个事件触发重拉)用静态契约断言锁住 —— 沿用 `tests/utils/ws.test.ts` 的范式。
 */

describe('computeReconcilePlan — 退避序列', () => {
  it('第一次失败 → 第一档退避', () => {
    expect(computeReconcilePlan(1)).toEqual({ kind: 'retry', delayMs: RECONCILE_BACKOFF_MS[0] })
  })

  it('第二次失败 → 第二档退避', () => {
    expect(computeReconcilePlan(2)).toEqual({ kind: 'retry', delayMs: RECONCILE_BACKOFF_MS[1] })
  })

  it('第三次失败 → 第三档退避', () => {
    expect(computeReconcilePlan(3)).toEqual({ kind: 'retry', delayMs: RECONCILE_BACKOFF_MS[2] })
  })

  it('第四次失败 → 放弃(退避表用尽)', () => {
    expect(computeReconcilePlan(4)).toEqual({ kind: 'give-up' })
  })

  it('越界/非法值 → 放弃, 不返回负延迟', () => {
    expect(computeReconcilePlan(99)).toEqual({ kind: 'give-up' })
    expect(computeReconcilePlan(0)).toEqual({ kind: 'give-up' })
    expect(computeReconcilePlan(-1)).toEqual({ kind: 'give-up' })
  })

  it('退避档数与常量表一致(改表就要改这里的期望)', () => {
    expect(RECONCILE_BACKOFF_MS).toEqual([300, 1000, 3000])
    expect(RECONCILE_DEBOUNCE_MS).toBe(500)
  })
})

describe('advanceReconcile — 计数状态机', () => {
  it('成功 → 计数归零, 不再处于失败态', () => {
    const s = advanceReconcile({ failures: 2, failed: false }, 'success')
    expect(s).toEqual({ failures: 0, failed: false })
  })

  it('失败 → 计数 +1, 未超退避表时不算放弃', () => {
    const s = advanceReconcile(INITIAL_RECONCILE_STATE, 'failure')
    expect(s).toEqual({ failures: 1, failed: false })
  })

  it('连续失败超出退避表 → 标记放弃', () => {
    let s = INITIAL_RECONCILE_STATE
    for (let i = 0; i < 3; i++) s = advanceReconcile(s, 'failure')
    expect(s).toEqual({ failures: 3, failed: false })
    s = advanceReconcile(s, 'failure')
    expect(s).toEqual({ failures: 4, failed: true })
  })

  it('放弃之后一次成功 → 完全复位(下一次失败重新从第一档退避开始)', () => {
    let s = INITIAL_RECONCILE_STATE
    for (let i = 0; i < 4; i++) s = advanceReconcile(s, 'failure')
    expect(s.failed).toBe(true)
    s = advanceReconcile(s, 'success')
    expect(s).toEqual({ failures: 0, failed: false })
  })

  it('初始状态是「一次都没失败过」', () => {
    expect(INITIAL_RECONCILE_STATE).toEqual({ failures: 0, failed: false })
  })
})

describe('接线 — 静态契约', () => {
  const seatSelect = readFileSync(
    fileURLToPath(new URL('../../src/views/SeatSelect.vue', import.meta.url)),
    'utf-8',
  )

  it('SeatSelect 用的是调度器, 策略不再内联', () => {
    expect(seatSelect).toContain("import { useSeatSync } from '../composables/useSeatSync'")
    expect(seatSelect).toContain('useSeatSync({')
    // 旧的内联实现必须已经消失
    expect(seatSelect).not.toContain('scheduleReconcile')
    expect(seatSelect).not.toContain('RECONCILE_BACKOFF_MS')
    expect(seatSelect).not.toContain('RECONCILE_DEBOUNCE_MS')
  })

  it('⚠️ 回归锁: 重连补偿仍在 closed → open 跳变时触发', () => {
    // 少了这一条, 断连期间丢失的事件就永远补不回来 ——
    // 症状是状态条正常回到「实时同步中」, 但网络面板里一条 seat-map 都没有。
    expect(seatSelect).toContain("prev === 'closed'")
    expect(seatSelect).toContain("seatSync.reconcile('reconnect')")
  })

  it('释放事件也触发重拉', () => {
    expect(seatSelect).toContain("seatSync.reconcile('release')")
  })

  it('失败提示读的是调度器的 failed 状态', () => {
    expect(seatSelect).toContain('seatSync.failed.value')
  })
})
