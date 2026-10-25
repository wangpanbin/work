<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { sessionPage, sessionCreate, sessionUpdate, sessionDelete, hallList, recoverSessionBitmap } from '../../api/admin'
import { page as moviePage } from '../../api/movie'
import {
  SESSION_STATUS_CODES,
  SESSION_STATUS_LABEL_KEY,
  resolveSessionStatusLabelKey,
  resolveSessionStatusTagType,
} from '../session/constants'
import type { Session } from '../../api/session'
import type { Movie } from '../../types'
import type { Hall } from '../../api/session'

const { t } = useI18n()
const list = ref<Session[]>([])
const movies = ref<Movie[]>([])
const halls = ref<Hall[]>([])
const dialogVisible = ref(false)
const editing = ref<Session | null>(null)
const form = reactive({ movieId: 0, hallId: 0, startTime: '', price: 49.9, status: 1 })
/** 分页状态 —— 2026-10-24: 场次表实测一次要返回 1000+ 行, 改走 /admin/sessions/page */
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const movieMap = computed(() => Object.fromEntries(movies.value.map((m) => [m.id, m])))
const hallMap = computed(() => Object.fromEntries(halls.value.map((h) => [h.id, h])))

async function load() {
  try {
    const data = await sessionPage({ page: pageNum.value, size: pageSize.value })
    list.value = data.records ?? []
    total.value = Number(data.total ?? 0)
  } catch {
    // 拦截器已提示
  }
}

function onPageChange(p: number) {
  pageNum.value = p
  load()
}

function onSizeChange(s: number) {
  pageSize.value = s
  pageNum.value = 1
  load()
}

async function loadOptions() {
  try {
    movies.value = (await moviePage({ page: 1, size: 100 })).records
    halls.value = await hallList()
  } catch {
    // 拦截器已提示
  }
}

function openCreate() {
  editing.value = null
  Object.assign(form, {
    movieId: movies.value[0]?.id || 0,
    hallId: halls.value[0]?.id || 0,
    startTime: dayjs().add(1, 'day').hour(19).minute(30).second(0).format('YYYY-MM-DDTHH:mm:ss'),
    price: 49.9, status: 1,
  })
  dialogVisible.value = true
}

function openEdit(row: Session) {
  editing.value = row
  Object.assign(form, {
    movieId: row.movieId,
    hallId: row.hallId,
    startTime: row.startTime.replace(' ', 'T').substring(0, 19),
    price: row.price,
    status: row.status,
  })
  dialogVisible.value = true
}

async function onSubmit() {
  try {
    if (editing.value) {
      await sessionUpdate(editing.value.id, { ...form, price: Number(form.price) })
      ElMessage.success(t('admin.sessionsUpdated'))
    } else {
      await sessionCreate({ ...form, price: Number(form.price) })
      ElMessage.success(t('admin.sessionsCreated'))
    }
    dialogVisible.value = false
    load()
  } catch {
    // 拦截器已提示; 保留弹窗让用户修正后重试
  }
}

async function onDelete(row: Session) {
  try {
    await sessionDelete(row.id)
    ElMessage.success(t('admin.sessionsDeleted'))
    // 删掉本页最后一条时回退一页, 否则停在空页上
    if (list.value.length === 1 && pageNum.value > 1) {
      pageNum.value -= 1
    }
    load()
  } catch {
    // 拦截器已提示
  }
}

/**
 * 手动恢复该场次的座位位图 —— 2026-10-24。
 *
 * <p>这个能力后端一直有(`POST /api/admin/sessions/bitmaps/recover`, P0-P5),
 * 但前端从来没有入口, 于是 `PRODUCT.md` 宣传的「手动恢复位图」在产品里点不到。
 * 位图与订单状态对不上时(Redis 重启 / flush / 历史脏位图), 管理员靠它把座位放回正确状态。
 */
async function onRecoverBitmap(row: Session) {
  try {
    await recoverSessionBitmap(row.id)
    ElMessage.success(t('admin.sessionsBitmapRecovered'))
  } catch {
    // 拦截器已提示
  }
}

onMounted(async () => {
  await loadOptions()
  await load()
})
</script>

<template>
  <div>
    <div class="page-header">
      <h2>{{ t('admin.sessionsTitle') }}</h2>
      <div class="header-actions">
        <el-button type="primary" @click="openCreate">{{ t('admin.sessionsAdd') }}</el-button>
      </div>
    </div>

    <el-table :data="list" stripe style="margin-top: 12px">
      <el-table-column label="ID" width="80">
        <template #default="{ row }">
          <span class="id-cell" :title="String(row.id)">#{{ row.id }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('admin.sessionsMovieCol')">
        <template #default="{ row }">{{ movieMap[row.movieId]?.title || row.movieId }}</template>
      </el-table-column>
      <el-table-column :label="t('admin.sessionsHallLabelCol')">
        <template #default="{ row }">{{ hallMap[row.hallId]?.name || row.hallId }}</template>
      </el-table-column>
      <el-table-column :label="t('admin.sessionsStartLabel')">
        <template #default="{ row }">{{ dayjs(row.startTime).format('MM-DD HH:mm') }}</template>
      </el-table-column>
      <el-table-column prop="price" :label="t('admin.sessionsPriceLabel')" width="100" />
      <el-table-column :label="t('admin.moviesStatusCol')" width="100">
        <template #default="{ row }">
          <el-tag :type="resolveSessionStatusTagType(row.status)">
            {{ t(resolveSessionStatusLabelKey(row.status)) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('admin.sessionsActionsCol')" width="240">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">{{ t('admin.moviesEdit') }}</el-button>
          <el-button link type="warning" @click="onRecoverBitmap(row)">{{ t('admin.sessionsRecoverBitmap') }}</el-button>
          <el-popconfirm :title="t('admin.sessionsConfirmDelete')" @confirm="onDelete(row)">
            <template #reference><el-button link type="danger">{{ t('admin.moviesDelete') }}</el-button></template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="sessions-pager"
      layout="total, sizes, prev, pager, next"
      :total="total"
      :current-page="pageNum"
      :page-size="pageSize"
      :page-sizes="[10, 20, 50, 100]"
      @current-change="onPageChange"
      @size-change="onSizeChange"
    />

    <el-dialog v-model="dialogVisible" :title="editing ? t('admin.sessionsEditTitle') : t('admin.sessionsAddTitle')" width="540px">
      <el-form :model="form" label-width="100px">
        <el-form-item :label="t('admin.sessionsMovieCol')">
          <el-select v-model="form.movieId" filterable>
            <el-option v-for="m in movies" :key="m.id" :value="m.id" :label="m.title" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('admin.sessionsHallLabelCol')">
          <el-select v-model="form.hallId">
            <el-option v-for="h in halls" :key="h.id" :value="h.id" :label="`${h.name} (${h.seatRows}x${h.seatCols}=${h.seatCount})`" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('admin.sessionsStartLabel')">
          <el-input v-model="form.startTime" placeholder="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
        <el-form-item :label="t('admin.sessionsPriceLabel')"><el-input-number v-model="form.price" :min="0.01" :precision="2" /></el-form-item>
        <el-form-item :label="t('admin.moviesStatusCol')">
          <el-select v-model="form.status">
            <el-option
              v-for="code in SESSION_STATUS_CODES"
              :key="code"
              :value="code"
              :label="t(SESSION_STATUS_LABEL_KEY[code])"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="onSubmit">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 页面标题: 衬线 */
h2 {
  margin: 0 0 12px;
  font-family: var(--font-display);
  font-size: 22px;
  color: var(--ink);
}
.id-cell {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 13px;
  color: var(--ink-2);
  display: inline-block;
  max-width: 78px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}

/* 统一页头: 标题在左, 操作在右 —— 与经营看板同一套骨架 */
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.page-header h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: 22px;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
/* 表格里的 ID 是票据数据 → 等宽墨色, 数字列能对齐 */
.id-cell {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  color: var(--ink-2);
}
@media (max-width: 768px) {
  .page-header h2 { font-size: 18px; }
}

/* 分页条: 右对齐, 与表格留出呼吸间距 */
.sessions-pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
