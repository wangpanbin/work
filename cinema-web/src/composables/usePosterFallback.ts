import { reactive } from 'vue'

/**
 * 海报加载失败降级。
 *
 * <p>体检发现(2026-10-11): 视图层的 `<img>` 只有 `v-if="poster"`，只挡得住「URL 为空」，
 * 挡不住「URL 有值但加载不出来」—— 演示现场断网、TMDB 抖动、本地海报被误删都属此类。
 * 此时浏览器画的是裂图图标，永远不会命中 `v-else` 的占位块，页面直接露出破损观感。
 *
 * <p>`@error` 触发后把该影片的 key 记进响应式 Set，模板改用
 * `v-if="poster && !isBroken(id)"` 判定，于是自然落回已有的占位块，样式零改动。
 *
 * <p>key 统一 `String()` 归一的原因：`Movie.id` 是雪花 ID(超 2^53)，前端全程用 string；
 * 万一某处传了 number，不归一就会漏判成「没坏」，于是又露出裂图。
 *
 * <p>用 `reactive(new Set())` 而非普通数组/对象：Vue 3 对集合有专门的响应式代理，
 * `add` / `has` 会被正确追踪 —— 这正是 `tests/composables/usePosterFallback.test.ts`
 * 里那条 watchEffect 用例守着的行为，换成普通对象就会红。
 */
export function usePosterFallback() {
  const broken = reactive(new Set<string>())

  /** 该影片的海报是否已确认加载失败(降级为占位块)。 */
  function isBroken(key: string | number): boolean {
    return broken.has(String(key))
  }

  /** 绑定到 `<img @error>`：标记该影片的海报不可用。 */
  function markBroken(key: string | number): void {
    broken.add(String(key))
  }

  return { isBroken, markBroken }
}