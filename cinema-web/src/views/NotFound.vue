<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="not-found">
    <div class="poster">
      <div class="poster-glow"></div>
      <div class="poster-inner">
        <div class="film-strip">
          <span v-for="i in 8" :key="i" class="hole" />
        </div>
        <div class="emoji">🎞</div>
        <div class="film-strip">
          <span v-for="i in 8" :key="i" class="hole" />
        </div>
      </div>
    </div>
    <h1 class="code">404</h1>
    <h2 class="title">这片子还没排片</h2>
    <p class="desc">你访问的页面不存在,可能链接已过期或地址输错了。</p>
    <div class="actions">
      <el-button type="primary" size="large" @click="router.push('/')">回到首页</el-button>
      <el-button @click="router.back()">返回上一页</el-button>
    </div>
  </div>
</template>

<style scoped>
.not-found {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
  text-align: center;
  padding: 24px;
  animation: fadeInUp 0.5s ease;
}

.poster {
  position: relative;
  margin-bottom: 24px;
}

.poster-glow {
  position: absolute;
  inset: -16px;
  background: var(--gradient-gold);
  border-radius: 16px;
  filter: blur(32px);
  opacity: 0.3;
  z-index: -1;
}

.poster-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 32px 48px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 12px;
}

.film-strip {
  display: flex;
  gap: 6px;
}

.hole {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
}

.emoji {
  font-size: 72px;
  line-height: 1;
}

.code {
  font-family: var(--font-display);
  font-size: 64px;
  font-weight: 700;
  background: var(--gradient-gold);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  margin: 0 0 8px;
  letter-spacing: 4px;
}

.title {
  font-size: 22px;
  color: var(--text-primary);
  margin: 0 0 12px;
}

.desc {
  color: var(--text-muted);
  font-size: 14px;
  max-width: 360px;
  margin-bottom: 28px;
  line-height: 1.6;
}

.actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: center;
}
</style>
