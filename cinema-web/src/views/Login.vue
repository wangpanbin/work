<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '../stores/user'
import { register } from '../api/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const activeTab = ref<'login' | 'register'>('login')
const loading = ref(false)

const loginFormRef = ref<FormInstance>()
const loginForm = reactive({ username: '', password: '' })
const loginRules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const registerFormRef = ref<FormInstance>()
const registerForm = reactive({ username: '', password: '', confirmPassword: '', nickname: '', phone: '' })
const registerRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]{4,20}$/, message: '4-20位字母/数字/下划线', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '6-32位', trigger: 'blur' },
  ],
  // P1-#8: 确认密码, 提交前再次校验用户输入无错
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== registerForm.password) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

async function handleLogin() {
  await loginFormRef.value?.validate()
  loading.value = true
  try {
    await userStore.login(loginForm.username, loginForm.password)
    ElMessage.success('登录成功')
    // 登录成功后: 优先回 redirect, 防止从抢票页踢出后回不去
    const redirect = String(route.query.redirect || '')
    // 安全校验: 只接受站内相对路径, 防止 open redirect
    const safeRedirect = redirect.startsWith('/') && !redirect.startsWith('//') ? redirect : '/'
    router.push(safeRedirect)
  } catch (e: unknown) {
    // P1-#11: 拦截器已弹通用 ElMessage, 这里针对"被限流"特殊码给更详细的 alert 提示
    // 后端 ResultCode.RATE_LIMIT = 42900, 触发条件: @RateLimit 注解拒绝 (RateLimitAspect)
    // 当前登录接口 AuthController#login 暂未加 @RateLimit, 此分支为将来留位
    // 真正的"账号被锁"机制后端目前未实现, 不在客户端穷举
    const err = e as { code?: number }
    if (err.code === 42900) {
      await ElMessageBox.alert(
        '登录请求过于频繁, 已被临时限流。\n请稍等 1-2 分钟后再试, 避免连续点击。',
        '操作太频繁',
        { confirmButtonText: '我知道了', type: 'warning' },
      )
    }
  } finally {
    loading.value = false
  }
}

// P1-#11: 忘记密码 — 当前项目没有密码重置流程, 给个明确的"开发中"占位
// 比直接给个坏链接好, 至少让用户知道"不是系统坏了"
async function onForgotPassword() {
  try {
    await ElMessageBox.alert(
      '密码找回功能正在开发中, 暂时请联系影院工作人员协助重置。\n带来的不便敬请谅解。',
      '找回密码',
      { confirmButtonText: '我知道了', type: 'info' },
    )
  } catch {
    // 用户点了取消 — 没事
  }
}

async function handleRegister() {
  await registerFormRef.value?.validate()
  loading.value = true
  try {
    // 提交前剥离 confirmPassword, 避免发给后端未知字段
    const { confirmPassword: _omit, ...payload } = registerForm
    await register(payload)
    ElMessage.success('注册成功,请登录')
    loginForm.username = registerForm.username
    activeTab.value = 'login'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <!-- Background decorations -->
    <div class="bg-orbs">
      <div class="orb orb-1"></div>
      <div class="orb orb-2"></div>
      <div class="orb orb-3"></div>
    </div>

    <div class="login-wrapper">
      <div class="login-brand">
        <div class="brand-logo">🎬</div>
        <div class="brand-name">星辉影城</div>
        <div class="brand-subtitle">STAR CINEMA</div>
      </div>

      <el-card class="login-card">
        <el-tabs v-model="activeTab" stretch class="login-tabs">
          <el-tab-pane label="欢迎回来" name="login">
            <el-form ref="loginFormRef" :model="loginForm" :rules="loginRules" label-width="0" @keyup.enter="handleLogin">
              <el-form-item prop="username">
                <el-input v-model="loginForm.username" placeholder="用户名" size="large" />
              </el-form-item>
              <el-form-item prop="password">
                <el-input v-model="loginForm.password" type="password" show-password placeholder="密码" size="large" />
              </el-form-item>
              <el-button type="primary" size="large" class="submit-btn" :loading="loading" @click="handleLogin">
                立即登录
              </el-button>
              <p class="tip">
                首次使用? <a href="#" @click.prevent="activeTab = 'register'">注册新账号</a>
                <span class="tip-sep">·</span>
                <a href="#" @click.prevent="onForgotPassword">忘记密码?</a>
              </p>
            </el-form>
          </el-tab-pane>

          <el-tab-pane label="加入我们" name="register">
            <el-form ref="registerFormRef" :model="registerForm" :rules="registerRules" label-width="0">
              <el-form-item prop="username">
                <el-input v-model="registerForm.username" placeholder="用户名(4-20位字母数字下划线)" size="large" />
              </el-form-item>
              <el-form-item prop="password">
                <el-input v-model="registerForm.password" type="password" show-password placeholder="密码(6-32位)" size="large" />
              </el-form-item>
              <!-- P1-#8: 确认密码, 避免输错注册后无法登录 -->
              <el-form-item prop="confirmPassword">
                <el-input v-model="registerForm.confirmPassword" type="password" show-password placeholder="确认密码" size="large" @keyup.enter="handleRegister" />
              </el-form-item>
              <el-form-item prop="nickname">
                <el-input v-model="registerForm.nickname" placeholder="昵称(可选)" size="large" />
              </el-form-item>
              <el-form-item prop="phone">
                <el-input v-model="registerForm.phone" placeholder="手机号(可选)" size="large" />
              </el-form-item>
              <el-button type="primary" size="large" class="submit-btn" :loading="loading" @click="handleRegister">
                创建账号
              </el-button>
            </el-form>
          </el-tab-pane>
        </el-tabs>
      </el-card>

      <div class="login-footer">
        <span class="footer-text">© 2026 星辉影城 · 光影世界 星光璀璨</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  min-height: calc(100vh - 64px);
  display: flex;
  justify-content: center;
  align-items: center;
  overflow: hidden;
  background: var(--gradient-hero);
}

.bg-orbs {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.4;
}

.orb-1 {
  width: 400px;
  height: 400px;
  background: var(--accent-gold);
  top: -100px;
  left: -100px;
  animation: float 8s ease-in-out infinite;
}

.orb-2 {
  width: 350px;
  height: 350px;
  background: var(--accent-purple);
  bottom: -80px;
  right: -80px;
  animation: float 10s ease-in-out infinite reverse;
}

.orb-3 {
  width: 250px;
  height: 250px;
  background: var(--accent-cyan);
  top: 40%;
  right: 20%;
  animation: float 7s ease-in-out infinite;
  opacity: 0.2;
}

.login-wrapper {
  position: relative;
  z-index: 2;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 32px;
  animation: fadeInUp 0.6s ease;
}

.login-brand {
  text-align: center;
}

.brand-logo {
  font-size: 48px;
  margin-bottom: 8px;
  filter: drop-shadow(0 0 20px rgba(245, 158, 11, 0.4));
}

.brand-name {
  font-family: var(--font-display);
  font-size: 32px;
  font-weight: 700;
  background: var(--gradient-gold);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  letter-spacing: 4px;
}

.brand-subtitle {
  font-family: var(--font-display);
  font-size: 12px;
  letter-spacing: 6px;
  color: var(--text-muted);
  margin-top: 4px;
}

.login-card {
  width: 440px;
  background: rgba(17, 24, 39, 0.9) !important;
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 1px solid rgba(255, 255, 255, 0.1) !important;
}

.login-tabs {
  padding-top: 8px;
}

.submit-btn {
  width: 100%;
  margin-top: 8px;
}

.tip {
  margin-top: 16px;
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
}

.tip-sep {
  margin: 0 8px;
  color: var(--border-color);
}

.login-footer {
  text-align: center;
}

.footer-text {
  font-size: 12px;
  color: var(--text-muted);
  letter-spacing: 1px;
}

@media (max-width: 480px) {
  .login-card {
    width: calc(100vw - 32px);
  }
  .brand-name {
    font-size: 26px;
  }
}
</style>
