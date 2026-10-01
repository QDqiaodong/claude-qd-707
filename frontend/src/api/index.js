import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一走 nginx 反代到后端 /api：
 *   成功 → 后端包成 { ok:true, message, data }，这里直接把 data 交给页面
 *   失败 → 后端包成 { ok:false, message:"中文业务提示" }，这里统一弹提示并 reject
 */
const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body === 'object' && Object.prototype.hasOwnProperty.call(body, 'ok')) {
      if (body.ok) return body.data
      ElMessage.error(body.message || '操作失败')
      return Promise.reject(new Error(body.message || '操作失败'))
    }
    return body
  },
  (err) => {
    const body = err.response && err.response.data
    const message = (body && body.message) || err.message || '网络异常，请稍后再试'
    ElMessage.error(message)
    return Promise.reject(new Error(message))
  }
)

/** 码头总览 */
export const marinaApi = {
  overview: () => http.get('/marina/overview')
}

/** 模块一：泊位（列表带出 @JoinFormula 派生出来的当前在泊船只） */
export const berthApi = {
  list: (params) => http.get('/berths', { params: params || {} }),
  create: (data) => http.post('/berths', data),
  occupy: (id, vesselId, etaDepart) => http.post(`/berths/${id}/occupy`, { vesselId, etaDepart }),
  depart: (id, actualDepart) => http.post(`/berths/${id}/depart`, actualDepart ? { actualDepart } : {}),
  setStatus: (id, status) => http.post(`/berths/${id}/status`, { status }),
  berthings: (id) => http.get(`/berths/${id}/berthings`)
}

/** 模块二：船只台账（列表带出 @JoinFormula 派生出来的当前泊位） */
export const vesselApi = {
  list: (params) => http.get('/vessels', { params: params || {} }),
  create: (data) => http.post('/vessels', data),
  setStatus: (id, status) => http.post(`/vessels/${id}/status`, { status }),
  renewInsurance: (id, insuranceDue) => http.post(`/vessels/${id}/insurance`, { insuranceDue }),
  berthings: (id) => http.get(`/vessels/${id}/berthings`)
}

/** 模块三：靠泊记录 */
export const berthingApi = {
  list: (params) => http.get('/berthings', { params: params || {} }),
  create: (data) => http.post('/berthings', data),
  depart: (id, actualDepart) => http.post(`/berthings/${id}/depart`, actualDepart ? { actualDepart } : {})
}

/** 模块四：出航申报 */
export const planApi = {
  list: (params) => http.get('/sailing-plans', { params: params || {} }),
  nextNo: () => http.get('/sailing-plans/next-no'),
  create: (data) => http.post('/sailing-plans', data),
  approve: (id) => http.post(`/sailing-plans/${id}/approve`),
  depart: (id) => http.post(`/sailing-plans/${id}/depart`),
  markReturn: (id) => http.post(`/sailing-plans/${id}/return`),
  cancel: (id) => http.post(`/sailing-plans/${id}/cancel`)
}

/** 模块五：码头服务与费用 */
export const serviceApi = {
  list: (params) => http.get('/service-records', { params: params || {} }),
  summary: () => http.get('/service-records/summary'),
  create: (data) => http.post('/service-records', data),
  settle: (id, settleStatus) => http.post(`/service-records/${id}/settle`, { settleStatus })
}

/** 模块六/七：封航令与返港处置台 */
export const closureApi = {
  list: (status) => http.get('/closures', { params: status ? { status } : {} }),
  active: () => http.get('/closures/active'),
  detail: (id) => http.get(`/closures/${id}`),
  affectedPlans: (id) => http.get(`/closures/${id}/affected-plans`),
  dispositions: (id, status) => http.get(`/closures/${id}/dispositions`, { params: status ? { status } : {} }),
  create: (data) => http.post('/closures', data),
  activate: (id, operator) => http.post(`/closures/${id}/activate`, { operator }),
  lift: (id, operator) => http.post(`/closures/${id}/lift`, { operator }),
  archive: (id, operator) => http.post(`/closures/${id}/archive`, { operator }),
  dispDetail: (dispId) => http.get(`/dispositions/${dispId}`),
  candidateBerths: (dispId) => http.get(`/dispositions/${dispId}/candidate-berths`),
  addContact: (dispId, data) => http.post(`/dispositions/${dispId}/contacts`, data),
  escalate: (dispId, data) => http.post(`/dispositions/${dispId}/escalate`, data),
  assignBerth: (dispId, data) => http.post(`/dispositions/${dispId}/assign-berth`, data),
  releaseBerth: (dispId, operator) => http.post(`/dispositions/${dispId}/release-berth`, { operator }),
  safeBerth: (dispId, data) => http.post(`/dispositions/${dispId}/safe-berth`, data)
}
