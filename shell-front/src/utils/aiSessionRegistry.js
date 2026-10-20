export function aiSessionOwner(server) {
  if (server?.accountId != null) return `account:${server.accountId}`
  if (server?.hostId != null) return `host:${server.hostId}`
  return 'unassigned'
}

export function createAiSessionRegistry() {
  const sessions = new Map()
  return {
    get(server) {
      return sessions.get(aiSessionOwner(server)) || null
    },
    set(server, snapshot) {
      sessions.set(aiSessionOwner(server), {
        ...snapshot,
        events: [...(snapshot.events || [])],
        pending: [...(snapshot.pending || [])],
        attachments: [...(snapshot.attachments || [])]
      })
    }
  }
}
