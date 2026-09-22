<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '../stores/user'
import { register } from '../api/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const { t } = useI18n()

const activeTab = ref<'login' | 'register'>('login')
const loading = ref(false)

const loginFormRef = ref<FormInstance>()
const loginForm = reactive({ username: '', password: '' })

// FormRules 必须用 computed:message 字段绑定的字符串在 setup 时计算,
// 若用普通对象赋值,locale 切换后 el-form-item 不会重新计算 message。
// computed() 让 rules 在 locale 变化时自动重算。
const loginRules = computed<FormRules>(() => ({
  username: [{ required: true, message: t('login.username'), trigger: 'blur' }],
  password: [{ required: true, message: t('login.password'), trigger: 'blur' }],
}))

const registerFormRef = ref<FormInstance>()
const registerForm = reactive({ username: '', password: '', confirmPassword: '', nickname: '', phone: '' })
const registerRules = computed<FormRules>(() => ({
  username: [
    { required: true, message: t('login.username'), trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]{4,20}$/, message: t('login.usernameReg'), trigger: 'blur' },
  ],
  password: [
    { required: true, message: t('login.password'), trigger: 'blur' },
    { min: 6, max: 32, message: t('login.passwordReg'), trigger: 'blur' },
  ],
  // P1-#8: 确认密码, 提交前再次校验用户输入无错
  confirmPassword: [
    { required: true, message: t('login.confirmPasswordReg'), trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== registerForm.password) {
          callback(new Error(t('login.passwordMismatch')))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}))

async function handleLogin() {
  await loginFormRef.value?.validate()
  loading.value = true
  try {
    await userStore.login(loginForm.username, loginForm.password)
    ElMessage.success(t('login.successLogin'))
    // 登录成功后: 优先回 redirect, 防止从抢票页踢出后回不去
    const redirect = String(route.query.redirect || '')
    // 安全校验: 只接受站内相对路径, 防止 open redirect
    const safeRedirect = redirect.startsWith('/') && !redirect.startsWith('//') ? redirect : '/'
    router.push(safeRedirect)
  } catch (e: unknown) {
    // P1-#11: 拦截器已弹通用 ElMessage, 这里针对"被限流"特殊码给更详细的 alert 提示
    const err = e as { code?: number }
    if (err.code === 42900) {
      await ElMessageBox.alert(
        t('login.rateLimitContent'),
        t('login.titleRateLimit'),
        { confirmButtonText: t('login.rateLimitOk'), type: 'warning' },
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
      t('login.forgotContent'),
      t('login.titleForgot'),
      { confirmButtonText: t('login.forgotOk'), type: 'info' },
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
    ElMessage.success(t('login.successRegister'))
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
        <div class="brand-name">{{ t('login.brandName') }}</div>
        <div class="brand-subtitle">{{ t('login.brandSubtitle') }}</div>
      </div>

      <el-card class="login-card">
        <el-tabs v-model="activeTab" stretch class="login-tabs">
          <el-tab-pane :label="t('login.tabLogin')" name="login">
            <el-form ref="loginFormRef" :model="loginForm" :rules="loginRules" label-width="0" @keyup.enter="handleLogin">
              <el-form-item prop="username">
                <el-input v-model="loginForm.username" :placeholder="t('login.username')" size="large" />
              </el-form-item>
              <el-form-item prop="password">
                <el-input v-model="loginForm.password" type="password" show-password :placeholder="t('login.password')" size="large" />
              </el-form-item>
              <el-button type="primary" size="large" class="submit-btn" :loading="loading" @click="handleLogin">
                {{ t('login.submitLogin') }}
              </el-button>
              <p class="tip">
                {{ t('login.tipFirstUse') }} <a href="#" @click.prevent="activeTab = 'register'">{{ t('login.tipRegister') }}</a>
                <span class="tip-sep">·</span>
                <a href="#" @click.prevent="onForgotPassword">{{ t('login.tipForgot') }}</a>
              </p>
            </el-form>
          </el-tab-pane>

          <el-tab-pane :label="t('login.tabRegister')" name="register">
            <el-form ref="registerFormRef" :model="registerForm" :rules="registerRules" label-width="0">
              <el-form-item prop="username">
                <el-input v-model="registerForm.username" :placeholder="t('login.usernameReg')" size="large" />
              </el-form-item>
              <el-form-item prop="password">
                <el-input v-model="registerForm.password" type="password" show-password :placeholder="t('login.passwordReg')" size="large" />
              </el-form-item>
              <!-- P1-#8: 确认密码, 避免输错注册后无法登录 -->
              <el-form-item prop="confirmPassword">
                <el-input v-model="registerForm.confirmPassword" type="password" show-password :placeholder="t('login.confirmPassword')" size="large" @keyup.enter="handleRegister" />
              </el-form-item>
              <el-form-item prop="nickname">
                <el-input v-model="registerForm.nickname" :placeholder="t('login.nickname')" size="large" />
              </el-form-item>
              <el-form-item prop="phone">
                <el-input v-model="registerForm.phone" :placeholder="t('login.phone')" size="large" />
              </el-form-item>
              <el-button type="primary" size="large" class="submit-btn" :loading="loading" @click="handleRegister">
                {{ t('login.submitRegister') }}
              </el-button>
            </el-form>
          </el-tab-pane>
        </el-tabs>
      </el-card>

      <div class="login-footer">
        <span class="footer-text">{{ t('login.footerCopy') }}</span>
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
