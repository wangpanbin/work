<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from './stores/user'
import { useI18nStore } from './stores/i18n'
import ChatWidget from './components/chat/ChatWidget.vue'

const userStore = useUserStore()
const i18nStore = useI18nStore()
const router = useRouter()

onMounted(() => {
  userStore.fetchMe()
})

// P2-#17: 登出前确认, 防止误触
async function logout() {
  try {
    await ElMessageBox.confirm($t('app.logoutConfirm'), $t('app.logoutTitle'), {
      confirmButtonText: $t('app.logoutConfirmOk'),
      cancelButtonText: $t('app.logoutConfirmCancel'),
      type: 'warning',
    })
  } catch {
    return
  }
  userStore.logout()
  router.push('/login')
}

// P2-#15: 移动端 dropdown 菜单项(label 在此用 $t 取值,避免硬编码)
const mobileMenu = computed(() => {
  const items: { key: string; label: string; icon: string; danger?: boolean; adminOnly?: boolean; needLogin?: boolean; localeSwitch?: boolean }[] = [
    { key: 'toggleLocale', label: $t('app.mobileMenuLocale'), icon: '🌐' },
    { key: '/orders', label: $t('app.chipOrders'), icon: '🎫', needLogin: true },
    { key: '/admin', label: $t('app.chipAdmin'), icon: '⚙', adminOnly: true },
    { key: 'logout', label: $t('app.chipLogout'), icon: '⏻', danger: true, needLogin: true },
  ]
  return items.filter((i) => {
    if (i.adminOnly && userStore.user?.role !== 1) return false
    if (i.needLogin && !userStore.isLogin) return false
    return true
  })
})

function onMobileSelect(key: string | number) {
  if (key === 'logout') {
    logout()
  } else if (key === 'toggleLocale') {
    i18nStore.toggleLocale()
  } else {
    router.push(String(key))
  }
}

// 模板里要直接调 $t — 在 <script setup> 下需要从 vue-i18n 拿一下
import { useI18n } from 'vue-i18n'
const { t: $t } = useI18n()
</script>

<template>
  <el-container class="app">
    <el-header class="app-header">
      <div class="brand" @click="router.push('/')">
        <span class="brand-text">{{ $t('app.brand') }}</span>
        <span class="brand-tag">{{ $t('app.brandTag') }}</span>
      </div>
      <!-- 桌面端: 横向 chip 列表 -->
      <div class="user-area desktop-only">
        <!-- T9: 语言切换 chip(spec §4.3)— 点击立即切换,刷新保持 -->
        <el-button
          class="header-chip chip-locale chip-icon-text"
          size="default"
          :aria-label="$t('app.localeSwitchAria', 'Switch language')"
          @click="i18nStore.toggleLocale()"
        >
          <span class="chip-icon">🌐</span>
          <span class="chip-text">{{ $t('app.localeSwitchTo') }}</span>
        </el-button>
        <el-button
          v-if="userStore.isLogin && userStore.user?.role === 1"
          class="header-chip chip-warning chip-icon-text"
          size="default"
          @click="router.push('/admin')"
        >
          <span class="chip-icon">⚙</span>
          <span class="chip-text">{{ $t('app.chipAdmin') }}</span>
        </el-button>
        <el-button
          v-if="userStore.isLogin"
          class="header-chip chip-primary chip-icon-text"
          size="default"
          @click="router.push('/orders')"
        >
          <span class="chip-icon">🎫</span>
          <span class="chip-text">{{ $t('app.chipOrders') }}</span>
        </el-button>
        <template v-if="userStore.user">
          <span class="header-chip chip-user nickname chip-icon-text">
            <span class="chip-icon">👤</span>
            <span class="chip-text">{{ userStore.user.nickname || userStore.user.username }}</span>
          </span>
          <el-button class="header-chip chip-danger chip-icon-text" size="default" @click="logout">
            <span class="chip-icon">⏻</span>
            <span class="chip-text">{{ $t('app.chipLogout') }}</span>
          </el-button>
        </template>
        <el-button v-else class="header-chip chip-login chip-icon-text" size="default" @click="router.push('/login')">
          <span class="chip-text">{{ $t('app.chipLogin') }}</span>
        </el-button>
      </div>

      <!-- 移动端: 折叠成下拉菜单 -->
      <div class="user-area mobile-only">
        <template v-if="userStore.user">
          <el-dropdown trigger="click" @command="onMobileSelect">
            <el-button class="avatar-btn" circle size="default" :aria-label="$t('app.menuAria', 'User menu')">
              <span class="chip-icon">👤</span>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <div class="dropdown-user">
                  <span class="dropdown-nick">{{ userStore.user.nickname || userStore.user.username }}</span>
                </div>
                <el-dropdown-item v-for="i in mobileMenu" :key="i.key" :command="i.key" :divided="i.danger">
                  <span style="margin-right:8px">{{ i.icon }}</span>{{ i.label }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
        <el-button v-else class="header-chip chip-login" size="default" @click="router.push('/login')">
          {{ $t('app.chipLogin') }}
        </el-button>
      </div>
    </el-header>
    <el-main class="app-main" :class="{ 'is-admin': $route.path.startsWith('/admin') }">
      <router-view />
    </el-main>

    <!-- T6: 对话式订票助手浮窗 — ChatWidget 内部按路由自动隐藏(/payment + /admin/**) -->
    <ChatWidget />
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

/* --- P2-#15: 桌面/移动 chip 切换 --- */
.desktop-only { display: flex; }
.mobile-only  { display: none; }

.avatar-btn {
  background: rgba(245, 158, 11, 0.15);
  border: 1px solid rgba(245, 158, 11, 0.4);
  color: var(--accent-gold-light);
  width: 36px;
  height: 36px;
  padding: 0;
}
.avatar-btn:hover {
  background: rgba(245, 158, 11, 0.25);
  border-color: var(--accent-gold);
}

/* --- T9: 语言切换 chip 样式 ---
   中性配色,避免与 chip-warning/danger/primary 重复 */
.chip-locale {
  background: rgba(99, 102, 241, 0.12);
  border: 1px solid rgba(99, 102, 241, 0.4);
  color: rgb(165, 168, 255);
}
.chip-locale:hover {
  background: rgba(99, 102, 241, 0.22);
  border-color: rgba(99, 102, 241, 0.7);
  color: rgb(199, 201, 255);
}
.dropdown-user {
  padding: 10px 16px;
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-tertiary);
}
.dropdown-nick {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

@media (max-width: 640px) {
  .desktop-only { display: none; }
  .mobile-only  { display: flex; align-items: center; gap: 8px; }
}
</style>
