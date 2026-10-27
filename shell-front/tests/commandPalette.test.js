import test from 'node:test'
import assert from 'node:assert/strict'

function matchTarget(item, query) {
  const q = String(query || '').trim().toLowerCase()
  if (!q) return true
  const terms = q.split(/\s+/).filter(Boolean)

  const hostAddr = String(item.hostAddress || '').toLowerCase()
  const hostName = String(item.hostName || '').toLowerCase()
  const user = String(item.username || '').toLowerCase()
  const group = String(item.groupName || '').toLowerCase()
  const accRemark = String(item.accountRemark || '').toLowerCase()
  const hostRemark = String(item.hostRemark || '').toLowerCase()
  const endpoint = `${hostAddr}:${item.hostPort || 22}`
  const userAtHost = user ? `${user}@${hostAddr}` : ''
  const userAtName = user ? `${user}@${hostName}` : ''

  return terms.every(term =>
    hostAddr.includes(term) ||
    endpoint.includes(term) ||
    hostName.includes(term) ||
    user.includes(term) ||
    group.includes(term) ||
    accRemark.includes(term) ||
    hostRemark.includes(term) ||
    userAtHost.includes(term) ||
    userAtName.includes(term)
  )
}

test('command palette finds server by exact IP address', () => {
  const target = {
    hostId: 1,
    hostName: 'Production-Web-01',
    hostAddress: '192.168.10.55',
    hostPort: 22,
    groupId: 2,
    groupName: '生产网段',
    accountId: 10,
    username: 'root'
  }

  assert.equal(matchTarget(target, '192.168.10.55'), true)
  assert.equal(matchTarget(target, '192.168.10.99'), false)
})

test('command palette finds server by partial IP prefix and subnet', () => {
  const target = {
    hostId: 2,
    hostName: 'Gateway-Router',
    hostAddress: '10.200.4.1',
    hostPort: 2222,
    username: 'admin'
  }

  assert.equal(matchTarget(target, '10.200.'), true)
  assert.equal(matchTarget(target, '4.1'), true)
  assert.equal(matchTarget(target, '10.200.4.1:2222'), true)
  assert.equal(matchTarget(target, 'admin@10.200'), true)
})

test('command palette supports multi-term query: user + IP', () => {
  const targetA = { hostAddress: '192.168.1.10', username: 'deploy', hostName: 'Node-A' }
  const targetB = { hostAddress: '192.168.1.10', username: 'root', hostName: 'Node-A' }
  const targetC = { hostAddress: '172.16.0.5', username: 'deploy', hostName: 'Node-C' }

  assert.equal(matchTarget(targetA, 'deploy 192.168'), true)
  assert.equal(matchTarget(targetB, 'deploy 192.168'), false)
  assert.equal(matchTarget(targetC, 'deploy 192.168'), false)
})

test('command palette finds server by group name and host remarks', () => {
  const target = {
    hostAddress: '172.30.0.12',
    hostName: 'K8s-Master',
    groupName: '边缘集群',
    hostRemark: '负责容器调度'
  }

  assert.equal(matchTarget(target, '边缘集群'), true)
  assert.equal(matchTarget(target, '调度'), true)
  assert.equal(matchTarget(target, '172.30'), true)
})
