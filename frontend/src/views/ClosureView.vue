<template>
  <div>
    <!-- 生效封航横幅：全站只此处+顶栏提示，不做「假提示」，所有按钮仍以后端闸门为准 -->
    <el-alert
      v-if="activeOrder && activeOrder.status === 'EFFECTIVE'"
      class="closure-banner"
      type="error"
      :closable="false"
      show-icon
      :title="`封航中：${activeOrder.orderNo} · ${activeOrder.reasonTypeName}`"
      :description="`生效于 ${fmtTime(activeOrder.activatedAt)}；新出航申报可保存为待审批但不得批准，已批准未出航的申报已暂停放行；在海船只 ${activeOrder.dispTotal} 艘，待安全靠泊 ${riskOpenCount} 艘。`"
    />

    <div class="panel">
      <div class="panel-head">
        <h3>封航令</h3>
        <span class="panel-sub">待生效 → 生效中 → 解除中 → 已归档；到点自动生效，历史令全量留痕</span>
        <div class="panel-extra">
          <el-select v-model="statusFilter" clearable placeholder="全部阶段" style="width:130px" @change="load">
            <el-option v-for="s in CLOSURE_STATUS" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
          <el-button @click="load">刷新</el-button>
          <el-button type="primary" :disabled="!!activeOrder" @click="openCreate">发布封航令</el-button>
        </div>
      </div>

      <el-table :data="orders" size="small" border stripe highlight-current-row
                :row-class-name="orderRowClass" @row-click="selectOrder"
                @current-change="(row) => row && selectOrder(row)">
        <el-table-column prop="orderNo" label="令号" width="140" />
        <el-table-column label="事由" width="170">
          <template #default="{ row }">
            {{ row.reasonTypeName }}
            <div class="muted" style="font-size:11px">{{ row.reasonDetail || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="effectiveAt" label="发布生效时间" width="150" :formatter="fmtTime" />
        <el-table-column label="阶段" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="closureTag(row.status)">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="返港处置进度" min-width="220">
          <template #default="{ row }">
            <el-progress :percentage="berthedPct(row)" :stroke-width="14"
                         :format="() => `${row.dispBerthed}/${row.dispTotal} 已安全靠泊`" />
            <div style="font-size:11px" class="muted">
              待联系 {{ row.dispToContact }} · 已确认 {{ row.dispConfirmed }} ·
              <span :style="row.dispEscalated ? 'color:#c62828;font-weight:700' : ''">失联 {{ row.dispEscalated }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="suspendedPlanCount" label="暂停申报" width="80" />
        <el-table-column prop="issuedBy" label="发布主管" width="90" />
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click.stop="selectOrder(row)">处置台</el-button>
            <el-button v-if="row.status === 'SCHEDULED'" size="small" link type="warning"
                       @click.stop="act(row, 'activate')">立即生效</el-button>
            <el-button v-if="row.status === 'EFFECTIVE'" size="small" link type="warning"
                       @click.stop="act(row, 'lift')">发起解除</el-button>
            <el-button v-if="row.status === 'LIFTING'" size="small" link type="success"
                       :title="riskOpenOf(row) ? '风险项未清零，只能停在解除中' : '风险项已清零，可以归档'"
                       @click.stop="act(row, 'archive')">归档</el-button>
            <el-button v-if="row.status === 'SCHEDULED'" size="small" link type="info"
                       @click.stop="act(row, 'archive')">作废归档</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- ====================== 处置台详情 ====================== -->
    <template v-if="selected">
      <div class="panel" v-loading="detailLoading">
        <div class="panel-head">
          <h3>{{ selected.orderNo }} · 处置台</h3>
          <el-tag size="small" :type="closureTag(selected.status)">{{ selected.statusName }}</el-tag>
          <span class="panel-sub">
            {{ selected.reasonTypeName }}<template v-if="selected.reasonDetail">：{{ selected.reasonDetail }}</template>
            · 生效 {{ fmtTime(null, null, selected.activatedAt) }}
            <template v-if="selected.liftingStartedAt"> · 解除中自 {{ fmtTime(null, null, selected.liftingStartedAt) }}</template>
            <template v-if="selected.archivedAt"> · 归档 {{ fmtTime(null, null, selected.archivedAt) }}</template>
          </span>
        </div>
        <el-descriptions :column="4" size="small" border>
          <el-descriptions-item label="在海处置项">{{ selected.dispTotal }}</el-descriptions-item>
          <el-descriptions-item label="待联系">{{ selected.dispToContact }}</el-descriptions-item>
          <el-descriptions-item label="已确认返港">{{ selected.dispConfirmed }}</el-descriptions-item>
          <el-descriptions-item label="失联升级">
            <span :style="selected.dispEscalated ? 'color:#c62828;font-weight:700' : ''">{{ selected.dispEscalated }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="已安全靠泊">{{ selected.dispBerthed }}</el-descriptions-item>
          <el-descriptions-item label="未清零风险项">
            <span :style="riskOpenOf(selected) ? 'color:#c62828;font-weight:700' : ''">{{ riskOpenOf(selected) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="暂停申报">{{ selected.suspendedPlanCount }}</el-descriptions-item>
          <el-descriptions-item label="发布主管">{{ selected.issuedBy || '—' }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <!-- 受影响的出航申报 -->
      <div class="panel">
        <div class="panel-head">
          <h3>受影响的出航申报</h3>
          <span class="panel-sub">
            已暂停的申报保留原审批顺序与暂停原因，解除后恢复「可继续审批」（不会被自动批准）；
            封航期间新建的申报停留在待审批
          </span>
        </div>
        <el-table :data="affectedPlans" size="small" border>
          <el-table-column prop="planNo" label="申报单号" width="130" />
          <el-table-column prop="vesselName" label="船只" width="100" />
          <el-table-column prop="skipper" label="船长" width="90" />
          <el-table-column prop="departTime" label="出航时间" width="140" :formatter="fmtTime" />
          <el-table-column prop="etaReturn" label="预计返回" width="140" :formatter="fmtTime" />
          <el-table-column label="分组" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.groupTag === 'PENDING' ? 'warning' : 'danger'">
                {{ row.groupTag === 'PENDING' ? '待审批(封航中)' : '暂停放行' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="当前状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="planStatusTag(row.status)">{{ row.statusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="暂停原因 / 留痕" min-width="220">
            <template #default="{ row }">
              <span v-if="row.suspendedReason">{{ row.suspendedReason }}</span>
              <span v-else class="muted">封航期间保存为待审批，可提交但暂不能批准</span>
              <div v-if="row.resumedAt" class="muted" style="font-size:11px">
                已于 {{ fmtTime(null, null, row.resumedAt) }} 恢复可继续审批（未自动批准）
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 返港处置项 -->
      <div class="panel">
        <div class="panel-head">
          <h3>返港处置项（在海船只与接纳泊位）</h3>
          <span class="panel-sub">同一艘船在本令下只有一条；重复点击 / 超时重试幂等，不会重复记录、倒退状态或重复占泊位</span>
          <div class="panel-extra">
            <el-select v-model="dispFilter" clearable placeholder="全部处置状态" style="width:150px" @change="loadDispositions">
              <el-option v-for="s in DISP_STATUS" :key="s.value" :label="s.label" :value="s.value" />
            </el-select>
            <el-button @click="loadDispositions">刷新名单</el-button>
          </div>
        </div>

        <el-table :data="dispositions" size="small" border :row-class-name="dispRowClass">
          <el-table-column prop="vesselName" label="船只" width="100" />
          <el-table-column label="出海申报/船长" width="150">
            <template #default="{ row }">
              {{ row.planNo || '无申报' }}
              <div class="muted" style="font-size:11px">{{ row.skipper || '—' }} · {{ row.phone || '—' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="dispTag(row.status)">{{ row.statusName }}</el-tag>
              <div v-if="row.escalateReason" class="muted" style="font-size:11px">{{ row.escalateReason }}</div>
            </template>
          </el-table-column>
          <el-table-column label="预留/实际泊位" width="160">
            <template #default="{ row }">
              <template v-if="row.status === 'SAFE_BERTHED'">
                <el-tag size="small" type="success">{{ row.berthedBerthNo }} 已靠泊</el-tag>
                <div class="muted" style="font-size:11px">{{ row.berthingNo }} · {{ fmtTime(null, null, row.berthedAt) }}</div>
              </template>
              <template v-else>
                <span v-if="row.assignedBerthNo">
                  预留 <b>{{ row.assignedBerthNo }}</b>
                  <el-tag v-if="row.fitAssignedBerth === false" size="small" type="danger">适泊异常</el-tag>
                </span>
                <span v-else class="muted">未预留</span>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="最近联系" min-width="220">
            <template #default="{ row }">
              <template v-if="row.contacts && row.contacts.length">
                <div>{{ row.contacts[0].resultName }} · {{ fmtTime(null, null, row.contacts[0].contactAt) }} · {{ row.contacts[0].operator || '—' }}</div>
                <div class="muted" style="font-size:11px">{{ row.contacts[0].remark || row.contacts[0].resultText || '' }}</div>
              </template>
              <span v-else class="muted">尚未联系</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="320" fixed="right">
            <template #default="{ row }">
              <el-button size="small" link @click="openContact(row)">联系记录</el-button>
              <el-button v-if="row.status !== 'SAFE_BERTHED'" size="small" link type="primary"
                         @click="openContact(row, 'ACK')">确认返港</el-button>
              <el-button v-if="row.status !== 'SAFE_BERTHED' && row.status !== 'ESCALATED'"
                         size="small" link type="danger" @click="openEscalate(row)">失联升级</el-button>
              <el-button v-if="row.status !== 'SAFE_BERTHED'" size="small" link type="warning"
                         @click="openAssign(row)">预留泊位</el-button>
              <el-button v-if="row.status === 'CONFIRMED_RETURN' || row.status === 'ESCALATED'"
                         size="small" link type="success" @click="openSafeBerth(row)">安全靠泊</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <!-- ====================== 发布封航令 ====================== -->
    <el-dialog v-model="createVisible" title="发布封航令" width="560px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px"
                title="发布后为「待生效」，到生效时间自动转为「生效中」：自动暂停已批准未出航申报，并为每条在海船生成一条返港处置项。" />
      <el-form :model="form" label-width="100px">
        <el-form-item label="封航事由">
          <el-select v-model="form.reasonType" style="width:100%">
            <el-option v-for="r in REASONS" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="事由说明"><el-input v-model="form.reasonDetail" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="生效时间">
          <el-date-picker v-model="form.effectiveAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
                          style="width:100%" placeholder="到点自动生效" />
        </el-form-item>
        <el-form-item label="发布主管"><el-input v-model="form.issuedBy" placeholder="值班主管姓名" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">发布</el-button>
      </template>
    </el-dialog>

    <!-- ====================== 联系记录（含时间/结果/备注留痕） ====================== -->
    <el-dialog v-model="contactVisible" :title="`联系记录 · ${contactCtx && contactCtx.vesselName}`" width="720px">
      <el-timeline style="max-height:300px;overflow:auto;margin:8px 0 16px">
        <el-timeline-item v-for="c in (contactCtx ? contactCtx.contacts : [])" :key="c.id"
                          :timestamp="`${fmtTime(null, null, c.contactAt)} · ${c.operator || '未署名'}`"
                          :type="contactDot(c.resultCode)">
          <el-tag size="small" :type="contactTag(c.resultCode)">{{ c.resultName }}</el-tag>
          <span style="margin-left:8px">{{ c.resultText }}</span>
          <div class="muted" style="font-size:12px">{{ c.remark || '无备注' }}</div>
        </el-timeline-item>
        <div v-if="contactCtx && (!contactCtx.contacts || !contactCtx.contacts.length)" class="muted">还没有联系记录</div>
      </el-timeline>

      <el-divider v-if="contactCtx && contactCtx.status !== 'SAFE_BERTHED'">新增一次联系</el-divider>
      <el-form v-if="contactCtx && contactCtx.status !== 'SAFE_BERTHED'" :model="contactForm" label-width="90px">
        <el-form-item label="联系时间">
          <el-date-picker v-model="contactForm.contactAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
                          style="width:100%" />
        </el-form-item>
        <el-form-item label="联系结果">
          <el-radio-group v-model="contactForm.resultCode">
            <el-radio-button v-for="r in CONTACT_RESULTS" :key="r.value" :value="r.value">{{ r.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="结果说明"><el-input v-model="contactForm.resultText" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="contactForm.remark" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="值班员"><el-input v-model="contactForm.operator" placeholder="本次联系的值班员" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="contactVisible = false">关闭</el-button>
        <el-button v-if="contactCtx && contactCtx.status !== 'SAFE_BERTHED'" type="primary"
                   :loading="submitting" @click="submitContact">保存联系（幂等）</el-button>
      </template>
    </el-dialog>

    <!-- ====================== 失联升级 ====================== -->
    <el-dialog v-model="escalateVisible" :title="`失联升级 · ${escalateCtx && escalateCtx.vesselName}`" width="480px">
      <el-alert type="error" :closable="false" show-icon style="margin-bottom:12px"
                title="标记后该船进入「失联升级」，封航令将不能归档，直到重新联系确认并安全靠泊。" />
      <el-form :model="escalateForm" label-width="90px">
        <el-form-item label="失联说明">
          <el-input v-model="escalateForm.reason" type="textarea" :rows="3"
                    placeholder="末次联系时间、应答情况、已采取措施等" />
        </el-form-item>
        <el-form-item label="值班员"><el-input v-model="escalateForm.operator" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="escalateVisible = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="submitEscalate">确认失联升级</el-button>
      </template>
    </el-dialog>

    <!-- ====================== 预留返港泊位 ====================== -->
    <el-dialog v-model="assignVisible" :title="`预留返港泊位 · ${assignCtx && assignCtx.vesselName}`" width="720px">
      <el-table :data="candidateBerths" size="small" border max-height="340"
                @row-dblclick="(row) => chooseBerth(row.id)">
        <el-table-column prop="berthNo" label="泊位号" width="90" />
        <el-table-column prop="zoneCode" label="区域" width="60" />
        <el-table-column label="长度/吃水(m)" width="130">
          <template #default="{ row }">{{ row.lengthM }} / {{ row.maxDraftM }}</template>
        </el-table-column>
        <el-table-column prop="allowTypeNames" label="允许船型" min-width="130" />
        <el-table-column prop="dailyRate" label="日租金" width="90" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'RESERVED' ? 'warning' : 'success'">
              {{ row.status === 'RESERVED' ? '本令已预留' : '空闲' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="chooseBerth(row.id)">预留</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="muted" style="margin-top:8px">仅显示该船适泊（船长/吃水/船型）且当前空闲或本令预留的泊位；双击行也可选中。</div>
      <el-form style="margin-top:10px" label-width="90px">
        <el-form-item label="值班员"><el-input v-model="assignForm.operator" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button v-if="assignCtx && assignCtx.assignedBerthNo" @click="doReleaseBerth">释放 {{ assignCtx.assignedBerthNo }}</el-button>
      </template>
    </el-dialog>

    <!-- ====================== 安全靠泊（一致性核心） ====================== -->
    <el-dialog v-model="safeVisible" :title="`登记安全靠泊 · ${safeCtx && safeCtx.vesselName}`" width="560px">
      <el-alert type="success" :closable="false" show-icon style="margin-bottom:12px"
                title="提交后在一笔事务内同时更新：处置项→已安全靠泊、出海申报→已返航、船只→在港、生成在泊靠泊记录、泊位→在泊。任一步失败全部回退，不会只改一半。" />
      <el-form :model="safeForm" label-width="100px">
        <el-form-item label="靠泊泊位">
          <el-select v-model="safeForm.berthId" filterable style="width:100%" :placeholder="safeCtx && safeCtx.assignedBerthNo ? `默认预留位 ${safeCtx.assignedBerthNo}` : '请选择'">
            <el-option v-for="b in candidateBerths" :key="b.id"
                       :label="`${b.berthNo}（${b.zoneCode}区 ${b.lengthM}m/${b.maxDraftM}m ${b.allowTypeNames}）`" :value="b.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="靠泊时间">
          <el-date-picker v-model="safeForm.berthTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="safeForm.remark" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="值班员"><el-input v-model="safeForm.operator" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="safeVisible = false">取消</el-button>
        <el-button type="success" :loading="submitting" @click="submitSafeBerth">确认安全靠泊</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { closureApi } from '../api'

const CLOSURE_STATUS = [
  { value: 'SCHEDULED', label: '待生效' },
  { value: 'EFFECTIVE', label: '生效中' },
  { value: 'LIFTING', label: '解除中' },
  { value: 'ARCHIVED', label: '已归档' }
]
const REASONS = [
  { value: 'TYPHOON', label: '台风' },
  { value: 'MARITIME_CONTROL', label: '海事管制' },
  { value: 'WATER_RISK', label: '突发水域风险' },
  { value: 'OTHER', label: '其他' }
]
const DISP_STATUS = [
  { value: 'TO_CONTACT', label: '待联系' },
  { value: 'CONFIRMED_RETURN', label: '已确认返港' },
  { value: 'ESCALATED', label: '失联升级' },
  { value: 'SAFE_BERTHED', label: '已安全靠泊' }
]
const CONTACT_RESULTS = [
  { value: 'NOTIFIED', label: '已通知返港' },
  { value: 'ACK', label: '确认返港' },
  { value: 'NO_ANSWER', label: '无人应答' },
  { value: 'ESCALATE', label: '失联上报' },
  { value: 'OTHER', label: '其他情况' }
]

const marina = inject('marina', null)
const orders = ref([])
const activeOrder = ref(null)
const selected = ref(null)
const affectedPlans = ref([])
const dispositions = ref([])
const statusFilter = ref('')
const dispFilter = ref('')
const detailLoading = ref(false)
const submitting = ref(false)

const createVisible = ref(false)
const form = ref({})
const contactVisible = ref(false)
const contactCtx = ref(null)
const contactForm = ref({})
const escalateVisible = ref(false)
const escalateCtx = ref(null)
const escalateForm = ref({})
const assignVisible = ref(false)
const assignCtx = ref(null)
const assignForm = ref({})
const candidateBerths = ref([])
const safeVisible = ref(false)
const safeCtx = ref(null)
const safeForm = ref({})

const riskOpenCount = computed(() => riskOpenOf(activeOrder.value))

function fmtTime(row, column, value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}
function closureTag(s) {
  return { SCHEDULED: 'warning', EFFECTIVE: 'danger', LIFTING: 'primary', ARCHIVED: 'info' }[s] || 'info'
}
function planStatusTag(s) {
  return { PENDING: 'warning', APPROVED: 'primary', SUSPENDED: 'danger', RETURNED: 'success', CANCELED: 'info' }[s] || 'info'
}
function dispTag(s) {
  return { TO_CONTACT: 'info', CONFIRMED_RETURN: 'primary', ESCALATED: 'danger', SAFE_BERTHED: 'success' }[s] || 'info'
}
function contactTag(r) {
  return { ACK: 'success', ESCALATE: 'danger', NO_ANSWER: 'warning', NOTIFIED: 'primary', SAFE: 'success', OTHER: 'info' }[r] || 'info'
}
function contactDot(r) {
  return { ACK: 'success', ESCALATE: 'danger', NO_ANSWER: 'warning', SAFE: 'success' }[r] || 'primary'
}
function berthedPct(row) {
  if (!row.dispTotal) return 0
  return Math.round((row.dispBerthed / row.dispTotal) * 100)
}
function riskOpenOf(row) {
  if (!row) return 0
  return (row.dispEscalated || 0) + (row.dispConfirmed || 0)
}
function orderRowClass({ row }) {
  return row.status === 'EFFECTIVE' ? 'row-closure-effective' : row.status === 'LIFTING' ? 'row-closure-lifting' : ''
}
function dispRowClass({ row }) {
  return row.status === 'ESCALATED' ? 'row-overdue' : ''
}

/** 前端幂等键：同一按钮一次会话内的重复提交/超时重试携带同一值，后端按它去重 */
function rid(prefix) {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

async function load() {
  orders.value = await closureApi.list(statusFilter.value)
  activeOrder.value = await closureApi.active()
  if (selected.value) {
    const still = orders.value.find((o) => o.id === selected.value.id)
    if (still) {
      selected.value = still
      await Promise.all([loadAffected(), loadDispositions()])
    } else if (!statusFilter.value) {
      selected.value = null
    }
  }
  if (marina) await marina.refreshOverview()
}

async function selectOrder(row) {
  selected.value = row
  dispFilter.value = ''
  detailLoading.value = true
  try {
    selected.value = await closureApi.detail(row.id)
    await Promise.all([loadAffected(), loadDispositions()])
  } finally {
    detailLoading.value = false
  }
}
async function loadAffected() {
  affectedPlans.value = selected.value ? await closureApi.affectedPlans(selected.value.id) : []
}
async function loadDispositions() {
  if (!selected.value) return
  dispositions.value = await closureApi.dispositions(selected.value.id, dispFilter.value)
}

function openCreate() {
  form.value = {
    reasonType: 'TYPHOON',
    reasonDetail: '',
    effectiveAt: new Date(Date.now() + 60 * 60 * 1000).toISOString().slice(0, 19),
    issuedBy: ''
  }
  createVisible.value = true
}
async function submitCreate() {
  submitting.value = true
  try {
    await closureApi.create(form.value)
    ElMessage.success('封航令已发布')
    createVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function act(row, action) {
  if (action === 'activate') {
    const { value: operator } = await ElMessageBox.prompt('立即让该封航令生效？将立即暂停未出航申报并生成返港名单。', '立即生效', {
      confirmButtonText: '立即生效', cancelButtonText: '取消', inputPlaceholder: '值班主管姓名', inputValue: row.issuedBy || ''
    }).catch(() => ({ value: undefined }))
    if (operator === undefined) return
    await closureApi.activate(row.id, operator || null)
  } else if (action === 'lift') {
    await ElMessageBox.confirm(
      '发起解除后进入「解除中」：暂停的申报恢复为可继续审批（保留原审批顺序与暂停原因，不会自动批准）。仍有失联/确认未靠泊处置项时不能归档。',
      '发起解除封航', { confirmButtonText: '发起解除', cancelButtonText: '取消', type: 'warning' })
    const { value: operator } = await ElMessageBox.prompt('确认发起解除？请输入值班员姓名', '解除封航', {
      confirmButtonText: '确认', cancelButtonText: '取消', inputPlaceholder: '值班员姓名'
    }).catch(() => ({ value: undefined }))
    if (operator === undefined) return
    await closureApi.lift(row.id, operator || null)
  } else if (action === 'archive') {
    if (action === 'archive' && riskOpenOf(row) > 0 && row.status === 'LIFTING') {
      ElMessage.warning(`仍有 ${riskOpenOf(row)} 条失联升级/已确认未靠泊的处置项，只能停在解除中`)
      return
    }
    const tip = row.status === 'SCHEDULED'
      ? '该封航令尚未生效，作废后直接归档（不会暂停申报、不生成返港名单）。'
      : '确认风险项已清零并归档封航令？归档后全流程仍可追溯查询。'
    const { value: operator } = await ElMessageBox.prompt(tip, '归档封航令', {
      confirmButtonText: '归档', cancelButtonText: '取消', inputPlaceholder: '值班员姓名'
    }).catch(() => ({ value: undefined }))
    if (operator === undefined) return
    await closureApi.archive(row.id, operator || null)
  }
  ElMessage.success('操作完成')
  await load()
}

async function openContact(row, presetResult) {
  const detail = await closureApi.dispDetail(row.id)
  contactCtx.value = detail
  contactForm.value = {
    contactAt: new Date().toISOString().slice(0, 19),
    resultCode: presetResult || 'NOTIFIED',
    resultText: '',
    remark: '',
    operator: '',
    requestId: rid('contact')
  }
  contactVisible.value = true
}
async function submitContact() {
  submitting.value = true
  try {
    const updated = await closureApi.addContact(contactCtx.value.id, contactForm.value)
    ElMessage.success('联系已记录')
    contactVisible.value = false
    await loadDispositions()
    if (selected.value && selected.value.id === updated.closureId) {
      await selectOrder({ id: selected.value.id })
    }
  } finally {
    submitting.value = false
  }
}

async function openEscalate(row) {
  escalateCtx.value = row
  escalateForm.value = { reason: '', operator: '', requestId: rid('escalate') }
  escalateVisible.value = true
}
async function submitEscalate() {
  submitting.value = true
  try {
    await closureApi.escalate(escalateCtx.value.id, escalateForm.value)
    ElMessage.success('已标记失联升级')
    escalateVisible.value = false
    await loadDispositions()
    await selectOrder({ id: selected.value.id })
  } finally {
    submitting.value = false
  }
}

async function openAssign(row) {
  assignCtx.value = row
  assignForm.value = { operator: '' }
  assignVisible.value = true
  candidateBerths.value = await closureApi.candidateBerths(row.id)
}
async function chooseBerth(berthId) {
  const { value: operator } = await ElMessageBox.prompt('确认将该泊位预留给此船返港靠泊？', '预留泊位', {
    confirmButtonText: '预留', cancelButtonText: '取消', inputPlaceholder: '值班员姓名'
  }).catch(() => ({ value: undefined }))
  if (operator === undefined) return
  await closureApi.assignBerth(assignCtx.value.id, { berthId, operator: operator || null })
  ElMessage.success('返港泊位已预留')
  assignVisible.value = false
  await loadDispositions()
  await selectOrder({ id: selected.value.id })
}
async function doReleaseBerth() {
  await ElMessageBox.confirm(`释放预留泊位 ${assignCtx.value.assignedBerthNo}？`, '释放泊位', { type: 'warning' })
  await closureApi.releaseBerth(assignCtx.value.id, null)
  ElMessage.success('预留泊位已释放')
  assignVisible.value = false
  await loadDispositions()
}

async function openSafeBerth(row) {
  safeCtx.value = row
  candidateBerths.value = await closureApi.candidateBerths(row.id)
  safeForm.value = {
    berthId: row.assignedBerthId || null,
    berthTime: new Date().toISOString().slice(0, 19),
    remark: '',
    operator: '',
    requestId: rid('berth')
  }
  safeVisible.value = true
}
async function submitSafeBerth() {
  submitting.value = true
  try {
    await closureApi.safeBerth(safeCtx.value.id, safeForm.value)
    ElMessage.success('安全靠泊已登记，申报、船只、靠泊记录与泊位已同步')
    safeVisible.value = false
    await loadDispositions()
    await loadAffected()
    await selectOrder({ id: selected.value.id })
    if (marina) {
      await marina.reloadVessels()
      await marina.refreshOverview()
    }
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.closure-banner {
  margin-bottom: 14px;
}
:deep(.row-closure-effective) {
  background-color: #fdecea !important;
}
:deep(.row-closure-lifting) {
  background-color: #eef4ff !important;
}
</style>
