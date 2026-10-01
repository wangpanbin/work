<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import BrandMark from '../components/BrandMark.vue'

const router = useRouter()
const { t } = useI18n()
</script>

<template>
  <div class="not-found">
    <BrandMark class="mark" :size="64" />
    <div class="poster">
      <div class="poster-inner">
        <div class="film-strip">
          <span v-for="i in 8" :key="i" class="hole" />
        </div>
        <div class="emoji"><BrandMark :size="44" /></div>
        <div class="film-strip">
          <span v-for="i in 8" :key="i" class="hole" />
        </div>
      </div>
    </div>
    <h1 class="code">{{ t('notFound.code') }}</h1>
    <h2 class="title">{{ t('notFound.title') }}</h2>
    <p class="desc">{{ t('notFound.subtitle') }}</p>
    <div class="actions">
      <el-button type="primary" size="large" @click="router.push('/')">{{ t('notFound.backHome') }}</el-button>
      <el-button @click="router.back()">{{ t('common.back') }}</el-button>
    </div>
  </div>
</template>

<style scoped>
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

/* 品牌标识: 票根 + 齿孔 + 条码, 与全站 header 同一枚 */
.mark {
  display: block;
  color: var(--accent);
  margin-bottom: 24px;
}

.poster {
  position: relative;
  margin-bottom: 24px;
}

.poster-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 32px 48px;
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
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

.emoji {
  font-size: 72px;
  line-height: 1;
}

/* 404 票号: 等宽 + tabular-nums */
.code {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 64px;
  font-weight: 700;
  color: var(--accent);
  margin: 0 0 8px;
  letter-spacing: 4px;
}

.title {
  font-family: var(--font-display);
  font-size: 22px;
  color: var(--ink);
  margin: 0 0 12px;
}

.desc {
  color: var(--ink-3);
  font-size: 14px;
  max-width: 360px;
  margin-bottom: 28px;
  line-height: 1.6;
}

.actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: center;
}
</style>
