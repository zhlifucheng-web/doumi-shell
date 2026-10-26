import test from 'node:test'
import assert from 'node:assert/strict'
import {
  isValidHostAddress,
  isValidPort,
  validateHostAddressRule,
  validateHostPortRule
} from '../src/utils/hostValidator.js'

test('isValidHostAddress: accepts valid IPv4 addresses', () => {
  assert.ok(isValidHostAddress('192.168.1.1'))
  assert.ok(isValidHostAddress('10.20.42.116'))
  assert.ok(isValidHostAddress('127.0.0.1'))
  assert.ok(isValidHostAddress('0.0.0.0'))
  assert.ok(isValidHostAddress('255.255.255.255'))
  assert.ok(isValidHostAddress('  192.168.0.254  ')) // whitespace trimmed
})

test('isValidHostAddress: rejects invalid IPv4 addresses', () => {
  assert.ok(!isValidHostAddress('256.0.0.1'))
  assert.ok(!isValidHostAddress('192.168.1.999'))
  assert.ok(!isValidHostAddress('192.168.1'))
  assert.ok(!isValidHostAddress('192.168.1.1.1'))
  assert.ok(!isValidHostAddress('192.168.1.-1'))
})

test('isValidHostAddress: accepts valid IPv6 addresses', () => {
  assert.ok(isValidHostAddress('::1'))
  assert.ok(isValidHostAddress('[::1]'))
  assert.ok(isValidHostAddress('fe80::1'))
  assert.ok(isValidHostAddress('2001:0db8:85a3:0000:0000:8a2e:0370:7334'))
})

test('isValidHostAddress: accepts valid domains and hostnames', () => {
  assert.ok(isValidHostAddress('localhost'))
  assert.ok(isValidHostAddress('server1'))
  assert.ok(isValidHostAddress('web-01'))
  assert.ok(isValidHostAddress('db_master'))
  assert.ok(isValidHostAddress('api.example.com'))
  assert.ok(isValidHostAddress('node.internal.corp.lan'))
})

test('isValidHostAddress: rejects empty and malformed addresses', () => {
  assert.ok(!isValidHostAddress(''))
  assert.ok(!isValidHostAddress(null))
  assert.ok(!isValidHostAddress(undefined))
  assert.ok(!isValidHostAddress('   '))
  assert.ok(!isValidHostAddress('-start-with-hyphen'))
  assert.ok(!isValidHostAddress('end-with-hyphen-'))
  assert.ok(!isValidHostAddress('two..dots.com'))
})

test('isValidPort: validates SSH port numbers in range 1-65535', () => {
  assert.ok(isValidPort(22))
  assert.ok(isValidPort('22'))
  assert.ok(isValidPort(1))
  assert.ok(isValidPort(65535))
  assert.ok(isValidPort('8080'))

  assert.ok(!isValidPort(0))
  assert.ok(!isValidPort(-1))
  assert.ok(!isValidPort(65536))
  assert.ok(!isValidPort(100000))
  assert.ok(!isValidPort('abc'))
  assert.ok(!isValidPort(null))
  assert.ok(!isValidPort(''))
  assert.ok(!isValidPort(22.5))
})

test('validateHostAddressRule: reports specific errors', () => {
  validateHostAddressRule({}, '', (err) => {
    assert.ok(err instanceof Error)
    assert.match(err.message, /请输入主机地址/)
  })

  validateHostAddressRule({}, '192.168.1.1:22', (err) => {
    assert.ok(err instanceof Error)
    assert.match(err.message, /请勿包含端口号/)
  })

  validateHostAddressRule({}, '192.168.1.1 80', (err) => {
    assert.ok(err instanceof Error)
    assert.match(err.message, /不能包含空格/)
  })

  validateHostAddressRule({}, '192.168.1.1', (err) => {
    assert.equal(err, undefined)
  })

  validateHostAddressRule({}, 'example.com', (err) => {
    assert.equal(err, undefined)
  })
})

test('validateHostPortRule: reports specific errors', () => {
  validateHostPortRule({}, '', (err) => {
    assert.ok(err instanceof Error)
    assert.match(err.message, /请输入 SSH 端口号/)
  })

  validateHostPortRule({}, 70000, (err) => {
    assert.ok(err instanceof Error)
    assert.match(err.message, /1 ~ 65535/)
  })

  validateHostPortRule({}, 22, (err) => {
    assert.equal(err, undefined)
  })
})
