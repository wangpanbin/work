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
    ElMessage.error(e?.message || t('chat.errorSend'))
    const errorTimestamp = new Date().toLocaleTimeString(locale.value, { hour: '2-digit', minute: '2-digit' })
    messages.value.push({
      role: 'assistant',
      message: t('chat.errorGeneric'),
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
  font-family: var(--font-body, sans-serif);
}
.chat-fab {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--accent-gold, #f59e0b);
  color: #1a1a1a;
  border: none;
  font-size: 24px;
  cursor: pointer;
  box-shadow: 0 4px 16px rgba(245, 158, 11, 0.3);
  transition: transform 0.2s;
}
.chat-fab:hover {
  transform: scale(1.05);
}
.chat-panel {
  width: 360px;
  height: 520px;
  background: var(--bg-secondary, #fff);
  border-radius: 14px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.15);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--border-color, #e5e5e5);
}
.chat-header {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  background: var(--bg-tertiary, #fafafa);
  border-bottom: 1px solid var(--border-color, #e5e5e5);
}
.chat-header-title {
  font-size: 14px;
  font-weight: 600;
  flex: 1;
}
.chat-header-meta {
  font-size: 11px;
  color: var(--text-muted, #999);
  padding: 2px 8px;
  border-radius: 10px;
  background: rgba(34, 197, 94, 0.15);
  color: rgb(34, 197, 94);
}
.chat-header-meta.anon {
  background: rgba(156, 163, 175, 0.15);
  color: rgb(107, 114, 128);
}
.chat-header-close {
  background: none;
  border: none;
  font-size: 22px;
  cursor: pointer;
  color: var(--text-muted, #999);
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
  color: var(--text-muted, #999);
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
  border-top: 1px solid var(--border-color, #e5e5e5);
  padding: 10px;
  display: flex;
  gap: 8px;
  align-items: stretch;
}
.chat-input textarea {
  flex: 1;
  border: 1px solid var(--border-color, #e5e5e5);
  border-radius: 8px;
  padding: 8px;
  font-size: 13px;
  resize: none;
  font-family: inherit;
}
.chat-send-btn {
  background: var(--accent-gold, #f59e0b);
  color: #1a1a1a;
  border: none;
  border-radius: 8px;
  padding: 0 14px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
}
.chat-send-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>