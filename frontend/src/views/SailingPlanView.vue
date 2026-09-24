<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <h3>出航申报</h3>
        <span class="panel-sub">待审批 → 已批准 → 已返航 / 已取消；已批准且超过预计返回时间的整行标红</span>
        <div class="panel-extra">
          <el-select v-model="statusFilter" clearable placeholder="全部状态" style="width:130px" @change="load">
            <el-option v-for="s in PLAN_STATUS" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
          <el-button type="primary" @click="openCreate">新建申报</el-button>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>

      <el-table :data="plans" size="small" border stripe :row-class-name="rowClass">
        <el-table-column prop="planNo" label="申报单号" width="130" />
        <el-table-column prop="vesselName" label="船只" width="110">
          <template #default="{ row }">
            {{ row.vesselName }}
            <div class="muted" style="font-size:11px">{{ row.vesselTypeName }} · {{ row.berthNo || '未靠泊' }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="skipper" label="船长/驾驶员" width="110" />
        <el-table-column prop="phone" label="联系电话" width="120" />
        <el-table-column prop="departTime" label="出航时间" width="150" :formatter="fmtTime" />
        <el-table-column prop="etaReturn" label="预计返回" width="150" :formatter="fmtTime" />
        <el-table-column prop="route" label="目的地/航线" min-width="150" />
        <el-table-column prop="crewCount" label="载员" width="70" />
        <el-table-column prop="weather" label="海况天气预报" min-width="180" />
        <el-table-column prop="statusName" label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="超期" width="110">
          <template #default="{ row }">
            <span v-if="row.overdueHours !== null && row.overdueHours !== undefined"
                  style="color:#c62828; font-weight:700">超 {{ row.overdueHours }} 小时</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PENDING'" type="primary" size="small" link @click="act(row, 'approve')">审批</el-button>
            <el-button v-if="row.status === 'APPROVED'" type="success" size="small" link @click="act(row, 'return')">登记返航</el-button>
            <el-button v-if="row.status === 'PENDING' || row.status === 'APPROVED'" type="danger" size="small" link
                       @click="act(row, 'cancel')">取消</el-button>
            <span v-if="row.status === 'RETURNED' || row.status === 'CANCELED'" class="muted">已结单</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="createVisible" title="新建出航申报" width="560px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="申报单号"><el-input v-model="form.planNo" /></el-form-item>
        <el-form-item label="船只">
          <el-select v-model="form.vesselId" filterable style="width:100%">
            <el-option v-for="v in vessels" :key="v.id" :label="`${v.name} · ${v.typeName}`" :value="v.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="船长/驾驶员"><el-input v-model="form.skipper" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="form.phone" placeholder="11 位手机号" /></el-form-item>
        <el-form-item label="出航时间">
          <el-date-picker v-model="form.departTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" />
        </el-form-item>
        <el-form-item label="预计返回">
          <el-date-picker v-model="form.etaReturn" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" />
        </el-form-item>
        <el-form-item label="目的地/航线"><el-input v-model="form.route" /></el-form-item>
        <el-form-item label="载员人数"><el-input-number v-model="form.crewCount" :min="1" :max="12" /></el-form-item>
        <el-form-item label="海况天气"><el-input v-model="form.weather" placeholder="如：东南风 3 级，浪高 0.6m" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { inject, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { planApi, vesselApi } from '../api'

const PLAN_STATUS = [
  { value: 'PENDING', label: '待审批' },
  { value: 'APPROVED', label: '已批准' },
  { value: 'RETURNED', label: '已返航' },
  { value: 'CANCELED', label: '已取消' }
]

const marina = inject('marina', null)
const plans = ref([])
const vessels = ref([])
const statusFilter = ref('')
const createVisible = ref(false)
const form = ref({})

function fmtTime(row, column, value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}

function statusTag(status) {
  return { PENDING: 'warning', APPROVED: 'primary', RETURNED: 'success', CANCELED: 'info' }[status] || 'info'
}

/** 超期未返：整行标红 */
function rowClass({ row }) {
  return row.overdueHours !== null && row.overdueHours !== undefined ? 'row-overdue' : ''
}

async function load() {
  plans.value = await planApi.list(statusFilter.value ? { status: statusFilter.value } : {})
  vessels.value = await vesselApi.list()
}

function openCreate() {
  form.value = {
    planNo: '', vesselId: null, skipper: '', phone: '',
    departTime: '', etaReturn: '', route: '', crewCount: 4, weather: ''
  }
  planApi.nextNo().then((no) => { form.value.planNo = no })
  createVisible.value = true
}

async function submit() {
  await planApi.create(form.value)
  createVisible.value = false
  ElMessage.success('出航申报已提交')
  await load()
}

async function act(row, action) {
  const text = { approve: '批准该申报？船只会放行出海（仍占泊位则先自动结算离泊）', return: '确认该船已返航？', cancel: '取消该申报？' }[action]
  await ElMessageBox.confirm(text, '出航申报')
  if (action === 'approve') await planApi.approve(row.id)
  if (action === 'return') await planApi.markReturn(row.id)
  if (action === 'cancel') await planApi.cancel(row.id)
  ElMessage.success('已处理')
  await load()
  if (marina) {
    await marina.refreshOverview()
    await marina.reloadVessels()
  }
}

onMounted(load)
</script>
