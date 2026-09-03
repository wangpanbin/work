<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { search as searchMovies } from '../api/movie'
import { useMovieCache } from '../stores/movieCache'
import type { Movie } from '../types'

const router = useRouter()
const movies = ref<Movie[]>([])
const loading = ref(false)
const cache = useMovieCache()

// F1 搜索 + 筛选
const keyword = ref('')
const genre = ref('')
const region = ref('')
// 静态可选项(后端搜索走 LIKE, 这里只负责 UI 入口)
// 真实电影类型/地区动态值需要后端额外提供 /api/movies/filters, 此处用常见值占位
const genreOptions = ['动作', '喜剧', '科幻', '爱情', '悬疑', '动画', '战争', '剧情']
const regionOptions = ['中国大陆', '美国', '日本', '韩国', '欧洲', '印度', '泰国']
let debounceTimer: number | null = null

async function loadMovies() {
  loading.value = true
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
    loading.value = false
  }
}

function onSearchInput() {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = window.setTimeout(loadMovies, 400)
}

function onFilterChange() {
  loadMovies()
}

function clearFilters() {
  keyword.value = ''
  genre.value = ''
  region.value = ''
  loadMovies()
}

onMounted(() => {
  // 优先用缓存, 但只用于空查询
  const cached = cache.getMovies()
  if (cached) {
    movies.value = cached
    return
  }
  loadMovies()
})
</script>

<template>
  <div v-loading="loading" class="home">
    <!-- Hero Banner -->
    <section class="hero">
      <div class="hero-bg"></div>
      <div class="hero-overlay"></div>
      <div class="hero-content">
        <div class="hero-badge">NOW SHOWING</div>
        <h1 class="hero-title">光影世界 · 星光璀璨</h1>
        <p class="hero-subtitle">精选热映大片，尊享极致观影体验</p>
        <div class="hero-stats">
          <div class="stat">
            <span class="stat-num">{{ movies.length }}</span>
            <span class="stat-label">部热映影片</span>
          </div>
          <div class="stat-divider"></div>
          <div class="stat">
            <span class="stat-num">4K</span>
            <span class="stat-label">超清画质</span>
          </div>
          <div class="stat-divider"></div>
          <div class="stat">
            <span class="stat-num">3D</span>
            <span class="stat-label">沉浸体验</span>
          </div>
        </div>
      </div>
      <!-- Decorative film reel -->
      <div class="film-reel film-reel-left">
        <div class="reel-circle"></div>
      </div>
      <div class="film-reel film-reel-right">
        <div class="reel-circle"></div>
      </div>
    </section>

    <!-- Movie Grid Section -->
    <section class="movies-section">
      <div class="section-header">
        <h2 class="section-title">🎬 热映影片</h2>
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
          placeholder="搜索片名 / 关键词"
          clearable
          :prefix-icon="'Search'"
          @input="onSearchInput"
          @clear="onFilterChange"
        />
        <el-select v-model="genre" class="filter-select" placeholder="类型" clearable @change="onFilterChange">
          <el-option v-for="g in genreOptions" :key="g" :label="g" :value="g" />
        </el-select>
        <el-select v-model="region" class="filter-select" placeholder="地区" clearable @change="onFilterChange">
          <el-option v-for="r in regionOptions" :key="r" :label="r" :value="r" />
        </el-select>
        <el-button v-if="keyword || genre || region" class="filter-clear" @click="clearFilters">重置</el-button>
        <span class="filter-meta">{{ movies.length }} 部影片</span>
      </div>

      <el-empty v-if="!loading && movies.length === 0" description="暂无匹配影片,试试清空筛选条件" />

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
                <span class="view-detail">查看详情 →</span>
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
                {{ m.duration }} 分钟
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

/* --- Hero Banner --- */
.hero {
  position: relative;
  height: 340px;
  border-radius: var(--radius-xl);
  overflow: hidden;
  margin-bottom: 40px;
  background: var(--gradient-hero);
  display: flex;
  align-items: center;
  padding: 0 48px;
}

.hero-bg {
  position: absolute;
  inset: 0;
  background-image:
    radial-gradient(circle at 20% 80%, rgba(245, 158, 11, 0.15) 0%, transparent 50%),
    radial-gradient(circle at 80% 20%, rgba(168, 85, 247, 0.12) 0%, transparent 50%),
    radial-gradient(circle at 50% 50%, rgba(6, 182, 212, 0.08) 0%, transparent 60%);
}

.hero-overlay {
  position: absolute;
  inset: 0;
  background-image:
    repeating-linear-gradient(
      0deg,
      transparent,
      transparent 2px,
      rgba(0, 0, 0, 0.03) 2px,
      rgba(0, 0, 0, 0.03) 4px
    );
}

.hero-content {
  position: relative;
  z-index: 2;
  max-width: 600px;
}

.hero-badge {
  display: inline-block;
  font-family: var(--font-display);
  font-size: 11px;
  letter-spacing: 4px;
  color: var(--accent-gold);
  border: 1px solid var(--accent-gold);
  padding: 6px 16px;
  border-radius: 20px;
  margin-bottom: 20px;
  animation: pulse-glow 3s ease-in-out infinite;
}

.hero-title {
  font-family: var(--font-display);
  font-size: 36px;
  font-weight: 700;
  letter-spacing: 2px;
  margin-bottom: 12px;
  background: linear-gradient(135deg, #f9fafb 0%, var(--accent-gold-light) 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.hero-subtitle {
  color: var(--text-secondary);
  font-size: 16px;
  margin-bottom: 28px;
  line-height: 1.6;
}

.hero-stats {
  display: flex;
  align-items: center;
  gap: 24px;
}

.stat {
  display: flex;
  flex-direction: column;
}

.stat-num {
  font-family: var(--font-display);
  font-size: 24px;
  font-weight: 700;
  color: var(--accent-gold);
  line-height: 1;
}

.stat-label {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 4px;
  letter-spacing: 1px;
}

.stat-divider {
  width: 1px;
  height: 32px;
  background: var(--border-color);
}

/* Film reel decorations */
.film-reel {
  position: absolute;
  width: 120px;
  height: 120px;
  opacity: 0.08;
  animation: float 6s ease-in-out infinite;
}

.film-reel-left {
  left: -30px;
  bottom: -30px;
}

.film-reel-right {
  right: -30px;
  top: -30px;
  animation-delay: -3s;
}

.reel-circle {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  border: 12px solid var(--accent-gold);
  position: relative;
}

.reel-circle::before,
.reel-circle::after {
  content: '';
  position: absolute;
  background: var(--accent-gold);
}

.reel-circle::before {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
}

.reel-circle::after {
  width: 100%;
  height: 4px;
  top: 50%;
  left: 0;
  transform: translateY(-50%);
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
  background: var(--accent-gold);
}

.deco-line {
  flex: 1;
  height: 1px;
  background: linear-gradient(90deg, var(--border-color), transparent);
}

/* --- Movie Grid --- */
.movie-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 24px;
}

.movie-card {
  position: relative;
  border-radius: var(--radius-lg);
  overflow: hidden;
  cursor: pointer;
  transition: transform var(--transition-normal);
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  animation: fadeInUp 0.5s ease both;
}

.movie-card:hover {
  transform: translateY(-8px);
  border-color: rgba(245, 158, 11, 0.4);
}

.movie-card:hover .card-glow {
  opacity: 1;
}

.movie-card:hover .poster img {
  transform: scale(1.08);
}

.movie-card:hover .poster-overlay {
  opacity: 1;
}

.poster {
  position: relative;
  height: 300px;
  overflow: hidden;
  background: var(--bg-tertiary);
}

.poster img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.5s ease;
}

.poster-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1e1b4b 0%, #312e81 50%, #4c1d95 100%);
  position: relative;
}

.poster-fallback::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    repeating-linear-gradient(90deg,
      transparent 0,
      transparent 8px,
      rgba(245, 158, 11, 0.06) 8px,
      rgba(245, 158, 11, 0.06) 10px
    );
}

.fallback-text {
  font-family: var(--font-display);
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
  text-align: center;
  padding: 0 16px;
  text-shadow: 0 2px 12px rgba(0, 0, 0, 0.5);
  position: relative;
  z-index: 1;
}

.poster-overlay {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    to top,
    rgba(0, 0, 0, 0.85) 0%,
    rgba(0, 0, 0, 0.4) 40%,
    transparent 100%
  );
  opacity: 0;
  transition: opacity var(--transition-normal);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.play-btn {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--gradient-gold);
  color: var(--text-inverse);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: var(--shadow-glow-gold);
  transform: scale(0.8);
  transition: transform var(--transition-normal);
}

.movie-card:hover .play-btn {
  transform: scale(1);
}

.view-detail {
  font-size: 13px;
  color: var(--text-primary);
  letter-spacing: 1px;
}

.movie-info {
  padding: 16px;
}

.title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
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
  color: var(--text-muted);
}

.meta-item svg {
  flex-shrink: 0;
}

.desc-truncate {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-glow {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  background: var(--gradient-gold);
  opacity: 0;
  transition: opacity var(--transition-normal);
  border-radius: var(--radius-lg) var(--radius-lg) 0 0;
}

/* F1 搜索筛选栏 */
.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
  flex-wrap: wrap;
  padding: 14px 18px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
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
  color: var(--text-muted);
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
  .hero {
    height: 260px;
    padding: 0 24px;
    margin-bottom: 28px;
  }
  .hero-title {
    font-size: 24px;
  }
  .hero-subtitle {
    font-size: 14px;
  }
  .hero-stats {
    gap: 16px;
  }
  .stat-num {
    font-size: 18px;
  }
  .film-reel {
    width: 80px;
    height: 80px;
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
  .hero {
    height: 220px;
    padding: 0 20px;
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
