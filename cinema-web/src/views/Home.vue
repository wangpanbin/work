<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { search as searchMovies } from '../api/movie'
import { useMovieCache } from '../stores/movieCache'
import { toIdString, type Movie } from '../types'
import { usePosterFallback } from '../composables/usePosterFallback'
import { Clock, VideoPlay } from '@element-plus/icons-vue'

const { t } = useI18n()
const movies = ref<Movie[]>([])
const loading = ref(false)
const cache = useMovieCache()
const { isBroken: isPosterBroken, markBroken: markPosterBroken } = usePosterFallback()

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
  <!-- 加载态不用 Element Plus 的整页蒙层: 骨架屏本身就是加载反馈,
       再叠一层蒙层会把骨架压暗, 反而看不清。蒙层留给没有骨架的页面。 -->
  <div class="home">
    <!-- Hero: 一张横向票根。主券是品牌主张, 副券是票面数据, 中间齿孔撕口分隔。
         原稿这里是金色渐变 + 双 radial 光斑 + 两个旋转胶片盘, 已整体删除。 -->
    <section class="hero">
      <div class="hero-content">
        <div class="hero-badge">{{ t('home.heroBadge') }}</div>
        <h1 class="hero-title">{{ t('home.heroTitle') }}</h1>
        <p class="hero-subtitle">{{ t('home.heroSubtitle') }}</p>
      </div>
      <div class="hero-stub">
        <!-- 副券: 竖排三行, 每行「左标签 + 右大号数字」。
             票面数据竖着排才像票; 横排三列是账本。 -->
        <div class="hero-stats">
          <div class="stat">
            <span class="stat-label">{{ t('home.statMovies') }}</span>
            <span class="stat-num">{{ movies.length }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">{{ t('home.statResolution') }}</span>
            <span class="stat-num">4K</span>
          </div>
          <div class="stat">
            <span class="stat-label">{{ t('home.statImmersive') }}</span>
            <span class="stat-num">3D</span>
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

      <!-- 加载中: 骨架屏占位。用 grid 撑住同样的版面, 卡片进场时不会「长出来」跳版。
           骨架屏只用 main.css 的 shimmer 关键帧(此前定义了却没人用)。 -->
      <div v-if="loading" class="movie-grid skeleton-grid" aria-hidden="true">
        <div v-for="n in 6" :key="`skeleton-${n}`" class="skeleton-card">
          <div class="skeleton-poster"></div>
          <div class="skeleton-line"></div>
          <div class="skeleton-line short"></div>
        </div>
      </div>

      <el-empty v-else-if="movies.length === 0" :description="t('home.empty')" />

      <div v-else class="movie-grid">
        <router-link
          v-for="(m, idx) in movies"
          :key="m.id"
          class="movie-card"
          :class="{ 'is-feature': idx === 0 }"
          :style="{ animationDelay: `${idx * 0.08}s` }"
          :to="`/movie/${toIdString(m.id)}`"
        >
          <div class="poster">
            <img
              v-if="m.poster && !isPosterBroken(m.id)"
              :src="m.poster"
              :alt="m.title"
              loading="lazy"
              @error="markPosterBroken(m.id)"
            />
            <div v-else class="poster-fallback">
              <span class="fallback-text">{{ m.title }}</span>
            </div>
            <div class="poster-overlay">
              <div class="play-btn">
                <el-icon :size="28"><VideoPlay /></el-icon>
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
                <el-icon :size="14"><Clock /></el-icon>
                {{ t('home.minutes', { n: m.duration }) }}
              </span>
              <span v-if="m.description" class="meta-item desc-truncate">{{ m.description }}</span>
            </div>
          </div>
          <div class="card-glow"></div>
        </router-link>
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

/* NOW SHOWING: 浏览域身份色小标签, 印刷风不做脉冲发光 */
.hero-badge {
  display: inline-block;
  align-self: flex-start;
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 3px;
  color: var(--scene-browse-ink);
  border: 1px solid var(--scene-browse-ink);
  background: rgb(var(--scene-browse-rgb) / 0.1);
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

/* --- 副券 ---
   宽度从 300px 收到 260px: 主券 1fr + 副券 260px, 不再是对半分的对称票。
   齿孔撕口保持不变 —— 变的是比例, 不是这张票的物理结构。 */
.hero-stub {
  position: relative;
  flex-shrink: 0;
  width: 260px;
  border-left: 1px dashed var(--rule);
  padding: 40px 32px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 24px;
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

/* 副券数据竖排三行: 每行「左标签 + 右大号数字」, 行间一道发丝线 */
.hero-stats {
  display: flex;
  flex-direction: column;
}

.stat {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--rule);
}
.stat:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

/* 票面数据 → 等宽 */
.stat-num {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 26px;
  font-weight: 600;
  color: var(--scene-browse-ink);
  line-height: 1;
}

.stat-label {
  font-size: 11px;
  color: var(--ink-3);
  letter-spacing: 1px;
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

/* --- 骨架屏 ---
   灰底 + 一道扫过的高光, 表达「正在取」而不是「这里有内容」。
   高光用暗场文字的 RGB 分量拼 10% 透明度, 两个模式通用。 */
.skeleton-grid {
  pointer-events: none;
}
.skeleton-card {
  grid-column: span 3;
  border: 1px solid var(--rule);
  border-radius: var(--radius-md);
  background: var(--paper-raised);
  overflow: hidden;
}
.skeleton-poster {
  height: 300px;
  background-color: var(--paper-sunk);
}
.skeleton-line {
  height: 14px;
  margin: 16px 16px 0;
  background-color: var(--paper-sunk);
  border-radius: 2px;
}
.skeleton-line.short {
  width: 40%;
  margin-bottom: 20px;
}
.skeleton-poster,
.skeleton-line {
  background-image: linear-gradient(
    90deg,
    transparent,
    rgb(var(--hall-ink-rgb) / 0.12),
    transparent
  );
  background-size: 200% 100%;
  background-repeat: no-repeat;
  animation: shimmer 1.6s linear infinite;
}

/* 章节墨条取浏览域身份色, 不再是全局印色 —— 见 DESIGN.md The Scene Ink Rule 第 1 条。
   用 ink 档(文字安全档)而不是 base 档: 3px 的细条在纸面上要能读出来,
   base 档的亮橙在冷灰白上只有 2.5:1, ink 档是 6.3:1。 */
.section-title::before {
  background: var(--scene-browse-ink);
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
  background: var(--scene-browse);
}

.deco-line {
  flex: 1;
  height: 1px;
  background: var(--rule);
}

/* --- Movie Grid ---
   12 列栅格: 首卡跨 6 列(特征卡), 其余各占 3 列。
   一排一模一样的卡片是上一版反复重复的形状 —— 不对称才有地方落颜色。 */
.movie-grid {
  display: grid;
  grid-template-columns: repeat(12, 1fr);
  gap: 24px;
}

.movie-card {
  position: relative;
  grid-column: span 3;
  border-radius: var(--radius-md);
  overflow: hidden;
  cursor: pointer;
  text-decoration: none;   /* router-link 渲染为 <a>, 去掉默认下划线 */
  transition:
    border-color var(--transition-normal),
    box-shadow var(--transition-normal),
    transform var(--transition-normal);
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  box-shadow: var(--shadow-paper);
  animation: fadeInUp 0.5s ease both;
}

/* 特征卡: 首片占双倍宽, 海报换成更宽的银幕比例 */
.movie-card.is-feature {
  grid-column: span 6;
}
.movie-card.is-feature .poster {
  height: 340px;
}

.movie-card:hover {
  border-color: var(--rule-strong);
  box-shadow: var(--shadow-paper-hover);
  /* 抬起 2px: 此刻这张卡已经有强边界(--rule-strong),
     满足 Flat-By-Default 的「有强边界才允许动」, 且幅度在 2px 上限内 */
  transform: translateY(-2px);
}

.movie-card:hover .card-glow {
  opacity: 1;
  transform: scaleX(1);
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
    rgb(var(--scene-browse-rgb) / 0.1) 8px,
    rgb(var(--scene-browse-rgb) / 0.1) 10px
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
  background: var(--overlay-poster);
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
  /* 浏览域身份色底 + 墨色字: base 都是高明度高饱和色, 反色字压在上面读不清 */
  background: var(--scene-browse);
  color: var(--ink);
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

/* 卡片顶部一道浏览域印色, hover 时从左展开 —— 替代原来的金色渐变光条 */
.card-glow {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  background: var(--scene-browse);
  opacity: 0;
  transform: scaleX(0);
  transform-origin: left center;
  transition:
    opacity var(--transition-normal),
    transform var(--transition-normal);
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
/* 769~1023px: 12 列太窄, 收成 6 列(特征卡跨 3) */
@media (max-width: 1023px) and (min-width: 769px) {
  .movie-grid {
    grid-template-columns: repeat(6, 1fr);
  }
  .movie-card {
    grid-column: span 2;
  }
  .movie-card.is-feature {
    grid-column: span 3;
  }
}

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
    padding: 20px 24px;
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
    gap: 0;
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
  /* 窄屏回到全等网格: 特征卡不再跨列 */
  .movie-card,
  .movie-card.is-feature {
    grid-column: auto;
  }
  .poster,
  .movie-card.is-feature .poster {
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
  .poster,
  .movie-card.is-feature .poster {
    height: 180px;
  }
  .movie-info {
    padding: 12px;
  }
}
</style>
