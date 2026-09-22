<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, type FormInstance } from 'element-plus'
import { moviePage, movieCreate, movieUpdate, movieDelete } from '../../api/admin'
import type { Movie } from '../../types'

const { t } = useI18n()
const loading = ref(false)
const list = ref<Movie[]>([])
const keyword = ref('')

const formRef = ref<FormInstance>()
const dialogVisible = ref(false)
const editing = ref<Movie | null>(null)
const form = reactive({ title: '', poster: '', duration: 0, description: '', status: 1 })

async function load() {
  loading.value = true
  try {
    const data = await moviePage({ page: 1, size: 20, keyword: keyword.value || undefined })
    list.value = data.records
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  Object.assign(form, { title: '', poster: '', duration: 0, description: '', status: 1 })
  dialogVisible.value = true
}

function openEdit(row: Movie) {
  editing.value = row
  Object.assign(form, { title: row.title, poster: row.poster, duration: row.duration, description: row.description, status: row.status })
  dialogVisible.value = true
}

async function onSubmit() {
  await formRef.value?.validate()
  if (editing.value) {
    await movieUpdate(editing.value.id, form)
    ElMessage.success(t('admin.moviesUpdated'))
  } else {
    await movieCreate(form)
    ElMessage.success(t('admin.moviesCreated'))
  }
  dialogVisible.value = false
  load()
}

async function onDelete(row: Movie) {
  await movieDelete(row.id)
  ElMessage.success(t('admin.moviesDeleted'))
  load()
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <h2>{{ t('admin.moviesTitle') }}</h2>
    <div class="toolbar">
      <el-input v-model="keyword" :placeholder="t('admin.moviesSearchPlaceholder')" clearable style="width: 240px" @change="load" />
      <el-button type="primary" @click="openCreate">{{ t('admin.moviesAdd') }}</el-button>
    </div>

    <el-table :data="list" stripe>
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
            <template #reference><el-button link type="danger">{{ t('admin.moviesDelete') }}</el-button></template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? t('admin.moviesEditTitle') : t('admin.moviesAddTitle')" width="540px">
      <el-form ref="formRef" :model="form" label-width="100px">
        <el-form-item :label="t('admin.moviesTitleCol')" prop="title" required>
          <el-input v-model="form.title" maxlength="128" />
        </el-form-item>
        <el-form-item :label="t('admin.moviesPosterCol')">
          <el-input v-model="form.poster" />
        </el-form-item>
        <el-form-item :label="t('admin.moviesDurationCol')" required>
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
        <el-button type="primary" @click="onSubmit">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}
/* ID 单元格: 19 位雪花 ID 强制 ellipsis, 不允许换行 */
.id-cell {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  color: var(--text-secondary);
  display: inline-block;
  max-width: 78px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}
</style>