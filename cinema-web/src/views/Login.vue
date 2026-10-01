<script setup lang="ts">
/**
 * 登录页 —— 票根布局（存根联 / 副券）
 *
 * <p>⚠️ 本文件里有 6 处**碰坏即回归**的行为, 改版式时别动:
 *   1. open-redirect 安全校验 (safeRedirect)
 *   2. 42900 限流 ElMessageBox
 *   3. 忘记密码占位弹窗
 *   4. handleRegister 里 validate() 的 reject 必须在 try/catch 内捕获并提前
 *      return —— 这是 P3-3 修过的 bug, 移出去会重新产生未处理 Promise rejection
 *   5. loginRules / registerRules 必须用 computed(() => ({...t()})),
 *      用普通对象则切语言后校验消息不会重算
 *   6. 注册成功要回填 username 并切回登录 tab
 */
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '../stores/user'
import { register } from '../api/user'
import BrandMark from '../components/BrandMark.vue'

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
  // P3-3:validate() 校验失败会 reject(不是 throw 到调用方),原来放在 try/catch 之外
  // → 每次表单校验不通过都产生一条未处理 Promise rejection(Vue warn)。
  // 这里显式捕获并提前返回,校验失败就不再发请求。
  try {
    await registerFormRef.value?.validate()
  } catch {
    return
  }
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

/**
 * 存根联上的条形码 —— 纯装饰, 不是可扫描的条码, 宽度手写只为"像票"。
 * 不要试图接真数据。
 */
const barcodeBars = [3, 1, 2, 1, 3, 2, 1, 1, 3, 1, 2, 3, 1, 2]
</script>

<template>
  <div class="login-page">
    <!-- 移动端品牌条: 存根联在 ≤480px 被隐藏, 用它保住品牌识别 -->
    <div class="login-mobile-brand">
      <BrandMark :size="26" />
      <span class="login-mobile-name">{{ t('login.brandName') }}</span>
    </div>

    <div class="login-stage">
      <!-- ============ 存根联（品牌面 · 装饰） ============
           齿孔撕口由 main.css 的 .stub-perf 提供, 是全站签名装置。
           下方场次 / 影厅 / 座位 / 票号全是**装饰性占位数据**,
           不接任何业务逻辑 —— 它要表达的是"这是一张票", 不是"这是你的票"。 -->
      <div class="stub stub-perf" aria-hidden="true">
        <div class="stub-head">
          <BrandMark :size="30" />
          <div class="stub-head-text">
            <div class="stub-brand">{{ t('login.brandName') }}</div>
            <div class="stub-brand-sub">{{ t('login.brandSubtitle') }}</div>
          </div>
        </div>

        <div class="stub-rule"></div>

        <div class="stub-fields">
          <div class="stub-field">
            <span class="stub-label">{{ t('login.stubShowtime') }}</span>
            <span class="stub-value mono">09-30 19:30</span>
          </div>
          <div class="stub-field">
            <span class="stub-label">{{ t('login.stubHall') }}</span>
            <span class="stub-value mono">3 LASER</span>
          </div>
          <div class="stub-field">
            <span class="stub-label">{{ t('login.stubSeats') }}</span>
            <span class="stub-value mono">R05 S12</span>
          </div>
        </div>

        <div class="stub-tear"></div>

        <div class="stub-foot">
          <div class="stub-no">
            <span class="stub-no-label">{{ t('login.stubTicketNo') }}</span>
            <span class="stub-no-value mono">8823-4471</span>
          </div>
          <div class="barcode">
            <i v-for="(w, i) in barcodeBars" :key="i" :style="{ width: w + 'px' }"></i>
          </div>
        </div>
      </div>

      <!-- ============ 副券（表单面） ============ -->
      <div class="counterfoil">
        <el-card class="counterfoil-card">
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
      </div>
    </div>

    <div class="login-footer">
      <span class="footer-text">{{ t('login.footerCopy') }}</span>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  min-height: calc(100vh - 64px);
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 28px;
  overflow: hidden;
  /* 存根联齿孔要靠"露出背后纸面"来成立, 所以页面底必须是 --paper */
  background: var(--paper);
  padding: 40px 16px;
}

/* ============ 存根联 + 副券 并排 ============ */
/* width:max-content 让两栏按内容定宽, max-width:100% 保证窄屏不撑破视口;
   两栏都加 flex-shrink:0 —— 否则 flex 收缩会把票面挤变形, 齿孔定位会跟着偏。 */
.login-stage {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: stretch;
  width: max-content;
  max-width: 100%;
  animation: fadeInUp 0.5s ease;
}

/* --- 存根联 --- */
.stub {
  width: 300px;
  flex-shrink: 0;
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  border-right: none;
  border-radius: var(--radius-lg) 0 0 var(--radius-lg);
  padding: 28px 24px 20px;
  display: flex;
  flex-direction: column;
  gap: 18px;
  color: var(--ink);
  overflow: hidden;
}

.stub-head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.stub-head-text {
  min-width: 0;
}
.stub-brand {
  font-family: var(--font-display);
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 2px;
  color: var(--ink);
  line-height: 1.2;
}
.stub-brand-sub {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 3px;
  color: var(--ink-3);
  margin-top: 2px;
}

.stub-rule {
  height: 1px;
  background: var(--rule);
  flex-shrink: 0;
}

/* 场次 / 影厅 / 座位: 左右两栏, 像票面表格 —— 标签在左, 等宽值在右。
   用 grid 而不是堆叠, 既更像真实票面, 也避免每格占两行把票面拉得过高。 */
.stub-fields {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.stub-field {
  display: grid;
  grid-template-columns: 3.5em 1fr;
  align-items: baseline;
  gap: 10px;
}
.stub-label {
  font-size: 10px;
  letter-spacing: 2px;
  color: var(--ink-3);
  white-space: nowrap;
}
.stub-value {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 15px;
  color: var(--ink);
  font-weight: 600;
  white-space: nowrap;
}

/* 撕口: 一排半圆缺口, 与 .stub-perf 的竖向撕口是同一个物理边界 */
.stub-tear {
  height: 10px;
  margin: 0 -24px;
  flex-shrink: 0;
  background-image: radial-gradient(circle at 5px 10px, var(--paper) 4.5px, transparent 5px);
  background-size: 14px 10px;
  background-position: center top;
  background-repeat: repeat-x;
}

.stub-foot {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.stub-no {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}
.stub-no-label {
  font-size: 10px;
  letter-spacing: 2px;
  color: var(--ink-3);
}
.stub-no-value {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 15px;
  color: var(--ink);
  font-weight: 600;
}

/* 条形码: 纯装饰, 宽度写死在脚本里的 barcodeBars */
.barcode {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 30px;
}
.barcode i {
  display: block;
  background: var(--ink);
  height: 100%;
}

/* --- 副券 --- */
.counterfoil {
  display: flex;
  flex-shrink: 0;
}
.counterfoil-card {
  width: 400px;
  max-width: 100%;
  border: 1px solid var(--rule) !important;
  border-left: none;
  border-radius: 0 var(--radius-lg) var(--radius-lg) 0 !important;
  box-shadow: var(--shadow-paper) !important;
  background: var(--paper-raised) !important;
}

/* tabs 改成分段控件: 纸面风里下划线太"App", 改成一格一块 */
.login-tabs {
  padding-top: 8px;
}
.login-tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}
.login-tabs :deep(.el-tabs__nav-wrap::after) {
  display: none;
}
.login-tabs :deep(.el-tabs__item) {
  height: 38px;
  line-height: 36px;
  border: 1px solid var(--rule);
  color: var(--ink-2);
  font-weight: 500;
}
.login-tabs :deep(.el-tabs__item:first-child) {
  border-radius: var(--radius-md) 0 0 var(--radius-md);
  margin-right: 0;
}
.login-tabs :deep(.el-tabs__item:last-child) {
  border-radius: 0 var(--radius-md) var(--radius-md) 0;
  border-left: none;
}
.login-tabs :deep(.el-tabs__item.is-active) {
  color: var(--ink-inverse);
  background: var(--accent);
  border-color: var(--accent);
}
.login-tabs :deep(.el-tabs__active-bar) {
  display: none;
}

.submit-btn {
  width: 100%;
  margin-top: 8px;
}

.tip {
  margin-top: 16px;
  text-align: center;
  color: var(--ink-3);
  font-size: 13px;
}

.tip-sep {
  margin: 0 8px;
  color: var(--rule-strong);
}

.login-footer {
  text-align: center;
  position: relative;
  z-index: 2;
}

.footer-text {
  font-size: 12px;
  color: var(--ink-3);
  letter-spacing: 1px;
}

/* 中屏: 两栏收窄但**保持并排** —— 齿孔撕口是签名装置,
   一旦上下堆叠就变成两个普通方块了, 所以宁可缩窄也不拆开。 */
@media (max-width: 820px) and (min-width: 481px) {
  .stub {
    width: 240px;
    padding: 22px 18px 16px;
  }
  .stub-brand {
    font-size: 17px;
  }
  .stub-value,
  .stub-no-value {
    font-size: 13px;
  }
  .stub-field {
    grid-template-columns: 3em 1fr;
  }
  .counterfoil-card {
    width: 340px;
  }
}

/* 移动端: 隐藏存根联, 只留 logo + 品牌名 + 表单 */
.login-mobile-brand {
  display: none;
  align-items: center;
  gap: 10px;
  position: relative;
  z-index: 2;
}
.login-mobile-name {
  font-family: var(--font-display);
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 2px;
  color: var(--ink);
}

@media (max-width: 480px) {
  .login-mobile-brand {
    display: flex;
  }
  .stub {
    display: none;
  }
  .counterfoil-card {
    width: calc(100vw - 32px);
    border-left: 1px solid var(--rule) !important;
    border-radius: var(--radius-lg) !important;
  }
  .login-page {
    padding: 24px 16px;
  }
}
</style>
