<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { errMsg, validateForm } from '../../utils/adminForm'
import { useI18n } from 'vue-i18n'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
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
import type { Id } from '../../types'

const { t } = useI18n()
const list = ref<Session[]>([])
const movies = ref<Movie[]>([])
const halls = ref<Hall[]>([])
/** 列表加载失败态 —— 与「空表」互斥 */
const loadError = ref(false)
/** 下拉选项加载失败态 —— 此时提交出去的是 movieId = 0 的脏数据 */
const optionsError = ref(false)
const dialogVisible = ref(false)
const editing = ref<Session | null>(null)
/** 保存闸门 —— 场次 CRUD 没有幂等键, 连点两下就会建出两场 */
const submitting = ref(false)
/** 后端返回的错误原文 —— 留在弹窗里, 而不是只闪一条 toast */
const submitError = ref('')
/** 删除 / 恢复位图在途的行 id */
const deletingId = ref<Id | null>(null)
const recoveringId = ref<Id | null>(null)
const formRef = ref<FormInstance>()
const form = reactive({ movieId: 0, hallId: 0, startTime: '', price: 49.9, status: 1 })
/** 分页状态 —— 2026-10-24: 场次表实测一次要返回 1000+ 行, 改走 /admin/sessions/page */
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const movieMap = computed(() => Object.fromEntries(movies.value.map((m) => [m.id, m])))
const hallMap = computed(() => Object.fromEntries(halls.value.map((h) => [h.id, h])))

/**
 * 下拉有没有选 —— id 可能是数字也可能是字符串。
 *
 * <p>⚠️ 不能按「是不是数字」判: 后端 Jackson 只在 |值| > 2^53-1 时把 Long
 * 序列化成字符串, 所以新建出来的影片/影厅 id 是**字符串**(雪花 ID), 种子数据
 * 才是数字。按数字判会把新建的影片直接判成非法, 场次根本存不进去。
 * 真正要拦的只有「没选」: 0 / 空 / null / undefined。
 */
function isPickedId(value: unknown): boolean {
  if (value === null || value === undefined || value === '') return false
  return Number(value) > 0
}

// rules 必须用 computed: message 里的 t() 在 setup 时求值, 切 locale 后不会重算
const rules = computed<FormRules>(() => ({
  // 下拉是 id, 用自定义 validator 而不是 type: 'number' —— 字符串 id 也算数
  movieId: [{
    required: true,
    validator: (_rule: unknown, value: unknown, cb: (e?: string | Error) => void) => {
      if (isPickedId(value)) cb()
      else cb(new Error(t('admin.sessionMovieRequired')))
    },
    trigger: 'change',
  }],
  hallId: [{
    required: true,
    validator: (_rule: unknown, value: unknown, cb: (e?: string | Error) => void) => {
      if (isPickedId(value)) cb()
      else cb(new Error(t('admin.sessionHallRequired')))
    },
    trigger: 'change',
  }],
  startTime: [{ required: true, whitespace: true, message: t('admin.sessionStartTimeRequired'), trigger: 'blur' }],
  price: [{ required: true, type: 'number', min: 0.01, message: t('admin.sessionPriceRequired'), trigger: 'change' }],
}))

/**
 * 开场时间归一 —— 后端只认 yyyy-MM-ddTHH:mm:ss。
 *
 * <p>用户从别处粘一份 "2026-01-01 19:30:00" 过来, 以前会直接吃后端 40001,
 * 而他并不知道是自己格式错了。这里先把日期与时间之间的空格换成 T,
 * 让「写法不同」不再等于「提交失败」。
 */
function normalizeStartTime(raw: string): string {
  return raw.trim().replace(' ', 'T')
}

async function load() {
  loadError.value = false
  try {
    const data = await sessionPage({ page: pageNum.value, size: pageSize.value })
    list.value = data.records ?? []
    total.value = Number(data.total ?? 0)
  } catch {
    // 拦截器已提示; 这里只把「加载失败」摆到台面上, 不让表格显示成「暂无数据」
    loadError.value = true
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
  optionsError.value = false
  try {
    movies.value = (await moviePage({ page: 1, size: 100 })).records
    halls.value = await hallList()
  } catch {
    // 拦截器已提示; 这里记一笔 —— 下拉是空的, 保存必须被挡住
    optionsError.value = true
  }
}

function openCreate() {
  editing.value = null
  submitError.value = ''
  Object.assign(form, {
    movieId: movies.value[0]?.id || 0,
    hallId: halls.value[0]?.id || 0,
    startTime: dayjs().add(1, 'day').hour(19).minute(30).second(0).format('YYYY-MM-DDTHH:mm:ss'),
    price: 49.9, status: 1,
  })
  formRef.value?.clearValidate()
  dialogVisible.value = true
}

function openEdit(row: Session) {
  editing.value = row
  submitError.value = ''
  Object.assign(form, {
    movieId: row.movieId,
    hallId: row.hallId,
    startTime: row.startTime.replace(' ', 'T').substring(0, 19),
    price: row.price,
    status: row.status,
  })
  formRef.value?.clearValidate()
  dialogVisible.value = true
}

async function onSubmit() {
  if (submitting.value) return
  submitting.value = true
  submitError.value = ''
  try {
    if (!(await validateForm(formRef.value))) return
    const payload = {
      ...form,
      startTime: normalizeStartTime(form.startTime),
      price: Number(form.price),
    }
    if (editing.value) {
      await sessionUpdate(editing.value.id, payload)
      ElMessage.success(t('admin.sessionsUpdated'))
    } else {
      await sessionCreate(payload)
      ElMessage.success(t('admin.sessionsCreated'))
    }
    dialogVisible.value = false
    load()
  } catch (e) {
    // 拦截器已经弹过一次 toast; 这里只把后端原文写进弹窗, 不重复弹
    submitError.value = t('admin.formSubmitFailed', { msg: errMsg(e) })
  } finally {
    submitting.value = false
  }
}

async function onDelete(row: Session) {
  if (deletingId.value !== null) return
  deletingId.value = row.id
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
  } finally {
    deletingId.value = null
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
  if (recoveringId.value !== null) return
  recoveringId.value = row.id
  try {
    await recoverSessionBitmap(row.id)
    ElMessage.success(t('admin.sessionsBitmapRecovered'))
  } catch {
    // 拦截器已提示
  } finally {
    recoveringId.value = null
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

    <el-alert v-if="optionsError" class="load-alert" type="error" :closable="false" show-icon role="alert">
      <span>{{ t('admin.optionsLoadFailed') }}</span>
      <el-button size="small" @click="loadOptions">{{ t('common.retry') }}</el-button>
    </el-alert>

    <el-alert v-if="loadError" class="load-alert" type="error" :closable="false" show-icon role="alert">
      <span>{{ t('admin.sessionsLoadFailed') }}</span>
      <el-button size="small" @click="load">{{ t('common.retry') }}</el-button>
    </el-alert>

    <el-table :data="list" stripe style="margin-top: 12px">
      <template #empty>
        <div v-if="!loadError" class="table-empty">
          <span>{{ t('admin.emptyHint') }}</span>
          <el-button type="primary" plain size="small" @click="openCreate">{{ t('admin.sessionsAdd') }}</el-button>
        </div>
      </template>
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
          <el-button link type="warning" :disabled="recoveringId !== null" @click="onRecoverBitmap(row)">{{ t('admin.sessionsRecoverBitmap') }}</el-button>
          <el-popconfirm :title="t('admin.sessionsConfirmDelete')" @confirm="onDelete(row)">
            <template #reference><el-button link type="danger" :disabled="deletingId !== null">{{ t('admin.moviesDelete') }}</el-button></template>
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
      <el-alert v-if="optionsError" class="submit-alert" type="error" :closable="false" show-icon>
        {{ t('admin.optionsLoadFailed') }}
      </el-alert>
      <el-alert v-if="submitError" class="submit-alert" type="error" :closable="false" show-icon>
        {{ submitError }}
      </el-alert>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item :label="t('admin.sessionsMovieCol')" prop="movieId">
          <el-select v-model="form.movieId" filterable>
            <el-option v-for="m in movies" :key="m.id" :value="m.id" :label="m.title" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('admin.sessionsHallLabelCol')" prop="hallId">
          <el-select v-model="form.hallId">
            <el-option v-for="h in halls" :key="h.id" :value="h.id" :label="`${h.name} (${h.seatRows}x${h.seatCols}=${h.seatCount})`" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('admin.sessionsStartLabel')" prop="startTime">
          <el-input v-model="form.startTime" placeholder="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
        <el-form-item :label="t('admin.sessionsPriceLabel')" prop="price"><el-input-number v-model="form.price" :min="0.01" :precision="2" /></el-form-item>
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
        <el-button type="primary" :loading="submitting" :disabled="submitting || optionsError" @click="onSubmit">{{ t('common.save') }}</el-button>
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

/* 加载失败条 / 弹窗错误条 / 空状态的样式在 main.css 的共享类里(.load-alert /
   .submit-alert / .table-empty) —— 三个管理端页共用一份, 不各抄一份。 */

/* 分页条: 右对齐, 与表格留出呼吸间距 */
.sessions-pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
