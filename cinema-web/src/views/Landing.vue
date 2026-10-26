<script setup lang="ts">
/**
 * 产品主页(/landing)—— 星辉影城的对外门面。
 *
 * 版式来自 2026-10-26 的三变体原型(一次性分支 prototype/landing-variants,
 * ?variant= 切换挑选):变体 A「夜场」的暗场分屏 hero 与证据 / 机制 / 助手 /
 * 运营 / 演示各区块,配变体 C「发布」的不对称 bento 作为功能面。
 *
 * 证据数字是 docs/压测报告-v3.md 的本机实测快照,改前先重跑压测。
 * 影片与海报走真实接口(5min 路由缓存);后端未启动时隐藏海报格,
 * 其余版式照常渲染 —— 营销页的海报是装饰,不是数据承诺。
 * 演示账号是后端启动时播种的真实账号(README 已公开)。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { search as searchMovies } from '../api/movie'
import { ROUTE_PATHS } from '../constants/routes'
import { useMovieCache } from '../stores/movieCache'
import { toIdString, type Movie } from '../types'
import { usePosterFallback } from '../composables/usePosterFallback'

const { t } = useI18n()
const movies = ref<Movie[]>([])
const cache = useMovieCache()
const { isBroken: isPosterBroken, markBroken: markPosterBroken } = usePosterFallback()

const stripMovies = computed(() => movies.value.slice(0, 2))

// bento 暗格的点阵: 装饰性座位图意象, 不绑真实场次。
// 占位写死而非随机 —— 每次刷新构图稳定, 看到的永远是同一张图。
const soldSeats = new Set([4, 5, 11, 17, 23, 24, 31, 38])
const mineSeats = new Set([21, 22])
function seatClass(n: number): string {
  if (mineSeats.has(n)) return 'is-mine'
  if (soldSeats.has(n)) return 'is-sold'
  return ''
}

// hero 暗场大点阵: 同一意象的另一块画布, 已选对在中央偏右。
const heroSoldSeats = new Set([6, 7, 18, 19, 20, 33, 46, 47, 58, 59, 60, 72, 85, 86])
const heroMineSeats = new Set([52, 53])
function heroSeatClass(n: number): string {
  if (heroMineSeats.has(n)) return 'is-mine'
  if (heroSoldSeats.has(n)) return 'is-sold'
  return ''
}

async function loadMovies(silent: boolean) {
  try {
    const data = await searchMovies({ page: 1, size: 50, status: 1 })
    movies.value = data.records
    if (!silent) cache.setMovies(data.records)
  } catch {
    if (!silent) movies.value = []
  }
}

onMounted(() => {
  const cached = cache.getMovies()
  if (cached) {
    movies.value = cached
    loadMovies(true)
  } else {
    loadMovies(false)
  }
})
</script>

<template>
  <div class="landing">
    <!-- hero: 暗场面板。文案在左, 座位图意象在右(变体 A)。 -->
    <section class="ld-hero">
      <div class="ld-hero-copy">
        <p class="ld-badge mono">{{ t('landing.heroBadge') }}</p>
        <h1 class="ld-title">{{ t('landing.heroTitle') }}</h1>
        <p class="ld-sub">{{ t('landing.heroSubtitle') }}</p>
        <div class="ld-cta">
          <router-link class="ld-btn-primary" :to="ROUTE_PATHS.home">{{ t('landing.ctaBrowse') }}</router-link>
          <a class="ld-btn-ghost" href="#evidence">{{ t('landing.ctaEvidence') }}</a>
        </div>
      </div>
      <div class="ld-map" aria-hidden="true">
        <div class="ld-screen"></div>
        <div class="ld-seat-rows">
          <span v-for="n in 96" :key="n" class="ld-seat" :class="heroSeatClass(n)"></span>
        </div>
      </div>
    </section>

    <!-- bento: 六格不对称, 7+5 / 7+5 / 5+4+3(变体 C)。
         暗格承载「实时选座 + 锁座」两步, 后排海报 / 助手 / 电子票收尾。 -->
    <section class="ld-bento">
      <div class="ld-cell ld-cell-dark">
        <div class="ld-dots" aria-hidden="true">
          <div class="ld-screen"></div>
          <div class="ld-dot-rows">
            <span v-for="n in 40" :key="n" class="ld-dot" :class="seatClass(n)"></span>
          </div>
        </div>
        <h3>{{ t('landing.expStep2Title') }}</h3>
        <p>{{ t('landing.expStep2Body') }}</p>
        <h3 class="ld-dark-next">{{ t('landing.expStep3Title') }}</h3>
        <p>{{ t('landing.expStep3Body') }}</p>
      </div>
      <div class="ld-cell ld-cell-wash">
        <span class="ld-big mono">0</span>
        <h3>{{ t('landing.evOversellLabel') }}</h3>
        <p>{{ t('landing.evOversellNote') }}</p>
      </div>
      <div class="ld-cell ld-cell-browse">
        <h3>{{ t('landing.expStep1Title') }}</h3>
        <p>{{ t('landing.expStep1Body') }}</p>
      </div>
      <div v-if="stripMovies.length" class="ld-cell ld-cell-posters">
        <router-link
          v-for="m in stripMovies"
          :key="m.id"
          class="ld-mini-poster"
          :to="`/movie/${toIdString(m.id)}`"
        >
          <img
            v-if="m.poster && !isPosterBroken(m.id)"
            :src="m.poster"
            :alt="m.title"
            loading="lazy"
            @error="markPosterBroken(m.id)"
          />
          <span v-else class="ld-mini-fallback mono">{{ m.title }}</span>
          <span class="ld-mini-name">{{ m.title }}</span>
        </router-link>
      </div>
      <!-- 后端未启动时的降级格: 运营端一句话补位, 保持六格无空洞。 -->
      <div v-else class="ld-cell ld-cell-posters ld-cell-fallback">
        <h3>{{ t('landing.opsTitle') }}</h3>
        <p>{{ t('landing.opsBody') }}</p>
      </div>
      <div class="ld-cell ld-cell-ai">
        <h3>{{ t('landing.aiTitle') }}</h3>
        <p>{{ t('landing.aiBody') }}</p>
      </div>
      <div class="ld-cell ld-cell-ticket">
        <h3>{{ t('landing.expStep4Title') }}</h3>
        <p>{{ t('landing.expStep4Body') }}</p>
      </div>
    </section>

    <!-- 证据区: 大数字编排, 首位「0 超卖」占双行。 -->
    <section id="evidence" class="ld-evidence">
      <header class="ld-head">
        <div class="ld-ev-head">
          <h2 class="ld-h2">{{ t('landing.evTitle') }}</h2>
          <span class="ld-tag mono">{{ t('landing.evMeasuredTag') }}</span>
        </div>
        <p class="ld-lead">{{ t('landing.evLead') }}</p>
      </header>
      <div class="ld-ev-grid">
        <div class="ld-ev ld-ev-hero">
          <span class="ld-ev-num mono">0</span>
          <span class="ld-ev-label">{{ t('landing.evOversellLabel') }}</span>
          <p class="ld-ev-note">{{ t('landing.evOversellNote') }}</p>
        </div>
        <div class="ld-ev ld-ev-line-l">
          <span class="ld-ev-num mono">2679</span>
          <span class="ld-ev-label">{{ t('landing.evQpsLabel') }}</span>
          <p class="ld-ev-note">{{ t('landing.evQpsNote') }}</p>
        </div>
        <div class="ld-ev ld-ev-line-l">
          <span class="ld-ev-num mono">54<small class="ld-ev-unit">ms</small></span>
          <span class="ld-ev-label">{{ t('landing.evP99Label') }}</span>
          <p class="ld-ev-note">{{ t('landing.evP99Note') }}</p>
        </div>
        <div class="ld-ev ld-ev-line-l ld-ev-line-t ld-ev-wide">
          <span class="ld-ev-num mono">0%</span>
          <span class="ld-ev-label">{{ t('landing.evErrorLabel') }}</span>
          <p class="ld-ev-note">{{ t('landing.evErrorNote') }}</p>
        </div>
      </div>
      <p class="ld-reproduce mono">{{ t('landing.evReproduce') }}</p>
    </section>

    <!-- 机制区: 编号行, 行间一道发丝线。 -->
    <section class="ld-mech">
      <header class="ld-head">
        <h2 class="ld-h2">{{ t('landing.mechTitle') }}</h2>
        <p class="ld-lead">{{ t('landing.mechLead') }}</p>
      </header>
      <div class="ld-mech-rows">
        <div class="ld-mech-row">
          <span class="ld-mech-no mono">M1</span>
          <h3>{{ t('landing.mech1Title') }}</h3>
          <p>{{ t('landing.mech1Body') }}</p>
        </div>
        <div class="ld-mech-row">
          <span class="ld-mech-no mono">M2</span>
          <h3>{{ t('landing.mech2Title') }}</h3>
          <p>{{ t('landing.mech2Body') }}</p>
        </div>
        <div class="ld-mech-row">
          <span class="ld-mech-no mono">M3</span>
          <h3>{{ t('landing.mech3Title') }}</h3>
          <p>{{ t('landing.mech3Body') }}</p>
        </div>
        <div class="ld-mech-row">
          <span class="ld-mech-no mono">M4</span>
          <h3>{{ t('landing.mech4Title') }}</h3>
          <p>{{ t('landing.mech4Body') }}</p>
        </div>
      </div>
    </section>

    <!-- 助手区: 一句衬线引言 + 三条边界。bento 的助手格已讲清它能做什么,
         这里只回答「为什么可以放心让它参与下单」。 -->
    <section class="ld-ai">
      <blockquote class="ld-quote">{{ t('landing.aiAsk') }}</blockquote>
      <ul class="ld-points">
        <li><span class="mono">1</span>{{ t('landing.aiPoint1') }}</li>
        <li><span class="mono">2</span>{{ t('landing.aiPoint2') }}</li>
        <li><span class="mono">3</span>{{ t('landing.aiPoint3') }}</li>
      </ul>
    </section>

    <!-- 运营区: 左标题右三条, 一行带过。 -->
    <section class="ld-ops">
      <div class="ld-ops-copy">
        <h2 class="ld-h2">{{ t('landing.opsTitle') }}</h2>
        <p class="ld-lead">{{ t('landing.opsBody') }}</p>
      </div>
      <ul class="ld-points ld-points-ops">
        <li><span class="mono">1</span>{{ t('landing.opsPoint1') }}</li>
        <li><span class="mono">2</span>{{ t('landing.opsPoint2') }}</li>
        <li><span class="mono">3</span>{{ t('landing.opsPoint3') }}</li>
      </ul>
    </section>

    <!-- 演示指引: 一张测试票。 -->
    <section class="ld-demo">
      <div class="ld-demo-fields">
        <h2 class="ld-h2">{{ t('landing.demoTitle') }}</h2>
        <p class="ld-lead">{{ t('landing.demoLead') }}</p>
        <dl class="ld-accounts">
          <div class="ld-account">
            <dt>{{ t('landing.demoUserLabel') }}</dt>
            <dd class="mono">user1</dd>
          </div>
          <div class="ld-account">
            <dt>{{ t('landing.demoAdminLabel') }}</dt>
            <dd class="mono">admin</dd>
          </div>
          <div class="ld-account">
            <dt>{{ t('landing.demoPasswordLabel') }}</dt>
            <dd class="mono">123456</dd>
          </div>
        </dl>
      </div>
      <div class="ld-demo-flow">
        <span class="ld-flow-label">{{ t('landing.demoFlowLabel') }}</span>
        <p class="mono">{{ t('landing.demoFlow') }}</p>
      </div>
    </section>

    <footer class="ld-footer">
      <p class="ld-footer-copy">{{ t('landing.footerCopy') }}</p>
      <p class="ld-footer-tech">{{ t('landing.footerTech') }}</p>
    </footer>
  </div>
</template>

<style scoped>
.landing {
  animation: fadeInUp 0.5s ease backwards;
}

/* --- hero: 暗场面板(变体 A)---
   --hall 是模式无关 token, 亮色主题下它就是「影厅暗场」, 与选座页同族。 */
.ld-hero {
  display: grid;
  grid-template-columns: 1.15fr 1fr;
  gap: 48px;
  align-items: center;
  background: var(--hall);
  border: 1px solid var(--hall-rule);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-hall);
  padding: 56px;
  color: var(--hall-ink);
}

.ld-badge {
  display: inline-block;
  font-size: 10px;
  letter-spacing: 3px;
  color: var(--hall-ink-2);
  border: 1px solid var(--hall-rule);
  border-radius: var(--radius-sm);
  padding: 4px 12px;
  margin: 0 0 20px;
}

.ld-title {
  font-family: var(--font-display);
  font-size: clamp(30px, 4vw, 44px);
  font-weight: 700;
  letter-spacing: 2px;
  line-height: 1.18;
  color: var(--hall-ink);
  margin: 0 0 14px;
}

.ld-sub {
  font-size: 15px;
  line-height: 1.8;
  color: var(--hall-ink-2);
  max-width: 36em;
  margin: 0 0 28px;
}

.ld-cta {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.ld-btn-primary {
  display: inline-flex;
  align-items: center;
  height: 42px;
  padding: 0 26px;
  background: var(--accent);
  color: var(--ink-inverse);
  font-size: 14px;
  font-weight: 500;
  border: 1px solid var(--accent);
  border-radius: var(--radius-md);
  transition:
    background-color var(--transition-fast),
    border-color var(--transition-fast);
}
.ld-btn-primary:hover {
  background: var(--accent-hover);
  border-color: var(--accent-hover);
  color: var(--ink-inverse);
}
.ld-btn-primary:active {
  background: var(--accent-press);
  border-color: var(--accent-press);
}

.ld-btn-ghost {
  display: inline-flex;
  align-items: center;
  height: 42px;
  padding: 0 22px;
  border: 1px solid var(--hall-rule);
  border-radius: var(--radius-md);
  color: var(--hall-ink);
  font-size: 14px;
  transition:
    border-color var(--transition-fast),
    background-color var(--transition-fast);
}
.ld-btn-ghost:hover {
  border-color: var(--hall-ink-2);
  background: var(--hall-wash);
}

/* hero 座位图意象: 装饰, 不绑真实场次 */
.ld-map {
  background: var(--hall-raised);
  border: 1px solid var(--hall-rule);
  border-radius: var(--radius-md);
  padding: 28px 32px 32px;
  display: flex;
  flex-direction: column;
  gap: 22px;
}

/* 银幕线: hero 大点阵与 bento 小点阵共用。 */
.ld-screen {
  height: 2px;
  width: 68%;
  margin: 0 auto;
  border-radius: 1px;
  background: linear-gradient(90deg, transparent, rgb(var(--hall-ink-rgb) / 0.4), transparent);
}

.ld-seat-rows {
  display: grid;
  grid-template-columns: repeat(12, 1fr);
  gap: 8px;
}

.ld-seat {
  display: block;
  aspect-ratio: 1;
  border-radius: 3px;
  background: rgb(var(--hall-ink-rgb) / 0.08);
  border: 1px solid rgb(var(--hall-ink-rgb) / 0.14);
}
.ld-seat.is-sold,
.ld-dot.is-sold {
  background: rgb(var(--hall-ink-rgb) / 0.24);
  border-color: transparent;
}
/* 被抢的那一对: 首屏唯一会动的东西, 动机是「这两个座位正在被争」。 */
.ld-seat.is-mine,
.ld-dot.is-mine {
  background: var(--accent);
  border-color: var(--accent-hover);
}
.ld-seat.is-mine {
  animation: ldSeatPulse 2.4s ease-in-out infinite;
}

@keyframes ldSeatPulse {
  0%,
  100% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.14);
  }
}

/* --- bento(变体 C): 12 列, 7+5 / 7+5 / 5+4+3 --- */
.ld-bento {
  display: grid;
  grid-template-columns: repeat(12, 1fr);
  gap: 16px;
  margin-top: 88px;
}
.ld-cell {
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-paper);
  padding: 26px 28px;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  gap: 8px;
  transition:
    border-color var(--transition-normal),
    box-shadow var(--transition-normal),
    transform var(--transition-normal);
}
.ld-cell:hover {
  border-color: var(--rule-strong);
  box-shadow: var(--shadow-paper-hover);
  transform: translateY(-2px);
}
.ld-cell h3 {
  font-family: var(--font-display);
  font-size: 17px;
  font-weight: 700;
  color: var(--ink);
  margin: 6px 0 0;
}
.ld-cell p {
  font-size: 13px;
  line-height: 1.75;
  color: var(--ink-2);
  margin: 0;
  max-width: 30em;
}

/* 暗格: 实时选座 + 锁座, 跨 7 列两行, 点阵在上文案在下。 */
.ld-cell-dark {
  grid-column: span 7;
  grid-row: span 2;
  background: var(--hall);
  border-color: var(--hall-rule);
  box-shadow: var(--shadow-hall);
  min-height: 340px;
}
.ld-cell-dark h3 {
  color: var(--hall-ink);
}
.ld-cell-dark p {
  color: var(--hall-ink-2);
}
.ld-cell-dark:hover {
  border-color: var(--hall-ink-2);
}
.ld-dark-next {
  margin-top: 14px !important;
}

.ld-dots {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 18px;
  justify-content: center;
  margin-bottom: 22px;
}
.ld-dot-rows {
  display: grid;
  grid-template-columns: repeat(10, 1fr);
  gap: 9px;
}
.ld-dot {
  display: block;
  aspect-ratio: 1;
  border-radius: 3px;
  background: rgb(var(--hall-ink-rgb) / 0.08);
  border: 1px solid rgb(var(--hall-ink-rgb) / 0.14);
}

/* 水洗格: 0 超卖。 */
.ld-cell-wash {
  grid-column: span 5;
  background: var(--accent-wash);
  border-color: rgb(var(--accent-rgb) / 0.25);
  justify-content: center;
}
.ld-cell-wash:hover {
  border-color: rgb(var(--accent-rgb) / 0.45);
}
.ld-big {
  font-size: 64px;
  font-weight: 600;
  line-height: 1;
  color: var(--accent);
  font-variant-numeric: tabular-nums;
}
.ld-cell-wash h3 {
  margin-top: 10px;
}

.ld-cell-browse {
  grid-column: span 5;
}
.ld-cell-posters {
  grid-column: span 5;
  flex-direction: row;
  gap: 12px;
  align-items: stretch;
  padding: 14px;
}
.ld-cell-fallback {
  justify-content: flex-end;
  padding: 26px 28px;
}
.ld-cell-ai {
  grid-column: span 4;
}
.ld-cell-ticket {
  grid-column: span 3;
}

.ld-mini-poster {
  flex: 1;
  min-width: 0;
  display: block;
  text-decoration: none;
  position: relative;
  border: 1px solid var(--rule);
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--paper-sunk);
  min-height: 180px;
}
.ld-mini-poster img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.5s ease;
}
.ld-mini-poster:hover img {
  transform: scale(1.04);
}
.ld-mini-fallback {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 12px;
  text-align: center;
  font-size: 13px;
  color: var(--ink-2);
}
/* 片名条: 压在海报下缘的实底纸条(不是悬浮 pill, 是固定字幕条)。 */
.ld-mini-name {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 6px 10px;
  font-size: 12px;
  color: var(--hall-ink);
  background: rgb(var(--hall-ink-rgb) / 0.86);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* --- 区块公共头 --- */
.ld-head {
  margin-bottom: 36px;
}
.ld-h2 {
  font-family: var(--font-display);
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 1px;
  color: var(--ink);
  margin: 0 0 10px;
}
.ld-lead {
  font-size: 14px;
  color: var(--ink-2);
  margin: 0;
  max-width: 46em;
}

/* --- 证据区: 大数字排版 --- */
.ld-evidence {
  padding-top: 88px;
}
.ld-ev-head {
  display: flex;
  align-items: baseline;
  gap: 14px;
}
.ld-ev-head .ld-h2 {
  margin-bottom: 0;
}
.ld-tag {
  font-size: 10px;
  letter-spacing: 2px;
  color: var(--accent);
  border: 1px solid rgb(var(--accent-rgb) / 0.4);
  border-radius: var(--radius-sm);
  padding: 3px 10px;
}
.ld-evidence .ld-lead {
  margin-top: 10px;
}

.ld-ev-grid {
  display: grid;
  grid-template-columns: 1.15fr 1fr 1fr;
}

.ld-ev {
  padding: 28px 32px 30px 0;
}
/* 发丝线只做内部分隔: 左列间一道, 行间一道, 不围框。 */
.ld-ev-line-l {
  border-left: 1px solid var(--rule);
  padding-left: 32px;
}
.ld-ev-line-t {
  border-top: 1px solid var(--rule);
}
.ld-ev-hero {
  grid-row: span 2;
  padding-right: 40px;
}
.ld-ev-wide {
  grid-column: span 2;
  display: flex;
  align-items: baseline;
  gap: 18px;
}

.ld-ev-num {
  display: block;
  font-size: clamp(44px, 5vw, 64px);
  font-weight: 600;
  line-height: 1;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}
.ld-ev-hero .ld-ev-num {
  font-size: clamp(96px, 12vw, 168px);
  color: var(--accent);
  margin-bottom: 14px;
}
.ld-ev-unit {
  font-size: 0.36em;
  color: var(--ink-3);
  margin-left: 6px;
  font-weight: 500;
}

.ld-ev-label {
  display: block;
  font-size: 12px;
  letter-spacing: 2px;
  color: var(--ink-3);
  margin: 12px 0 8px;
}
.ld-ev-hero .ld-ev-label {
  margin-top: 0;
}
.ld-ev-note {
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink-2);
  margin: 0;
  max-width: 34em;
}
.ld-ev-wide .ld-ev-note {
  margin-top: 2px;
}

.ld-reproduce {
  margin: 28px 0 0;
  padding-top: 20px;
  border-top: 1px solid var(--rule);
  font-size: 12px;
  letter-spacing: 0.5px;
  color: var(--ink-3);
}

/* --- 机制区: 编号行 --- */
.ld-mech {
  padding-top: 88px;
}
.ld-mech-rows {
  max-width: 72em;
}
.ld-mech-row {
  display: grid;
  grid-template-columns: 56px 220px 1fr;
  gap: 28px;
  align-items: baseline;
  padding: 22px 0;
}
.ld-mech-row + .ld-mech-row {
  border-top: 1px solid var(--rule);
}
.ld-mech-no {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 2px;
  color: var(--accent);
}
.ld-mech-row h3 {
  font-family: var(--font-display);
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
  margin: 0;
}
.ld-mech-row p {
  font-size: 13.5px;
  line-height: 1.75;
  color: var(--ink-2);
  margin: 0;
}

/* --- 助手区 --- */
.ld-ai {
  padding-top: 88px;
  max-width: 60em;
}
.ld-quote {
  font-family: var(--font-display);
  font-size: clamp(20px, 2.6vw, 28px);
  font-weight: 700;
  line-height: 1.5;
  color: var(--ink);
  border-left: 3px solid var(--accent);
  padding-left: 22px;
  margin: 0 0 22px;
}

.ld-points {
  list-style: none;
  margin: 0;
  padding: 0;
}
.ld-points li {
  display: flex;
  gap: 12px;
  align-items: baseline;
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--ink-2);
  padding: 7px 0;
}
.ld-points li span {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  color: var(--accent);
}

/* --- 运营区 --- */
.ld-ops {
  padding-top: 88px;
  display: grid;
  grid-template-columns: 1fr 1.2fr;
  gap: 56px;
  align-items: start;
}

/* --- 演示指引: 测试票 --- */
.ld-demo {
  margin-top: 88px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 40px;
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-paper);
  padding: 40px 44px;
}
.ld-demo-fields .ld-h2 {
  font-size: 22px;
}
.ld-demo-fields .ld-lead {
  margin-bottom: 22px;
}
.ld-accounts {
  margin: 0;
  display: flex;
  flex-direction: column;
}
.ld-account {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 0;
  border-bottom: 1px solid var(--rule);
}
.ld-account:last-child {
  border-bottom: none;
}
.ld-account dt {
  font-size: 12px;
  letter-spacing: 1px;
  color: var(--ink-3);
}
.ld-account dd {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
}
.ld-demo-flow {
  border-left: 1px dashed var(--rule);
  padding-left: 40px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 12px;
}
.ld-flow-label {
  font-size: 12px;
  letter-spacing: 1px;
  color: var(--ink-3);
}
.ld-demo-flow p {
  margin: 0;
  font-size: 13px;
  line-height: 2;
  color: var(--ink-2);
}

/* --- 页脚 --- */
.ld-footer {
  margin-top: 88px;
  border-top: 1px solid var(--rule);
  padding: 28px 0 8px;
}
.ld-footer-copy {
  font-family: var(--font-display);
  font-size: 14px;
  letter-spacing: 1px;
  color: var(--ink-2);
  margin: 0 0 6px;
}
.ld-footer-tech {
  font-size: 12px;
  color: var(--ink-3);
  margin: 0;
}

/* --- 响应式 --- */
@media (max-width: 1023px) {
  .ld-hero {
    grid-template-columns: 1fr;
    gap: 36px;
    padding: 40px;
  }
  .ld-ev-grid {
    grid-template-columns: 1fr 1fr;
  }
  .ld-ev-hero {
    grid-row: auto;
    grid-column: span 2;
    padding-right: 0;
  }
  .ld-mech-row {
    grid-template-columns: 48px 1fr;
  }
  .ld-mech-row p {
    grid-column: 2;
  }
  .ld-ops {
    grid-template-columns: 1fr;
    gap: 28px;
  }
  .ld-demo {
    grid-template-columns: 1fr;
    gap: 28px;
  }
  .ld-demo-flow {
    border-left: none;
    border-top: 1px dashed var(--rule);
    padding-left: 0;
    padding-top: 24px;
  }
  .ld-cell-dark {
    grid-column: span 12;
    grid-row: auto;
    min-height: 300px;
  }
  .ld-cell-wash,
  .ld-cell-browse,
  .ld-cell-posters,
  .ld-cell-ai {
    grid-column: span 6;
  }
  .ld-cell-ticket {
    grid-column: span 12;
  }
}

@media (max-width: 768px) {
  .ld-hero {
    padding: 28px 24px;
  }
  .ld-bento {
    margin-top: 64px;
  }
  .ld-evidence,
  .ld-mech,
  .ld-ai,
  .ld-ops {
    padding-top: 64px;
  }
  .ld-demo {
    margin-top: 64px;
  }
  .ld-ev-grid {
    grid-template-columns: 1fr;
  }
  .ld-ev-line-l {
    border-left: none;
    padding-left: 0;
    border-top: 1px solid var(--rule);
  }
  .ld-ev-hero {
    grid-column: auto;
  }
  .ld-ev-wide {
    grid-column: auto;
    flex-direction: column;
    gap: 4px;
  }
  .ld-ev {
    padding-right: 20px;
  }
  .ld-cell-wash,
  .ld-cell-browse,
  .ld-cell-posters,
  .ld-cell-ai,
  .ld-cell-ticket {
    grid-column: span 12;
  }
  .ld-mini-poster {
    min-height: 220px;
  }
  .ld-ops {
    padding-top: 64px;
  }
}

@media (max-width: 640px) {
  .ld-seat-rows {
    gap: 5px;
  }
  .ld-points li {
    align-items: flex-start;
  }
}
</style>
