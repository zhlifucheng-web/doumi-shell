/**
 * 校验主机地址（支持 IPv4、IPv6 及标准域名/主机名）
 */
export function isValidHostAddress(value) {
  if (value === undefined || value === null) return false
  const str = String(value).trim()
  if (!str) return false

  // 纯数字与点组成的内容按 IPv4 校验：必须为严格的 4 段，每段 0 ~ 255
  if (/^[\d.]+$/.test(str)) {
    const ipv4Regex = /^(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/
    return ipv4Regex.test(str)
  }

  // IPv6: 支持标准展开格式、零压缩格式 :: 以及中括号包裹 [::1]
  const cleanIpv6 = str.startsWith('[') && str.endsWith(']') ? str.slice(1, -1) : str
  const ipv6Regex = /^(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]+|::(ffff(:0{1,4})?:)?((25[0-5]|(2[0-4]|1?[0-9])?[0-9])\.){3}(25[0-5]|(2[0-4]|1?[0-9])?[0-9])|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1?[0-9])?[0-9])\.){3}(25[0-5]|(2[0-4]|1?[0-9])?[0-9]))$/
  if (ipv6Regex.test(cleanIpv6)) return true

  // 域名 / 主机名：支持单主机名 (如 localhost, node1, server-01)、多级域名 (如 api.example.com)
  const hostnameRegex = /^(?=.{1,253}$)(?!-)[a-zA-Z0-9_-]{1,63}(?<!-)(\.(?!-)[a-zA-Z0-9_-]{1,63}(?<!-))*$/
  if (hostnameRegex.test(str)) {
    // 多级域名的顶级域（最后一段）不得为纯数字
    if (str.includes('.')) {
      const parts = str.split('.')
      const tld = parts[parts.length - 1]
      if (/^\d+$/.test(tld)) return false
    }
    return true
  }

  return false
}

/**
 * 校验 SSH 端口号（1 ~ 65535 整数）
 */
export function isValidPort(value) {
  if (value === undefined || value === null || value === '') return false
  const num = Number(value)
  if (!Number.isInteger(num)) return false
  return num >= 1 && num <= 65535
}

/**
 * Element Plus 表单校验器：主机地址
 */
export function validateHostAddressRule(rule, value, callback) {
  if (!value || !String(value).trim()) {
    return callback(new Error('请输入主机地址（IP 或域名）'))
  }
  const str = String(value).trim()
  if (str.includes(' ') || str.includes('\t')) {
    return callback(new Error('地址中不能包含空格'))
  }
  if (/:[0-9]+$/.test(str) && !str.includes('::')) {
    return callback(new Error('地址请勿包含端口号，端口请在下方端口输入框中填写'))
  }
  if (!isValidHostAddress(str)) {
    return callback(new Error('请输入有效的 IP 地址（IPv4/IPv6）或域名/主机名'))
  }
  callback()
}

/**
 * Element Plus 表单校验器：端口号
 */
export function validateHostPortRule(rule, value, callback) {
  if (value === undefined || value === null || value === '') {
    return callback(new Error('请输入 SSH 端口号'))
  }
  const num = Number(value)
  if (!Number.isInteger(num)) {
    return callback(new Error('端口号必须为整数'))
  }
  if (num < 1 || num > 65535) {
    return callback(new Error('端口号范围必须在 1 ~ 65535 之间'))
  }
  callback()
}
