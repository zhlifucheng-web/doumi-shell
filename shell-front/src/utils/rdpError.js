// Server errors may echo credentials or ticket URLs. Only recognized diagnostic
// phrases/codes are safe to retain; arbitrary server text never reaches the UI.
const diagnostics = /\b(?:ERRCONNECT_(?:TLS_CONNECT_FAILED|LOGON_FAILURE|CONNECT_FAILED|AUTHENTICATION_FAILED|DNS_NAME_NOT_FOUND|SECURITY_NEGO_CONNECT_FAILED)|STATUS_(?:LOGON_FAILURE|WRONG_PASSWORD|ACCOUNT_LOCKED_OUT|PASSWORD_EXPIRED)|certificate verify failed|self[- ]signed certificate|certificate (?:expired|has expired|validation failed|verification failed)|TLS connection failed|TLS handshake failed|SSL connection failed|authentication failed|connection refused|connection timed out|guacd unavailable)\b/gi

export function describeRdpError(value, profile = {}) {
  const raw = typeof value === 'string' ? value : ''
  let kind = 'unknown', title = '连接失败', message = '请检查连接设置后重试。'
  if (/\[GUACD_UNAVAILABLE\]|guacd unavailable/i.test(raw)) {
    kind = 'gateway'; title = 'guacd 网关不可用'
    message = '无法连接 guacd。请在运行本项目的服务器上安装并启动 guacd；若已安装，请检查网关地址、端口和网络。远程 Windows 无需安装 guacd。'
  } else if (/\[GUACD_DISABLED\]/.test(raw)) {
    kind = 'gateway'; title = '远程桌面已禁用'; message = '请由管理员启用项目服务器上的 RDP 功能并配置 guacd 网关。'
  } else if (/\[GUACD_CHECK_FAILED\]/.test(raw)) {
    kind = 'gateway-check'; title = '无法检查 guacd 网关'; message = '网关检查请求未完成，请检查项目服务和网络后重试。'
  } else if (/certificate (?:verify|validation|verification) failed|certificate (?:has )?expired|certificate is not trusted|证书(?:验证|校验)失败|证书已?过期/i.test(raw)) {
    kind = 'certificate'; title = '证书验证失败'
    message = profile.ignoreCertificate
      ? '已设置忽略证书；请检查服务器证书和安全协议设置后重试。'
      : '请检查服务器证书。确认服务器可信后，可在编辑连接中启用“忽略证书”。'
  } else if (/ERRCONNECT_(?:LOGON_FAILURE|AUTHENTICATION_FAILED)|STATUS_(?:LOGON_FAILURE|WRONG_PASSWORD|ACCOUNT_LOCKED_OUT|PASSWORD_EXPIRED)|authentication (?:failed|failure)|logon (?:failed|failure)|身份验证失败|认证失败|登录失败/i.test(raw)) {
    kind = 'authentication'; title = '身份验证失败'; message = '请检查用户名、密码、域和远程登录权限后重试。'
  } else if (/ERRCONNECT_(?:TLS_CONNECT_FAILED|SECURITY_NEGO_CONNECT_FAILED)|(?:TLS|SSL)(?: connection| handshake)? (?:failed|failure)|security negotiation failed|安全协议(?:协商)?失败/i.test(raw)) {
    kind = 'tls'; title = '安全连接失败'
    message = /untrusted|self[- ]signed|证书/i.test(raw)
      ? profile.ignoreCertificate
        ? '已设置忽略证书；请检查服务器安全协议和连接设置后重试。'
        : '可能与服务器证书或安全协议有关。请检查连接设置；确认服务器可信后，可选择忽略证书。'
      : '请检查服务器安全协议和连接设置后重试。'
  } else if (/ERRCONNECT_(?:CONNECT_FAILED|DNS_NAME_NOT_FOUND)|connection (?:refused|timed? out)|timed out|unreachable|guacd unavailable|连接超时|网络不可达|拒绝连接/i.test(raw)) {
    kind = 'network'; title = '无法连接服务器'; message = '请检查地址、端口、网络及远程桌面服务后重试。'
  }
  // Bounded, allowlisted diagnostic excerpts rather than a best-effort secret
  // denylist: even unlabelled credentials in a server error remain private.
  const detail = [...new Set(raw.match(diagnostics) || [])].slice(0, 8).join('\n')
  return { kind, title, message, detail }
}
