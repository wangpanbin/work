<script setup lang="ts">
import { ref, nextTick, onMounted } from 'vue'
import { useUserStore } from '../stores/user'
import { sendMessage } from '../api/chat'

const userStore = useUserStore()

interface ChatMsg {
  role: 'user' | 'ai'
  content: string
  time: string
}

const messages = ref<ChatMsg[]>([])
const input = ref('')
const loading = ref(false)
const listRef = ref<HTMLElement | null>(null)

const quickQuestions = [
  '怎么买票？',
  '能退票吗？',
  '怎么取票？',
  '能选几个座位？',
  '票价是多少？',
]

function now(): string {
  const d = new Date()
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function scrollBottom() {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

async function send(text: string) {
  if (!text.trim() || loading.value) return
  const content = text.trim()
  messages.value.push({ role: 'user', content, time: now() })
  input.value = ''
  loading.value = true
  scrollBottom()

  try {
    const res = await sendMessage(content)
    messages.value.push({ role: 'ai', content: res.reply, time: now() })
  } catch {
    messages.value.push({ role: 'ai', content: '网络异常，请稍后重试~', time: now() })
  } finally {
    loading.value = false
    scrollBottom()
  }
}

function onQuickAsk(q: string) {
  send(q)
}

onMounted(() => {
  // 初始欢迎消息
  messages.value.push({
    role: 'ai',
    content: `你好 ${userStore.user?.nickname || userStore.user?.username || ''}！我是影院智能助手，可以回答您关于购票、选座、支付、退票等问题。请问有什么可以帮您？`,
    time: now(),
  })
})
</script>

<template>
  <div class="chat-page">
    <!-- Header -->
    <div class="chat-header">
      <div class="header-left">
        <div class="avatar ai-avatar">AI</div>
        <div class="header-info">
          <h3>影院智能助手</h3>
          <span class="status-dot"></span>
          <span class="status-text">在线</span>
        </div>
      </div>
    </div>

    <!-- Messages -->
    <div class="chat-messages" ref="listRef">
      <div v-for="(msg, i) in messages" :key="i" class="msg-row" :class="msg.role">
        <div class="msg-avatar" :class="msg.role === 'user' ? 'user-avatar' : 'ai-avatar'">
          {{ msg.role === 'user' ? (userStore.user?.nickname?.[0] || '我') : 'AI' }}
        </div>
        <div class="msg-body">
          <div class="msg-bubble">{{ msg.content }}</div>
          <div class="msg-time">{{ msg.time }}</div>
        </div>
      </div>
      <!-- Typing indicator -->
      <div v-if="loading" class="msg-row ai">
        <div class="msg-avatar ai-avatar">AI</div>
        <div class="msg-body">
          <div class="msg-bubble typing">
            <span></span><span></span><span></span>
          </div>
        </div>
      </div>
    </div>

    <!-- Quick questions (only show when no user message yet) -->
    <div v-if="messages.length <= 1 && !loading" class="quick-questions">
      <button v-for="q in quickQuestions" :key="q" class="quick-btn" @click="onQuickAsk(q)">
        {{ q }}
      </button>
    </div>

    <!-- Input -->
    <div class="chat-input">
      <input
        v-model="input"
        @keyup.enter="send(input)"
        placeholder="输入您的问题..."
        :disabled="loading"
      />
      <button @click="send(input)" :disabled="loading || !input.trim()">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="20" height="20">
          <line x1="22" y1="2" x2="11" y2="13" />
          <polygon points="22 2 15 22 11 13 2 9 22 2" />
        </svg>
      </button>
    </div>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 80px);
  max-width: 800px;
  margin: 0 auto;
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  overflow: hidden;
  animation: fadeInUp 0.4s ease;
}

/* Header */
.chat-header {
  display: flex;
  align-items: center;
  padding: 16px 20px;
  background: var(--bg-primary);
  border-bottom: 1px solid var(--border-subtle);
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.header-info h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}
.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #10b981;
  margin-right: 4px;
  vertical-align: middle;
}
.status-text {
  font-size: 12px;
  color: var(--text-muted);
}

/* Avatar */
.msg-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  flex-shrink: 0;
}
.ai-avatar {
  background: linear-gradient(135deg, var(--accent-gold), #f59e0b);
  color: #000;
}
.user-avatar {
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff;
}

/* Messages */
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.msg-row {
  display: flex;
  gap: 10px;
  max-width: 80%;
}
.msg-row.user {
  align-self: flex-end;
  flex-direction: row-reverse;
}
.msg-row.ai {
  align-self: flex-start;
}
.msg-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.msg-row.user .msg-body {
  align-items: flex-end;
}
.msg-bubble {
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}
.msg-row.ai .msg-bubble {
  background: var(--bg-tertiary);
  color: var(--text-primary);
  border-top-left-radius: 4px;
}
.msg-row.user .msg-bubble {
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff;
  border-top-right-radius: 4px;
}
.msg-time {
  font-size: 11px;
  color: var(--text-muted);
  padding: 0 4px;
}

/* Typing indicator */
.typing {
  display: flex;
  gap: 4px;
  align-items: center;
  padding: 12px 16px;
}
.typing span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-muted);
  animation: typing-bounce 1.2s ease-in-out infinite;
}
.typing span:nth-child(2) { animation-delay: 0.2s; }
.typing span:nth-child(3) { animation-delay: 0.4s; }
@keyframes typing-bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.4; }
  30% { transform: translateY(-6px); opacity: 1; }
}

/* Quick questions */
.quick-questions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 20px;
  border-top: 1px solid var(--border-subtle);
  background: var(--bg-primary);
}
.quick-btn {
  padding: 6px 14px;
  border-radius: 16px;
  border: 1px solid var(--border-color);
  background: var(--bg-secondary);
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}
.quick-btn:hover {
  border-color: var(--accent-gold);
  color: var(--accent-gold);
  background: rgba(245, 158, 11, 0.06);
}

/* Input */
.chat-input {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 20px;
  background: var(--bg-primary);
  border-top: 1px solid var(--border-subtle);
}
.chat-input input {
  flex: 1;
  padding: 10px 16px;
  border-radius: 20px;
  border: 1px solid var(--border-color);
  background: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
}
.chat-input input:focus {
  border-color: var(--accent-gold);
}
.chat-input input::placeholder {
  color: var(--text-muted);
}
.chat-input button {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: none;
  background: linear-gradient(135deg, var(--accent-gold), #f59e0b);
  color: #000;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.2s, opacity 0.2s;
  flex-shrink: 0;
}
.chat-input button:hover:not(:disabled) {
  transform: scale(1.05);
}
.chat-input button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

/* Responsive */
@media (max-width: 768px) {
  .chat-page {
    height: calc(100vh - 60px);
    border-radius: 0;
    border-left: none;
    border-right: none;
  }
  .msg-row {
    max-width: 90%;
  }
  .quick-questions {
    padding: 8px 12px;
  }
  .quick-btn {
    font-size: 12px;
    padding: 5px 10px;
  }
}
</style>
