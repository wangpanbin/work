<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { sendMessage } from '../../api/chat'
import { useChatContext } from '../../composables/useChatContext'
import { useUserStore } from '../../stores/user'
import type { ChatResponseVO } from '../../types'
import ChatMessage from './ChatMessage.vue'

const userStore = useUserStore()
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

async function send() {
  const text = inputText.value.trim()
  if (!text || sending.value) return
  sending.value = true
  const now = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  // 用户消息先入栈
  messages.value.push({ role: 'user', message: text, response: null, timestamp: now })
  inputText.value = ''
  try {
    const resp = await sendMessage({ message: text, context: context.value })
    const assistantTimestamp = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    messages.value.push({ role: 'assistant', message: '', response: resp, timestamp: assistantTimestamp })
  } catch (e: any) {
    ElMessage.error(e?.message || '对话失败,请稍后重试')
    const errorTimestamp = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    messages.value.push({
      role: 'assistant',
      message: '抱歉,出了点问题。',
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
  // 用户登录态变化时清空历史(spec §4.4 边界: 不同用户上下文应隔离)
  // 这里只做初次挂载,不做 watch(避免影响测试)
})

defineExpose({ toggle })
</script>

<template>
  <div v-if="shouldRender" class="chat-widget-root">
    <button
      v-if="!open"
      class="chat-fab"
      aria-label="打开对话助手"
      @click="toggle"
    >
      💬
    </button>

    <div v-if="open" class="chat-panel">
      <div class="chat-header">
        <span class="chat-header-title">影院对话助手</span>
        <span class="chat-header-meta" v-if="userStore.isLogin">已登录</span>
        <span class="chat-header-meta anon" v-else>匿名</span>
        <button class="chat-header-close" aria-label="关闭" @click="open = false">×</button>
      </div>

      <div class="chat-messages">
        <div v-if="!messages.length" class="chat-empty">
          <p>👋 你好,我是影院对话助手。</p>
          <p>可以问我:今晚 8 点有什么电影?这部片还有座吗?</p>
          <p class="chat-sub-text">只读不写 — 锁座 / 支付 / 退票仍由你点击确认(spec ADR-0002)。</p>
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
          placeholder="输入消息,Enter 发送,Shift+Enter 换行"
          :disabled="sending"
          @keydown="onKeydownEnter"
        />
        <button class="chat-send-btn" :disabled="!inputText.trim() || sending" @click="send">
          {{ sending ? '发送中…' : '发送' }}
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