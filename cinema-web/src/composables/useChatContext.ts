import { computed, type ComputedRef } from 'vue'
import { useRoute } from 'vue-router'
import type { ChatContextDTO } from '../types'
import { useSeatStore } from '../stores/seat'

/**
 * 纯函数:判断浮窗是否应在当前路由隐藏(spec §7.1).
 * 默认收起路由:`/payment`(倒计时不能被挡)、`/admin/**`(表格操作区)。
 *
 * <p>独立 export 是为了测试不依赖 vue-router / pinia — 测试可直接调用。
 */
export function isChatHiddenRoute(path: string): boolean {
  return path === '/payment' || path.startsWith('/admin')
}

/**
 * 纯函数:从 route + seatStore 组装 ChatContextDTO(spec §6.1).
 *
 * <p>独立 export 是为了测试 — useChatContext composable 内部调用它,
 * 测试可直接调用,避免 vue-router / pinia mock 成本。
 */
export function buildChatContext(
  routePath: string,
  params: Record<string, string | string[] | undefined>,
  selectedSeatIndexes: number[],
): ChatContextDTO {
  return {
    route: routePath,
    sessionId: (params.sessionId as string | undefined) ?? null,
    seatCount: selectedSeatIndexes.length,
    selectedSeatIndexes: [...selectedSeatIndexes],
  }
}

/**
 * 组装聊天场次上下文 (spec §6.1 context + §7.1 路由感知).
 *
 * <p>字段来源:
 * <ul>
 *   <li>{@code route} — {@code useRoute().path}</li>
 *   <li>{@code sessionId} — {@code route.params.sessionId}(仅 {@code /seat/:sessionId} 时有值)</li>
 *   <li>{@code seatCount} / {@code selectedSeatIndexes} — {@code useSeatStore()}</li>
 * </ul>
 *
 * <p>默认收起浮窗的路由(spec §7.1):{@code /payment}(倒计时不能被挡)、
 * {@code /admin/**}(表格操作区)。这两个路由不构造 ChatContext(节省 payload)。
 */
export function useChatContext(): {
  context: ComputedRef<ChatContextDTO | null>
  isHidden: ComputedRef<boolean>
} {
  const route = useRoute()
  const seatStore = useSeatStore()

  const isHidden = computed(() => isChatHiddenRoute(route.path))

  const context = computed<ChatContextDTO | null>(() => {
    if (isHidden.value) return null
    return buildChatContext(route.path, route.params as Record<string, string | string[] | undefined>, Array.from(seatStore.selected))
  })

  return { context, isHidden }
}