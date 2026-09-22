<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { sessionList, sessionCreate, sessionUpdate, sessionDelete, hallList } from '../../api/admin'
import { page as moviePage } from '../../api/movie'
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
const movieMap = computed(() => Object.fromEntries(movies.value.map((m) => [m.id, m])))
const hallMap = computed(() => Object.fromEntries(halls.value.map((h) => [h.id, h])))

// 场次状态文案 (后端 0/1/2/3 → i18n key)
const sessionStatusLabelKey = ['admin.sessionStatusPending', '', 'admin.sessionStatusStarted', 'admin.sessionStatusEnded']

async function load() {
  list.value = await sessionList()
}

async function loadOptions() {
  movies.value = (await moviePage({ page: 1, size: 100 })).records
  halls.value = await hallList()
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
  if (editing.value) {
    await sessionUpdate(editing.value.id, { ...form, price: Number(form.price) })
    ElMessage.success(t('admin.sessionsUpdated'))
  } else {
    await sessionCreate({ ...form, price: Number(form.price) })
    ElMessage.success(t('admin.sessionsCreated'))
  }
  dialogVisible.value = false
  load()
}

async function onDelete(row: Session) {
  await sessionDelete(row.id)
  ElMessage.success(t('admin.hallsDeleted'))
  load()
}

onMounted(async () => {
  await loadOptions()
  await load()
})
</script>

<template>
  <div>
    <h2>{{ t('admin.sessionsTitle') }}</h2>
    <el-button type="primary" @click="openCreate">{{ t('admin.sessionsAdd') }}</el-button>

    <el-table :data="list" stripe style="margin-top: 12px">
      <el-table-column prop="id" label="ID" width="80" />
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
          <el-tag :type="row.status === 1 ? 'success' : 'info'">
            {{ sessionStatusLabelKey[row.status] ? t(sessionStatusLabelKey[row.status]) : t('admin.sessionStatusUnknown') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('admin.sessionsActionsCol')" width="180">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">{{ t('admin.moviesEdit') }}</el-button>
          <el-popconfirm :title="t('admin.sessionsConfirmDelete')" @confirm="onDelete(row)">
            <template #reference><el-button link type="danger">{{ t('admin.moviesDelete') }}</el-button></template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

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
            <el-option :value="0" :label="t('admin.sessionStatusPending')" />
            <el-option :value="1" :label="t('admin.sessionStatusSelling')" />
            <el-option :value="2" :label="t('admin.sessionStatusStarted')" />
            <el-option :value="3" :label="t('admin.sessionStatusEnded')" />
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