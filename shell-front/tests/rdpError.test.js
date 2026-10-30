import test from 'node:test'
import assert from 'node:assert/strict'
import { describeRdpError } from '../src/utils/rdpError.js'

test('certificate verification failures offer explicit connection editing guidance', () => {
  const error = describeRdpError('ERRCONNECT_TLS_CONNECT_FAILED: certificate verify failed')
  assert.equal(error.kind, 'certificate')
  assert.match(error.message, /忽略证书/)
  assert.match(error.message, /可信/)
})
test('generic TLS negotiation failures do not claim a certificate cause', () => {
  const error = describeRdpError('Error connecting to RDP server: TLS connection failed')
  assert.equal(error.kind, 'tls')
  assert.doesNotMatch(error.message, /忽略证书/)
  assert.match(error.message, /安全协议/)
})
test('already ignored certificate failures do not promise the same option fixes TLS', () => {
  const error = describeRdpError('certificate verify failed', { ignoreCertificate: true })
  assert.equal(error.kind, 'certificate')
  assert.doesNotMatch(error.message, /启用|开启/)
  assert.match(error.message, /已/)
})
test('authentication and network errors have distinct recovery guidance', () => {
  for (const text of ['ERRCONNECT_LOGON_FAILURE', 'Authentication failed', 'STATUS_WRONG_PASSWORD', 'CredSSP authentication failed']) {
    assert.equal(describeRdpError(text).kind, 'authentication')
    assert.match(describeRdpError(text).message, /用户名.*密码/)
  }
  for (const text of ['Connection refused', 'ERRCONNECT_CONNECT_FAILED', 'Connection timed out', 'guacd unavailable']) {
    assert.equal(describeRdpError(text).kind, 'network')
    assert.match(describeRdpError(text).message, /地址.*端口/)
  }
})
test('unknown failures remain generic and unstructured content cannot echo credentials', () => {
  const result = describeRdpError('Unexpected server echo superSecret', { password: 'superSecret' })
  assert.equal(result.kind, 'unknown')
  assert.doesNotMatch(JSON.stringify(result), /superSecret/)
})
test('technical disclosure strips credential lines, URL credentials and ticket queries', () => {
  const result = describeRdpError('TLS connection failed\npassword="abc def"\nAuthorization: Bearer bearerSecret\nhttps://user:pass@host/ws?token=ticketSecret&password=moreSecret\nusername=admin', { username: 'admin' })
  assert.equal(result.kind, 'tls')
  assert.doesNotMatch(result.detail, /abc|bearerSecret|ticketSecret|moreSecret|user:pass|admin/)
  assert.match(result.detail, /TLS/)
})
test('details retain useful known diagnostic codes but omit arbitrary server messages', () => {
  const result = describeRdpError('ERRCONNECT_LOGON_FAILURE: secret echoed without a label')
  assert.match(result.detail, /ERRCONNECT_LOGON_FAILURE/)
  assert.doesNotMatch(result.detail, /secret echoed/)
  assert.equal(describeRdpError('').detail, '')
})

test('generic bridge checklists do not assert certificate, authentication or network causes', () => {
  for (const text of [
    'RDP 连接失败，请检查 guacd、Windows 远程桌面、账户和证书设置',
    '远程桌面连接失败，请检查网关、Windows RDP 和凭据',
    '连接失败，请检查身份验证和安全协议设置',
    'Connection failed; check credentials, certificate and TLS settings'
  ]) {
    const result = describeRdpError(text)
    assert.equal(result.kind, 'unknown', text)
    assert.match(result.message, /连接设置/)
  }
})
test('a tentative certificate hint in a TLS error remains a security connection failure', () => {
  const result = describeRdpError('SSL/TLS connection failed (untrusted/self-signed certificate?)')
  assert.equal(result.kind, 'tls')
  assert.equal(result.title, '安全连接失败')
  assert.match(result.message, /可能.*证书/)
  assert.doesNotMatch(result.message, /证书验证失败/)
  const ignored = describeRdpError('SSL/TLS connection failed (untrusted/self-signed certificate?)', { ignoreCertificate: true })
  assert.doesNotMatch(ignored.message, /启用|开启/)
})
test('explicit Chinese verification failures remain recognizable', () => {
  assert.equal(describeRdpError('服务器证书验证失败').kind, 'certificate')
  assert.equal(describeRdpError('身份验证失败').kind, 'authentication')
})
