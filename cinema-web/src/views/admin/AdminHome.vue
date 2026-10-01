<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useUserStore } from '../../stores/user'
import { dashboardSummary, type DashboardSummary } from '../../api/admin'
import BrandMark from '../../components/BrandMark.vue'

const userStore = useUserStore()
const router = useRouter()
const { t } = useI18n()

const isAdmin = computed(() => userStore.user?.role === 1)
const summary = ref<DashboardSummary | null>(null)

// 移动端顶部导航项
// ⚠️ 原稿每项带彩色 emoji 图标(🏠📊📡🎞🛋📅)。票根主题是克制的印刷质感,
//    满屏彩色 emoji 是"廉价活动页"观感的主要来源之一, 这里去掉只留文字。
const navItems = computed(() => [
  { path: '/admin', labelKey: 'admin.mobileNavHome' },
  { path: '/admin/dashboard', labelKey: 'admin.mobileNavDashboard' },
  { path: '/admin/live', labelKey: 'admin.mobileNavLive' },
  { path: '/admin/movies', labelKey: 'admin.mobileNavMovies' },
  { path: '/admin/halls', labelKey: 'admin.mobileNavHalls' },
  { path: '/admin/sessions', labelKey: 'admin.mobileNavSessions' },
])

async function load() {
  try {
    summary.value = await dashboardSummary()
  } catch {
    // ignore
  }
}

onMounted(() => {
  if (isAdmin.value) load()
})

function logout() {
  userStore.logout()
  router.push('/login')
}
</script>

<template>
  <el-container class="admin">
    <el-aside width="220px" class="aside">
      <div class="brand">
        <span class="brand-icon"><BrandMark :size="22" /></span>
        <span class="brand-text">{{ t('admin.asideBrand') }}</span>
      </div>
      <el-menu router :default-active="$route.path" mode="vertical" class="menu">
        <el-menu-item index="/admin/dashboard">{{ t('admin.homeSidebar.dashboard') }}</el-menu-item>
        <el-menu-item index="/admin/live">{{ t('admin.homeSidebar.live') }}</el-menu-item>
        <el-menu-item index="/admin/movies">{{ t('admin.homeSidebar.movies') }}</el-menu-item>
        <el-menu-item index="/admin/halls">{{ t('admin.homeSidebar.halls') }}</el-menu-item>
        <el-menu-item index="/admin/sessions">{{ t('admin.homeSidebar.sessions') }}</el-menu-item>
      </el-menu>
    </el-aside>
    <el-main class="content">
      <!-- 移动端顶部水平导航 (桌面端 CSS display:none 隐藏) -->
      <nav class="mobile-nav" :aria-label="t('admin.mobileNavAria')">
        <router-link
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          custom
          v-slot="{ navigate, isActive }"
        >
          <a
            class="nav-item"
            :class="{ active: isActive || $route.path === item.path }"
            @click="navigate"
          >
            {{ t(item.labelKey) }}
          </a>
        </router-link>
      </nav>
      <!-- /admin 默认页 → 若路径是 /admin 本身 (无子路由激活), 渲染欢迎页/概览 -->
      <router-view v-slot="{ Component, route }">
        <component :is="Component" v-if="Component && route.path !== '/admin'" />
        <div v-else class="welcome">
          <div class="welcome-hero">
            <div class="welcome-badge">ADMIN CONSOLE</div>
            <h2>{{ t('admin.welcomeTitle', { name: userStore.user?.nickname || userStore.user?.username || '' }) }}</h2>
            <p class="welcome-subtitle">{{ t('admin.welcomeSubtitle') }}</p>
          </div>

          <div v-if="summary" class="welcome-cards">
            <div class="card gold">
              <div class="label">{{ t('admin.cardTodayRevenue') }}</div>
              <div class="value">¥{{ Number(summary.todayRevenue || 0).toFixed(2) }}</div>
            </div>
            <div class="card">
              <div class="label">{{ t('admin.cardTodayOrders') }}</div>
              <div class="value">{{ summary.todayOrders || 0 }}</div>
            </div>
            <div class="card">
              <div class="label">{{ t('admin.welcomePaidLocked') }}</div>
              <div class="value">{{ summary.todayPaid || 0 }} <small>/ {{ summary.todayPendingSeats || 0 }} {{ t('admin.welcomeSeats') }}</small></div>
            </div>
            <div class="card">
              <div class="label">{{ t('admin.cardRefunded') }}</div>
              <div class="value">{{ summary.todayRefunded || 0 }}</div>
            </div>
          </div>

          <div class="welcome-actions">
            <el-button type="primary" size="large" @click="router.push('/admin/dashboard')">
              {{ t('admin.welcomeEnterDashboard') }}
            </el-button>
            <el-button @click="router.push('/admin/live')">
              {{ t('admin.welcomeEnterLive') }}
            </el-button>
            <el-button @click="router.push('/admin/sessions')">
              {{ t('admin.welcomeEnterSessions') }}
            </el-button>
          </div>
        </div>
      </router-view>
    </el-main>
  </el-container>
</template>

<style scoped>
.admin {
  height: 100%;
  min-height: calc(100vh - 64px);
  background: var(--paper);
}

/* 移动端: 隐藏侧边栏, 内容顶部增加横向导航 */
@media (max-width: 768px) {
  .admin :deep(.el-aside) {
    display: none !important;
  }
  .admin :deep(.el-main) {
    display: block !important;
    width: 100% !important;
    height: auto !important;
    position: static !important;
    overflow: visible !important;
    padding: 0 !important;
    box-sizing: border-box !important;
  }
  /* 顶部移动端专用导航条 */
  .mobile-nav {
    display: flex !important;
    overflow-x: auto;
    background: var(--paper-raised);
    border-bottom: 1px solid var(--rule);
    padding: 8px 12px;
    gap: 6px;
    position: sticky;
    top: 64px;
    z-index: 50;
  }
  .mobile-nav .nav-item {
    flex-shrink: 0;
    height: 36px;
    line-height: 36px;
    padding: 0 14px;
    border-radius: var(--radius-md);
    font-size: 13px;
    color: var(--ink-2);
    cursor: pointer;
    transition: all var(--transition-fast);
    text-decoration: none;
    white-space: nowrap;
  }
  .mobile-nav .nav-item:hover {
    color: var(--ink);
    background: var(--paper-sunk);
  }
  .mobile-nav .nav-item.active {
    color: var(--ink-inverse);
    background: var(--accent);
    font-weight: 600;
  }
  .content {
    padding: 16px !important;
  }
}

/* ========== 侧边栏：与全局票根浅色主题统一 ========== */
.aside {
  background: var(--paper-raised);
  color: var(--ink);
  border-right: 1px solid var(--rule);
  position: relative;
}

/* 侧边栏品牌区 —— 与 App.vue 顶部品牌视觉一致 */
.brand {
  height: 72px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 0 16px;
  border-bottom: 1px solid var(--rule);
  position: relative;
}
.brand-icon {
  font-size: 22px;
}
.brand-text {
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 700;
  color: var(--accent);
  letter-spacing: 2px;
}

/* 移动端导航: 默认隐藏, 仅 max-width:768px 时显示 */
.mobile-nav {
  display: none;
}

/* Element Plus 菜单样式覆盖 */
.aside :deep(.el-menu) {
  background: transparent;
  border-right: none;
  padding: 12px 8px;
}
.aside :deep(.el-menu-item) {
  color: var(--ink-2);
  height: 48px;
  line-height: 48px;
  border-radius: var(--radius-md);
  margin: 4px 0;
  font-size: 14px;
  font-weight: 500;
  letter-spacing: 0.5px;
  transition: all var(--transition-fast);
}
.aside :deep(.el-menu-item.is-active) {
  color: var(--ink-inverse);
  background: var(--accent);
}
.aside :deep(.el-menu-item:hover) {
  color: var(--ink);
  background: var(--paper-sunk);
}
.aside :deep(.el-menu-item.is-active:hover) {
  color: var(--ink-inverse);
  background: var(--accent-hover);
}

/* ========== 右侧内容区 ========== */
.content {
  padding: 20px 24px;
  background: var(--paper);
  box-sizing: border-box;
}

/* ========== /admin 默认欢迎页 ========== */
.welcome {
  animation: fadeInUp 0.5s ease;
}
.welcome-hero {
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  border-radius: var(--radius-xl);
  padding: 28px 24px;
  margin-bottom: 16px;
  position: relative;
  overflow: hidden;
}
.welcome-badge {
  display: inline-block;
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 4px;
  color: var(--accent);
  border: 1px solid var(--accent);
  padding: 4px 14px;
  border-radius: 16px;
  margin-bottom: 16px;
  position: relative;
  z-index: 1;
}
.welcome-hero h2 {
  font-family: var(--font-display);
  font-size: 28px;
  font-weight: 700;
  color: var(--ink);
  margin-bottom: 8px;
  letter-spacing: 1px;
  position: relative;
  z-index: 1;
  word-break: break-word;      /* 小屏防中文标题受子元素影响竖排 */
  overflow-wrap: anywhere;
}
.welcome-subtitle {
  color: var(--ink-2);
  font-size: 14px;
  position: relative;
  z-index: 1;
}

.welcome-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
.welcome-cards .card {
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
  padding: 16px;
}
.welcome-cards .card.gold {
  background: var(--accent-wash);
  border-color: var(--rule-strong);
}
.welcome-cards .label {
  font-size: 13px;
  color: var(--ink-3);
  margin-bottom: 8px;
  letter-spacing: 0.5px;
}
/* 金额 / 单量: 等宽 + tabular-nums, 避免刷新时数字左右跳 */
.welcome-cards .value {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 24px;
  font-weight: 700;
  color: var(--ink);
}
.welcome-cards .card.gold .value {
  color: var(--accent);
}
.welcome-cards .value small {
  font-size: 12px;
  color: var(--ink-3);
  font-weight: 400;
  font-family: var(--font-body);
}

.welcome-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

@media (max-width: 768px) {
  .welcome-cards { grid-template-columns: repeat(2, 1fr); }
  .welcome-hero { padding: 24px 20px; }
  .welcome-hero h2 { font-size: 22px; }
  .welcome-actions .el-button { flex: 1; }
}
</style>