import type { ActionCardVO } from '../../types'

/**
 * 纯函数:构造 ActionCard 跳转路由(spec §7.2 + ADR-0002).
 *
 * <p>关键不变量(可被 ActionCard.test.ts 验证):
 * <ul>
 *   <li>{@code card.sessionId} 保持字符串 — 雪花 ID 超 2^53,前端 URL 走 String</li>
 *   <li>{@code card.seatIndexes} 转为逗号分隔字符串作 {@code preselect} query</li>
 *   <li>路由名固定 {@code 'seat-select'}</li>
 * </ul>
 *
 * <p>独立 export 是为了测试 — ActionCard.vue onClick 调它,测试可直接调,
 * 不依赖 vue-router mock。<b>不能直接放进 ActionCard.vue</b>,因为
 * {@code <script setup>} 不允许 ES module exports。
 */
export function buildSeatRoute(card: ActionCardVO): {
  name: string
  params: { sessionId: string }
  query?: { preselect: string }
} {
  const preselect = (card.seatIndexes ?? []).join(',')
  return {
    name: 'seat-select',
    params: { sessionId: card.sessionId }, // 保持字符串
    query: preselect ? { preselect } : undefined,
  }
}