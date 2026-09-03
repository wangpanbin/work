<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from './stores/user'

const userStore = useUserStore()
const router = useRouter()

onMounted(() => {
  userStore.fetchMe()
})

function logout() {
  userStore.logout()
  router.push('/login')
}
</script>

<template>
  <el-container class="app">
    <el-header class="app-header">
      <div class="brand" @click="router.push('/')">
        <span class="brand-text">星辉影城</span>
        <span class="brand-tag">CINEMA</span>
      </div>
      <div class="user-area">
        <el-button
          v-if="userStore.isLogin && userStore.user?.role === 1"
          class="header-chip chip-warning chip-icon-text"
          size="default"
          @click="router.push('/admin')"
        >
          <span class="chip-icon">⚙</span>
          <span class="chip-text">管理端</span>
        </el-button>
        <el-button
          v-if="userStore.isLogin"
          class="header-chip chip-primary chip-icon-text"
          size="default"
          @click="router.push('/orders')"
        >
          <span class="chip-icon">🎫</span>
          <span class="chip-text">我的订单</span>
        </el-button>
        <template v-if="userStore.user">
          <span class="header-chip chip-user nickname chip-icon-text">
            <span class="chip-icon">👤</span>
            <span class="chip-text">{{ userStore.user.nickname || userStore.user.username }}</span>
          </span>
          <el-button class="header-chip chip-danger chip-icon-text" size="default" @click="logout">
            <span class="chip-icon">⏻</span>
            <span class="chip-text">退出</span>
          </el-button>
        </template>
        <el-button v-else class="header-chip chip-login chip-icon-text" size="default" @click="router.push('/login')">
          <span class="chip-text">登录 / 注册</span>
        </el-button>
      </div>
    </el-header>
    <el-main class="app-main" :class="{ 'is-admin': $route.path.startsWith('/admin') }">
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  user-select: none;
  flex-shrink: 0;        /* 防止被 user-area 挤碎 */
  min-width: 0;           /* 允许内部收缩 */
}
.brand-text {
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 700;
  background: var(--gradient-gold);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  letter-spacing: 2px;
  filter: drop-shadow(0 0 12px rgba(245, 158, 11, 0.4));
  transition: transform var(--transition-fast);
  white-space: nowrap;    /* 防止中文 brand 字面被按字换行 */
}
.brand:hover .brand-text {
  transform: scale(1.03);
}
.brand-tag {
  font-family: var(--font-display);
  font-size: 10px;
  letter-spacing: 3px;
  color: var(--text-muted);
  border: 1px solid var(--border-color);
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  text-transform: uppercase;
  flex-shrink: 0;
}

/* chip 内的 icon / text 容器, 用于小屏切换 */
.chip-icon-text {
  /* 默认 desktop 正常显示 */
}
.chip-icon-text .chip-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.chip-icon-text .chip-text {
  display: inline-block;
}
</style>
