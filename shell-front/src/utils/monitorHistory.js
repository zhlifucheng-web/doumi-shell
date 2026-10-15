// 当前浏览器会话内保存最近访问的 8 个账户，不写入磁盘或配置备份。
const histories = new Map()
export function readMonitorHistory(key) { return histories.get(key) || [] }
export function saveMonitorHistory(key, points) {
  histories.delete(key)
  histories.set(key, points)
  while (histories.size > 8) histories.delete(histories.keys().next().value)
}
