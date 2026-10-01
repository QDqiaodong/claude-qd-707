import { createRouter, createWebHistory } from 'vue-router'

/**
 * 导航形态：顶部一条横向模块条（不是侧边菜单）。
 * 四个 Tab：泊位图（首页主视觉）/ 出航申报 / 船只 / 服务费用。
 */
export const navItems = [
  {
    key: 'berths',
    path: '/berths',
    icon: '⚓',
    label: '泊位图',
    hint: 'SVG 手绘码头泊位俯视图 · 点泊位办靠泊/离泊'
  },
  {
    key: 'plans',
    path: '/plans',
    icon: '🧭',
    label: '出航申报',
    hint: '待审批 → 已批准 → 已返航 · 超期未返标红'
  },
  {
    key: 'closures',
    path: '/closures',
    icon: '🌊',
    label: '封航返港',
    hint: '封航令四阶段 · 受影响申报 · 在海船只返港处置 · 安全靠泊一笔一致'
  },
  {
    key: 'vessels',
    path: '/vessels',
    icon: '⛵',
    label: '船只',
    hint: '卡片墙 · 保险到期预警 · 历史靠泊抽屉'
  },
  {
    key: 'services',
    path: '/services',
    icon: '🧾',
    label: '服务费用',
    hint: '月度费用汇总 · 类型筛选 · 结算挂账'
  }
]

const routes = [
  { path: '/', redirect: '/berths' },
  { path: '/berths', name: 'berths', component: () => import('../views/BerthView.vue') },
  { path: '/plans', name: 'plans', component: () => import('../views/SailingPlanView.vue') },
  { path: '/closures', name: 'closures', component: () => import('../views/ClosureConsoleView.vue') },
  { path: '/vessels', name: 'vessels', component: () => import('../views/VesselView.vue') },
  { path: '/services', name: 'services', component: () => import('../views/ServiceRecordView.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/berths' }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
