import { get, post, del } from '@/utils/apiHandler'
const root = '/api/ssh-console/ai'
const quiet = { showSuccess: false, silent: true }
export const aiApi = {
  providers: () => get(`${root}/providers`, {}, quiet),
  saveProvider: data => post(`${root}/providers`, data, quiet),
  deleteProvider: id => del(`${root}/providers/${id}`, quiet),
  testProvider: id => post(`${root}/providers/${id}/test`, {}, { ...quiet, timeout: 110000 }),
  create: providerId => post(`${root}/conversations`, { providerId }, quiet),
  status: (id, since) => get(`${root}/conversations/${id}`, { since }, quiet),
  send: (id, data) => post(`${root}/conversations/${id}/messages`, data, quiet),
  approve: (id, actionIds, approved) => post(`${root}/conversations/${id}/approve`, { actionIds, approved }, quiet),
  stop: id => post(`${root}/conversations/${id}/stop`, {}, quiet),
  remove: id => del(`${root}/conversations/${id}`, quiet),
  attach: (id, data, onUploadProgress) => post(`${root}/conversations/${id}/attachments`, data, { ...quiet, timeout: 120000, onUploadProgress }),
  tools: () => get(`${root}/tools`, {}, quiet)
}
