/**
 * File type & syntax detection helper for SFTP file explorer and online editor
 */

export const EDITABLE_EXTENSIONS = new Set([
  'txt', 'text', 'log', 'out', 'err',
  'sh', 'bash', 'zsh', 'csh', 'ksh', 'profile', 'rc',
  'conf', 'config', 'cfg', 'ini', 'cnf',
  'yml', 'yaml',
  'json', 'js', 'mjs', 'cjs', 'ts', 'jsx', 'tsx',
  'py', 'pyw',
  'xml', 'html', 'htm', 'xhtml', 'css', 'scss', 'less',
  'sql',
  'properties', 'prop', 'env',
  'md', 'markdown',
  'dockerfile',
  'java', 'c', 'cpp', 'cc', 'cxx', 'h', 'hpp', 'hxx',
  'go', 'rs', 'php', 'rb', 'lua', 'pl', 'pm', 'swift',
  'svg', 'csv', 'tsv', 'diff', 'patch'
])

export const EDITABLE_FILENAMES = new Set([
  'dockerfile', 'makefile', 'readme', 'license', 'hosts',
  'resolv.conf', 'profile', 'bashrc', 'zshrc', 'environment',
  'crontab', 'fstab', 'sudoers', 'passwd', 'group', 'shadow',
  'nginx.conf', 'my.cnf', 'redis.conf', 'ssh_config', 'sshd_config',
  'known_hosts', 'authorized_keys', 'config', '.gitignore', '.gitattributes',
  '.bash_profile', '.bash_login', '.bash_logout', '.bashrc', '.zshrc',
  '.profile', '.env', '.env.local', '.env.development', '.env.production'
])

export const BINARY_EXTENSIONS = new Set([
  'zip', 'tar', 'gz', 'tgz', 'bz2', 'xz', '7z', 'rar',
  'bin', 'exe', 'dll', 'so', 'dylib', 'o', 'a',
  'iso', 'img', 'dmg',
  'png', 'jpg', 'jpeg', 'gif', 'bmp', 'ico', 'webp',
  'mp3', 'wav', 'ogg', 'flac', 'aac',
  'mp4', 'avi', 'mkv', 'mov', 'webm',
  'pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx',
  'woff', 'woff2', 'ttf', 'eot', 'otf',
  'pyc', 'pyo', 'class', 'jar', 'war'
])

export function getFileExtension(filename) {
  if (!filename || typeof filename !== 'string') return ''
  const base = filename.trim().split(/[\\/]/).pop() || ''
  const dotIdx = base.lastIndexOf('.')
  if (dotIdx === -1 || dotIdx === 0) return ''
  return base.slice(dotIdx + 1).toLowerCase()
}

export function isDefaultEditableFile(filename, size = 0) {
  if (!filename || typeof filename !== 'string') return false
  const lowerName = filename.trim().split(/[\\/]/).pop()?.toLowerCase() || ''
  if (!lowerName) return false

  // Size limit: 5MB for automatic double-click open
  if (size > 5 * 1024 * 1024) return false

  // Known exact filenames
  if (EDITABLE_FILENAMES.has(lowerName)) return true

  // Known dotfiles without extension like .bashrc, .env
  if (lowerName.startsWith('.') && !lowerName.includes('.', 1)) {
    return true
  }

  const ext = getFileExtension(lowerName)
  if (ext && EDITABLE_EXTENSIONS.has(ext)) return true
  if (ext && BINARY_EXTENSIONS.has(ext)) return false

  // Files with no extension under 2MB (often scripts/config files on Linux)
  if (!ext && size <= 2 * 1024 * 1024) {
    return true
  }

  return false
}

export function isBinaryFile(filename) {
  const ext = getFileExtension(filename)
  return BINARY_EXTENSIONS.has(ext)
}

export function isTextFile(filename) {
  if (!filename || typeof filename !== 'string') return false
  const lowerName = filename.trim().split(/[\\/]/).pop()?.toLowerCase() || ''
  if (!lowerName) return false
  if (EDITABLE_FILENAMES.has(lowerName)) return true
  if (lowerName.startsWith('.') && !lowerName.includes('.', 1)) return true
  const ext = getFileExtension(lowerName)
  if (ext && EDITABLE_EXTENSIONS.has(ext)) return true
  if (ext && BINARY_EXTENSIONS.has(ext)) return false
  return !ext
}

export function getLanguageName(filename) {
  const lower = (filename || '').toLowerCase().trim().split(/[\\/]/).pop() || ''
  const ext = getFileExtension(lower)

  if (['sh', 'bash', 'zsh', 'csh', 'ksh'].includes(ext) || ['profile', 'bashrc', 'zshrc', '.bashrc', '.zshrc'].includes(lower)) {
    return 'shell'
  }
  if (['js', 'mjs', 'cjs', 'ts', 'jsx', 'tsx', 'json'].includes(ext)) {
    return 'javascript'
  }
  if (['py', 'pyw'].includes(ext)) {
    return 'python'
  }
  if (['yml', 'yaml'].includes(ext)) {
    return 'yaml'
  }
  if (['toml'].includes(ext)) {
    return 'toml'
  }
  if (['xml', 'html', 'htm', 'xhtml', 'svg'].includes(ext)) {
    return 'xml'
  }
  if (['sql'].includes(ext)) {
    return 'sql'
  }
  if (['conf', 'nginx'].includes(ext) || lower.includes('nginx')) {
    return 'nginx'
  }
  if (['properties', 'prop', 'env', 'ini', 'cfg'].includes(ext) || lower.startsWith('.env')) {
    return 'properties'
  }
  if (ext === 'dockerfile' || lower === 'dockerfile') {
    return 'dockerfile'
  }
  if (['c', 'cpp', 'cc', 'cxx', 'h', 'hpp', 'java'].includes(ext)) {
    return 'clike'
  }
  if (ext === 'go') {
    return 'go'
  }
  if (ext === 'rs') {
    return 'rust'
  }
  if (ext === 'lua') {
    return 'lua'
  }
  if (['md', 'markdown'].includes(ext)) {
    return 'markdown'
  }
  return 'text'
}

export const ENCODING_OPTIONS = [
  { label: 'UTF-8 (推荐)', value: 'UTF-8' },
  { label: 'GBK (简体中文)', value: 'GBK' },
  { label: 'GB18030 (国标汉字)', value: 'GB18030' },
  { label: 'Big5 (繁体中文)', value: 'Big5' },
  { label: 'ISO-8859-1 (Latin-1)', value: 'ISO-8859-1' },
  { label: 'Windows-1252 (ANSI)', value: 'Windows-1252' },
  { label: 'Shift_JIS (日文)', value: 'Shift_JIS' },
  { label: 'EUC-KR (韩文)', value: 'EUC-KR' },
  { label: 'UTF-16LE', value: 'UTF-16LE' },
  { label: 'UTF-16BE', value: 'UTF-16BE' }
]

export function detectFileEncoding(buffer) {
  if (!buffer) return 'UTF-8'
  const bytes = buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer)
  if (bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) {
    return 'UTF-8'
  }
  if (bytes.length >= 2 && bytes[0] === 0xff && bytes[1] === 0xfe) {
    return 'UTF-16LE'
  }
  if (bytes.length >= 2 && bytes[0] === 0xfe && bytes[1] === 0xff) {
    return 'UTF-16BE'
  }

  // Check if valid UTF-8
  try {
    new TextDecoder('utf-8', { fatal: true }).decode(bytes)
    return 'UTF-8'
  } catch {
    // If not valid UTF-8, check if valid GB18030 / GBK
    try {
      new TextDecoder('gbk', { fatal: true }).decode(bytes)
      return 'GBK'
    } catch {
      try { new TextDecoder('gb18030', { fatal: true }).decode(bytes); return 'GB18030' }
      catch { return 'UTF-8' }
    }
  }
}

export function decodeFileBuffer(buffer, encoding = 'UTF-8') {
  if (!buffer) return ''
  const bytes = buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer)
  const enc = (encoding || 'UTF-8').toLowerCase()
  try {
    const decoder = new TextDecoder(enc)
    return decoder.decode(bytes)
  } catch (err) {
    console.warn(`[decodeFileBuffer] 无法按 ${encoding} 解码，回退至 utf-8`, err)
    return new TextDecoder('utf-8').decode(bytes)
  }
}

export function base64ToUint8Array(base64) {
  if (!base64) return new Uint8Array(0)
  const binary = atob(base64)
  const len = binary.length
  const bytes = new Uint8Array(len)
  for (let i = 0; i < len; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes
}

/**
 * 判断 parentPath 是否为 childPath 的父目录或祖先目录
 */
export function isParentPath(parentPath, childPath) {
  if (!parentPath || !childPath || parentPath === childPath) return false
  const p = parentPath.endsWith('/') ? parentPath : `${parentPath}/`
  const c = childPath.endsWith('/') ? childPath : `${childPath}/`
  if (p === '/') return c !== '/'
  return c.startsWith(p)
}
