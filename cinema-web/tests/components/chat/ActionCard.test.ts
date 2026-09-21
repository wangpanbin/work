import { describe, it, expect } from 'vitest'
import { buildSeatRoute } from '../../../src/components/chat/actionCardRoute'
import type { ActionCardVO } from '../../../src/types'

/**
 * ActionCard 跳转参数构造单测(spec T6 acceptance §9.2 第 3 条).
 *
 * <p>关键不变量:
 * <ul>
 *   <li>{@code card.sessionId} 保持字符串 — 雪花 ID 超 2^53</li>
 *   <li>{@code card.seatIndexes} 转为逗号分隔字符串作 {@code preselect} query</li>
 *   <li>路由名固定 {@code 'seat-select'}</li>
 * </ul>
 *
 * <p>注意:ActionCard.vue 是 Vue SFC,但 vitest 可以从 .vue 文件 import named exports
 * (rollup/vue-plugin 支持)。Vue 组件默认 export 是在 SFC 上 <script setup> 内 export 即可。
 */
describe('ActionCard — buildSeatRoute (spec §7.2)', () => {
  it('sessionId 保持字符串(雪花 ID 不转 number)', () => {
    const card: ActionCardVO = {
      type: 'SEAT_SUGGESTION',
      sessionId: '99999999999999999999', // 远超 2^53
      seatIndexes: [52, 53],
    }
    const route = buildSeatRoute(card)
    expect(typeof route.params.sessionId).toBe('string')
    expect(route.params.sessionId).toBe('99999999999999999999')
  })

  it('seatIndexes 转为逗号分隔字符串作 preselect query', () => {
    const route = buildSeatRoute({
      type: 'SEAT_SUGGESTION',
      sessionId: '1001',
      seatIndexes: [52, 53, 54],
    })
    expect(route.query).toEqual({ preselect: '52,53,54' })
  })

  it('seatIndexes 为空 → 不带 query (避免空字符串 query 污染 URL)', () => {
    const route = buildSeatRoute({
      type: 'SEAT_SUGGESTION',
      sessionId: '1001',
      seatIndexes: [],
    })
    expect(route.query).toBeUndefined()
  })

  it('seatIndexes 为 undefined → 不带 query (同上)', () => {
    const route = buildSeatRoute({
      type: 'SEAT_SUGGESTION',
      sessionId: '1001',
      // seatIndexes: undefined
    })
    expect(route.query).toBeUndefined()
  })

  it('路由名固定 seat-select', () => {
    const route = buildSeatRoute({
      type: 'SEAT_SUGGESTION',
      sessionId: '1001',
      seatIndexes: [52],
    })
    expect(route.name).toBe('seat-select')
  })

  it('单座位 → preselect=单值字符串(不丢尾)', () => {
    const route = buildSeatRoute({
      type: 'SEAT_SUGGESTION',
      sessionId: '1001',
      seatIndexes: [999],
    })
    expect(route.query?.preselect).toBe('999')
  })
})