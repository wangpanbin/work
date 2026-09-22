<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { hallList, hallCreate, hallUpdate, hallDelete } from '../../api/admin'
import type { Hall } from '../../api/session'

const { t } = useI18n()
const list = ref<Hall[]>([])
const dialogVisible = ref(false)
const editing = ref<Hall | null>(null)
const form = reactive({ cinemaId: 1, name: '', seatRows: 8, seatCols: 12, vipFromRow: 7 as number | undefined })

async function load() {
  list.value = await hallList()
}

function openCreate() {
  editing.value = null
  Object.assign(form, { cinemaId: 1, name: '', seatRows: 8, seatCols: 12, vipFromRow: 7 })
  dialogVisible.value = true
}

function openEdit(row: Hall) {
  editing.value = row
  Object.assign(form, {
    cinemaId: row.cinemaId, name: row.name,
    seatRows: row.seatRows, seatCols: row.seatCols, vipFromRow: undefined as number | undefined,
  })
  dialogVisible.value = true
}

async function onSubmit() {
  const data = { ...form }
  if (data.vipFromRow == null || (data.vipFromRow as unknown) === '') delete (data as Record<string, unknown>).vipFromRow
  if (editing.value) {
    await hallUpdate(editing.value.id, data as typeof form)
    ElMessage.success(t('admin.hallsUpdated'))
  } else {
    await hallCreate(data as typeof form)
    ElMessage.success(t('admin.hallsCreated'))
  }
  dialogVisible.value = false
  load()
}

async function onDelete(row: Hall) {
  await hallDelete(row.id)
  ElMessage.success(t('admin.hallsDeleted'))
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <h2>{{ t('admin.hallsTitle') }}</h2>
    <el-button type="primary" @click="openCreate">{{ t('admin.hallsAdd') }}</el-button>

    <el-table :data="list" stripe style="margin-top: 12px">
      <el-table-column label="ID" width="90">
        <template #default="{ row }">
          <span class="id-cell" :title="String(row.id)">#{{ row.id }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="name" :label="t('admin.hallsNameCol')" />
      <el-table-column :label="t('admin.hallsCinemaCol')" width="90">
        <template #default="{ row }">
          <span class="id-cell" :title="String(row.cinemaId)">#{{ row.cinemaId }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="seatRows" :label="t('admin.hallsRowsCol')" width="80" />
      <el-table-column prop="seatCols" :label="t('admin.hallsColsCol')" width="80" />
      <el-table-column prop="seatCount" :label="t('admin.hallsCapacityCol')" width="100" />
      <el-table-column :label="t('admin.hallsActionsCol')" width="180">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">{{ t('admin.moviesEdit') }}</el-button>
          <el-popconfirm :title="t('admin.hallsConfirmDelete')" @confirm="onDelete(row)">
            <template #reference><el-button link type="danger">{{ t('admin.moviesDelete') }}</el-button></template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? t('admin.hallsEditTitle') : t('admin.hallsAddTitle')" width="480px">
      <el-form :model="form" label-width="100px">
        <el-form-item :label="t('admin.hallsCinemaIdCol')"><el-input-number v-model="form.cinemaId" :min="1" /></el-form-item>
        <el-form-item :label="t('admin.hallsNameCol')"><el-input v-model="form.name" maxlength="64" /></el-form-item>
        <el-form-item :label="t('admin.hallsRowsLabel')"><el-input-number v-model="form.seatRows" :min="1" :max="30" /></el-form-item>
        <el-form-item :label="t('admin.hallsColsLabel')"><el-input-number v-model="form.seatCols" :min="1" :max="30" /></el-form-item>
        <el-form-item :label="t('admin.hallsVipLabel')"><el-input-number v-model="form.vipFromRow" :min="1" :placeholder="t('admin.hallsVipPlaceholder')" /></el-form-item>
        <el-alert type="warning" :closable="false" show-icon style="margin-top: 8px">
          {{ t('admin.hallsRebuildWarn') }}
        </el-alert>
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