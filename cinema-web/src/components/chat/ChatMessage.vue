<script setup lang="ts">
import type { ChatResponseVO } from '../../types'
import ActionCard from './ActionCard.vue'

defineProps<{
  /** 用户消息(空字符串表示 assistant 消息) */
  message: string
  /** assistant 响应(用户消息时为空) */
  response?: ChatResponseVO | null
  /** 当前时间显示 */
  timestamp: string
}>()
</script>

<template>
  <div class="chat-msg user" v-if="message">
    <div class="chat-msg-bubble user-bubble">{{ message }}</div>
    <div class="chat-msg-meta">{{ timestamp }}</div>
  </div>
  <div class="chat-msg assistant" v-else-if="response">
    <div class="chat-msg-bubble assistant-bubble">
      {{ response.reply }}
      <div v-if="response.cards && response.cards.length" class="chat-msg-cards">
        <ActionCard
          v-for="(card, idx) in response.cards"
          :key="`${card.sessionId}-${idx}`"
          :card="card"
        />
      </div>
      <div v-if="response.followUps && response.followUps.length" class="chat-msg-followups">
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
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.5;
  word-break: break-word;
  white-space: pre-wrap;
}
.user-bubble {
  background: var(--accent-gold, #f59e0b);
  color: #1a1a1a;
}
.assistant-bubble {
  background: var(--bg-tertiary, #f5f5f5);
  color: var(--text-primary, #333);
}
.chat-msg-meta {
  font-size: 11px;
  color: var(--text-muted, #999);
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
  background: rgba(245, 158, 11, 0.1);
  border: 1px solid rgba(245, 158, 11, 0.3);
  border-radius: 16px;
  padding: 4px 10px;
  font-size: 12px;
  color: var(--accent-gold, #f59e0b);
}
</style>