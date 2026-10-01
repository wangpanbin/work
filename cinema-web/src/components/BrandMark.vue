<script setup lang="ts">
/**
 * 品牌标识 —— 票根 + 齿孔撕口 + 条码。
 *
 * 票根主题的签名装置之一, 替代原先 header 里的 🎬 emoji。
 * 用 `currentColor` 取色, 调用方通过 CSS `color` 控制颜色:
 * 默认 header 用朱红 (main.css 的 .brand-mark), 深色底上用 --ink-inverse。
 */
const props = withDefaults(defineProps<{ size?: number }>(), { size: 24 })
</script>

<template>
  <svg
    class="brand-mark"
    :width="props.size"
    :height="Math.round(props.size * 0.667)"
    viewBox="0 0 36 24"
    fill="none"
    aria-hidden="true"
    focusable="false"
  >
    <!-- 票面外框 -->
    <rect
      x="1"
      y="1"
      width="34"
      height="22"
      rx="1.5"
      stroke="currentColor"
      stroke-width="1.6"
    />
    <!-- 齿孔撕口: 虚线, 存根联 / 副券的物理边界 -->
    <line
      x1="12"
      y1="4"
      x2="12"
      y2="20"
      stroke="currentColor"
      stroke-width="1.8"
      stroke-linecap="round"
      stroke-dasharray="0.1 3"
    />
    <!-- 条码 -->
    <rect x="15.5" y="7" width="1.6" height="10" fill="currentColor" />
    <rect x="18.6" y="7" width="1" height="10" fill="currentColor" />
    <rect x="21.1" y="7" width="2.2" height="10" fill="currentColor" />
    <rect x="24.8" y="7" width="1" height="10" fill="currentColor" />
    <rect x="27.3" y="7" width="1.6" height="10" fill="currentColor" />
    <rect x="30.4" y="7" width="1" height="10" fill="currentColor" />
  </svg>
</template>
