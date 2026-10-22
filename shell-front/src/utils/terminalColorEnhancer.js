/**
 * terminalColorEnhancer.js
 * 纯 Web 端终端色彩增强器 (Pure Web Rendering Color Enhancer)
 * 专为原生未配置着色的 Linux/Unix 服务器提供纯前端色彩渲染。
 * 无需触碰服务器任何配置文件或磁盘数据，在 Web 数据流渲染层赋予提示符与关键状态纯彩呈现。
 */

/**
 * 为原生未染色的 Bash 提示符进行纯 Web 渲染增强
 * @param {string} text 远端输出的解码文本
 * @param {object} options 配置选项
 * @returns {string} 注入 ANSI 调色板代码后的文本
 */
export function enhanceTerminalOutput(text, options = {}) {
  if (!text || typeof text !== 'string') return text

  // 1. CentOS / RHEL / Fedora 风格提示符: [user@host dir]# 或 $
  // 匹配形如 [root@linuxtestbc5b ~]# 或 [user@host /dir]$，兼顾前导控制字符（如 OSC 标题、8位输入模式等）
  const centosPromptRegex = /\[([a-zA-Z0-9_.-]+)@([a-zA-Z0-9_.-]+)\s+([^\]\x1b\r\n]+)\]([#$]\s*)/g
  let result = text.replace(centosPromptRegex, (match, user, host, dir, sym) => {
    const userColor = user === 'root' ? '\x1b[1;31m' : '\x1b[1;32m'
    return `\x1b[90m[\x1b[0m${userColor}${user}\x1b[0m\x1b[90m@\x1b[1;36m${host}\x1b[0m \x1b[1;34m${dir}\x1b[0m\x1b[90m]\x1b[1;37m${sym}\x1b[0m`
  })

  // 2. Ubuntu / Debian 风格提示符: user@host:dir# 或 $
  const debianPromptRegex = /(^|[\r\n\x07h])([a-zA-Z0-9_.-]+)@([a-zA-Z0-9_.-]+):([^#$\x1b\r\n]+)([#$]\s*)/g
  result = result.replace(debianPromptRegex, (match, prefix, user, host, dir, sym) => {
    const userColor = user === 'root' ? '\x1b[1;31m' : '\x1b[1;32m'
    return `${prefix}${userColor}${user}\x1b[0m\x1b[90m@\x1b[1;36m${host}\x1b[0m:\x1b[1;34m${dir}\x1b[0m\x1b[1;37m${sym}\x1b[0m`
  })

  // 3. 常见系统关键状态高亮 (可选)
  if (options.enhanceStatusWords) {
    result = result
      .replace(/\b(SUCCESS|Active: active \(running\))\b/g, '\x1b[1;32m$1\x1b[0m')
      .replace(/\b(FAILED|ERROR|Active: failed)\b/g, '\x1b[1;31m$1\x1b[0m')
      .replace(/\b(WARNING|WARN)\b/g, '\x1b[1;33m$1\x1b[0m')
  }

  return result
}
