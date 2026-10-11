<script setup lang="ts">
/**
 * chat 模块图标 —— 对话 / 关闭 / 发送。
 *
 * 本仓库图形资产的唯一形态是手写内联 SVG(9 处、0 个 .svg 文件、0 个图标库、
 * 0 处 el-icon),所以延续该做法,而不是引入图标库 —— 见 BrandMark.vue,
 * 它本身就是上一次「emoji → 手写 SVG」的产物。
 *
 * 放在一个组件里而不是三个文件,是为了让三个图标共用同一套 stroke 参数:
 * 既有 24×24 功能线图标(OrderList / SeatSelect / Payment 等 7 处)漏声明
 * stroke-linecap,走的是 butt 端点 + miter 尖角,与 BrandMark 的 round 端点
 * 是两套画法。本组件统一 round,让 chat 的三个图标与品牌线同族。
 *
 * 对话图标的最后一行画成点线(BrandMark.vue 里齿孔撕口的同一手法:
 * stroke-dasharray="0.1 n" + stroke-linecap="round"),是票根签名装置的第二次
 * 出现 —— 观感上只是「一行没打完的字」,不额外增加噪音。
 *
 * 颜色全部走 currentColor,由调用方的 CSS color 控制。
 */
const props = withDefaults(
  defineProps<{
    /** 图标语义。装饰性图标,语义由外层按钮的 aria-label 承担。 */
    name: 'chat' | 'close' | 'send'
    size?: number
  }>(),
  { size: 24 },
)
</script>

<template>
  <svg
    class="chat-icon"
    :width="props.size"
    :height="props.size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="2"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
  >
    <template v-if="props.name === 'chat'">
      <!--
        气泡 + 尾巴画成一条闭合 path,而不是 rect + 独立尾巴:
        尾巴必须长在底边上,否则会和 rect 的下边描边交叠成脏线。
        气泡本体 y 3..18,尾巴尖到 21.5 —— 加尾巴后整体上移,
        让含尾巴的视觉重心仍落在 24px 网格中心(实测 y 向范围 2..22.3)。
      -->
      <path
        d="M 6.5 3 H 17.5 A 3.5 3.5 0 0 1 21 6.5 V 14.5 A 3.5 3.5 0 0 1 17.5 18 H 10.5 L 6 21.5 L 8.8 18 H 6.5 A 3.5 3.5 0 0 1 3 14.5 V 6.5 A 3.5 3.5 0 0 1 6.5 3 Z"
      />
      <!-- 两行消息 -->
      <line x1="7" y1="7.5" x2="17" y2="7.5" />
      <line x1="7" y1="10.5" x2="17" y2="10.5" />
      <!-- 第三行:齿孔点线(票根签名装置) -->
      <line x1="7" y1="13.5" x2="13" y2="13.5" stroke-dasharray="0.1 2.6" />
    </template>

    <template v-else-if="props.name === 'close'">
      <line x1="6" y1="6" x2="18" y2="18" />
      <line x1="18" y1="6" x2="6" y2="18" />
    </template>

    <template v-else>
      <line x1="4" y1="12" x2="20" y2="12" />
      <polyline points="14 6 20 12 14 18" />
    </template>
  </svg>
</template>

<style scoped>
.chat-icon {
  display: block;
  flex-shrink: 0;
}
</style>