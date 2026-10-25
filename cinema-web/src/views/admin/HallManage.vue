<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { errMsg, validateForm } from '../../utils/adminForm'
import { useI18n } from 'vue-i18n'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { hallList, hallCreate, hallUpdate, hallDelete } from '../../api/admin'
import { emptyHallForm, formToPayload, hallToForm, vipInfoKnown } from '../../utils/hallForm'
import type { HallForm } from '../../utils/hallForm'
import type { Hall } from '../../api/session'
import type { Id } from '../../types'

const { t } = useI18n()
const list = ref<Hall[]>([])
/** 加载失败态 —— 与「空表」互斥, 否则用户分不清是真没数据还是请求挂了 */
const loadError = ref(false)
const dialogVisible = ref(false)
const editing = ref<Hall | null>(null)
/** 保存闸门 —— 影厅 CRUD 没有幂等键, 连点两下就会建出两个厅 */
const submitting = ref(false)
/** 后端返回的错误原文 —— 留在弹窗里, 而不是只闪一条 toast */
const submitError = ref('')
/** 删除在途的行 id */
const deletingId = ref<Id | null>(null)
/**
 * 该影厅的 VIP 区信息**未知**(后端没返回 vipFromRow 字段)。
 *
 * <p>此时表单里的 VIP 起始行是空的, 但那是「不知道」不是「没有」——
 * 不说清楚, 管理员会以为这一厅本来就没有 VIP 区。
 */
const vipInfoUnknown = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<HallForm>(emptyHallForm())

// rules 必须用 computed: message 里的 t() 在 setup 时求值, 切 locale 后不会重算
const rules = computed<FormRules>(() => ({
  name: [{ required: true, whitespace: true, message: t('admin.hallNameRequired'), trigger: 'blur' }],
  seatRows: [{ required: true, type: 'number', min: 1, max: 30, message: t('admin.hallRowsRequired'), trigger: 'change' }],
  seatCols: [{ required: true, type: 'number', min: 1, max: 30, message: t('admin.hallColsRequired'), trigger: 'change' }],
}))

async function load() {
  loadError.value = false
  try {
    list.value = await hallList()
  } catch {
    // 拦截器已提示; 这里只把「加载失败」摆到台面上, 不让表格显示成「暂无数据」
    loadError.value = true
  }
}

function openCreate() {
  editing.value = null
  submitError.value = ''
  vipInfoUnknown.value = false
  Object.assign(form, emptyHallForm())
  formRef.value?.clearValidate()
  dialogVisible.value = true
}

/**
 * 打开编辑 —— 回填必须走 hallToForm 纯函数。
 *
 * <p>VIP 起始行(vipFromRow)是这一页最容易丢的字段: 后端 update 会删光全部座位
 * 再按它重插。现在后端的语义是「null = 保持原样」(2026-10-25 交互审查 P2-1),
 * 所以即使接口没给这个值, 保存也不会清空 VIP 区; 但界面仍要说清楚
 * 「这个空是不知道, 不是没有」。
 */
function openEdit(row: Hall) {
  editing.value = row
  submitError.value = ''
  vipInfoUnknown.value = !vipInfoKnown(row)
  Object.assign(form, hallToForm(row))
  formRef.value?.clearValidate()
  dialogVisible.value = true
}

async function onSubmit() {
  if (submitting.value) return
  submitting.value = true
  submitError.value = ''
  try {
    if (!(await validateForm(formRef.value))) return
    // 载荷由纯函数拼, vipFromRow 原样带回去(缺省 = 显式的 null = 无 VIP 区)
    const payload = formToPayload(form)
    if (editing.value) {
      await hallUpdate(editing.value.id, payload)
      ElMessage.success(t('admin.hallsUpdated'))
    } else {
      await hallCreate(payload)
      ElMessage.success(t('admin.hallsCreated'))
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

async function onDelete(row: Hall) {
  if (deletingId.value !== null) return
  deletingId.value = row.id
  try {
    await hallDelete(row.id)
    ElMessage.success(t('admin.hallsDeleted'))
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
  <div>
    <div class="page-header">
      <h2>{{ t('admin.hallsTitle') }}</h2>
      <div class="header-actions">
        <el-button type="primary" @click="openCreate">{{ t('admin.hallsAdd') }}</el-button>
      </div>
    </div>

    <el-alert v-if="loadError" class="load-alert" type="error" :closable="false" show-icon role="alert">
      <span>{{ t('admin.hallsLoadFailed') }}</span>
      <el-button size="small" @click="load">{{ t('common.retry') }}</el-button>
    </el-alert>

    <el-table :data="list" stripe style="margin-top: 12px">
      <template #empty>
        <div v-if="!loadError" class="table-empty">
          <span>{{ t('admin.emptyHint') }}</span>
          <el-button type="primary" plain size="small" @click="openCreate">{{ t('admin.hallsAdd') }}</el-button>
        </div>
      </template>
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
            <template #reference><el-button link type="danger" :disabled="deletingId !== null">{{ t('admin.moviesDelete') }}</el-button></template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? t('admin.hallsEditTitle') : t('admin.hallsAddTitle')" width="480px">
      <el-alert v-if="submitError" class="submit-alert" type="error" :closable="false" show-icon>
        {{ submitError }}
      </el-alert>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item :label="t('admin.hallsCinemaIdCol')"><el-input-number v-model="form.cinemaId" :min="1" /></el-form-item>
        <el-form-item :label="t('admin.hallsNameCol')" prop="name"><el-input v-model="form.name" maxlength="64" /></el-form-item>
        <el-form-item :label="t('admin.hallsRowsLabel')" prop="seatRows"><el-input-number v-model="form.seatRows" :min="1" :max="30" /></el-form-item>
        <el-form-item :label="t('admin.hallsColsLabel')" prop="seatCols"><el-input-number v-model="form.seatCols" :min="1" :max="30" /></el-form-item>
        <el-form-item :label="t('admin.hallsVipLabel')" prop="vipFromRow"><el-input-number v-model="form.vipFromRow" :min="1" :placeholder="t('admin.hallsVipPlaceholder')" /></el-form-item>
        <el-alert type="warning" :closable="false" show-icon style="margin-top: 8px">
          {{ t('admin.hallsRebuildWarn') }}
        </el-alert>
        <!-- VIP 区信息未知(后端没返回该字段): 这里的空值是「不知道」不是「没有」。
             ⚠️ 文案刻意**不预测**保存后的结果: 新后端的语义是「null = 保持原样」,
             而这条分支只会在**后端没部署到这一版**时出现(那时才是旧语义)。
             说不清就不说, 只提示「无法确认, 请谨慎保存」。 -->
        <el-alert v-if="vipInfoUnknown" type="error" :closable="false" show-icon style="margin-top: 8px">
          {{ t('admin.hallsVipUnknownWarn') }}
        </el-alert>
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
</style>
