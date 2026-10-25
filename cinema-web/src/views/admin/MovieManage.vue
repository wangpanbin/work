<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { errMsg, validateForm } from '../../utils/adminForm'
import { useI18n } from 'vue-i18n'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { moviePage, movieCreate, movieUpdate, movieDelete } from '../../api/admin'
import type { Id, Movie } from '../../types'

const { t } = useI18n()
const loading = ref(false)
/** 加载失败态 —— 与「空表」互斥, 否则用户分不清是真没数据还是请求挂了 */
const loadError = ref(false)
const list = ref<Movie[]>([])
const keyword = ref('')

/** 分页状态 —— 影片一多, 硬编码前 20 条会让后面的永远看不到 */
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)

const formRef = ref<FormInstance>()
const dialogVisible = ref(false)
const editing = ref<Movie | null>(null)
/** 保存闸门 —— 影片 CRUD 没有幂等键, 连点两下就会建出两条 */
const submitting = ref(false)
/** 后端返回的错误原文 —— 留在弹窗里, 而不是只闪一条 toast */
const submitError = ref('')
/** 删除在途的行 id */
const deletingId = ref<Id | null>(null)
const form = reactive({ title: '', poster: '', duration: 0, description: '', status: 1 })

// rules 必须用 computed: message 里的 t() 在 setup 时求值, 切 locale 后不会重算
const rules = computed<FormRules>(() => ({
  title: [{ required: true, whitespace: true, message: t('admin.movieTitleRequired'), trigger: 'blur' }],
  duration: [{ required: true, type: 'number', min: 1, max: 500, message: t('admin.movieDurationRequired'), trigger: 'change' }],
}))

async function load() {
  loading.value = true
  loadError.value = false
  try {
    const data = await moviePage({
      page: pageNum.value,
      size: pageSize.value,
      keyword: keyword.value || undefined,
    })
    list.value = data.records ?? []
    total.value = Number(data.total ?? 0)
  } catch {
    // 拦截器已提示; 这里只把「加载失败」摆到台面上, 不让表格显示成「暂无数据」
    loadError.value = true
  } finally {
    loading.value = false
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

/** 换关键字要回到第一页, 否则会停在一个已经空了的页码上 */
function onSearch() {
  pageNum.value = 1
  load()
}

function openCreate() {
  editing.value = null
  submitError.value = ''
  Object.assign(form, { title: '', poster: '', duration: 0, description: '', status: 1 })
  formRef.value?.clearValidate()
  dialogVisible.value = true
}

function openEdit(row: Movie) {
  editing.value = row
  submitError.value = ''
  Object.assign(form, {
    title: row.title,
    poster: row.poster,
    duration: row.duration,
    description: row.description,
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
    if (editing.value) {
      await movieUpdate(editing.value.id, form)
      ElMessage.success(t('admin.moviesUpdated'))
    } else {
      await movieCreate(form)
      ElMessage.success(t('admin.moviesCreated'))
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

async function onDelete(row: Movie) {
  if (deletingId.value !== null) return
  deletingId.value = row.id
  try {
    await movieDelete(row.id)
    ElMessage.success(t('admin.moviesDeleted'))
    load()
  } catch {
    // 拦截器已提示
  } finally {
    deletingId.value = null
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="page-header">
      <h2>{{ t('admin.moviesTitle') }}</h2>
      <div class="header-actions">
        <el-input v-model="keyword" :placeholder="t('admin.moviesSearchPlaceholder')" clearable style="width: 240px" @change="onSearch" />
        <el-button type="primary" @click="openCreate">{{ t('admin.moviesAdd') }}</el-button>
      </div>
    </div>

    <el-alert v-if="loadError" class="load-alert" type="error" :closable="false" show-icon role="alert">
      <span>{{ t('admin.moviesLoadFailed') }}</span>
      <el-button size="small" @click="load">{{ t('common.retry') }}</el-button>
    </el-alert>

    <el-table :data="list" stripe>
      <template #empty>
        <div v-if="!loadError" class="table-empty">
          <span>{{ t('admin.emptyHint') }}</span>
          <el-button type="primary" plain size="small" @click="openCreate">{{ t('admin.moviesAdd') }}</el-button>
        </div>
      </template>
      <el-table-column prop="id" label="ID" width="90">
        <template #default="{ row }">
          <span class="id-cell" :title="String(row.id)">#{{ row.id }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="title" :label="t('admin.moviesTitleCol')" />
      <el-table-column prop="duration" :label="t('admin.moviesDurationCol')" width="120" />
      <el-table-column prop="description" :label="t('admin.moviesDescCol')" show-overflow-tooltip />
      <el-table-column :label="t('admin.moviesStatusCol')" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? t('admin.moviesStatusActive') : t('admin.moviesStatusInactive') }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('admin.moviesActionsCol')" width="180">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">{{ t('admin.moviesEdit') }}</el-button>
          <el-popconfirm :title="t('admin.moviesConfirmDelete')" @confirm="onDelete(row)">
            <template #reference><el-button link type="danger" :disabled="deletingId !== null">{{ t('admin.moviesDelete') }}</el-button></template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="movies-pager"
      layout="total, sizes, prev, pager, next"
      :total="total"
      :current-page="pageNum"
      :page-size="pageSize"
      :page-sizes="[10, 20, 50, 100]"
      @current-change="onPageChange"
      @size-change="onSizeChange"
    />

    <el-dialog v-model="dialogVisible" :title="editing ? t('admin.moviesEditTitle') : t('admin.moviesAddTitle')" width="540px">
      <el-alert v-if="submitError" class="submit-alert" type="error" :closable="false" show-icon>
        {{ submitError }}
      </el-alert>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item :label="t('admin.moviesTitleCol')" prop="title">
          <el-input v-model="form.title" maxlength="128" />
        </el-form-item>
        <el-form-item :label="t('admin.moviesPosterCol')">
          <el-input v-model="form.poster" />
        </el-form-item>
        <el-form-item :label="t('admin.moviesDurationCol')" prop="duration">
          <el-input-number v-model="form.duration" :min="1" :max="500" />
        </el-form-item>
        <el-form-item :label="t('admin.moviesDescCol')">
          <el-input v-model="form.description" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item :label="t('admin.moviesStatusCol')">
          <el-select v-model="form.status">
            <el-option :value="1" :label="t('admin.moviesStatusActive')" />
            <el-option :value="0" :label="t('admin.moviesStatusInactive')" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" :disabled="submitting" @click="onSubmit">{{ t('common.save') }}</el-button>
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
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}
/* ID 单元格: 19 位雪花 ID 强制 ellipsis, 不允许换行 */
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
.movies-pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
