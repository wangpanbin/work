<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import BrandMark from '../components/BrandMark.vue'

const router = useRouter()
const { t } = useI18n()
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
        <el-button type="primary" size="large" @click="router.push('/')">{{ t('notFound.backHome') }}</el-button>
        <el-button @click="router.back()">{{ t('common.back') }}</el-button>
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
