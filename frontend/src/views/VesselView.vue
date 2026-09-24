<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <h3>船只台账</h3>
        <span class="panel-sub">卡片墙 · 保险到期预警徽章 · 「当前泊位」来自后端 @JoinFormula 派生关联</span>
        <div class="panel-extra">
          <el-select v-model="statusFilter" clearable placeholder="全部状态" style="width:120px" @change="load">
            <el-option v-for="s in VESSEL_STATUS" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
          <el-select v-model="typeFilter" clearable placeholder="全部船型" style="width:130px" @change="load">
            <el-option v-for="t in VESSEL_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
          <el-button type="primary" @click="openCreate">登记新船</el-button>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>

      <div class="vessel-wall">
        <div v-for="v in vessels" :key="v.id" class="vessel-card" :class="{ 'is-alert': v.insuranceLevel === 'EXPIRED' }">
          <div class="vessel-card-head">
            <b>{{ v.name }}</b>
            <span class="reg">{{ v.regNo }}</span>
            <el-tag size="small" :type="statusTag(v.status)">{{ v.statusName }}</el-tag>
          </div>

          <div class="muted" style="font-size:12px">
            船主 {{ v.ownerName }} · {{ v.typeName }}
            · 当前泊位 <b :style="{ color: v.currentBerthNo ? '#0277bd' : '#90a4ae' }">{{ v.currentBerthNo || '未靠泊' }}</b>
          </div>

          <div class="vessel-metrics">
            <div><span>船长</span><b>{{ v.lengthM }}m</b></div>
            <div><span>船宽</span><b>{{ v.widthM }}m</b></div>
            <div><span>吃水</span><b>{{ v.draftM }}m</b></div>
          </div>

          <div class="vessel-card-foot">
            <span>登记 {{ v.regDate }}</span>
            <span>靠泊 {{ v.berthingCount }} 次 · 累计停泊费 ¥{{ v.berthingFee }}</span>
          </div>

          <div class="vessel-card-foot">
            <span>保险到期 {{ v.insuranceDue }}</span>
            <el-tag v-if="v.insuranceLevel === 'EXPIRED'" size="small" type="danger">
              已逾期 {{ Math.abs(v.insuranceDays) }} 天
            </el-tag>
            <el-tag v-else-if="v.insuranceLevel === 'WARN'" size="small" type="warning">
              {{ v.insuranceDays }} 天后到期
            </el-tag>
            <el-tag v-else size="small" type="success">剩余 {{ v.insuranceDays }} 天</el-tag>
          </div>

          <div class="vessel-card-foot" style="gap:4px">
            <el-button size="small" @click="openHistory(v)">历史靠泊</el-button>
            <el-button size="small" type="warning" @click="openInsurance(v)">续保</el-button>
            <el-select v-model="v.nextStatus" size="small" style="width:104px" placeholder="改状态"
                       @change="(val) => changeStatus(v, val)">
              <el-option v-for="s in VESSEL_STATUS" :key="s.value" :label="s.label" :value="s.value" />
            </el-select>
          </div>
        </div>
      </div>
    </div>

    <!-- 历史靠泊抽屉 -->
    <el-drawer v-model="historyVisible" :title="`${historyVessel.name || ''} · 历史靠泊记录`" size="560px">
      <el-table :data="history" size="small" border>
        <el-table-column prop="recordNo" label="记录单号" width="130" />
        <el-table-column prop="berthNo" label="泊位" width="80" />
        <el-table-column prop="berthTime" label="靠泊时间" width="150" :formatter="fmtTime" />
        <el-table-column prop="actualDepart" label="离泊时间" width="150" :formatter="fmtTime" />
        <el-table-column prop="days" label="天数" width="70" />
        <el-table-column prop="fee" label="停泊费" width="100" />
        <el-table-column prop="statusName" label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'IN_PORT' ? 'primary' : 'info'">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-dialog v-model="createVisible" title="登记新船" width="520px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="船名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="船籍注册号"><el-input v-model="form.regNo" placeholder="如 CN-YC-1016" /></el-form-item>
        <el-form-item label="船主"><el-input v-model="form.ownerName" /></el-form-item>
        <el-form-item label="船型">
          <el-select v-model="form.typeCode" style="width:100%">
            <el-option v-for="t in VESSEL_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="船长(m)"><el-input-number v-model="form.lengthM" :min="1" :step="0.1" /></el-form-item>
        <el-form-item label="船宽(m)"><el-input-number v-model="form.widthM" :min="0.5" :step="0.1" /></el-form-item>
        <el-form-item label="吃水(m)"><el-input-number v-model="form.draftM" :min="0.1" :step="0.1" /></el-form-item>
        <el-form-item label="登记日期">
          <el-date-picker v-model="form.regDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>
        <el-form-item label="保险到期日">
          <el-date-picker v-model="form.insuranceDue" type="date" value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>
        <div class="muted" style="font-size:12px">新船默认「出海」状态，要变成「在港」请到泊位图上安排靠泊</div>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="insuranceVisible" title="保险续保" width="420px">
      <el-form label-width="110px">
        <el-form-item label="新到期日">
          <el-date-picker v-model="insuranceDue" type="date" value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>
        <div class="muted" style="font-size:12px">原到期日 {{ historyVessel.insuranceDue }}，新到期日必须晚于今天且不早于原日期</div>
      </el-form>
      <template #footer>
        <el-button @click="insuranceVisible = false">取消</el-button>
        <el-button type="primary" @click="submitInsurance">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { inject, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { vesselApi } from '../api'

const VESSEL_TYPES = [
  { value: 'SAIL', label: '帆船' },
  { value: 'POWER', label: '动力艇' },
  { value: 'FISHING', label: '钓鱼艇' },
  { value: 'CATAMARAN', label: '双体船' },
  { value: 'SPEED', label: '快艇' }
]
const VESSEL_STATUS = [
  { value: 'IN_PORT', label: '在港' },
  { value: 'OUT_SEA', label: '出海' },
  { value: 'MAINTENANCE', label: '维修' },
  { value: 'TERMINATED', label: '退租' }
]

const marina = inject('marina', null)
const vessels = ref([])
const statusFilter = ref('')
const typeFilter = ref('')

const historyVisible = ref(false)
const history = ref([])
const historyVessel = ref({})
const insuranceVisible = ref(false)
const insuranceDue = ref('')
const createVisible = ref(false)
const form = ref({})

function fmtTime(row, column, value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}

function statusTag(status) {
  return { IN_PORT: 'primary', OUT_SEA: 'success', MAINTENANCE: 'warning', TERMINATED: 'info' }[status] || 'info'
}

async function load() {
  const params = statusFilter.value ? { status: statusFilter.value } : (typeFilter.value ? { typeCode: typeFilter.value } : {})
  vessels.value = (await vesselApi.list(params)).map((v) => ({ ...v, nextStatus: '' }))
}

function openHistory(v) {
  historyVessel.value = v
  historyVisible.value = true
  vesselApi.berthings(v.id).then((data) => { history.value = data })
}

function openInsurance(v) {
  historyVessel.value = v
  insuranceDue.value = ''
  insuranceVisible.value = true
}

async function submitInsurance() {
  await vesselApi.renewInsurance(historyVessel.value.id, insuranceDue.value)
  insuranceVisible.value = false
  ElMessage.success('保险已续保')
  await load()
  if (marina) await marina.refreshOverview()
}

async function changeStatus(v, status) {
  if (!status) return
  await vesselApi.setStatus(v.id, status)
  v.nextStatus = ''
  ElMessage.success('船只状态已更新')
  await load()
  if (marina) {
    await marina.refreshOverview()
    await marina.reloadVessels()
  }
}

function openCreate() {
  const today = new Date().toISOString().slice(0, 10)
  form.value = {
    name: '', regNo: '', ownerName: '', typeCode: 'SAIL',
    lengthM: 10, widthM: 3.2, draftM: 1.2, regDate: today, insuranceDue: ''
  }
  createVisible.value = true
}

async function submit() {
  await vesselApi.create(form.value)
  createVisible.value = false
  ElMessage.success('船只已登记')
  await load()
  if (marina) await marina.reloadVessels()
}

onMounted(load)
</script>
