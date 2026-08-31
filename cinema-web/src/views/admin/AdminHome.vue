<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'

const userStore = useUserStore()
const router = useRouter()

const isAdmin = computed(() => userStore.user?.role === 1)

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
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.admin {
  height: 100%;
  min-height: calc(100vh - 64px);
  background: var(--bg-primary);
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
</style>