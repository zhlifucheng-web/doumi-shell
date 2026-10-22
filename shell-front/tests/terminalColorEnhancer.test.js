import test from 'node:test'
import assert from 'node:assert/strict'
import { enhanceTerminalOutput } from '../src/utils/terminalColorEnhancer.js'

test('enhanceTerminalOutput: colors CentOS/RHEL root prompt', () => {
  const input = 'Last login: Wed Oct 21 from 10.20.42.116\r\n[root@linuxtestbc5b ~]# '
  const output = enhanceTerminalOutput(input)

  assert.ok(output.includes('\x1b[1;31mroot\x1b[0m'), 'root should be colored red')
  assert.ok(output.includes('\x1b[1;36mlinuxtestbc5b\x1b[0m'), 'host should be cyan')
  assert.ok(output.includes('\x1b[1;34m~\x1b[0m'), 'dir should be blue')
  assert.ok(output.includes('\x1b[1;37m# \x1b[0m'), 'symbol should be bold')
})

test('enhanceTerminalOutput: colors non-root user prompt with green', () => {
  const input = '[pino@web-server /var/www]$ '
  const output = enhanceTerminalOutput(input)

  assert.ok(output.includes('\x1b[1;32mpino\x1b[0m'), 'non-root user should be green')
  assert.ok(output.includes('\x1b[1;36mweb-server\x1b[0m'), 'host should be cyan')
  assert.ok(output.includes('\x1b[1;34m/var/www\x1b[0m'), 'path should be blue')
})

test('enhanceTerminalOutput: colors Debian/Ubuntu style prompt', () => {
  const input = 'root@ubuntu-srv:/etc/nginx# '
  const output = enhanceTerminalOutput(input)

  assert.ok(output.includes('\x1b[1;31mroot\x1b[0m'), 'root should be red')
  assert.ok(output.includes('\x1b[1;36mubuntu-srv\x1b[0m'), 'host should be cyan')
  assert.ok(output.includes('\x1b[1;34m/etc/nginx\x1b[0m'), 'path should be blue')
})

test('enhanceTerminalOutput: ignores already-colored prompts (Zsh / OhMyZsh)', () => {
  const input = '\x1b[32m➜ \x1b[36mmyproject\x1b[0m \x1b[34mgit:(main)\x1b[0m '
  const output = enhanceTerminalOutput(input)

  assert.equal(output, input, 'already-colored prompt must not be altered')
})
