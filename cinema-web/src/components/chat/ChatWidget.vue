<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { sendMessage } from '../../api/chat'
import { useChatContext } from '../../composables/useChatContext'
import { useUserStore } from '../../stores/user'
import { detectLoginRequired } from '../../utils/loginRequiredDetector'
import type { ChatResponseVO } from '../../types'
import ChatMessage from './ChatMessage.vue'

const userStore = useUserStore()
const router = useRouter()
const { t, locale } = useI18n()
const { context, isHidden } = useChatContext()

const open = ref(false)
const messages = ref<Array<{ role: 'user' | 'assistant'; message: string; response: ChatResponseVO | null; timestamp: string }>>([])
const inputText = ref('')
const sending = ref(false)

const visibleDueToHidden = computed(() => isHidden.value)
const shouldRender = computed(() => !visibleDueToHidden.value)

function toggle() {
  open.value = !open.value
}

/**
 * spec #17 ID-5 — 检测到 LOGIN_REQUIRED 回复时,弹 ElMessageBox 引导登录。
 */
async function maybePromptLogin(reply: string | undefined) {
  if (!userStore.isLogin && detectLoginRequired(reply)) {
    try {
      await ElMessageBox.confirm(
        t('chat.loginRequiredBody'),
        t('chat.loginRequiredTitle'),
        { confirmButtonText: t('chat.loginRequiredOk'), cancelButtonText: t('chat.loginRequiredCancel'), type: 'info' },
      )
      router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
    } catch {
      // 用户点"稍后"或关闭,不动作
    }
  }
}

async function send() {
  const text = inputText.value.trim()
  if (!text || sending.value) return
  sending.value = true
  const now = new Date().toLocaleTimeString(locale.value, { hour: '2-digit', minute: '2-digit' })
  // 用户消息先入栈
  messages.value.push({ role: 'user', message: text, response: null, timestamp: now })
  inputText.value = ''
  try {
    const resp = await sendMessage({ message: text, context: context.value })
    const assistantTimestamp = new Date().toLocaleTimeString(locale.value, { hour: '2-digit', minute: '2-digit' })
    messages.value.push({ role: 'assistant', message: '', response: resp, timestamp: assistantTimestamp })
    maybePromptLogin(resp?.reply)
  } catch (e: any) {
    // P2-3:气泡里原本固定显示"抱歉,出了点问题。",把后端的具体原因(如 42900
    // 「对话请求过于频繁,请稍后再试」)全吞了。axios 拦截器 reject 的 Error 已带
    // msg,这里直接用;拿不到才退回通用文案。
    const reason = e?.message?.trim() || t('chat.errorGeneric')
    ElMessage.error(reason)
    const errorTimestamp = new Date().toLocaleTimeString(locale.value, { hour: '2-digit', minute: '2-digit' })
    messages.value.push({
      role: 'assistant',
      message: reason,
      response: null,
      timestamp: errorTimestamp,
    })
  } finally {
    sending.value = false
  }
}

function onKeydownEnter(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}

onMounted(() => {
  // 用户登录态变化时清空历史(spec §4.4 边界)
})

defineExpose({ toggle })
</script>

<template>
  <div v-if="shouldRender" class="chat-widget-root">
    <button
      v-if="!open"
      class="chat-fab"
      :aria-label="t('chat.fabAria')"
      @click="toggle"
    >
      💬
    </button>

    <div v-if="open" class="chat-panel">
      <div class="chat-header">
        <span class="chat-header-title">{{ t('chat.title') }}</span>
        <span class="chat-header-meta" v-if="userStore.isLogin">{{ t('chat.metaLogged') }}</span>
        <span class="chat-header-meta anon" v-else>{{ t('chat.metaAnon') }}</span>
        <button class="chat-header-close" :aria-label="t('chat.closeAria')" @click="open = false">×</button>
      </div>

      <div class="chat-messages">
        <div v-if="!messages.length" class="chat-empty">
          <p>{{ t('chat.emptyGreeting') }}</p>
          <p>{{ t('chat.emptyExamples') }}</p>
          <p class="chat-sub-text">{{ t('chat.emptyReadonly') }}</p>
        </div>
        <ChatMessage
          v-for="(m, idx) in messages"
          :key="idx"
          :role="m.role"
          :message="m.message"
          :response="m.response"
          :timestamp="m.timestamp"
        />
      </div>

      <div class="chat-input">
        <textarea
          v-model="inputText"
          rows="2"
          :placeholder="t('chat.inputPlaceholder')"
          :disabled="sending"
          @keydown="onKeydownEnter"
        />
        <button class="chat-send-btn" :disabled="!inputText.trim() || sending" @click="send">
          {{ sending ? t('chat.sending') : t('chat.sendBtn') }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-widget-root {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 9999;
}
.chat-fab {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--accent);
  color: var(--ink-inverse);
  border: 1px solid var(--accent);
  font-size: 24px;
  cursor: pointer;
  box-shadow: var(--shadow-modal);
  transition: background-color var(--transition-fast);
}
.chat-fab:hover {
  background: var(--accent-hover);
  border-color: var(--accent-hover);
}
.chat-panel {
  width: 360px;
  height: 520px;
  background: var(--paper-raised);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-modal);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--rule);
}
.chat-header {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  background: var(--paper-sunk);
  border-bottom: 1px solid var(--rule);
}
.chat-header-title {
  font-family: var(--font-display);
  font-size: 14px;
  font-weight: 600;
  flex: 1;
}
.chat-header-meta {
  font-size: 11px;
  color: var(--ok);
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  padding: 2px 8px;
  border-radius: 10px;
}
.chat-header-meta.anon {
  color: var(--ink-2);
}
.chat-header-close {
  background: none;
  border: none;
  font-size: 22px;
  cursor: pointer;
  color: var(--ink-3);
  margin-left: 8px;
}
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
}
.chat-empty {
  text-align: center;
  color: var(--ink-3);
  font-size: 13px;
  margin-top: 24px;
}
.chat-empty p {
  margin: 4px 0;
}
.chat-sub-text {
  font-size: 11px;
  margin-top: 8px !important;
}
.chat-input {
  border-top: 1px solid var(--rule);
  padding: 10px;
  display: flex;
  gap: 8px;
  align-items: stretch;
}
.chat-input textarea {
  flex: 1;
  border: 1px solid var(--rule);
  border-radius: var(--radius-md);
  padding: 8px;
  font-size: 13px;
  resize: none;
  font-family: inherit;
  background: var(--paper);
  color: var(--ink);
}
.chat-send-btn {
  background: var(--accent);
  color: var(--ink-inverse);
  border: none;
  border-radius: var(--radius-md);
  padding: 0 14px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  transition: background-color var(--transition-fast);
}
.chat-send-btn:hover:not(:disabled) {
  background: var(--accent-hover);
}
.chat-send-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>