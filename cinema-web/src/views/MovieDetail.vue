<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import dayjs from 'dayjs'
import { detail } from '../api/movie'
import { listByMovieAndDate } from '../api/session'
import { useMovieCache } from '../stores/movieCache'
import type { Movie, SessionVO } from '../types'
import { useUserStore } from '../stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const cache = useMovieCache()
const { t } = useI18n()

const movieId = route.params.id as string
const movie = ref<Movie | null>(null)
const sessions = ref<SessionVO[]>([])
const loading = ref(false)
const selectedDate = ref(dayjs().add(1, 'day').format('YYYY-MM-DD'))

const dateOptions = computed(() =>
  [0, 1, 2].map((d) => ({
    value: dayjs().add(d, 'day').format('YYYY-MM-DD'),
    label: d === 0 ? t('movie.dateToday') : d === 1 ? t('movie.dateTomorrow') : t('movie.dateDayAfter'),
  })),
)

async function loadMovie() {
  // P0-3: 命中缓存时先立即渲染, 再后台静默刷新拉新, 让 5min TTL 真正生效
  // 保持 movieId 为 string, 与 URL 路径 / cache key 类型一致; 后端雪花 ID 转 number 会丢精度
  const id = movieId
  const cached = cache.getMovieDetail(id)
  if (cached) {
    movie.value = cached
    detail(movieId).then((m) => {
      if (m) {
        movie.value = m
        cache.setMovieDetail(id, m)
      }
    }).catch(() => { /* 拦截器已提示 */ })
    return
  }
  const m = await detail(movieId)
  movie.value = m
  if (m) cache.setMovieDetail(id, m)
}

async function loadSessions() {
  // P0-3: 缓存命中也走 silent 刷新, 不再"命中即 return"
  loading.value = true
  try {
    const id = movieId
    const cached = cache.getSessionList(id, selectedDate.value)
    if (cached) {
      // 立即渲染缓存, 避免切换日期时骨架闪烁
      sessions.value = cached
      // 后台静默刷新
      listByMovieAndDate(movieId, selectedDate.value).then((list) => {
        sessions.value = list
        cache.setSessionList(id, selectedDate.value, list)
      }).catch(() => { /* 拦截器已提示 */ })
      return
    }
    const list = await listByMovieAndDate(movieId, selectedDate.value)
    sessions.value = list
    cache.setSessionList(id, selectedDate.value, list)
  } finally {
    loading.value = false
  }
}

watch(selectedDate, () => {
  loadSessions()
})

function goSeat(s: SessionVO) {
  if (!userStore.isLogin) {
    // P2-1:必须带 redirect,否则登录后落在首页,用户选场次的上下文全丢
    router.push({ path: '/login', query: { redirect: `/seat/${s.id}` } })
    return
  }
  router.push(`/seat/${s.id}`)
}

function fmt(t: string) {
  return dayjs(t).format('HH:mm')
}

onMounted(async () => {
  await loadMovie()
  await loadSessions()
})
</script>

<template>
  <div v-loading="loading" class="movie-detail">
    <template v-if="movie">
      <!-- Hero Section -->
      <section class="detail-hero">
        <div class="hero-bg"></div>
        <div class="movie-head">
          <div class="poster-wrapper">
            <div class="poster-glow"></div>
            <div class="poster">
              <img v-if="movie.poster" :src="movie.poster" :alt="movie.title" />
              <div v-else class="poster-fallback"><span>{{ movie.title }}</span></div>
            </div>
          </div>
          <div class="info">
            <div v-if="movie.status === 1" class="info-badge">{{ t('movie.nowPlaying') }}</div>
            <div v-else-if="movie.status === 0" class="info-badge coming-soon">{{ t('movie.comingSoon') }}</div>
            <h1 class="movie-title">{{ movie.title }}</h1>
            <p class="desc">{{ movie.description || t('movie.noDescription') }}</p>
            <div class="meta-tags">
              <span class="meta-tag">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
                  <circle cx="12" cy="12" r="10"/>
                  <polyline points="12 6 12 12 16 14"/>
                </svg>
                {{ t('movie.minutes', { n: movie.duration }) }}
              </span>
              <span v-if="movie.description" class="meta-tag">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
                  <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/>
                  <path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/>
                </svg>
                {{ movie.description.slice(0, 18) }}{{ movie.description.length > 18 ? t('movie.descEllipsis') : '' }}
              </span>
              <span v-else class="meta-tag">{{ t('movie.classic') }}</span>
            </div>
          </div>
        </div>
      </section>

      <!-- Sessions Section -->
      <section class="sessions-section">
        <div class="sessions-header">
          <h2 class="section-title">{{ t('movie.sectionTitle') }}</h2>
          <el-radio-group v-model="selectedDate" @change="loadSessions" class="date-picker">
            <el-radio-button v-for="d in dateOptions" :key="d.value" :value="d.value">{{ d.label }}</el-radio-button>
          </el-radio-group>
        </div>

        <el-empty v-if="!loading && sessions.length === 0" :description="t('movie.noSessions')" />

        <div class="session-list">
          <div v-for="(s, idx) in sessions" :key="s.id" class="session-card" :style="{ animationDelay: `${idx * 0.06}s` }">
            <div class="session-time">
              <div class="start-time">{{ fmt(s.startTime) }}</div>
              <div class="end-time">{{ fmt(s.endTime) }} {{ t('movie.endTime') }}</div>
            </div>
            <div class="session-info">
              <div class="hall-name">{{ s.hallName }}</div>
              <div class="cinema-name">{{ s.cinemaName }}</div>
            </div>
            <div class="session-price">
              <span class="price-symbol">{{ t('order.currency') }}</span>
              <span class="price-value">{{ s.price.toFixed(2) }}</span>
            </div>
            <el-button type="primary" size="large" class="buy-btn" @click="goSeat(s)">{{ t('movie.goSeats') }}</el-button>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.movie-detail {
  animation: fadeInUp 0.5s ease;
}

/* --- Hero Section --- */
.detail-hero {
  position: relative;
  border-radius: var(--radius-lg);
  overflow: hidden;
  margin-bottom: 40px;
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  padding: 48px;
}

/* 原稿是两层 gold/purple radial 光斑, 纸面风删掉 —— 换成一道极淡的印章叠印 */
.hero-bg {
  position: absolute;
  inset: 0;
  background: var(--accent-stamp);
}

.movie-head {
  position: relative;
  z-index: 2;
  display: flex;
  gap: 40px;
  align-items: flex-start;
}

.poster-wrapper {
  position: relative;
  flex-shrink: 0;
}

/* 原稿是金色渐变 + blur(24px) 的辉光底, 纸面风删掉 */
.poster-glow {
  display: none;
}

.poster {
  width: 240px;
  height: 340px;
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--paper-sunk);
  border: 1px solid var(--rule);
  box-shadow: var(--shadow-paper);
}

.poster img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* 海报兜底: 原稿是紫蓝渐变, 与新色板无任何关系, 改纯纸面 + 墨字 */
.poster-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 700;
  color: var(--ink-2);
  text-align: center;
  padding: 20px;
  background: var(--paper-sunk);
  border: 1px dashed var(--rule-strong);
}

.info {
  flex: 1;
  padding-top: 8px;
}

.info-badge {
  display: inline-block;
  font-family: var(--font-display);
  font-size: 10px;
  letter-spacing: 4px;
  color: var(--accent);
  border: 1px solid var(--accent);
  padding: 4px 12px;
  border-radius: var(--radius-sm);
  margin-bottom: 16px;
}

/* 原稿是 --accent-cyan 青, 票根色板里没有青 —— 改中性墨色区分"即将上映" */
.info-badge.coming-soon {
  color: var(--ink-2);
  border-color: var(--rule-strong);
}

.movie-title {
  font-family: var(--font-display);
  font-size: 36px;
  font-weight: 700;
  color: var(--ink);
  letter-spacing: 2px;
  margin-bottom: 16px;
  line-height: 1.2;
}

.desc {
  color: var(--ink-2);
  line-height: 1.8;
  margin-bottom: 20px;
  font-size: 15px;
}

.meta-tags {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.meta-tag {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  background: var(--paper-sunk);
  border: 1px solid var(--rule);
  border-radius: var(--radius-sm);
  font-size: 13px;
  color: var(--ink-2);
}

/* --- Sessions Section --- */
.sessions-section {
  animation: fadeInUp 0.6s ease 0.2s both;
}

.sessions-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
  flex-wrap: wrap;
  gap: 16px;
}

.sessions-header .section-title {
  margin-bottom: 0;
}

.date-picker {
  flex-shrink: 0;
}

/* --- Session List --- */
.session-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.session-card {
  display: flex;
  align-items: center;
  gap: 28px;
  padding: 20px 28px;
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-paper);
  cursor: pointer;
  transition: border-color var(--transition-normal);
  animation: fadeInUp 0.4s ease both;
}

/* 印刷风不做位移, 只提亮边框 */
.session-card:hover {
  border-color: var(--rule-strong);
}

.session-time {
  text-align: left;
  min-width: 80px;
}

/* 开场时间是票据数据 → 等宽 */
.start-time {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 28px;
  font-weight: 600;
  color: var(--ink);
  line-height: 1;
}

.end-time {
  color: var(--ink-3);
  font-size: 13px;
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  margin-top: 6px;
}

.session-info {
  flex: 1;
}

.hall-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 4px;
}

.cinema-name {
  color: var(--ink-3);
  font-size: 13px;
}

.session-price {
  display: flex;
  align-items: baseline;
  gap: 2px;
  margin-right: 12px;
}

.price-symbol {
  color: var(--accent);
  font-size: 16px;
  font-weight: 600;
}

/* 票价 = 票据数据 → 等宽 */
.price-value {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 26px;
  font-weight: 600;
  color: var(--accent);
}

.buy-btn {
  flex-shrink: 0;
}

/* --- Responsive --- */
@media (max-width: 768px) {
  .detail-hero {
    padding: 28px;
  }
  .movie-head {
    flex-direction: column;
    align-items: center;
    text-align: center;
  }
  .poster {
    width: 180px;
    height: 255px;
  }
  .movie-title {
    font-size: 26px;
  }
  .meta-tags {
    justify-content: center;
  }
  .sessions-header {
    flex-direction: column;
    align-items: flex-start;
  }
  .session-card {
    flex-wrap: wrap;
    gap: 16px;
    padding: 16px 20px;
  }
  .session-price {
    margin-right: 0;
  }
  .buy-btn {
    width: 100%;
  }
}

@media (max-width: 480px) {
  .detail-hero {
    padding: 20px;
    border-radius: var(--radius-lg);
    margin-bottom: 28px;
  }
  .movie-title {
    font-size: 22px;
  }
  .start-time {
    font-size: 22px;
  }
  .price-value {
    font-size: 22px;
  }
}
</style>
