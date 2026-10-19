import { describe, it, expect } from 'vitest'
import { watchEffect, nextTick } from 'vue'
import { usePosterFallback } from '../../src/composables/usePosterFallback'

/**
 * 海报加载失败降级逻辑单测。
 *
 * <p>背景(体检 2026-10-11): {@code Home.vue} / {@code MovieDetail.vue} 的 {@code <img>}
 * 只有 {@code v-if="poster"} —— 只挡住了「URL 为空」, 挡不住「URL 存在但加载不出来」
 * (演示现场断网 / TMDB 抖动 / 本地文件被误删)。此时浏览器画的是裂图图标,
 * 不会命中 {@code v-else} 的占位块, 页面直接露出破损观感。
 *
 * <p>本仓库 vitest 跑在 {@code environment: 'node'} 且没装 jsdom / @vue/test-utils,
 * 挂不了组件。判断逻辑因此下沉到 {@code usePosterFallback}, 在这条 seam 上测;
 * 「{@code @error} 确实绑到了 img 上」由 {@code tests/views/posterWiring.test.ts}
 * 做模板契约断言兜底。两处缺一不可, 详见该文件注释。
 */
describe('usePosterFallback — 海报加载失败降级', () => {
  it('初始状态: 任何 key 都未降级(正常走 <img>)', () => {
    const { isBroken } = usePosterFallback()

    expect(isBroken(113)).toBe(false)
  })

  it('markBroken 后对应 key 判定为降级(切到占位块)', () => {
    const { isBroken, markBroken } = usePosterFallback()

    markBroken(113)

    expect(isBroken(113)).toBe(true)
  })

  it('降级只影响被标记的那一个 key, 不牵连同页其他海报', () => {
    const { isBroken, markBroken } = usePosterFallback()

    markBroken(113)

    expect(isBroken(114)).toBe(false)
  })

  it('number / string 两种 key 形态归一为同一部影片', () => {
    const { isBroken, markBroken } = usePosterFallback()

    markBroken(113)

    expect(isBroken('113')).toBe(true)
  })

  it('雪花 ID 误传 number 时 String() 也救不回来 —— 前端必须全程用 string', () => {
    // 这里钉的是 JS 的精度事实本身, 而不是 isBroken 的返回值(断言返回值会把实现锁死)。
    // 雪花 ID 超 2^53, 任何"先转 number 再归一"的写法都在这里丢位, 后果是占位块
    // 不触发、页面又露出裂图。同一条约束也记在 AGENTS.md 的雪花 ID 小节。
    expect(String(2095719517377556481)).not.toBe('2095719517377556481')
  })

  it('重复 markBroken 幂等(滚动列表会反复触发 error)', () => {
    const { isBroken, markBroken } = usePosterFallback()

    markBroken(113)

    expect(() => markBroken(113)).not.toThrow()
    expect(isBroken(113)).toBe(true)
  })

  it('响应式: markBroken 会让依赖 isBroken 的渲染副作用重跑', async () => {
    const { isBroken, markBroken } = usePosterFallback()
    const seen: boolean[] = []

    // 模拟模板里 v-if="isBroken(m.id)" 的依赖收集
    const stop = watchEffect(() => {
      seen.push(isBroken(113))
    })

    markBroken(113)
    await nextTick()
    stop()

    // 两次求值: 标记前 false, 标记后 true。
    // 若 Set 换成普通对象/数组而没做响应式改造, 这里会只拿到 [false] 而挂掉。
    expect(seen).toEqual([false, true])
  })
})