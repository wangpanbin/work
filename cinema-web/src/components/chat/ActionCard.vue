<script setup lang="ts">
import { useRouter } from 'vue-router'
import type { ActionCardVO } from '../../types'
import { buildSeatRoute } from './actionCardRoute'

const props = defineProps<{
  card: ActionCardVO
}>()

const router = useRouter()

/**
 * 跳到选座流程并预选座位(spec §7.2 + ADR-0002).
 *
 * <p>路由参数构造在 actionCardRoute.ts (独立文件,因为
 * {@code <script setup>} 不允许 ES module exports)。
 */
function onClick() {
  router.push(buildSeatRoute(props.card))
}
</script>

<template>
  <div class="action-card" @click="onClick">
    <div class="action-card-header">
      <span class="action-card-type">{{ card.type }}</span>
      <span class="action-card-price" v-if="card.price != null">¥{{ card.price }}</span>
    </div>
    <div class="action-card-body">
      <div class="action-card-title">{{ card.movieTitle ?? '推荐场次' }}</div>
      <div class="action-card-meta" v-if="card.hallName">{{ card.hallName }} · {{ card.startTime }}</div>
      <div class="action-card-meta" v-if="card.seatDesc">座位: {{ card.seatDesc }}</div>
      <div class="action-card-meta" v-if="card.totalAmount != null">合计: ¥{{ card.totalAmount }}</div>
    </div>
    <button class="action-card-btn" type="button">{{ card.actionLabel ?? '去选座确认' }}</button>
  </div>
</template>

<style scoped>
.action-card {
  border: 1px solid var(--border-color, #e5e5e5);
  border-radius: 10px;
  padding: 10px 12px;
  background: #fff;
  cursor: pointer;
  transition: all 0.2s;
}
.action-card:hover {
  border-color: var(--accent-gold, #f59e0b);
  box-shadow: 0 2px 8px rgba(245, 158, 11, 0.15);
}
.action-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}
.action-card-type {
  font-size: 11px;
  color: var(--text-muted, #999);
  letter-spacing: 0.5px;
}
.action-card-price {
  font-weight: 700;
  color: var(--accent-gold, #f59e0b);
  font-size: 14px;
}
.action-card-body {
  margin-bottom: 8px;
}
.action-card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary, #333);
}
.action-card-meta {
  font-size: 12px;
  color: var(--text-secondary, #666);
  margin-top: 2px;
}
.action-card-btn {
  background: var(--accent-gold, #f59e0b);
  color: #1a1a1a;
  border: none;
  border-radius: 6px;
  padding: 6px 12px;
  font-size: 12px;
  cursor: pointer;
  width: 100%;
}
.action-card-btn:hover {
  background: var(--accent-gold-light, #fbbf24);
}
</style>