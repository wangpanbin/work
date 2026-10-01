<script setup lang="ts">
import { computed } from 'vue'
import type { ChatResponseVO } from '../../types'
import { renderMarkdown } from '../../utils/markdown'
import ActionCard from './ActionCard.vue'

const props = defineProps<{
  /** 说话方。错误提示也是 assistant 说的(见 ChatWidget 的 catch 分支) */
  role: 'user' | 'assistant'
  /** 用户消息;assistant 消息时为空字符串(错误提示除外,它由 assistant 说) */
  message: string
  /** assistant 响应(用户消息 / 纯文本错误提示时为空) */
  response?: ChatResponseVO | null
  /** 当前时间显示 */
  timestamp: string
}>()

/**
 * P2-2:LLM 回复的表格/加粗原本以原始 `|` 文本直出,这里走 Markdown 渲染。
 * `renderMarkdown` 内部先转义再拼标签,产物可安全交给 v-html(见 utils/markdown.ts 的 XSS 模型)。
 */
const renderedReply = computed(() => renderMarkdown(props.response?.reply))
</script>

<template>
  <!-- 按 role 分支,而不是"message 非空即用户":
       ChatWidget 的错误分支 push 的是 { role:'assistant', message:原因, response:null },
       旧模板会把它渲染成金色右对齐的"用户气泡",看起来像是用户自己说的话。 -->
  <div class="chat-msg user" v-if="role === 'user'">
    <div class="chat-msg-bubble user-bubble">{{ message }}</div>
    <div class="chat-msg-meta">{{ timestamp }}</div>
  </div>
  <div class="chat-msg assistant" v-else>
    <div class="chat-msg-bubble assistant-bubble">
      <!-- 纯文本错误提示(无 response) -->
      <div class="chat-md" v-if="!response">{{ message }}</div>
      <!-- P2-2:Markdown 渲染(已转义,见 utils/markdown.ts) -->
      <div class="chat-md" v-else v-html="renderedReply"></div>
      <div v-if="response && response.cards && response.cards.length" class="chat-msg-cards">
        <ActionCard
          v-for="(card, idx) in response.cards"
          :key="`${card.sessionId}-${idx}`"
          :card="card"
        />
      </div>
      <div v-if="response && response.followUps && response.followUps.length" class="chat-msg-followups">
        <span
          v-for="(q, idx) in response.followUps"
          :key="idx"
          class="chat-followup-chip"
        >{{ q }}</span>
      </div>
    </div>
    <div class="chat-msg-meta">{{ timestamp }}</div>
  </div>
</template>

<style scoped>
.chat-msg {
  display: flex;
  flex-direction: column;
  margin: 8px 0;
  max-width: 80%;
}
.chat-msg.user {
  align-self: flex-end;
  align-items: flex-end;
}
.chat-msg.assistant {
  align-self: flex-start;
  align-items: flex-start;
}
.chat-msg-bubble {
  padding: 8px 12px;
  border-radius: var(--radius-lg);
  font-size: 14px;
  line-height: 1.5;
  word-break: break-word;
  white-space: pre-wrap;
}
.user-bubble {
  background: var(--accent);
  color: var(--ink-inverse);
}
.assistant-bubble {
  background: var(--paper-sunk);
  color: var(--ink);
}

/* ---------- P2-2 Markdown 渲染样式 ---------- */
/* 气泡的 pre-wrap 是给纯文本用的;渲染成 HTML 后标签缩进会变成真实空白,必须关掉 */
.chat-md {
  white-space: normal;
  word-break: break-word;
}
.chat-md :deep(p) {
  margin: 0 0 6px;
}
.chat-md :deep(p:last-child) {
  margin-bottom: 0;
}
.chat-md :deep(table) {
  border-collapse: collapse;
  margin: 6px 0;
  font-size: 12px;
  display: block;
  overflow-x: auto;
  max-width: 100%;
}
.chat-md :deep(th),
.chat-md :deep(td) {
  border: 1px solid var(--rule);
  padding: 4px 8px;
  text-align: left;
  white-space: nowrap;
}
.chat-md :deep(th) {
  background: var(--accent-wash);
  color: var(--ink);
  font-weight: 600;
}
.chat-md :deep(ul),
.chat-md :deep(ol) {
  margin: 4px 0;
  padding-left: 20px;
}
.chat-md :deep(li) {
  margin: 2px 0;
}
.chat-md :deep(code) {
  background: var(--paper-sunk);
  border-radius: var(--radius-sm);
  padding: 1px 4px;
  font-size: 12px;
  font-family: var(--font-mono);
}
.chat-md :deep(pre) {
  background: var(--paper-sunk);
  border-radius: var(--radius-md);
  padding: 8px;
  margin: 6px 0;
  overflow-x: auto;
}
.chat-md :deep(pre code) {
  background: none;
  padding: 0;
  white-space: pre;
}
.chat-md :deep(blockquote) {
  margin: 6px 0;
  padding-left: 8px;
  border-left: 3px solid var(--rule-strong);
  color: var(--ink-3);
}
.chat-md :deep(h1),
.chat-md :deep(h2),
.chat-md :deep(h3),
.chat-md :deep(h4),
.chat-md :deep(h5),
.chat-md :deep(h6) {
  margin: 8px 0 4px;
  font-family: var(--font-display);
  font-size: 14px;
  font-weight: 600;
}
.chat-md :deep(hr) {
  border: none;
  border-top: 1px solid var(--rule);
  margin: 8px 0;
}
/* 时间戳: 等宽, 便于纵向对齐 */
.chat-msg-meta {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 11px;
  color: var(--ink-3);
  margin-top: 2px;
}
.chat-msg-cards {
  margin-top: 8px;
}
.chat-msg-followups {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.chat-followup-chip {
  background: var(--accent-wash);
  border: 1px solid var(--rule-strong);
  border-radius: 16px;
  padding: 4px 10px;
  font-size: 12px;
  color: var(--accent);
}
</style>