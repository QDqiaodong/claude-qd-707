<template>
  <div>
    <!-- ============ 生效中的封航令横幅 ============ -->
    <div v-if="activeOrder" class="closure-banner" :class="`is-${activeOrder.status.toLowerCase()}`">
      <div class="cb-left">
        <div class="cb-icon">{{ activeOrder.status === 'RELEASING' ? '🌤️' : '🌊' }}</div>
        <div>
          <div class="cb-title">
            {{ activeOrder.orderNo }} · {{ activeOrder.reasonTypeName }}：{{ activeOrder.title }}
            <el-tag size="small" :type="orderTag(activeOrder.status)" effect="dark" style="margin-left:8px">
              {{ activeOrder.statusName }}
            </el-tag>
          </div>
          <div class="cb-sub">
            生效于 {{ fmt(activeOrder.activatedAt) }} · 发布人 {{ activeOrder.issuedBy }}
            · 在海处置 {{ activeOrder.dispositionTotal }} 艘
            （待联系 {{ activeOrder.toContactCount }} / 已确认 {{ activeOrder.confirmedCount }}
            / 失联 {{ activeOrder.lostCount }} / 已靠泊 {{ activeOrder.berthedCount }}）
            · 暂停申报 {{ activeOrder.suspendedPlanCount }} 条
            <b v-if="activeOrder.riskCount > 0" style="color:#ffebee"> · 风险项剩余 {{ activeOrder.riskCount }}</b>
          </div>
        </div>
      </div>
      <div>
        <el-button type="primary" plain size="small" @click="openOrder(activeOrder.id)">进入处置台</el-button>
      </div>
    </div>

    <!-- ============ 封航令列表 ============ -->
    <div class="panel">
      <div class="panel-head">
        <h3>封航与返港处置台</h3>
        <span class="panel-sub">
          待生效 → 生效中 → 解除中 → 已归档；同一处看受影响申报、在海船只处置项与可接纳返港泊位
        </span>
        <div class="panel-extra">
          <el-checkbox v-model="includeArchived" @change="loadOrders">含已归档历史</el-checkbox>
          <el-button type="primary" :disabled="!!activeOrder" @click="openCreate">发布封航令</el-button>
          <el-button @click="loadOrders">刷新</el-button>
        </div>
      </div>

      <el-table :data="orders" size="small" border stripe @row-click="(row) => openOrder(row.id)"
                :row-style="{ cursor: 'pointer' }">
        <el-table-column prop="orderNo" label="封航令编号" width="140" />
        <el-table-column prop="reasonTypeName" label="事由" width="100" />
        <el-table-column prop="title" label="标题" min-width="160" />
        <el-table-column label="阶段" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="orderTag(row.status)">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="计划/实际生效" width="200">
          <template #default="{ row }">
            <div>计划 {{ fmt(row.effectiveAt) }}</div>
            <div class="muted" style="font-size:11px">
              实际 {{ row.activatedAt ? fmt(row.activatedAt) : '—' }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="处置项（风险/总数）" width="130">
          <template #default="{ row }">
            <span :style="{ color: row.riskCount > 0 ? '#c62828' : '#2e7d32', fontWeight: 700 }">
              {{ row.riskCount }} / {{ row.dispositionTotal }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="suspendedPlanCount" label="暂停申报" width="80" />
        <el-table-column prop="issuedBy" label="发布人" width="90" />
      </el-table>
    </div>

    <!-- ============ 发布封航令 ============ -->
    <el-dialog v-model="createVisible" title="发布封航令" width="620px">
      <el-alert type="warning" :closable="false" style="margin-bottom:12px"
                title="生效后：已出海的船自动生成返港处置项；已批准未出航的申报将被暂停放行（不会改成取消/返航）；新申报可保存为待审批但不能批准。" />
      <el-form :model="form" label-width="100px">
        <el-form-item label="事由类型">
          <el-radio-group v-model="form.reasonType">
            <el-radio-button v-for="r in REASONS" :key="r.value" :value="r.value">{{ r.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="封航标题"><el-input v-model="form.title" placeholder="如：超强台风「海燕」临近" /></el-form-item>
        <el-form-item label="详细说明"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="发布人">
          <el-input v-model="form.issuedBy" placeholder="值班主管姓名" style="width:200px" />
        </el-form-item>
        <el-form-item label="生效时间">
          <el-date-picker v-model="form.effectiveAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
                          placeholder="不填 = 立即生效" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">发布</el-button>
      </template>
    </el-dialog>

    <!-- ============ 处置台抽屉 ============ -->
    <el-drawer v-model="detailVisible" size="92%" :title="detailTitle" destroy-on-close>
      <template v-if="detail">
        <div class="detail-toolbar">
          <div>
            <el-tag :type="orderTag(detail.order.status)" size="large">{{ detail.order.statusName }}</el-tag>
            <span class="muted" style="margin-left:10px">{{ detail.order.description }}</span>
          </div>
          <div>
            <el-button v-if="detail.order.status === 'PENDING'" size="small"
                       @click="doActivate(detail.order)">立即激活</el-button>
            <el-button v-if="detail.order.status === 'EFFECTIVE'" size="small" type="warning"
                       @click="openRelease">申请解除</el-button>
            <el-button v-if="detail.order.status === 'EFFECTIVE' || detail.order.status === 'RELEASING'"
                       size="small" type="success" :disabled="detail.order.riskCount > 0"
                       @click="doArchive(detail.order)">
              归档{{ detail.order.riskCount > 0 ? `（风险剩 ${detail.order.riskCount}）` : '' }}
            </el-button>
            <el-button size="small" @click="loadDetail(detail.order.id)">刷新</el-button>
          </div>
        </div>

        <el-alert v-if="detail.order.status === 'RELEASING'" type="warning" :closable="false" style="margin:10px 0"
                  :title="`解除中：仍有 ${detail.order.riskCount} 条风险处置项（失联升级 / 已确认未靠泊），清零后方可归档；暂停申报不会被自动批准。`" />
        <el-alert v-if="detail.order.status === 'EFFECTIVE'" type="error" :closable="false" style="margin:10px 0"
                  title="封航生效中：新申报只能保存为待审批、不得批准；已批准未出航申报全部暂停放行；在海船只请逐船完成返港处置与安全靠泊。" />

        <el-tabs v-model="tab">
          <!-- ===== 受影响出航申报 ===== -->
          <el-tab-pane :label="`受影响申报 (${detail.affectedPlans.length})`" name="plans">
            <el-table :data="detail.affectedPlans" size="small" border>
              <el-table-column prop="effectTypeName" label="影响动作" width="110">
                <template #default="{ row }">
                  <el-tag size="small" :type="effectTag(row.effectType)">{{ row.effectTypeName }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="planNo" label="申报单号" width="130" />
              <el-table-column prop="vesselName" label="船只" width="100" />
              <el-table-column label="出航/预计返" width="210">
                <template #default="{ row }">
                  {{ fmt(row.departTime) }}<br><span class="muted" style="font-size:11px">返 {{ fmt(row.etaReturn) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="影响时状态" width="100">
                <template #default="{ row }">{{ planStatusName(row.planStatusSnap) }}</template>
              </el-table-column>
              <el-table-column label="当前状态" width="110">
                <template #default="{ row }">
                  <el-tag size="small" :type="planTag(row.planStatus)">{{ row.planStatusName }}</el-tag>
                  <div v-if="row.currentHoldSeq" class="muted" style="font-size:11px">审批顺序 #{{ row.currentHoldSeq }}</div>
                </template>
              </el-table-column>
              <el-table-column prop="effectRemark" label="暂停原因 / 说明" min-width="220" />
              <el-table-column label="影响时间" width="150">
                <template #default="{ row }">{{ fmt(row.effectCreatedAt) }} · {{ row.effectOperator }}</template>
              </el-table-column>
            </el-table>
            <p class="muted" style="margin-top:8px; font-size:12px">
              归档解除时只把暂停申报恢复为「可继续审批」，按暂停时分配的顺序号依次审批；系统不会一键自动批准。
            </p>
          </el-tab-pane>

          <!-- ===== 在海船只返港处置 ===== -->
          <el-tab-pane :label="`返港处置 (${detail.dispositions.length})`" name="dispositions">
            <el-table :data="detail.dispositions" size="small" border :row-class-name="dispRowClass">
              <el-table-column prop="vesselName" label="在海船只" width="100" />
              <el-table-column prop="vesselTypeName" label="船型" width="80" />
              <el-table-column prop="planNo" label="关联申报" width="130">
                <template #default="{ row }">{{ row.planNo || '—' }}</template>
              </el-table-column>
              <el-table-column label="处置状态" width="120">
                <template #default="{ row }">
                  <el-tag size="small" :type="dispTag(row.status)">{{ row.statusName }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="预约泊位" width="90">
                <template #default="{ row }">{{ row.plannedBerthNo || '—' }}</template>
              </el-table-column>
              <el-table-column label="实际靠泊" width="160">
                <template #default="{ row }">
                  <span v-if="row.berthNo">{{ row.berthNo }} · {{ fmt(row.berthedAt) }}</span>
                  <span v-else class="muted">未靠泊</span>
                </template>
              </el-table-column>
              <el-table-column label="联系记录" width="90">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click.stop="openContacts(row)">
                    {{ row.contacts.length }} 条
                  </el-button>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="300" fixed="right">
                <template #default="{ row }">
                  <template v-if="row.status !== 'BERTHED'">
                    <el-button size="small" @click.stop="openContact(row, 'CONTACTED')">联系备注</el-button>
                    <el-button size="small" type="primary" @click.stop="openContact(row, 'CONFIRMED_RETURN')">
                      确认返港
                    </el-button>
                    <el-button size="small" type="warning" @click.stop="openContact(row, 'LOST_CONTACT')">
                      失联升级
                    </el-button>
                    <el-button v-if="row.status === 'LOST_CONTACT'" size="small"
                               @click.stop="openContact(row, 'REACHED')">重新取得联系</el-button>
                    <el-button size="small" type="success"
                               :disabled="row.status === 'TO_CONTACT'"
                               @click.stop="openBerth(row)">安全靠泊</el-button>
                  </template>
                  <el-tag v-else type="success" size="small">已安全靠泊（结）</el-tag>
                </template>
              </el-table-column>
            </el-table>
            <p class="muted" style="margin-top:8px; font-size:12px">
              同一艘船在本命下只有一条处置项；每次按钮点击都带幂等令牌，双击、超时重试不会产生重复记录或状态倒退。
            </p>
          </el-tab-pane>

          <!-- ===== 可接纳返港泊位 ===== -->
          <el-tab-pane :label="`可接纳泊位 (${detail.returnBerths.length})`" name="berths">
            <el-table :data="detail.returnBerths" size="small" border>
              <el-table-column prop="berthNo" label="泊位号" width="100" />
              <el-table-column prop="zoneCode" label="区域" width="70" />
              <el-table-column label="长度/吃水" width="130">
                <template #default="{ row }">{{ row.lengthM }}m / {{ row.maxDraftM }}m</template>
              </el-table-column>
              <el-table-column prop="allowTypeNames" label="允许船型" min-width="160" />
              <el-table-column prop="dailyRate" label="日租金" width="100" />
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag size="small" type="success">{{ row.statusName }}</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <!-- ===== 操作日志（可追溯） ===== -->
          <el-tab-pane :label="`操作日志 (${detail.logs.length})`" name="logs">
            <el-timeline style="padding:10px 0 0 20px">
              <el-timeline-item v-for="log in [...detail.logs].reverse()" :key="log.id"
                                :timestamp="fmt(log.createdAt) + ' · ' + log.operator" placement="top">
                <b>{{ log.action }}</b> — {{ log.detail }}
              </el-timeline-item>
            </el-timeline>
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-drawer>

    <!-- ============ 联系/确认/升级 对话框 ============ -->
    <el-dialog v-model="contactVisible" :title="contactTitle" width="520px" append-to-body>
      <el-form label-width="92px">
        <el-form-item label="处置船只">{{ contactRow?.vesselName }}（{{ contactRow?.statusName }}）</el-form-item>
        <el-form-item v-if="contactResult === 'CONFIRMED_RETURN' || contactResult === 'REACHED'" label="预约泊位">
          <el-select v-model="contactForm.plannedBerthId" clearable filterable placeholder="可选：预约返港泊位"
                     style="width:100%">
            <el-option v-for="b in detail?.returnBerths || []" :key="b.id"
                       :label="`${b.berthNo} · ${b.lengthM}m/${b.maxDraftM}m · ${b.allowTypeNames}`"
                       :value="b.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="联系时间">
          <el-date-picker v-model="contactForm.contactTime" type="datetime"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="contactForm.remark" type="textarea" :rows="2"
                    :placeholder="contactResult === 'LOST_CONTACT' ? '如：VHF 16 频道三次呼叫无应答，电话关机' : '联系情况备注'" />
        </el-form-item>
        <el-form-item label="值班员"><el-input v-model="contactForm.operator" style="width:200px" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="contactVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitContact">提交</el-button>
      </template>
    </el-dialog>

    <!-- ============ 安全靠泊对话框 ============ -->
    <el-dialog v-model="berthVisible" title="安全靠泊（处置项 / 申报 / 船只 / 靠泊记录 / 泊位一次提交）"
               width="560px" append-to-body>
      <el-alert type="info" :closable="false" style="margin-bottom:10px"
                title="任一步骤失败将整笔回退，并明确告知哪些数据没有改变；重复提交带同一令牌自动幂等。" />
      <el-form label-width="92px">
        <el-form-item label="处置船只">{{ berthRow?.vesselName }}</el-form-item>
        <el-form-item label="接纳泊位">
          <el-select v-model="berthForm.berthId" filterable placeholder="选择可接纳返港船只的泊位" style="width:100%">
            <el-option v-for="b in detail?.returnBerths || []" :key="b.id"
                       :label="`${b.berthNo} · ${b.zoneCode}区 · ${b.lengthM}m/${b.maxDraftM}m · ${b.allowTypeNames}`"
                       :value="b.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="靠泊时间">
          <el-date-picker v-model="berthForm.berthTime" type="datetime"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="berthForm.remark" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="值班员"><el-input v-model="berthForm.operator" style="width:200px" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="berthVisible = false">取消</el-button>
        <el-button type="success" :loading="submitting" @click="submitBerth">确认安全靠泊</el-button>
      </template>
    </el-dialog>

    <!-- ============ 安全靠泊成功结果 ============ -->
    <el-dialog v-model="resultVisible" title="✅ 安全靠泊完成（数据已一致）" width="560px" append-to-body>
      <el-alert type="success" :closable="false" :title="resultMessage" style="margin-bottom:10px" />
      <ul style="line-height:1.9; padding-left:18px">
        <li v-for="(item, i) in safeResult?.changedItems || []" :key="i">{{ item }}</li>
      </ul>
      <div class="muted" style="font-size:12px">
        处置项：{{ safeResult?.dispositionStatusName }} ｜ 船只：{{ safeResult?.vesselStatusName }}
        ｜ 申报：{{ safeResult?.planStatusName || '无关联' }} ｜ 泊位：{{ safeResult?.berthStatusName }}
      </div>
      <template #footer>
        <el-button type="primary" @click="resultVisible = false">知道了</el-button>
      </template>
    </el-dialog>

    <!-- ============ 联系记录抽屉 ============ -->
    <el-drawer v-model="contactsVisible" :title="`${contactRow?.vesselName || ''} · 返港联系记录`" size="46%" append-to-body>
      <el-timeline style="padding:10px 0 0 20px">
        <el-timeline-item v-for="c in contactRow?.contacts || []" :key="c.id"
                          :timestamp="fmt(c.contactTime) + ' · ' + c.operator" placement="top">
          <el-tag size="small" :type="contactTag(c.result)">{{ c.resultName }}</el-tag>
          <span style="margin-left:8px">{{ c.remark || '（无备注）' }}</span>
        </el-timeline-item>
      </el-timeline>
    </el-drawer>

    <!-- ============ 申请解除 ============ -->
    <el-dialog v-model="releaseVisible" title="申请解除封航" width="480px" append-to-body>
      <el-alert type="warning" :closable="false" style="margin-bottom:10px"
                title="进入解除中后，失联升级或已确认未靠泊的处置项仍需清零才能归档；暂停申报不会被自动批准。" />
      <el-form label-width="80px">
        <el-form-item label="解除备注"><el-input v-model="releaseForm.remark" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="值班员"><el-input v-model="releaseForm.operator" style="width:200px" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="releaseVisible = false">取消</el-button>
        <el-button type="warning" :loading="submitting" @click="submitRelease">申请解除</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { closureApi } from '../api'

const REASONS = [
  { value: 'TYPHOON', label: '台风' },
  { value: 'MARITIME_CONTROL', label: '海事管制' },
  { value: 'WATER_RISK', label: '突发水域风险' }
]

const orders = ref([])
const includeArchived = ref(false)
const createVisible = ref(false)
const creating = ref(false)
const form = ref({})

const detailVisible = ref(false)
const detail = ref(null)
const tab = ref('dispositions')

const contactVisible = ref(false)
const contactRow = ref(null)
const contactResult = ref('')
const contactForm = ref({})
const contactsVisible = ref(false)

const berthVisible = ref(false)
const berthRow = ref(null)
const berthForm = ref({})

const resultVisible = ref(false)
const safeResult = ref(null)
const resultMessage = ref('')

const releaseVisible = ref(false)
const releaseForm = ref({})

const submitting = ref(false)
let pollTimer = null

const activeOrder = computed(() => orders.value.find((o) => o.status !== 'ARCHIVED') || null)
const detailTitle = computed(() => detail.value
  ? `${detail.value.order.orderNo} · ${detail.value.order.reasonTypeName} · ${detail.value.order.title}`
  : '封航处置台')

function fmt(v) {
  return v ? String(v).replace('T', ' ').slice(0, 16) : '—'
}

/** 每次点击生成一个幂等令牌；按钮 loading 期间防重复，网络重试时前端可复用同一令牌 */
function reqId() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

function orderTag(s) {
  return { PENDING: 'info', EFFECTIVE: 'danger', RELEASING: 'warning', ARCHIVED: 'success' }[s] || 'info'
}
function planTag(s) {
  return { PENDING: 'warning', APPROVED: 'primary', SUSPENDED: 'danger', RETURNED: 'success', CANCELED: 'info' }[s] || 'info'
}
function planStatusName(s) {
  return { PENDING: '待审批', APPROVED: '已批准', SUSPENDED: '暂停放行', RETURNED: '已返航', CANCELED: '已取消' }[s] || s
}
function effectTag(t) {
  return { SUSPENDED: 'danger', BLOCKED_NEW: 'warning', RESTORED: 'success' }[t] || 'info'
}
function dispTag(s) {
  return { TO_CONTACT: 'info', CONFIRMED_RETURN: 'primary', LOST_CONTACT: 'danger', BERTHED: 'success' }[s] || 'info'
}
function contactTag(r) {
  return { CONTACTED: 'info', CONFIRMED_RETURN: 'primary', LOST_CONTACT: 'danger', REACHED: 'warning', BERTHED: 'success' }[r] || 'info'
}
function dispRowClass({ row }) {
  if (row.status === 'LOST_CONTACT') return 'row-lost'
  if (row.status === 'BERTHED') return 'row-berthed'
  return ''
}

async function loadOrders() {
  orders.value = await closureApi.list(includeArchived.value)
}

async function openOrder(id) {
  detailVisible.value = true
  await loadDetail(id)
}

async function loadDetail(id) {
  detail.value = await closureApi.detail(id)
}

function openCreate() {
  form.value = { reasonType: 'TYPHOON', title: '', description: '', issuedBy: '值班主管', effectiveAt: '' }
  createVisible.value = true
}

async function submitCreate() {
  if (!form.value.title || !form.value.description) {
    ElMessage.warning('标题和详细说明必填')
    return
  }
  creating.value = true
  try {
    const payload = { ...form.value, effectiveAt: form.value.effectiveAt || null }
    const view = await closureApi.create(payload)
    createVisible.value = false
    ElMessage.success(view.status === 'EFFECTIVE' ? '封航令已生效' : '封航令已发布，待生效')
    await loadOrders()
    if (view.status === 'EFFECTIVE') openOrder(view.id)
  } finally {
    creating.value = false
  }
}

async function doActivate(order) {
  await ElMessageBox.confirm('确认立即激活该封航令？将立即生成返港处置项并暂停相关申报。', '激活封航令', { type: 'warning' })
  await closureApi.activate(order.id, '值班主管')
  ElMessage.success('封航令已生效')
  await Promise.all([loadOrders(), loadDetail(order.id)])
}

function openRelease() {
  releaseForm.value = { remark: '', operator: '值班主管' }
  releaseVisible.value = true
}

async function submitRelease() {
  submitting.value = true
  try {
    await closureApi.release(detail.value.order.id, releaseForm.value)
    releaseVisible.value = false
    ElMessage.success('已进入解除中')
    await Promise.all([loadOrders(), loadDetail(detail.value.order.id)])
  } finally {
    submitting.value = false
  }
}

async function doArchive(order) {
  await ElMessageBox.confirm(
    '确认归档？暂停放行的申报将恢复为可继续审批（保留原审批顺序与暂停原因，不会自动批准）。',
    '归档封航令', { type: 'success' })
  await closureApi.archive(order.id, '值班主管')
  ElMessage.success('封航令已归档')
  await Promise.all([loadOrders(), loadDetail(order.id)])
}

// ---------------- 处置项操作 ----------------
const CONTACT_TITLE = {
  CONTACTED: '登记联系情况',
  CONFIRMED_RETURN: '确认返港',
  LOST_CONTACT: '失联升级',
  REACHED: '重新取得联系并确认返港'
}
const contactTitle = computed(() => CONTACT_TITLE[contactResult.value] || '联系')

function openContact(row, result) {
  contactRow.value = row
  contactResult.value = result
  contactForm.value = {
    result,
    remark: '',
    operator: '值班员',
    clientRequestId: reqId(),
    contactTime: '',
    plannedBerthId: row.plannedBerthId || null
  }
  contactVisible.value = true
}

async function submitContact() {
  submitting.value = true
  try {
    await closureApi.contact(contactRow.value.id, {
      ...contactForm.value,
      contactTime: contactForm.value.contactTime || null
    })
    contactVisible.value = false
    ElMessage.success('处置结果已记录')
    await Promise.all([loadOrders(), loadDetail(detail.value.order.id)])
    // 同步最新联系记录到已打开的抽屉数据
    const fresh = detail.value.dispositions.find((d) => d.id === contactRow.value.id)
    if (fresh) contactRow.value = fresh
  } finally {
    submitting.value = false
  }
}

function openContacts(row) {
  contactRow.value = row
  contactsVisible.value = true
}

function openBerth(row) {
  berthRow.value = row
  berthForm.value = {
    berthId: row.plannedBerthId || null,
    berthTime: '',
    remark: '',
    operator: '值班员',
    clientRequestId: reqId()
  }
  berthVisible.value = true
}

async function submitBerth() {
  if (!berthForm.value.berthId) {
    ElMessage.warning('请选择接纳泊位')
    return
  }
  submitting.value = true
  try {
    safeResult.value = await closureApi.safeBerth(berthRow.value.id, {
      ...berthForm.value,
      berthTime: berthForm.value.berthTime || null
    })
    resultMessage.value = `${safeResult.value.vesselName} 已靠泊 ${safeResult.value.berthNo}，关联申报、船只、靠泊记录、泊位状态一次性一致。`
    berthVisible.value = false
    resultVisible.value = true
    await Promise.all([loadOrders(), loadDetail(detail.value.order.id)])
  } finally {
    submitting.value = false
  }
}

// 生效/解除中每 20 秒自动刷新列表与详情，关页重开后看到同一服务端事实
onMounted(() => {
  loadOrders()
  pollTimer = setInterval(() => {
    loadOrders()
    if (detailVisible.value && detail.value &&
        ['PENDING', 'EFFECTIVE', 'RELEASING'].includes(detail.value.order.status)) {
      loadDetail(detail.value.order.id)
    }
  }, 20_000)
})

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.closure-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-radius: 8px;
  margin-bottom: 12px;
  color: #fff;
  background: linear-gradient(90deg, #c62828, #ef5350);
  box-shadow: 0 2px 8px rgba(198, 40, 40, 0.3);
}
.closure-banner.is-releasing {
  background: linear-gradient(90deg, #ef6c00, #ffa726);
}
.cb-left { display: flex; align-items: center; gap: 14px; }
.cb-icon { font-size: 34px; }
.cb-title { font-size: 16px; font-weight: 700; }
.cb-sub { font-size: 12.5px; opacity: 0.95; margin-top: 4px; }
.detail-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 4px;
  border-bottom: 1px solid #eee;
}
:deep(.row-lost) { background: #ffebee !important; }
:deep(.row-berthed) { background: #f1f8e9 !important; }
</style>
