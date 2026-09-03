<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { dashboardSummary, type DashboardSummary } from '../../api/admin'

const userStore = useUserStore()
const router = useRouter()

const isAdmin = computed(() => userStore.user?.role === 1)
const summary = ref<DashboardSummary | null>(null)

// 移动端顶部导航项
const navItems = [
  { path: '/admin', label: '概览', icon: '🏠' },
  { path: '/admin/dashboard', label: '经营看板', icon: '📊' },
  { path: '/admin/live', label: '实时大屏', icon: '📡' },
  { path: '/admin/movies', label: '影片', icon: '🎞' },
  { path: '/admin/halls', label: '影厅', icon: '🛋' },
  { path: '/admin/sessions', label: '场次', icon: '📅' },
]

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
        <span class="brand-icon">🎬</span>
        <span class="brand-text">管理端</span>
      </div>
      <el-menu router :default-active="$route.path" mode="vertical" class="menu">
        <el-menu-item index="/admin/dashboard">
          <span>📊</span>&nbsp;<span>经营看板</span>
        </el-menu-item>
        <el-menu-item index="/admin/live">
          <span>📡</span>&nbsp;<span>实时数据大屏</span>
        </el-menu-item>
        <el-menu-item index="/admin/movies">
          <span>🎞</span>&nbsp;<span>影片管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/halls">
          <span>🛋</span>&nbsp;<span>影厅管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/sessions">
          <span>📅</span>&nbsp;<span>场次管理</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-main class="content">
      <!-- 移动端顶部水平导航 (桌面端 CSS display:none 隐藏) -->
      <nav class="mobile-nav" aria-label="admin-nav">
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
            {{ item.icon }} {{ item.label }}
          </a>
        </router-link>
      </nav>
      <!-- /admin 默认页 → 若路径是 /admin 本身 (无子路由激活), 渲染欢迎页/概览 -->
      <router-view v-slot="{ Component, route }">
        <component :is="Component" v-if="Component && route.path !== '/admin'" />
        <div v-else class="welcome">
          <div class="welcome-hero">
            <div class="welcome-badge">ADMIN CONSOLE</div>
            <h2>欢迎回来, {{ userStore.user?.nickname || userStore.user?.username }}</h2>
            <p class="welcome-subtitle">从左侧导航开始管理影院运营 · 今日经营概况如下</p>
          </div>

          <div v-if="summary" class="welcome-cards">
            <div class="card gold">
              <div class="label">今日票房</div>
              <div class="value">¥{{ Number(summary.todayRevenue || 0).toFixed(2) }}</div>
            </div>
            <div class="card">
              <div class="label">今日订单</div>
              <div class="value">{{ summary.todayOrders || 0 }}</div>
            </div>
            <div class="card">
              <div class="label">已支付 / 锁座</div>
              <div class="value">{{ summary.todayPaid || 0 }} <small>/ {{ summary.todayPendingSeats || 0 }} 座</small></div>
            </div>
            <div class="card">
              <div class="label">今日退票</div>
              <div class="value">{{ summary.todayRefunded || 0 }}</div>
            </div>
          </div>

          <div class="welcome-actions">
            <el-button type="primary" size="large" @click="router.push('/admin/dashboard')">
              📊 进入经营看板
            </el-button>
            <el-button @click="router.push('/admin/live')">
              📡 实时数据大屏
            </el-button>
            <el-button @click="router.push('/admin/sessions')">
              📅 管理场次
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
  background: var(--bg-primary);
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
    background: var(--bg-secondary);
    border-bottom: 1px solid var(--border-subtle);
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
    color: var(--text-secondary);
    cursor: pointer;
    transition: all var(--transition-fast);
    text-decoration: none;
    white-space: nowrap;
  }
  .mobile-nav .nav-item:hover {
    color: var(--text-primary);
    background: var(--bg-tertiary);
  }
  .mobile-nav .nav-item.active {
    color: var(--text-inverse);
    background: var(--gradient-gold);
    font-weight: 600;
  }
  .content {
    padding: 16px !important;
  }
}

/* ========== 侧边栏：与全局 Cinema Noir 深色主题统一 ========== */
.aside {
  background: linear-gradient(180deg, var(--bg-secondary) 0%, var(--bg-primary) 100%);
  color: var(--text-primary);
  border-right: 1px solid var(--border-subtle);
  position: relative;
}
.aside::after {
  content: '';
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: 1px;
  background: linear-gradient(180deg,
    transparent 0%,
    rgba(245, 158, 11, 0.35) 45%,
    rgba(168, 85, 247, 0.35) 55%,
    transparent 100%);
  pointer-events: none;
}

/* 侧边栏品牌区 —— 与 App.vue 顶部品牌视觉一致 */
.brand {
  height: 72px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 0 16px;
  border-bottom: 1px solid var(--border-subtle);
  position: relative;
}
.brand-icon {
  font-size: 22px;
  filter: drop-shadow(0 0 8px rgba(245, 158, 11, 0.5));
}
.brand-text {
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 700;
  background: var(--gradient-gold);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  letter-spacing: 2px;
  filter: drop-shadow(0 0 10px rgba(245, 158, 11, 0.35));
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
  color: var(--text-secondary);
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
  color: var(--text-inverse);
  background: var(--gradient-gold);
  box-shadow: 0 4px 14px rgba(245, 158, 11, 0.35);
}
.aside :deep(.el-menu-item:hover) {
  color: var(--text-primary);
  background: var(--bg-tertiary);
}
.aside :deep(.el-menu-item.is-active:hover) {
  color: var(--text-inverse);
  background: var(--gradient-gold);
}

/* ========== 右侧内容区 ========== */
.content {
  padding: 28px 36px;
  background: var(--bg-primary);
  box-sizing: border-box;
}

/* ========== /admin 默认欢迎页 ========== */
.welcome {
  animation: fadeInUp 0.5s ease;
}
.welcome-hero {
  background: var(--gradient-hero);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-xl);
  padding: 36px 32px;
  margin-bottom: 24px;
  position: relative;
  overflow: hidden;
}
.welcome-hero::after {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    radial-gradient(circle at 80% 30%, rgba(245, 158, 11, 0.18) 0%, transparent 50%),
    radial-gradient(circle at 20% 70%, rgba(168, 85, 247, 0.12) 0%, transparent 50%);
  pointer-events: none;
}
.welcome-badge {
  display: inline-block;
  font-family: var(--font-display);
  font-size: 11px;
  letter-spacing: 4px;
  color: var(--accent-gold);
  border: 1px solid var(--accent-gold);
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
  color: var(--text-primary);
  margin-bottom: 8px;
  letter-spacing: 1px;
  position: relative;
  z-index: 1;
  word-break: break-word;      /* 小屏防中文标题受子元素影响竖排 */
  overflow-wrap: anywhere;
}
.welcome-subtitle {
  color: var(--text-secondary);
  font-size: 14px;
  position: relative;
  z-index: 1;
}

.welcome-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}
.welcome-cards .card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  padding: 20px;
}
.welcome-cards .card.gold {
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.15), rgba(245, 158, 11, 0.04));
  border-color: rgba(245, 158, 11, 0.3);
}
.welcome-cards .label {
  font-size: 13px;
  color: var(--text-muted);
  margin-bottom: 10px;
  letter-spacing: 0.5px;
}
.welcome-cards .value {
  font-family: var(--font-display);
  font-size: 26px;
  font-weight: 700;
  color: var(--text-primary);
}
.welcome-cards .card.gold .value {
  color: var(--accent-gold);
}
.welcome-cards .value small {
  font-size: 13px;
  color: var(--text-muted);
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