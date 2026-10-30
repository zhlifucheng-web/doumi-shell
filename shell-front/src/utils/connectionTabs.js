import { generateTerminalSessionKey } from './sshConsoleStorage.js'

export function matchesConnection(tab, id, protocol = 'SSH') {
  return protocol === 'RDP'
    ? tab.protocol === 'RDP' && Number(tab.connectionId || tab.account?.id) === Number(id)
    : tab.protocol !== 'RDP' && Number(tab.accountId) === Number(id)
}

export function createRdpTab(account, host, tabs) {
  const subIndex = tabs.filter(tab => matchesConnection(tab, account.id, 'RDP')).length + 1
  const name = account.name || account.username
  const baseLabel = `${host.name || host.host} · ${name}`
  return {
    tabId: `rdp-${account.id}-${globalThis.crypto?.randomUUID?.() || generateTerminalSessionKey()}`,
    protocol: 'RDP', connectionId: account.id, accountId: `rdp:${account.id}`,
    account: { ...account, protocol: 'RDP' }, hostId: account.hostId,
    hostName: host.name || '', hostAddress: host.host || '',
    label: subIndex > 1 ? `${baseLabel} #${subIndex}` : baseLabel,
    subIndex, customTitle: '', token: '', connectionState: 'connecting'
  }
}
