<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
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

const movieId = route.params.id as string
const movie = ref<Movie | null>(null)
const sessions = ref<SessionVO[]>([])
const loading = ref(false)
const selectedDate = ref(dayjs().add(1, 'day').format('YYYY-MM-DD'))

const dateOptions = computed(() =>
  [0, 1, 2].map((d) => ({
    value: dayjs().add(d, 'day').format('YYYY-MM-DD'),
    label: d === 0 ? '今天' : d === 1 ? '明天' : '后天',
  })),
)

async function loadMovie() {
  // Phase D-⑯: 详情走缓存
  const id = Number(movieId)
  const cached = cache.getMovieDetail(id)
  if (cached) {
    movie.value = cached
    return
  }
  const m = await detail(movieId)
  movie.value = m
  if (m) cache.setMovieDetail(id, m)
}

async function loadSessions() {
  loading.value = true
  try {
    // Phase D-⑯: 场次列表按 (movieId, date) 缓存
    const id = Number(movieId)
    const cached = cache.getSessionList(id, selectedDate.value)
    if (cached) {
      sessions.value = cached
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
    router.push('/login')
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
            <div v-if="movie.status === 1" class="info-badge">NOW PLAYING</div>
            <div v-else-if="movie.status === 0" class="info-badge coming-soon">即将上映</div>
            <h1 class="movie-title">{{ movie.title }}</h1>
            <p class="desc">{{ movie.description || '暂无影片简介' }}</p>
            <div class="meta-tags">
              <span class="meta-tag">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
                  <circle cx="12" cy="12" r="10"/>
                  <polyline points="12 6 12 12 16 14"/>
                </svg>
                {{ movie.duration }} 分钟
              </span>
              <span v-if="movie.description" class="meta-tag">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
                  <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/>
                  <path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/>
                </svg>
                {{ movie.description.slice(0, 18) }}{{ movie.description.length > 18 ? '...' : '' }}
              </span>
              <span v-else class="meta-tag">经典影片</span>
            </div>
          </div>
        </div>
      </section>

      <!-- Sessions Section -->
      <section class="sessions-section">
        <div class="sessions-header">
          <h2 class="section-title">🎟 选择场次</h2>
          <el-radio-group v-model="selectedDate" @change="loadSessions" class="date-picker">
            <el-radio-button v-for="d in dateOptions" :key="d.value" :value="d.value">{{ d.label }}</el-radio-button>
          </el-radio-group>
        </div>

        <el-empty v-if="!loading && sessions.length === 0" description="该日期暂无场次" />

        <div class="session-list">
          <div v-for="(s, idx) in sessions" :key="s.id" class="session-card" :style="{ animationDelay: `${idx * 0.06}s` }">
            <div class="session-time">
              <div class="start-time">{{ fmt(s.startTime) }}</div>
              <div class="end-time">{{ fmt(s.endTime) }} 散场</div>
            </div>
            <div class="session-info">
              <div class="hall-name">{{ s.hallName }}</div>
              <div class="cinema-name">{{ s.cinemaName }}</div>
            </div>
            <div class="session-price">
              <span class="price-symbol">￥</span>
              <span class="price-value">{{ s.price.toFixed(2) }}</span>
            </div>
            <el-button type="primary" size="large" class="buy-btn" @click="goSeat(s)">选座购票</el-button>
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
  border-radius: var(--radius-xl);
  overflow: hidden;
  margin-bottom: 40px;
  background: var(--gradient-hero);
  padding: 48px;
}

.hero-bg {
  position: absolute;
  inset: 0;
  background-image:
    radial-gradient(circle at 30% 70%, rgba(245, 158, 11, 0.18) 0%, transparent 50%),
    radial-gradient(circle at 70% 30%, rgba(168, 85, 247, 0.12) 0%, transparent 50%);
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

.poster-glow {
  position: absolute;
  inset: -10px;
  background: var(--gradient-gold);
  border-radius: var(--radius-lg);
  filter: blur(24px);
  opacity: 0.3;
  z-index: -1;
}

.poster {
  width: 240px;
  height: 340px;
  border-radius: var(--radius-lg);
  overflow: hidden;
  background: var(--bg-tertiary);
  box-shadow: var(--shadow-lg);
}

.poster img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.poster-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
  text-align: center;
  padding: 20px;
  background: linear-gradient(135deg, #1e1b4b 0%, #4c1d95 100%);
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
  color: var(--accent-gold);
  border: 1px solid var(--accent-gold);
  padding: 4px 12px;
  border-radius: 16px;
  margin-bottom: 16px;
}

.info-badge.coming-soon {
  color: var(--accent-cyan);
  border-color: var(--accent-cyan);
}

.movie-title {
  font-family: var(--font-display);
  font-size: 36px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: 2px;
  margin-bottom: 16px;
  line-height: 1.2;
}

.desc {
  color: var(--text-secondary);
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
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid var(--border-subtle);
  border-radius: 20px;
  font-size: 13px;
  color: var(--text-secondary);
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
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  cursor: pointer;
  transition: all var(--transition-normal);
  animation: fadeInUp 0.4s ease both;
}

.session-card:hover {
  border-color: rgba(245, 158, 11, 0.4);
  transform: translateX(4px);
  box-shadow: var(--shadow-md);
}

.session-time {
  text-align: left;
  min-width: 80px;
}

.start-time {
  font-family: var(--font-display);
  font-size: 28px;
  font-weight: 700;
  color: var(--accent-gold);
  line-height: 1;
}

.end-time {
  color: var(--text-muted);
  font-size: 13px;
  margin-top: 6px;
}

.session-info {
  flex: 1;
}

.hall-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 4px;
}

.cinema-name {
  color: var(--text-muted);
  font-size: 13px;
}

.session-price {
  display: flex;
  align-items: baseline;
  gap: 2px;
  margin-right: 12px;
}

.price-symbol {
  color: var(--accent-red);
  font-size: 16px;
  font-weight: 600;
}

.price-value {
  font-family: var(--font-display);
  font-size: 26px;
  font-weight: 700;
  color: var(--accent-red);
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
