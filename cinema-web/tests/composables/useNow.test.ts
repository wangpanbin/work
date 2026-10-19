import { afterEach, describe, expect, it, vi } from 'vitest'
import { useNow } from '../../src/composables/useNow'

/**
 * `useNow` 的定时器契约。
 *
 * <p>**为什么能在 node 环境测**: 本仓 vitest 跑在 `environment: 'node'`, 没有 `window` ——
 * 所以 `useNow` 刻意用全局 `setInterval` 而不是 `window.setInterval`。
 * 这样它在浏览器与 node 下都是同一份实现, 定时器行为也能用假定时器锁死。
 *
 * <p>**锁住三件事**:
 * <ol>
 *   <li>初始值就是当前时间</li>
 *   <li>时间推进时 `now` 跟着走</li>
 *   <li>`stop()` 之后不再走(非组件场景靠它收尾; 组件场景靠 onBeforeUnmount)</li>
 * </ol>
 */
describe('useNow — 反应式时钟', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('初始值 = 当前时间', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-19T08:00:00Z'))

    const { now, stop } = useNow(1000)

    expect(now.value).toBe(new Date('2026-10-19T08:00:00Z').getTime())
    stop()
  })

  it('定时器推进 → now 跟着前进', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-19T08:00:00Z'))

    const { now, stop } = useNow(1000)
    vi.advanceTimersByTime(3000)

    expect(now.value).toBe(new Date('2026-10-19T08:00:03Z').getTime())
    stop()
  })

  it('stop() 之后不再前进(定时器已清)', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-19T08:00:00Z'))

    const { now, stop } = useNow(1000)
    vi.advanceTimersByTime(1000)
    const before = now.value
    stop()
    vi.advanceTimersByTime(5000)

    expect(now.value).toBe(before)
  })

  it('自定义间隔: 10ms 的时钟比 1000ms 的时钟跟得更紧', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-19T08:00:00Z'))

    const { now, stop } = useNow(10)
    vi.advanceTimersByTime(30)

    expect(now.value).toBe(new Date('2026-10-19T08:00:00.030Z').getTime())
    stop()
  })
})
