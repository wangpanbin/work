<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import dayjs from 'dayjs'
import { detail } from '../api/movie'
import { listByMovieAndDate } from '../api/session'
import { useMovieCache } from '../stores/movieCache'
import { toIdString, type Movie, type SessionVO } from '../types'
import { useUserStore } from '../stores/user'
import { usePosterFallback } from '../composables/usePosterFallback'
import { Clock, Document } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const cache = useMovieCache()
const { t } = useI18n()
const { isBroken: isPosterBroken, markBroken: markPosterBroken } = usePosterFallback()

const movieId = route.params.id as string
const movie = ref<Movie | null>(null)
const sessions = ref<SessionVO[]>([])
const loading = ref(false)
const selectedDate = ref(dayjs().add(1, 'day').format('YYYY-MM-DD'))
/**
 * 加载失败态(2026-10-24)。
 *
 * <p>原先影片详情一失败, `onMounted` 里的 `await loadMovie()` 就把整个挂载流程打断 ——
 * 既拿不到影片, `loadSessions()` 也根本没跑, 模板根节点又是 `v-if="movie"` → **整页空白**,
 * 没有报错、没有重试入口。而「暂无场次」与「请求失败」在界面上长得一模一样。
 */
const loadError = ref<string | null>(null)
const sessionsError = ref<string | null>(null)

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
  try {
    const m = await detail(movieId)
    movie.value = m
    if (m) cache.setMovieDetail(id, m)
    loadError.value = null
  } catch (e) {
    loadError.value = t('movie.loadFailed')
    throw e
  }
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
      // 缓存分支提前 return, 但错误态必须一起复位 ——
      // 否则上一个日期拉取失败的提示会一直挂在新日期的场次列表上方。
      sessionsError.value = null
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
    sessionsError.value = null
  } catch (e) {
    // 区分「真的没有场次」与「请求失败」—— 两者在界面上不能长得一样
    sessionsError.value = t('movie.sessionsLoadFailed')
    throw e
  } finally {
    loading.value = false
  }
}

/**
 * 切换场次日期。
 *
 * <p><b>只由 watch 驱动</b>(2026-10-24): 原先 `watch(selectedDate)` 与
 * `<el-radio-group @change="loadSessions">` 各触发一次 —— 切一个日期发**两次**
 * 相同请求, 两个响应还可能先后到达互相覆盖。
 */
watch(selectedDate, () => {
  loadSessions().catch(() => { /* 错误态已记录 */ })
})

function goSeat(s: SessionVO) {
  if (!userStore.isLogin) {
    // P2-1:必须带 redirect,否则登录后落在首页,用户选场次的上下文全丢
    router.push({ path: '/login', query: { redirect: `/seat/${toIdString(s.id)}` } })
    return
  }
  router.push(`/seat/${toIdString(s.id)}`)
}

function fmt(t: string) {
  return dayjs(t).format('HH:mm')
}

onMounted(async () => {
  // 两个加载都各自兜住 —— 影片失败不该连场次都不拉(2026-10-24)
  try {
    await loadMovie()
  } catch {
    // 错误态已记录在 loadError
  }
  try {
    await loadSessions()
  } catch {
    // 错误态已记录在 sessionsError
  }
})

/** 错误态上的「重试」—— 重拉影片详情。 */
async function reloadMovie() {
  try {
    await loadMovie()
  } catch {
    // 错误态已更新, 保持在页面上
  }
}

/** 错误态上的「重试」—— 重拉场次列表。 */
async function reloadSessions() {
  try {
    await loadSessions()
  } catch {
    // 错误态已更新, 保持在页面上
  }
}
</script>

<template>
  <!-- 影片详情加载失败: 错误态 + 重试, 不再是一整页空白(2026-10-24) -->
  <div v-if="loadError && !movie" class="load-error" role="alert">
    <p class="load-error-text">{{ loadError }}</p>
    <el-button type="primary" @click="reloadMovie">{{ t('common.retry') }}</el-button>
  </div>
  <div v-loading="loading" class="movie-detail">
    <template v-if="movie">
      <!-- Hero Section -->
      <section class="detail-hero">
        <div class="movie-head">
          <div class="poster-wrapper">
            <div class="poster-glow"></div>
            <div class="poster">
              <img
                v-if="movie.poster && !isPosterBroken(movie.id)"
                :src="movie.poster"
                :alt="movie.title"
                @error="markPosterBroken(movie.id)"
              />
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
                <el-icon :size="16"><Clock /></el-icon>
                {{ t('movie.minutes', { n: movie.duration }) }}
              </span>
              <span v-if="movie.description" class="meta-tag">
                <el-icon :size="16"><Document /></el-icon>
                {{ movie.description.slice(0, 18) }}{{ movie.description.length > 18 ? t('movie.descEllipsis') : '' }}
              </span>
              <span v-else class="meta-tag">{{ t('movie.classic') }}</span>
            </div>
          </div>
        </div>
        <!-- 场次票号: 右下角一枚大号等宽数字, 票根上最像"编号"的东西 -->
        <div class="hero-tally">
          <span class="hero-tally-num">{{ sessions.length }}</span>
          <span class="hero-tally-label">{{ t('movie.sessionCount') }}</span>
        </div>
      </section>

      <!-- Sessions Section -->
      <section class="sessions-section">
        <div class="sessions-header">
          <h2 class="section-title">{{ t('movie.sectionTitle') }}</h2>
          <el-radio-group v-model="selectedDate" class="date-picker">
            <el-radio-button v-for="d in dateOptions" :key="d.value" :value="d.value">{{ d.label }}</el-radio-button>
          </el-radio-group>
        </div>

        <div v-if="sessionsError && !loading" class="sessions-error" role="alert">
          <p class="sessions-error-text">{{ sessionsError }}</p>
          <el-button @click="reloadSessions">{{ t('common.retry') }}</el-button>
        </div>
        <el-empty v-else-if="!loading && sessions.length === 0" :description="t('movie.noSessions')" />

        <!-- v-else: 与错误块/空态互斥 —— 加载失败时不再同时渲染上一个日期的旧场次 -->
        <div v-else class="session-list">
          <div v-for="(s, idx) in sessions" :key="s.id" class="session-card card-reveal" :style="{ animationDelay: `${idx * 0.06}s` }">
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
/* 加载失败态(2026-10-24): 居中的错误提示 + 重试, 不再是一整页空白。
   整页加载失败的样式在 main.css 的共享 `.load-error` —— 五个视图共用同一份, 不再各抄一份。 */
/* ⚠️ 下面这两个是**另一回事**: 场次区加载失败(影片详情出来了, 但场次没加载出来),
   必须与「暂无场次」区分开 —— 两者不能长得一样。
   所以它不是 `.load-error` 的副本, 是刻意更紧凑的一个独立状态。 */
.sessions-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 28px 16px;
  text-align: center;
}
.sessions-error-text {
  margin: 0;
  font-size: 14px;
  color: var(--ink-2);
}

.movie-detail {
  animation: fadeInUp 0.5s ease;
}

/* --- Hero Section --- */
.detail-hero {
  position: relative;
  display: flex;
  flex-direction: column;
  border-radius: var(--radius-lg);
  overflow: hidden;
  margin-bottom: 40px;
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  padding: 48px;
}

/* 顶部一道浏览域印色条: 原来是整块 accent-stamp 铺底, 那是最"一片蓝"的地方。
   同样占位, 但 4px 印色条 + 右下角一枚大号数字有主次。 */
.detail-hero::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 4px;
  background: var(--scene-browse);
}

.movie-head {
  position: relative;
  z-index: 2;
  display: flex;
  gap: 40px;
  /* 海报与信息垂直居中: 顶对齐会在右侧留一大块空洞 */
  align-items: center;
}

/* 海报压左 38%, 信息占右侧 62% —— 原稿是「240px 海报 + 剩余空间」, 比例由内容长度决定, 看起来总是一样 */
.poster-wrapper {
  position: relative;
  flex: 0 0 38%;
  max-width: 300px;
}

/* 原稿是金色渐变 + blur(24px) 的辉光底, 纸面风删掉 */
.poster-glow {
  display: none;
}

.poster {
  width: 100%;
  height: auto;
  aspect-ratio: 3 / 4;
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
}

.info-badge {
  display: inline-block;
  font-family: var(--font-display);
  font-size: 10px;
  letter-spacing: 4px;
  color: var(--scene-browse-ink);
  border: 1px solid var(--scene-browse-ink);
  background: rgb(var(--scene-browse-rgb) / 0.1);
  padding: 4px 12px;
  border-radius: var(--radius-sm);
  margin-bottom: 16px;
}

/* 原稿是 --accent-cyan 青, 票根色板里没有青 —— 改中性墨色区分"即将上映" */
.info-badge.coming-soon {
  color: var(--ink-2);
  border-color: var(--rule-strong);
  background: var(--paper-sunk);
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
  /* 影片描述常含分段换行, 不加这条会被折叠成一整段 */
  white-space: pre-wrap;
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

/* 场次票号: 右下角一枚大号等宽数字, 票根上最像"编号"的东西 */
.hero-tally {
  align-self: flex-end;
  margin-top: 28px;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  line-height: 1;
}
.hero-tally-num {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 56px;
  font-weight: 600;
  color: var(--scene-browse-ink);
  opacity: 0.22;
}
.hero-tally-label {
  margin-top: 8px;
  font-size: 11px;
  letter-spacing: 2px;
  color: var(--ink-3);
}

/* --- Sessions Section --- */
.sessions-section {
  animation: fadeInUp 0.6s ease 0.2s backwards;
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

/* 章节墨条取浏览域身份色(与 Home 同一套语言)—— 见 DESIGN.md The Scene Ink Rule 第 1 条。 */
.section-title::before {
  background: var(--scene-browse-ink);
}

/* 日期票签: 三张并列的小票, 底边虚线当撕口 */
.date-picker {
  flex-shrink: 0;
  display: flex;
  gap: 8px;
}
.date-picker :deep(.el-radio-button) {
  margin-right: 0;
}
.date-picker :deep(.el-radio-button__inner) {
  font-family: var(--font-mono);
  font-size: 12px;
  letter-spacing: 1px;
  padding: 8px 16px;
  border-color: var(--rule);
  border-bottom-style: dashed;
  background: var(--paper-raised);
  color: var(--ink-2);
  box-shadow: none;
}
.date-picker :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  border-color: var(--scene-browse-ink);
  border-bottom-color: var(--scene-browse-ink);
  background: rgb(var(--scene-browse-rgb) / 0.1);
  color: var(--scene-browse-ink);
  box-shadow: none;
}

/* --- Session List --- */
.session-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.session-card {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 20px 28px;
  /* 悬停揭示走 main.css 的 .card-reveal, 域色在这里给 —— 浏览域用浏览域的身份色。 */
  --card-ink: var(--scene-browse-ink);
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
  animation: fadeInUp 0.4s ease backwards;
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

/* 票价做成一枚印色印章: 场次卡上唯一该有颜色的小面 */
.session-price {
  display: flex;
  align-items: baseline;
  gap: 2px;
  margin-right: 16px;
  padding: 6px 12px;
  border: 1px solid var(--scene-browse-ink);
  border-radius: var(--radius-sm);
  background: rgb(var(--scene-browse-rgb) / 0.1);
}

.price-symbol {
  color: var(--scene-browse-ink);
  font-size: 16px;
  font-weight: 600;
}

/* 票价 = 票据数据 → 等宽 */
.price-value {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 26px;
  font-weight: 600;
  color: var(--scene-browse-ink);
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
  .poster-wrapper {
    flex: none;
    width: 180px;
    max-width: 100%;
  }
  .poster {
    width: 100%;
    height: auto;
  }
  .hero-tally {
    align-self: center;
  }
  .hero-tally-num {
    font-size: 40px;
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
