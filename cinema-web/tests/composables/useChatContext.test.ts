import { describe, it, expect } from 'vitest'
import {
  isChatHiddenRoute,
  buildChatContext,
} from '../../src/composables/useChatContext'

/**
 * useChatContext 纯逻辑单测(spec T6 acceptance §9.2 第 2 条).
 *
 * <p>composable 本身依赖 vue-router + pinia 难测;抽出纯函数
 * {@link isChatHiddenRoute} / {@link buildChatContext} 让测试不依赖
 * vue context。组件层 `useChatContext()` 内部就是调用它们。
 */
describe('useChatContext 纯逻辑 — isChatHiddenRoute (spec §7.1)', () => {
  it('/payment 在隐藏列表(倒计时不能被挡)', () => {
    expect(isChatHiddenRoute('/payment')).toBe(true)
  })

  it('/admin/* 路由全部隐藏(/admin 表格操作区)', () => {
    expect(isChatHiddenRoute('/admin')).toBe(true)
    expect(isChatHiddenRoute('/admin/dashboard')).toBe(true)
    expect(isChatHiddenRoute('/admin/movies')).toBe(true)
  })

  it('/movies、/movie/:id、/seat/:sessionId 不隐藏', () => {
    expect(isChatHiddenRoute('/movies')).toBe(false)
    expect(isChatHiddenRoute('/movie/3')).toBe(false)
    expect(isChatHiddenRoute('/seat/1001')).toBe(false)
    expect(isChatHiddenRoute('/orders')).toBe(false)
    expect(isChatHiddenRoute('/')).toBe(false)
  })
})

describe('useChatContext 纯逻辑 — buildChatContext (spec §6.1)', () => {
  it('/seat/:sessionId 路由 + 用户选了 2 个座位 → 透出 seatCount=2 + selectedSeatIndexes=[..]', () => {
    const ctx = buildChatContext(
      '/seat/1001',
      { sessionId: '1001' },
      [52, 53],
    )
    expect(ctx.route).toBe('/seat/1001')
    expect(ctx.sessionId).toBe('1001')
    expect(ctx.seatCount).toBe(2)
    expect(ctx.selectedSeatIndexes).toEqual([52, 53])
  })

  it('/movies 路由(无 sessionId) → sessionId=null + selectedSeatIndexes=[]', () => {
    const ctx = buildChatContext('/movies', {}, [])
    expect(ctx.route).toBe('/movies')
    expect(ctx.sessionId).toBeNull()
    expect(ctx.seatCount).toBe(0)
    expect(ctx.selectedSeatIndexes).toEqual([])
  })

  it('selectedSeatIndexes 数组被防御性复制 — 外部改动不影响 ctx', () => {
    const input = [1, 2, 3]
    const ctx = buildChatContext('/seat/1001', { sessionId: '1001' }, input)
    input.push(999)
    expect(ctx.selectedSeatIndexes).toEqual([1, 2, 3])
  })

  it('sessionId 在 URL 里始终是字符串类型(雪花 ID 超 2^53 不能转 number)', () => {
    const ctx = buildChatContext(
      '/seat/99999999999999999999',
      { sessionId: '99999999999999999999' }, // 远超 2^53
      [],
    )
    expect(typeof ctx.sessionId).toBe('string')
    expect(ctx.sessionId).toBe('99999999999999999999')
  })
})