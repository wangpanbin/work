<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance } from 'element-plus'
import { moviePage, movieCreate, movieUpdate, movieDelete } from '../../api/admin'
import type { Movie } from '../../types'

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
    ElMessage.success('已更新')
  } else {
    await movieCreate(form)
    ElMessage.success('已创建')
  }
  dialogVisible.value = false
  load()
}

async function onDelete(row: Movie) {
  await movieDelete(row.id)
  ElMessage.success('已删除')
  load()
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <h2>影片管理</h2>
    <div class="toolbar">
      <el-input v-model="keyword" placeholder="搜索片名" clearable style="width: 240px" @change="load" />
      <el-button type="primary" @click="openCreate">新增影片</el-button>
    </div>

    <el-table :data="list" stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="title" label="片名" />
      <el-table-column prop="duration" label="时长(分钟)" width="120" />
      <el-table-column prop="description" label="简介" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '热映' : '下架' }}</el-tag>
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

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑影片' : '新增影片'" width="540px">
      <el-form ref="formRef" :model="form" label-width="100px">
        <el-form-item label="片名" prop="title" required>
          <el-input v-model="form.title" maxlength="128" />
        </el-form-item>
        <el-form-item label="海报URL">
          <el-input v-model="form.poster" />
        </el-form-item>
        <el-form-item label="时长(分钟)" required>
          <el-input-number v-model="form.duration" :min="1" :max="500" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="form.description" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status">
            <el-option :value="1" label="热映" />
            <el-option :value="0" label="下架" />
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

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}
</style>