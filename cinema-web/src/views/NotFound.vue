<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ROUTE_PATHS } from '../constants/routes'
import BrandMark from '../components/BrandMark.vue'

const router = useRouter()
const { t } = useI18n()

/**
 * 浏览器历史里**确有站内上一页**吗?
 *
 * <p>判据取 vue-router 写在 `history.state.back` 上的「站内上一页路径」:
 * 首次导航时它是 undefined, 从站内某个页面跳过来时它是那个页面的 fullPath。
 *
 * <p>⚠️ **不能用 `window.history.length > 1`** —— 那个数把**站外**的历史一起数,
 * 从外链直接进来时 length 也是 2, 据此判断会显示「返回上一页」,
 * 而 `router.back()` 会把用户带回外链(或空白页)—— 404 页把人带离本站。
 *
 * <p>没有站内上一页时, 只留「返回首页」。
 */
const canGoBack = !!window.history.state?.back
</script>

<template>
  <div class="not-found">
    <!-- 404 也是一张票: 票号就是 404, 齿孔在票面上缘(与全站票根同语言) -->
    <div class="ticket stub-perf-x">
      <div class="ticket-head">
        <BrandMark class="mark" :size="40" />
        <h1 class="title">{{ t('notFound.title') }}</h1>
        <div class="stub-no">
          <span class="stub-no-value mono">{{ t('notFound.code') }}</span>
        </div>
      </div>

      <div class="poster">
        <div class="poster-inner">
          <div class="film-strip">
            <span v-for="i in 8" :key="i" class="hole" />
          </div>
          <div class="ticket-mark"><BrandMark :size="44" /></div>
          <div class="film-strip">
            <span v-for="i in 8" :key="i" class="hole" />
          </div>
        </div>
      </div>

      <p class="desc">{{ t('notFound.subtitle') }}</p>

      <div class="actions">
        <el-button type="primary" size="large" @click="router.push(ROUTE_PATHS.home)">{{ t('notFound.backHome') }}</el-button>
        <!-- v-if: 只有确有站内上一页时才给「返回上一页」, 否则这一键会把用户带离本站 -->
        <el-button v-if="canGoBack" @click="router.back()">{{ t('common.back') }}</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 404 票面: 一张没有座位可售的票 —— 票号是 404 */
.not-found {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
  text-align: center;
  padding: 24px;
  animation: fadeInUp 0.5s ease;
}

.ticket {
  position: relative;
  width: 100%;
  max-width: 460px;
  padding: 28px 32px 32px;
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  /* 票面上缘一道印色条 */
  border-top: 4px solid var(--accent);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-paper);
}

/* 品牌标识: 票根 + 齿孔 + 条码, 与全站 header 同一枚 */
.mark {
  display: block;
  color: var(--accent);
  flex-shrink: 0;
}

.ticket-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

.title {
  font-family: var(--font-display);
  font-size: 18px;
  color: var(--ink);
  margin: 0;
  text-align: left;
}

/* 404 就是这张票的票号 */
.stub-no {
  margin-left: auto;
  flex-shrink: 0;
  padding: 3px 10px;
  border: 1px solid rgb(var(--accent-rgb) / 0.35);
  border-radius: var(--radius-sm);
  background: rgb(var(--accent-rgb) / 0.08);
}

.stub-no-value {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 18px;
  font-weight: 700;
  color: var(--accent);
  letter-spacing: 1px;
}

.poster {
  position: relative;
  margin-bottom: 20px;
}

.poster-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 28px 44px;
  background: var(--paper-sunk);
  border: 1px solid var(--rule);
  border-radius: var(--radius-md);
}

.film-strip {
  display: flex;
  gap: 6px;
}

.hole {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--paper);
  border: 1px solid var(--rule-strong);
}

.ticket-mark {
  font-size: 72px;
  line-height: 1;
}

.desc {
  color: var(--ink-3);
  font-size: 14px;
  max-width: 360px;
  margin: 0 auto 24px;
  line-height: 1.6;
}

.actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: center;
}
</style>
