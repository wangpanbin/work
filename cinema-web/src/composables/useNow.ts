import { getCurrentInstance, onBeforeUnmount, ref, type Ref } from 'vue'

/**
 * 反应式时钟 —— 每隔 `intervalMs` 把当前时间写进 `now`。
 *
 * <p>**为什么要它**: 需要「随时间推移而变化」的界面时, 直接在组件里写
 * `setInterval(() => now.value = Date.now(), 1000)` 有两个老问题:
 * <ul>
 *   <li>卸载时忘了清定时器 → 组件销毁后仍在跑, 内存泄漏 + 无效重渲</li>
 *   <li>多个组件各写一份 → 各漏一次</li>
 * </ul>
 * 本 composable 统一收口: 卸载自动清, 并额外返回 `stop()` 供非组件场景(单测)手动收。
 *
 * <p>**怎么用才不整页重渲**: 让模板只读**布尔量**(如 `expired` / `urgent`),
 * 而不要直接读秒数。Vue 的 computed 在值没变时不会触发渲染, 所以:
 * 模板读 `urgent` → 每秒重算但只在跨过 60 秒那一刻触发一次重渲;
 * 模板直接读 `remaining` → 每秒触发一次重渲。
 * 倒计时**文本**本身交给 {@code Countdown.vue} 渲染 —— 它自带定时器, 只有那一个
 * span 的文本在动, 父组件完全不参与。
 *
 * <p>本仓 vitest 跑在 `environment: 'node'`, 所以这里用全局 `setInterval`
 * (浏览器与 node 都有), 不写 `window.setInterval`。
 */
export function useNow(intervalMs = 1000): { now: Ref<number>; stop: () => void } {
  const now = ref(Date.now())
  const timer: ReturnType<typeof setInterval> = setInterval(() => {
    now.value = Date.now()
  }, intervalMs)

  const stop = () => {
    clearInterval(timer)
  }

  // 只有在组件实例里才注册卸载钩子; 单测等非组件场景用 stop() 手动收
  if (getCurrentInstance()) {
    onBeforeUnmount(stop)
  }

  return { now, stop }
}
