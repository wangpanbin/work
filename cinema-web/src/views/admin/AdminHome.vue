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
    <el-aside width="200px" class="aside">
      <div class="brand">🎬 管理端</div>
      <el-menu router :default-active="$route.path" mode="vertical">
        <el-menu-item index="/admin/movies">影片管理</el-menu-item>
        <el-menu-item index="/admin/halls">影厅管理</el-menu-item>
        <el-menu-item index="/admin/sessions">场次管理</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span>{{ userStore.user?.nickname || userStore.user?.username }}</span>
        <div>
          <el-button v-if="!isAdmin" type="warning" size="small" @click="router.push('/')">回到用户端</el-button>
          <el-button type="danger" link @click="logout">退出登录</el-button>
        </div>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.admin {
  height: calc(100vh - 60px);
}
.aside {
  background: #001529;
  color: #fff;
}
.brand {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  font-weight: 700;
  border-bottom: 1px solid #1f2d3d;
}
.aside :deep(.el-menu) {
  background: transparent;
  border-right: none;
}
.aside :deep(.el-menu-item) {
  color: #c0c4cc;
}
.aside :deep(.el-menu-item.is-active),
.aside :deep(.el-menu-item:hover) {
  color: #fff;
  background: #002140;
}
.header {
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #ebeef5;
}
</style>