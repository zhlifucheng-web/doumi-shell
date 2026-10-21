import { get, post, put, del, API_BASE } from '@/utils/apiHandler'
const root = '/api/ssh-console/ai'
const quiet = { showSuccess: false, silent: true }
export const aiApi = {
  providers: () => get(`${root}/providers`, {}, quiet),
  saveProvider: data => post(`${root}/providers`, data, quiet),
  deleteProvider: id => del(`${root}/providers/${id}`, quiet),
  testProvider: id => post(`${root}/providers/${id}/test`, {}, { ...quiet, timeout: 110000 }),
  create: (providerId, authorizationMode = 'ask', server = null) => post(`${root}/conversations`, { providerId, authorizationMode, server }, quiet),
  history: accountId => get(`${root}/conversations/history`, accountId == null ? {} : { accountId }, quiet),
  latest: (accountId, hostId) => get(`${root}/conversations/latest`, { ...(accountId == null ? {} : { accountId }), ...(hostId == null ? {} : { hostId }) }, quiet),
  active: (accountId, hostId) => get(`${root}/conversations/active`, { ...(accountId == null ? {} : { accountId }), ...(hostId == null ? {} : { hostId }) }, quiet),
  setActive: (accountId, hostId, conversationId) => {
    const params = new URLSearchParams()
    if (accountId != null) params.set('accountId', accountId)
    if (hostId != null) params.set('hostId', hostId)
    return put(`${root}/conversations/active?${params}`, { conversationId }, quiet)
  },
  status: (id, since) => get(`${root}/conversations/${id}`, { since }, quiet),
  setAuthorization: (id, mode) => post(`${root}/conversations/${id}/authorization`, { mode }, quiet),
  setProvider: (id, providerId) => post(`${root}/conversations/${id}/provider`, { providerId }, quiet),
  eventsUrl: (id, since) => `${API_BASE}${root}/conversations/${encodeURIComponent(id)}/events?since=${since}`,
  send: (id, data) => post(`${root}/conversations/${id}/messages`, data, quiet),
  approve: (id, actionIds, approved) => post(`${root}/conversations/${id}/approve`, { actionIds, approved }, quiet),
  stop: id => post(`${root}/conversations/${id}/stop`, {}, quiet),
  remove: id => del(`${root}/conversations/${id}`, quiet),
  attach: (id, data, onUploadProgress) => post(`${root}/conversations/${id}/attachments`, data, { ...quiet, timeout: 120000, onUploadProgress }),
  tools: () => get(`${root}/tools`, {}, quiet)
}
