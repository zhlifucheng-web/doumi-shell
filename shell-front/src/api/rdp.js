import { get, post, put, del } from '@/utils/apiHandler'
const BASE = '/api/ssh-console/rdp'
const quiet = { showSuccess: false, showFailure: false }
export const listRdpConnections = hostId => get(`${BASE}/connections`, { hostId }, quiet)
export const getRdpConnection = id => get(`${BASE}/connections/${id}`, {}, quiet)
export const createRdpConnection = data => post(`${BASE}/connections`, data, quiet)
export const updateRdpConnection = (id, data) => put(`${BASE}/connections/${id}`, data, quiet)
export const deleteRdpConnection = id => del(`${BASE}/connections/${id}`, quiet)
export const testRdpConnection = data => post(`${BASE}/connections/test`, data, quiet)
export const openRdpSession = data => post(`${BASE}/sessions`, data, quiet)
export const closeRdpSession = token => del(`${BASE}/sessions/${encodeURIComponent(token)}`, quiet)
export const getRdpGateway = () => get(`${BASE}/gateway`, {}, quiet)
