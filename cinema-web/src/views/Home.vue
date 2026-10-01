<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { search as searchMovies } from '../api/movie'
import { useMovieCache } from '../stores/movieCache'
import type { Movie } from '../types'

const router = useRouter()
const { t } = useI18n()
const movies = ref<Movie[]>([])
const loading = ref(false)
const cache = useMovieCache()

// F1 搜索 + 筛选
const keyword = ref('')
const genre = ref('')
const region = ref('')
// 静态可选项(后端搜索走 LIKE, 这里只负责 UI 入口)
// value 用中文(后端契约),label 走 category 字典
const genreOptions = ['动作', '喜剧', '科幻', '爱情', '悬疑', '动画', '战争', '剧情']
const regionOptions = ['中国大陆', '美国', '日本', '韩国', '欧洲', '印度', '泰国']
let debounceTimer: number | null = null

/**
 * Hero 副券上的装饰条码 —— 纯 CSS, 宽度写死只为"像票", 不可扫描。
 */
const barcodeBars = [2, 1, 3, 1, 2, 2, 1, 3, 1, 2, 1, 3, 2, 1]

async function loadMovies(silent = false) {
  if (!silent) loading.value = true
  try {
    const data = await searchMovies({
      page: 1, size: 50, status: 1,
      keyword: keyword.value || undefined,
      genre: genre.value || undefined,
      region: region.value || undefined,
    })
    movies.value = data.records
    if (!keyword.value && !genre.value && !region.value) {
      cache.setMovies(data.records)
    }
  } finally {
    if (!silent) loading.value = false
  }
}

// P2-#19: 统一防抖 — 输入/筛选/清除都走同一路径, 避免"清除 X 立刻刷新"vs"输入 400ms 才刷新"的不一致
function debouncedSearch(delay = 300) {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = window.setTimeout(() => loadMovies(false), delay)
}

function onSearchInput() {
  debouncedSearch(400)   // 输入稍长防抖, 减少每键一查
}

function onFilterChange() {
  debouncedSearch(300)
}

function clearFilters() {
  keyword.value = ''
  genre.value = ''
  region.value = ''
  debouncedSearch(300)
}

onMounted(() => {
  // P0-3: 命中缓存时先立即渲染(避免首屏空), 再后台静默刷新拉新
  // 让 movieCache 的 5min TTL 真正生效 — 后台请求拿到结果后会覆盖, 用户能看到新影片
  const cached = cache.getMovies()
  if (cached) {
    movies.value = cached
    loadMovies(true)   // silent: 不触发顶层 loading
  } else {
    loadMovies(false)
  }
})
</script>

<template>
  <div v-loading="loading" class="home">
    <!-- Hero: 一张横向票根。主券是品牌主张, 副券是票面数据, 中间齿孔撕口分隔。
         原稿这里是金色渐变 + 双 radial 光斑 + 两个旋转胶片盘, 已整体删除。 -->
    <section class="hero">
      <div class="hero-content">
        <div class="hero-badge">{{ t('home.heroBadge') }}</div>
        <h1 class="hero-title">{{ t('home.heroTitle') }}</h1>
        <p class="hero-subtitle">{{ t('home.heroSubtitle') }}</p>
      </div>
      <div class="hero-stub">
        <div class="hero-stats">
          <div class="stat">
            <span class="stat-num">{{ movies.length }}</span>
            <span class="stat-label">{{ t('home.statMovies') }}</span>
          </div>
          <div class="stat-divider"></div>
          <div class="stat">
            <span class="stat-num">4K</span>
            <span class="stat-label">{{ t('home.statResolution') }}</span>
          </div>
          <div class="stat-divider"></div>
          <div class="stat">
            <span class="stat-num">3D</span>
            <span class="stat-label">{{ t('home.statImmersive') }}</span>
          </div>
        </div>
        <!-- 装饰条码, 纯 CSS, 不是可扫描的条码 -->
        <div class="hero-barcode" aria-hidden="true">
          <i v-for="(w, i) in barcodeBars" :key="i" :style="{ width: w + 'px' }"></i>
        </div>
      </div>
    </section>

    <!-- Movie Grid Section -->
    <section class="movies-section">
      <div class="section-header">
        <h2 class="section-title">{{ t('home.sectionTitle') }}</h2>
        <div class="section-decoration">
          <span class="deco-dot"></span>
          <span class="deco-line"></span>
          <span class="deco-dot"></span>
        </div>
      </div>

      <!-- F1 搜索 + 筛选 (CSS 早就写好, 但模板里没渲染 → 修) -->
      <div class="filter-bar">
        <el-input
          v-model="keyword"
          class="search-input"
          :placeholder="t('home.searchPlaceholder')"
          clearable
          :prefix-icon="'Search'"
          @input="onSearchInput"
          @clear="onFilterChange"
        />
        <el-select v-model="genre" class="filter-select" :placeholder="t('home.filterGenre')" clearable @change="onFilterChange">
          <el-option v-for="g in genreOptions" :key="g" :label="t(`category.genre.${g}`)" :value="g" />
        </el-select>
        <el-select v-model="region" class="filter-select" :placeholder="t('home.filterRegion')" clearable @change="onFilterChange">
          <el-option v-for="r in regionOptions" :key="r" :label="t(`category.region.${r}`)" :value="r" />
        </el-select>
        <el-button v-if="keyword || genre || region" class="filter-clear" @click="clearFilters">{{ t('home.filterClear') }}</el-button>
        <span class="filter-meta">{{ t('home.filterMeta', { count: movies.length }) }}</span>
      </div>

      <el-empty v-if="!loading && movies.length === 0" :description="t('home.empty')" />

      <div v-else class="movie-grid">
        <div
          v-for="(m, idx) in movies"
          :key="m.id"
          class="movie-card"
          :style="{ animationDelay: `${idx * 0.08}s` }"
          @click="router.push(`/movie/${m.id}`)"
        >
          <div class="poster">
            <img
              v-if="m.poster"
              :src="m.poster"
              :alt="m.title"
              loading="lazy"
            />
            <div v-else class="poster-fallback">
              <span class="fallback-text">{{ m.title }}</span>
            </div>
            <div class="poster-overlay">
              <div class="play-btn">
                <svg viewBox="0 0 24 24" fill="currentColor" width="28" height="28">
                  <path d="M8 5v14l11-7z"/>
                </svg>
              </div>
              <div class="overlay-info">
                <span class="view-detail">{{ t('home.viewDetail') }}</span>
              </div>
            </div>
          </div>
          <div class="movie-info">
            <h3 class="title">{{ m.title }}</h3>
            <div class="meta-row">
              <span class="meta-item">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
                  <circle cx="12" cy="12" r="10"/>
                  <polyline points="12 6 12 12 16 14"/>
                </svg>
                {{ t('home.minutes', { n: m.duration }) }}
              </span>
              <span v-if="m.description" class="meta-item desc-truncate">{{ m.description }}</span>
            </div>
          </div>
          <div class="card-glow"></div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.home {
  animation: fadeInUp 0.5s ease;
}

/* --- Hero: 一张横向票根 ---
   主券 = 品牌主张, 副券 = 票面数据, 中间是齿孔撕口(.stub-perf) */
.hero {
  position: relative;
  min-height: 240px;
  display: flex;
  align-items: stretch;
  margin-bottom: 40px;
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-paper);
  max-width: 100%;
}

.hero-content {
  position: relative;
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 40px 48px;
}

/* NOW SHOWING: 印色小标签, 印刷风不做脉冲发光 */
.hero-badge {
  display: inline-block;
  align-self: flex-start;
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 3px;
  color: var(--accent);
  border: 1px solid var(--accent);
  padding: 4px 12px;
  border-radius: var(--radius-sm);
  margin-bottom: 16px;
}

.hero-title {
  font-family: var(--font-display);
  font-size: 36px;
  font-weight: 700;
  letter-spacing: 2px;
  color: var(--ink);
  margin-bottom: 10px;
  line-height: 1.2;
}

.hero-subtitle {
  color: var(--ink-2);
  font-size: 16px;
  line-height: 1.7;
  max-width: 42em;
}

/* --- 副券 --- */
.hero-stub {
  position: relative;
  flex-shrink: 0;
  width: 300px;
  border-left: 1px dashed var(--rule);
  padding: 40px 32px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 20px;
}
/* 齿孔撕口: 沿副券左缘打出半圆缺口, 露出背后纸面 */
.hero-stub::before {
  content: "";
  position: absolute;
  top: 14px;
  bottom: 14px;
  left: -6px;
  width: 12px;
  pointer-events: none;
  background: radial-gradient(circle at 6px 6px, var(--paper) 5.5px, transparent 6px)
    center top / 12px 18px repeat-y;
}

.hero-stats {
  display: flex;
  align-items: center;
  gap: 16px;
}

.stat {
  display: flex;
  flex-direction: column;
}

/* 票面数据 → 等宽 */
.stat-num {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 22px;
  font-weight: 600;
  color: var(--accent);
  line-height: 1;
}

.stat-label {
  font-size: 11px;
  color: var(--ink-3);
  margin-top: 5px;
  letter-spacing: 1px;
}

.stat-divider {
  width: 1px;
  height: 30px;
  background: var(--rule);
}

.hero-barcode {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 24px;
}
.hero-barcode i {
  display: block;
  background: var(--ink-3);
  height: 100%;
}

/* --- Movies Section --- */
.movies-section {
  position: relative;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-bottom: 28px;
}

.section-header .section-title {
  margin-bottom: 0;
}

.section-decoration {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
}

.deco-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
}

.deco-line {
  flex: 1;
  height: 1px;
  background: var(--rule);
}

/* --- Movie Grid --- */
.movie-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 24px;
}

.movie-card {
  position: relative;
  border-radius: var(--radius-md);
  overflow: hidden;
  cursor: pointer;
  transition: border-color var(--transition-normal);
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  box-shadow: var(--shadow-paper);
  animation: fadeInUp 0.5s ease both;
}

.movie-card:hover {
  border-color: var(--rule-strong);
}

.movie-card:hover .card-glow {
  opacity: 1;
}

.movie-card:hover .poster img {
  transform: scale(1.05);
}

.movie-card:hover .poster-overlay {
  opacity: 1;
}

.poster {
  position: relative;
  height: 300px;
  overflow: hidden;
  background: var(--paper-sunk);
}

.poster img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.5s ease;
}

/* 海报兜底: 原稿是紫蓝三段渐变 + 金色竖条纹 + 文字投影, 与新色板无关。
   改成纸面 + 极淡的印色纹理, 文字用墨色, 不再需要 text-shadow。 */
.poster-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--paper-sunk);
  position: relative;
}

.poster-fallback::before {
  content: '';
  position: absolute;
  inset: 0;
  background: repeating-linear-gradient(
    90deg,
    transparent 0,
    transparent 8px,
    var(--accent-wash) 8px,
    var(--accent-wash) 10px
  );
}

.fallback-text {
  font-family: var(--font-display);
  font-size: 20px;
  font-weight: 700;
  color: var(--ink-2);
  text-align: center;
  padding: 0 16px;
  position: relative;
  z-index: 1;
}

/* 悬停蒙版: 海报上是深色压暗(为了白字可读), 保留, 但去掉底部渐变的浓重感 */
.poster-overlay {
  position: absolute;
  inset: 0;
  background: rgba(31, 27, 22, 0.6);
  opacity: 0;
  transition: opacity var(--transition-normal);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.play-btn {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  background: var(--accent);
  color: var(--ink-inverse);
  display: flex;
  align-items: center;
  justify-content: center;
  transform: scale(0.8);
  transition: transform var(--transition-normal);
}

.movie-card:hover .play-btn {
  transform: scale(1);
}

.view-detail {
  font-size: 13px;
  color: var(--ink-inverse);
  letter-spacing: 1px;
}

.movie-info {
  padding: 16px;
}

.title {
  font-family: var(--font-display);
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
  margin-bottom: 8px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.meta-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--ink-3);
}

.meta-item svg {
  flex-shrink: 0;
}

.desc-truncate {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 卡片顶部一道印色, hover 时显形 —— 替代原来的金色渐变光条 */
.card-glow {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  background: var(--accent);
  opacity: 0;
  transition: opacity var(--transition-normal);
  z-index: 2;
}

/* F1 搜索筛选栏 */
.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
  flex-wrap: wrap;
  padding: 14px 18px;
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-paper);
}
.search-input {
  flex: 1;
  min-width: 240px;
  max-width: 380px;
}
.filter-select {
  width: 140px;
}
.filter-clear {
  border-radius: var(--radius-md);
}
.filter-meta {
  margin-left: auto;
  font-size: 13px;
  color: var(--ink-3);
  letter-spacing: 0.5px;
}
@media (max-width: 640px) {
  .filter-bar { gap: 8px; padding: 12px; }
  .search-input { min-width: 160px; max-width: 100%; }
  .filter-select { width: 110px; }
  .filter-meta { width: 100%; margin-left: 0; }
}

/* --- Responsive --- */
@media (max-width: 768px) {
  /* 窄屏: 副券落到主券下方, 撕口转成横向 */
  .hero {
    flex-direction: column;
  }
  .hero-content {
    padding: 28px 24px 20px;
  }
  .hero-title {
    font-size: 24px;
  }
  .hero-subtitle {
    font-size: 14px;
  }
  .hero-stub {
    width: auto;
    border-left: none;
    border-top: 1px dashed var(--rule);
    padding: 20px 24px 22px;
  }
  .hero-stub::before {
    top: -6px;
    bottom: auto;
    left: 14px;
    right: 14px;
    width: auto;
    height: 12px;
    background: radial-gradient(circle at 6px 6px, var(--paper) 5.5px, transparent 6px)
      left top / 18px 12px repeat-x;
  }
  .hero-stats {
    gap: 12px;
  }
  .stat-num {
    font-size: 18px;
  }
  .hero-barcode {
    display: none;
  }
  .movie-grid {
    grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
    gap: 16px;
  }
  .poster {
    height: 220px;
  }
  .title {
    font-size: 14px;
  }
}

@media (max-width: 480px) {
  .hero-content {
    padding: 24px 20px 18px;
  }
  .hero-title {
    font-size: 20px;
    letter-spacing: 1px;
  }
  .hero-stats {
    display: none;
  }
  .movie-grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 12px;
  }
  .poster {
    height: 180px;
  }
  .movie-info {
    padding: 12px;
  }
}
</style>
