export function createUpdateClient({ base = '', fetchImpl = fetch } = {}) {
  return async function request(path, { method = 'GET', token = '', body } = {}) {
    if (!['status', 'settings', 'install'].includes(path)) throw new Error('无效更新接口')
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), 15000)
    try {
      const headers = { Accept: 'application/json' }
      if (token) headers['X-Doumi-Update-Token'] = token
      if (body !== undefined) headers['Content-Type'] = 'application/json'
      const response = await fetchImpl(`${base.replace(/\/$/, '')}/api/ssh-console/update/${path}`, {
        method, headers, body: body === undefined ? undefined : JSON.stringify(body),
        cache: 'no-store', signal: controller.signal
      })
      const payload = await response.json()
      if (!response.ok || payload?.success !== true || payload?.code !== 200) {
        throw new Error(payload?.externMessage || payload?.message || `更新请求失败（HTTP ${response.status}）`)
      }
      if (!payload.data || typeof payload.data !== 'object') throw new Error('更新服务返回了无效数据')
      return payload.data
    } catch (error) {
      if (controller.signal.aborted) throw new Error('更新接口暂时无响应，请稍后重试；安装期间请等待服务恢复')
      if (error instanceof TypeError || error instanceof SyntaxError) throw new Error('暂时无法连接更新服务；正在安装时请等待服务恢复')
      throw error
    } finally { clearTimeout(timer) }
  }
}
const phases = {
  IDLE: ['等待更新', 'info'], CHECKING: ['确认版本', 'info'], DOWNLOADING: ['下载安装包', 'info'],
  VERIFYING: ['校验安装包', 'info'], PREPARING: ['准备更新环境', 'info'], DRAINING: ['等待请求结束', 'info'],
  STOPPING: ['停止旧服务', 'info'], BACKING_UP: ['备份程序和数据', 'info'], INSTALLING: ['安装新版本', 'info'],
  RESTARTING: ['检查新服务', 'info'], ROLLING_BACK: ['恢复旧版本', 'warning'],
  SUCCEEDED: ['更新完成', 'success'], FAILED: ['更新失败', 'error'],
  ROLLED_BACK: ['已恢复旧版本', 'warning'], ROLLBACK_FAILED: ['需要在部署机器检查', 'error']
}
export function updatePhase(phase) {
  const [label, type] = phases[phase] || ['状态未知，请稍后重试', 'warning']
  return { label, type }
}
