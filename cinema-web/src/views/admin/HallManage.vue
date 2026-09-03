<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { hallList, hallCreate, hallUpdate, hallDelete } from '../../api/admin'
import type { Hall } from '../../api/session'

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
    ElMessage.success('已更新,座位布局已重建')
  } else {
    await hallCreate(data as typeof form)
    ElMessage.success('已创建,座位已生成')
  }
  dialogVisible.value = false
  load()
}

async function onDelete(row: Hall) {
  await hallDelete(row.id)
  ElMessage.success('已删除')
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <h2>影厅管理</h2>
    <el-button type="primary" @click="openCreate">新增影厅</el-button>

    <el-table :data="list" stripe style="margin-top: 12px">
      <el-table-column label="ID" width="90">
        <template #default="{ row }">
          <span class="id-cell" :title="String(row.id)">#{{ row.id }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="影厅名" />
      <el-table-column label="影院" width="90">
        <template #default="{ row }">
          <span class="id-cell" :title="String(row.cinemaId)">#{{ row.cinemaId }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="seatRows" label="行" width="80" />
      <el-table-column prop="seatCols" label="列" width="80" />
      <el-table-column prop="seatCount" label="座位数" width="100" />
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-popconfirm title="删除影厅会同时删除座位,确定?" @confirm="onDelete(row)">
            <template #reference><el-button link type="danger">删除</el-button></template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑影厅' : '新增影厅'" width="480px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="影院ID"><el-input-number v-model="form.cinemaId" :min="1" /></el-form-item>
        <el-form-item label="影厅名"><el-input v-model="form.name" maxlength="64" /></el-form-item>
        <el-form-item label="行数"><el-input-number v-model="form.seatRows" :min="1" :max="30" /></el-form-item>
        <el-form-item label="列数"><el-input-number v-model="form.seatCols" :min="1" :max="30" /></el-form-item>
        <el-form-item label="VIP 起始行"><el-input-number v-model="form.vipFromRow" :min="1" placeholder="空=无VIP" /></el-form-item>
        <el-alert type="warning" :closable="false" show-icon style="margin-top: 8px">
          编辑时将重建座位布局(删除并重新插入所有座位)
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSubmit">保存</el-button>
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