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
        <el-button v-if="userStore.isLogin && userStore.user?.role === 1" link type="warning" @click="router.push('/admin')">
          ⚙ 管理端
        </el-button>
        <el-button v-if="userStore.isLogin" link type="primary" @click="router.push('/orders')">🎫 我的订单</el-button>
        <template v-if="userStore.user">
          <span class="nickname">{{ userStore.user.nickname || userStore.user.username }}</span>
          <el-button link type="danger" @click="logout">退出</el-button>
        </template>
        <el-button v-else type="primary" @click="router.push('/login')">登录 / 注册</el-button>
      </div>
    </el-header>
    <el-main class="app-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.brand {
  display: flex;
  align-items: baseline;
  gap: 10px;
  cursor: pointer;
  user-select: none;
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
}
</style>
