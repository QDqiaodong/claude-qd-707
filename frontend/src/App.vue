<template>
  <div class="shell">
    <!-- 顶部固定条：品牌 + 码头计数器 -->
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">⚓</span>
        <div>
          游艇码头 Marina 泊位运营管理平台
          <div class="brand-sub">{{ todayText }} · 泊位占用 {{ overview.berthInPort || 0 }}/{{ overview.berthTotal || 0 }}</div>
        </div>
      </div>

      <div class="top-stats">
        <div class="stat-chip"><b>{{ overview.berthFree || 0 }}</b><span>空闲泊位</span></div>
        <div class="stat-chip"><b>{{ overview.berthReserved || 0 }}</b><span>预留</span></div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.berthDisabled || 0) > 0 }">
          <b>{{ overview.berthDisabled || 0 }}</b><span>停用维修</span>
        </div>
        <div class="stat-chip"><b>{{ overview.vesselInPort || 0 }}</b><span>在港船</span></div>
        <div class="stat-chip"><b>{{ overview.vesselAtSea || 0 }}</b><span>出海中</span></div>
        <div class="stat-chip"><b>{{ overview.planPending || 0 }}</b><span>待审批</span></div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.planSuspended || 0) > 0 }">
          <b>{{ overview.planSuspended || 0 }}</b><span>暂停放行</span>
        </div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.closureEffective || 0) > 0 }">
          <b>{{ overview.closureActive || 0 }}</b><span>封航令</span>
        </div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.dispRiskOpen || 0) > 0 }">
          <b>{{ overview.dispRiskOpen || 0 }}</b><span>返港风险</span>
        </div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.planOverdue || 0) > 0 }">
          <b>{{ overview.planOverdue || 0 }}</b><span>超期未返</span>
        </div>
        <div class="stat-chip" :class="{ 'is-warn': (overview.serviceUnsettled || 0) > 0 }">
          <b>{{ overview.serviceUnsettled || 0 }}</b><span>未结费用</span>
        </div>
        <div class="stat-chip" :class="{ 'is-warn': (overview.insuranceWarn || 0) > 0 }">
          <b>{{ overview.insuranceWarn || 0 }}</b><span>保险预警</span>
        </div>
      </div>
    </header>

    <!-- 横向模块条 -->
    <nav class="navbar">
      <button
        v-for="item in navItems"
        :key="item.key"
        class="nav-btn"
        :class="{ 'is-on': activeKey === item.key }"
        type="button"
        :title="item.hint"
        @click="go(item.path)"
      >
        <span class="nav-ico">{{ item.icon }}</span>{{ item.label }}
      </button>
    </nav>

    <main class="stage">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed, getCurrentInstance, onMounted, provide, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import router, { navItems } from './router'
import { marinaApi, vesselApi } from './api'

/**
 * 模板自带的 main.js 只装了 Element Plus，没装 router（那份文件不动它），
 * 所以在这里把 router 装到根组件所属的 app 实例上。
 * vue-router 的 install 自带幂等保护，重复调用无副作用。
 */
getCurrentInstance().appContext.app.use(router)

const route = useRoute()
const nav = useRouter()

const overview = ref({})
const vessels = ref([])

/** 全局上下文：顶栏计数 + 船只下拉（各页 dialog 直接用） */
const marina = {
  overview,
  vessels,
  refreshOverview: loadOverview,
  reloadVessels: loadVessels
}
provide('marina', marina)

const todayText = computed(() => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
})

const activeKey = computed(() => {
  const hit = navItems.find((item) => route.path.startsWith(item.path))
  return hit ? hit.key : 'berths'
})

function go(path) {
  nav.push(path)
}

async function loadOverview() {
  overview.value = await marinaApi.overview()
}

async function loadVessels() {
  vessels.value = await vesselApi.list()
}

onMounted(async () => {
  await loadOverview()
  await loadVessels()
})
</script>
