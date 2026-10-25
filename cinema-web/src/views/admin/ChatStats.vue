<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { fetchChatStats, type ChatAssistantStats } from '../../api/admin'

/**
 * 对话助手用量与成本看板(v0.4.0 IT-11)。
 *
 * <p>**管理端的颜色纪律**(AGENTS.md「管理端是控制台」):这里一个 `--scene-*` 都不用 ——
 * 数字走墨色 `--ink` 系, 交互走 `--accent`, 层级靠结构不靠颜色。
 *
 * <p>成本是**估算值**:单价来自后端配置且需人工维护, 页面显式标注, 不冒充账单。
 */
const { t } = useI18n()

const stats = ref<ChatAssistantStats | null>(null)
const loading = ref(false)
const error = ref('')
const days = ref(7)

async function load() {
  loading.value = true
  error.value = ''
  try {
    stats.value = await fetchChatStats(days.value)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    stats.value = null
  } finally {
    loading.value = false
  }
}

/** 失败率 → 百分比字符串; 无样本时显示占位而不是 0%(0% 会被读成「一次都没失败」)。 */
const failureRateText = computed(() => {
  const s = stats.value
  if (!s || s.totalCalls === 0) return '—'
  return `${(s.failureRate * 100).toFixed(2)}%`
})

/** 成本(美分) → 美元字符串; 未知时显示「未配置单价」而不是 0。 */
const costText = computed(() => {
  const s = stats.value
  if (!s || s.costCents == null) return t('admin.chatStats.costUnknown')
  return `$${(s.costCents / 100).toFixed(4)}`
})

const p95Text = computed(() => {
  const s = stats.value
  if (!s || s.p95LatencyMs == null) return '—'
  return `${s.p95LatencyMs} ms`
})

/** 红线指标: 未经用户显式确认的写操作数。必须恒为 0。 */
const redlineOk = computed(() => (stats.value?.unconfirmedWrites ?? 0) === 0)

onMounted(load)
</script>

<template>
  <section class="chat-stats">
    <header class="chat-stats-head">
      <h2 class="chat-stats-title">{{ t('admin.chatStats.title') }}</h2>
      <div class="chat-stats-actions">
        <el-select v-model="days" size="small" style="width: 120px" @change="load">
          <el-option :value="1" :label="t('admin.chatStats.days1')" />
          <el-option :value="7" :label="t('admin.chatStats.days7')" />
          <el-option :value="30" :label="t('admin.chatStats.days30')" />
        </el-select>
        <el-button size="small" :loading="loading" @click="load">{{ t('admin.chatStats.refresh') }}</el-button>
      </div>
    </header>

    <p v-if="error" class="chat-stats-error">{{ error }}</p>

    <div v-loading="loading" class="chat-stats-body">
      <div class="stat-grid">
        <div class="stat-card">
          <div class="stat-label">{{ t('admin.chatStats.calls') }}</div>
          <div class="stat-value">{{ stats?.totalCalls ?? '—' }}</div>
          <div class="stat-sub">{{ t('admin.chatStats.sessions') }}: {{ stats?.sessions ?? 0 }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">{{ t('admin.chatStats.failureRate') }}</div>
          <div class="stat-value">{{ failureRateText }}</div>
          <div class="stat-sub">{{ t('admin.chatStats.failedCalls') }}: {{ stats?.failedCalls ?? 0 }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">{{ t('admin.chatStats.p95') }}</div>
          <div class="stat-value">{{ p95Text }}</div>
          <div class="stat-sub">{{ t('admin.chatStats.tokens') }}: {{ stats?.promptTokens ?? 0 }} / {{ stats?.completionTokens ?? 0 }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">{{ t('admin.chatStats.cost') }}</div>
          <div class="stat-value">{{ costText }}</div>
          <div class="stat-sub">{{ t('admin.chatStats.costDisclaimer') }}</div>
        </div>
      </div>

      <div class="stat-card stat-card-wide">
        <div class="stat-label">{{ t('admin.chatStats.redline') }}</div>
        <div class="stat-value" :class="redlineOk ? 'ok' : 'bad'">
          {{ stats?.unconfirmedWrites ?? '—' }}
        </div>
        <div class="stat-sub">{{ t('admin.chatStats.redlineHint') }}</div>
        <div class="stat-sub">
          {{ t('admin.chatStats.writeStats') }}: {{ stats?.writeSuccess ?? 0 }} / {{ stats?.writeAttempts ?? 0 }}
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.chat-stats {
  padding: 4px 2px;
}
.chat-stats-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.chat-stats-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
  margin: 0;
}
.chat-stats-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.chat-stats-error {
  color: var(--danger);
  font-size: 12px;
  margin: 0 0 8px;
}
.chat-stats-body {
  min-height: 120px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
}
.stat-card {
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
  background: var(--paper-raised);
  padding: 12px 14px;
}
.stat-card-wide {
  margin-top: 12px;
}
.stat-label {
  font-size: 11px;
  color: var(--ink-3);
  letter-spacing: 0.5px;
}
.stat-value {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-weight: 700;
  font-size: 22px;
  color: var(--ink);
  margin: 4px 0 2px;
}
.stat-value.ok {
  color: var(--ok);
}
.stat-value.bad {
  color: var(--danger);
}
.stat-sub {
  font-size: 11px;
  color: var(--ink-2);
  line-height: 1.5;
}
</style>
