import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildShareUrl,
  formatExpireDuration,
  formatSharePermission,
  buildTerminalWsQuery
} from '../src/utils/terminalShareHelper.js'

test('buildShareUrl: generates correct hash route url with encoded token', () => {
  const origin = 'https://shell.example.com'
  const pathname = '/app'
  const token = 'sh_test123abc'
  const url = buildShareUrl(origin, pathname, token)
  assert.equal(url, 'https://shell.example.com/app/#/share?token=sh_test123abc')
})

test('buildShareUrl: handles trailing slashes gracefully', () => {
  const origin = 'http://localhost:3100/'
  const pathname = '/'
  const token = 'sh_xyz789'
  const url = buildShareUrl(origin, pathname, token)
  assert.equal(url, 'http://localhost:3100/#/share?token=sh_xyz789')
})

test('formatExpireDuration: handles permanent, valid, and expired states', () => {
  const now = 1700000000000
  // Permanent
  assert.equal(formatExpireDuration(null, now), '永久有效')
  assert.equal(formatExpireDuration(0, now), '永久有效')

  // Expired
  assert.equal(formatExpireDuration(now - 1000, now), '已过期')

  // Minutes valid
  assert.equal(formatExpireDuration(now + 25 * 60 * 1000, now), '25分钟有效')

  // Hours valid
  assert.equal(formatExpireDuration(now + 2 * 3600 * 1000, now), '2小时有效')
  assert.equal(formatExpireDuration(now + (2 * 3600 + 15 * 60) * 1000, now), '2小时15分有效')
})

test('formatSharePermission: parses READ_ONLY and READ_WRITE correctly', () => {
  const ro = formatSharePermission('READ_ONLY')
  assert.equal(ro.readOnly, true)
  assert.equal(ro.label, '只读观看')
  assert.equal(ro.badgeText, '👁️ 只读观看')

  const rw = formatSharePermission('READ_WRITE')
  assert.equal(rw.readOnly, false)
  assert.equal(rw.label, '实时协同')
  assert.equal(rw.badgeText, '✍️ 实时协同')
})

test('buildTerminalWsQuery: builds query string for shareToken', () => {
  const qs = buildTerminalWsQuery({
    shareToken: 'sh_abcdef123456',
    cols: 80,
    rows: 24
  })
  const params = new URLSearchParams(qs)
  assert.equal(params.get('shareToken'), 'sh_abcdef123456')
  assert.equal(params.get('accountId'), null)
  assert.equal(params.get('cols'), '80')
  assert.equal(params.get('rows'), '24')
})

test('buildTerminalWsQuery: builds query string for normal terminal session', () => {
  const qs = buildTerminalWsQuery({
    accountId: 101,
    terminalId: 'term-2',
    cols: 120,
    rows: 36
  })
  const params = new URLSearchParams(qs)
  assert.equal(params.get('accountId'), '101')
  assert.equal(params.get('terminalId'), 'term-2')
  assert.equal(params.get('cols'), '120')
  assert.equal(params.get('rows'), '36')
})
