<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { sessionList, sessionCreate, sessionUpdate, sessionDelete, hallList } from '../../api/admin'
import { page as moviePage } from '../../api/movie'
import type { Session } from '../../api/session'
import type { Movie } from '../../types'
import type { Hall } from '../../api/session'

const list = ref<Session[]>([])
const movies = ref<Movie[]>([])
const halls = ref<Hall[]>([])
const dialogVisible = ref(false)
const editing = ref<Session | null>(null)
const form = reactive({ movieId: 0, hallId: 0, startTime: '', price: 49.9, status: 1 })
const movieMap = computed(() => Object.fromEntries(movies.value.map((m) => [m.id, m])))
const hallMap = computed(() => Object.fromEntries(halls.value.map((h) => [h.id, h])))

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
    ElMessage.success('已更新')
  } else {
    await sessionCreate({ ...form, price: Number(form.price) })
    ElMessage.success('已创建')
  }
  dialogVisible.value = false
  load()
}

async function onDelete(row: Session) {
  await sessionDelete(row.id)
  ElMessage.success('已删除')
  load()
}

onMounted(async () => {
  await loadOptions()
  await load()
})
</script>

<template>
  <div>
    <h2>场次管理</h2>
    <el-button type="primary" @click="openCreate">新增场次</el-button>

    <el-table :data="list" stripe style="margin-top: 12px">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column label="影片">
        <template #default="{ row }">{{ movieMap[row.movieId]?.title || row.movieId }}</template>
      </el-table-column>
      <el-table-column label="影厅">
        <template #default="{ row }">{{ hallMap[row.hallId]?.name || row.hallId }}</template>
      </el-table-column>
      <el-table-column label="开映时间">
        <template #default="{ row }">{{ dayjs(row.startTime).format('MM-DD HH:mm') }}</template>
      </el-table-column>
      <el-table-column prop="price" label="票价" width="100" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">
            {{ ['', '在售', '已开场', '已结束'][row.status] || '未知' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-popconfirm title="确定删除?" @confirm="onDelete(row)">
            <template #reference><el-button link type="danger">删除</el-button></template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑场次' : '新增场次'" width="540px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="影片">
          <el-select v-model="form.movieId" filterable>
            <el-option v-for="m in movies" :key="m.id" :value="m.id" :label="m.title" />
          </el-select>
        </el-form-item>
        <el-form-item label="影厅">
          <el-select v-model="form.hallId">
            <el-option v-for="h in halls" :key="h.id" :value="h.id" :label="`${h.name} (${h.seatRows}x${h.seatCols}=${h.seatCount})`" />
          </el-select>
        </el-form-item>
        <el-form-item label="开映时间">
          <el-input v-model="form.startTime" placeholder="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
        <el-form-item label="票价"><el-input-number v-model="form.price" :min="0.01" :precision="2" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status">
            <el-option :value="0" label="待开售" />
            <el-option :value="1" label="在售" />
            <el-option :value="2" label="已开场" />
            <el-option :value="3" label="已结束" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>