export function rdpTunnel(Guacamole, url) {
  // WebSocketTunnel 1.5 always appends '?' plus connect data. An empty
  // additional query field keeps the ticket in the URL intact.
  return new Guacamole.WebSocketTunnel(`${url}&`)
}

/** The upstream client has no dispose API for its synchronous DOM listeners. */
export function trackGuacamoleListeners(targets, construct) {
  const removers = []
  const originals = targets.map(target => [target, target.addEventListener])
  try {
    for (const [target, original] of originals) {
      target.addEventListener = function(type, listener, options) {
        original.call(target, type, listener, options)
        removers.push(() => target.removeEventListener(type, listener, options))
      }
    }
    return { value: construct(), dispose: () => { removers.splice(0).forEach(remove => remove()) } }
  } catch (error) {
    removers.forEach(remove => remove()); throw error
  } finally {
    for (const [target, original] of originals) target.addEventListener = original
  }
}
