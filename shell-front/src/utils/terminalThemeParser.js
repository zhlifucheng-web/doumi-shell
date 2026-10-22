/**
 * 终端与 Zsh 配色解析引擎
 * 支持智能解析：
 * 1. iTerm2 配色方案 (.itermcolors XML Plist)
 * 2. Gogh / Shell / Zsh 导出脚本 (.sh, .zsh, export COLOR_01=...)
 * 3. JSON 格式 (Windows Terminal, VS Code, xterm.js 主题)
 * 4. Xresources / Xdefaults (*.color0: #...)
 * 5. Alacritty / Kitty 配置文件 (TOML / YAML / kitty.conf)
 */

function rgbToHex(r, g, b) {
  const clamp = v => Math.max(0, Math.min(255, Math.round(v)))
  const toHex = v => clamp(v).toString(16).padStart(2, '0')
  return `#${toHex(r)}${toHex(g)}${toHex(b)}`.toLowerCase()
}

function normalizeHex(hex) {
  if (!hex || typeof hex !== 'string') return ''
  let clean = hex.trim().replace(/^['"]|['"]$/g, '')
  if (clean.startsWith('0x') || clean.startsWith('0X')) {
    clean = '#' + clean.slice(2)
  }
  if (!clean.startsWith('#')) {
    if (/^[0-9a-f]{3,8}$/i.test(clean)) clean = '#' + clean
    else return ''
  }
  if (clean.length === 4) {
    // #RGB -> #RRGGBB
    clean = `#${clean[1]}${clean[1]}${clean[2]}${clean[2]}${clean[3]}${clean[3]}`
  }
  return clean.slice(0, 7).toLowerCase()
}

/**
 * 1. 解析 iTerm2 (.itermcolors XML)
 */
export function parseItermColors(xmlText) {
  if (!xmlText.includes('plist') && !xmlText.includes('Ansi') && !xmlText.includes('Color')) {
    return null
  }

  const parser = new DOMParser()
  const xmlDoc = parser.parseFromString(xmlText, 'text/xml')
  if (xmlDoc.getElementsByTagName('parsererror').length > 0) {
    return null
  }

  const dictElements = xmlDoc.querySelectorAll('plist > dict')
  const rootDict = dictElements[0]
  if (!rootDict) return null

  const colorMap = {}
  let currentKey = null

  for (let i = 0; i < rootDict.children.length; i++) {
    const el = rootDict.children[i]
    if (el.tagName === 'key') {
      currentKey = el.textContent?.trim()
    } else if (el.tagName === 'dict' && currentKey) {
      // 解析单个颜色组件
      let r = 0, g = 0, b = 0
      let subKey = null
      for (let j = 0; j < el.children.length; j++) {
        const sub = el.children[j]
        if (sub.tagName === 'key') {
          subKey = sub.textContent?.trim()
        } else if (subKey && (sub.tagName === 'real' || sub.tagName === 'integer')) {
          const val = parseFloat(sub.textContent || '0')
          // itermcolors 颜色分量通常是 0.0 - 1.0 的浮点数，或者 0-255
          const normalized = val <= 1.0 ? val * 255 : val
          if (subKey === 'Red Component') r = normalized
          else if (subKey === 'Green Component') g = normalized
          else if (subKey === 'Blue Component') b = normalized
          subKey = null
        }
      }
      colorMap[currentKey] = rgbToHex(r, g, b)
      currentKey = null
    }
  }

  if (Object.keys(colorMap).length < 4) return null

  return {
    background: colorMap['Background Color'] || '#1e1e2e',
    foreground: colorMap['Foreground Color'] || '#cdd6f4',
    cursor: colorMap['Cursor Color'] || colorMap['Foreground Color'] || '#f5e0dc',
    cursorAccent: colorMap['Cursor Text Color'] || colorMap['Background Color'] || '#11111b',
    selectionBackground: colorMap['Selection Color'] || 'rgba(99, 102, 241, 0.35)',
    selectionForeground: colorMap['Selected Text Color'] || '#ffffff',
    black: colorMap['Ansi 0 Color'] || '#45475a',
    red: colorMap['Ansi 1 Color'] || '#f38ba8',
    green: colorMap['Ansi 2 Color'] || '#a6e3a1',
    yellow: colorMap['Ansi 3 Color'] || '#f9e2af',
    blue: colorMap['Ansi 4 Color'] || '#89b4fa',
    magenta: colorMap['Ansi 5 Color'] || '#f5c2e7',
    cyan: colorMap['Ansi 6 Color'] || '#94e2d5',
    white: colorMap['Ansi 7 Color'] || '#bac2de',
    brightBlack: colorMap['Ansi 8 Color'] || '#585b70',
    brightRed: colorMap['Ansi 9 Color'] || '#f38ba8',
    brightGreen: colorMap['Ansi 10 Color'] || '#a6e3a1',
    brightYellow: colorMap['Ansi 11 Color'] || '#f9e2af',
    brightBlue: colorMap['Ansi 12 Color'] || '#89b4fa',
    brightMagenta: colorMap['Ansi 13 Color'] || '#f5c2e7',
    brightCyan: colorMap['Ansi 14 Color'] || '#94e2d5',
    brightWhite: colorMap['Ansi 15 Color'] || '#a6adc8'
  }
}

/**
 * 2. 解析 JSON (VS Code / Windows Terminal / xterm json)
 */
export function parseJsonColors(jsonText) {
  try {
    const raw = JSON.parse(jsonText)
    const obj = raw.colors || raw.colorScheme || raw.theme || raw

    const ansiIndexMap = [
      'black', 'red', 'green', 'yellow', 'blue', 'magenta', 'cyan', 'white',
      'brightBlack', 'brightRed', 'brightGreen', 'brightYellow', 'brightBlue', 'brightMagenta', 'brightCyan', 'brightWhite'
    ]

    const result = {}
    if (obj.background) result.background = normalizeHex(obj.background)
    if (obj.foreground) result.foreground = normalizeHex(obj.foreground)
    if (obj.cursorColor || obj.cursor) result.cursor = normalizeHex(obj.cursorColor || obj.cursor)
    if (obj.selectionBackground || obj.selection) result.selectionBackground = normalizeHex(obj.selectionBackground || obj.selection)

    // 检查直接命名的 ANSI 属性
    for (const key of ansiIndexMap) {
      if (obj[key]) result[key] = normalizeHex(obj[key])
    }
    // 检查 Windows Terminal / VS Code 别名 (purple -> magenta)
    if (!result.magenta && obj.purple) result.magenta = normalizeHex(obj.purple)
    if (!result.brightMagenta && obj.brightPurple) result.brightMagenta = normalizeHex(obj.brightPurple)

    // 检查数组 ansi: [...]
    if (Array.isArray(obj.ansi)) {
      obj.ansi.slice(0, 16).forEach((col, idx) => {
        if (col && ansiIndexMap[idx]) result[ansiIndexMap[idx]] = normalizeHex(col)
      })
    }

    if (result.background && result.foreground) {
      return fillMissingColors(result, raw.name || obj.name || '导入配色')
    }
  } catch {
    /* not json */
  }
  return null
}

/**
 * 3. 解析 Gogh / Shell / Zsh 导出脚本 (.sh, .zsh)
 */
export function parseShellScriptColors(text) {
  const result = {}

  // 1. 匹配 export COLOR_01="#..." 或 COLOR_01="#..." 或 color0="#..."
  const goghRegex = /(?:export\s+)?COLOR_?(\d{1,2})=["']?([#0-9a-fA-F]+)["']?/gi
  let match
  const ansiIndexMap = [
    'black', 'red', 'green', 'yellow', 'blue', 'magenta', 'cyan', 'white',
    'brightBlack', 'brightRed', 'brightGreen', 'brightYellow', 'brightBlue', 'brightMagenta', 'brightCyan', 'brightWhite'
  ]

  while ((match = goghRegex.exec(text)) !== null) {
    const num = parseInt(match[1], 10)
    // Gogh COLOR_01..16 对应 0..15
    const idx = num >= 1 && num <= 16 ? num - 1 : num
    if (idx >= 0 && idx < 16) {
      result[ansiIndexMap[idx]] = normalizeHex(match[2])
    }
  }

  // 匹配 color0..15
  const colorRegex = /(?:export\s+)?color(\d{1,2})=["']?([#0-9a-fA-F]+)["']?/gi
  while ((match = colorRegex.exec(text)) !== null) {
    const idx = parseInt(match[1], 10)
    if (idx >= 0 && idx < 16) {
      result[ansiIndexMap[idx]] = normalizeHex(match[2])
    }
  }

  // 匹配 BACKGROUND_COLOR / FOREGROUND_COLOR / CURSOR_COLOR
  const bgMatch = text.match(/(?:export\s+)?(?:BACKGROUND_COLOR|background)=["']?([#0-9a-fA-F]+)["']?/i)
  if (bgMatch) result.background = normalizeHex(bgMatch[1])

  const fgMatch = text.match(/(?:export\s+)?(?:FOREGROUND_COLOR|foreground)=["']?([#0-9a-fA-F]+)["']?/i)
  if (fgMatch) result.foreground = normalizeHex(fgMatch[1])

  const curMatch = text.match(/(?:export\s+)?(?:CURSOR_COLOR|cursor)=["']?([#0-9a-fA-F]+)["']?/i)
  if (curMatch) result.cursor = normalizeHex(curMatch[1])

  // 匹配 OSC 序列：printf '\033]4;0;#000000\007' 或 '\e]10;#...\a'
  const osc4Regex = /(?:\\033|\\e)\]4;(\d{1,2});([#0-9a-fA-F]+)/gi
  while ((match = osc4Regex.exec(text)) !== null) {
    const idx = parseInt(match[1], 10)
    if (idx >= 0 && idx < 16) {
      result[ansiIndexMap[idx]] = normalizeHex(match[2])
    }
  }

  const osc10 = text.match(/(?:\\033|\\e)\]10;([#0-9a-fA-F]+)/i)
  if (osc10) result.foreground = normalizeHex(osc10[1])
  const osc11 = text.match(/(?:\\033|\\e)\]11;([#0-9a-fA-F]+)/i)
  if (osc11) result.background = normalizeHex(osc11[1])

  // 提取主题名字
  let themeName = '导入脚本配色'
  const nameMatch = text.match(/(?:THEME_NAME|name)=["']?([^"'\n\r]+)["']?/i)
  if (nameMatch) themeName = nameMatch[1].trim()

  if (Object.keys(result).length >= 4) {
    return fillMissingColors(result, themeName)
  }
  return null
}

/**
 * 4. 解析 Xresources / Xdefaults / Alacritty / Kitty
 */
export function parseConfigColors(text) {
  const result = {}
  const ansiIndexMap = [
    'black', 'red', 'green', 'yellow', 'blue', 'magenta', 'cyan', 'white',
    'brightBlack', 'brightRed', 'brightGreen', 'brightYellow', 'brightBlue', 'brightMagenta', 'brightCyan', 'brightWhite'
  ]

  const lines = text.split(/\r?\n/)
  for (const line of lines) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('!') || trimmed.startsWith('#')) continue

    // Xresources: *.color0: #... 或 color0: #...
    const xresMatch = trimmed.match(/^\*?\.(color\d{1,2}|background|foreground|cursorColor):\s*([#0-9a-fA-F]+)/i)
    if (xresMatch) {
      const key = xresMatch[1].toLowerCase()
      const val = normalizeHex(xresMatch[2])
      if (key === 'background') result.background = val
      else if (key === 'foreground') result.foreground = val
      else if (key === 'cursorcolor') result.cursor = val
      else if (key.startsWith('color')) {
        const idx = parseInt(key.replace('color', ''), 10)
        if (idx >= 0 && idx < 16) result[ansiIndexMap[idx]] = val
      }
      continue
    }

    // Kitty: color0 #... 或 background #...
    const kittyMatch = trimmed.match(/^(color\d{1,2}|background|foreground|cursor)\s+([#0-9a-fA-F]+)/i)
    if (kittyMatch) {
      const key = kittyMatch[1].toLowerCase()
      const val = normalizeHex(kittyMatch[2])
      if (key === 'background') result.background = val
      else if (key === 'foreground') result.foreground = val
      else if (key === 'cursor') result.cursor = val
      else if (key.startsWith('color')) {
        const idx = parseInt(key.replace('color', ''), 10)
        if (idx >= 0 && idx < 16) result[ansiIndexMap[idx]] = val
      }
      continue
    }

    // Alacritty / Key-Value: black = '#...'
    const kvMatch = trimmed.match(/^([a-zA-Z0-9_]+)\s*[:=]\s*["']?([#0-9a-fA-F]+)["']?/i)
    if (kvMatch) {
      const key = kvMatch[1].toLowerCase()
      const val = normalizeHex(kvMatch[2])
      if (ansiIndexMap.map(k => k.toLowerCase()).includes(key)) {
        const standardKey = ansiIndexMap.find(k => k.toLowerCase() === key)
        if (standardKey) result[standardKey] = val
      } else if (key === 'background') result.background = val
      else if (key === 'foreground') result.foreground = val
      else if (key === 'cursor') result.cursor = val
    }
  }

  if (Object.keys(result).length >= 4) {
    return fillMissingColors(result, '自定义终端配色')
  }
  return null
}

/**
 * 智能自动检测并解析任何 Zsh / 终端配色文本或文件
 */
export function parseTerminalColorScheme(rawContent, filename = '') {
  if (!rawContent || typeof rawContent !== 'string') return null
  const content = rawContent.trim()

  // 1. 尝试 iTerm2 .itermcolors
  if (content.includes('plist') || filename.endsWith('.itermcolors')) {
    const iterm = parseItermColors(content)
    if (iterm) {
      const name = filename ? filename.replace(/\.itermcolors$/i, '') : 'iTerm2 导入主题'
      return fillMissingColors(iterm, name)
    }
  }

  // 2. 尝试 JSON
  if (content.startsWith('{') || filename.endsWith('.json')) {
    const jsonTheme = parseJsonColors(content)
    if (jsonTheme) return jsonTheme
  }

  // 3. 尝试 Gogh / Shell 脚本
  if (content.includes('COLOR_') || content.includes('color0') || content.includes('printf') || filename.endsWith('.sh') || filename.endsWith('.zsh')) {
    const shellTheme = parseShellScriptColors(content)
    if (shellTheme) {
      if (filename) shellTheme.name = filename.replace(/\.(sh|zsh)$/i, '')
      return shellTheme
    }
  }

  // 4. 尝试 Xresources / Kitty / Alacritty
  const configTheme = parseConfigColors(content)
  if (configTheme) {
    if (filename) configTheme.name = filename.replace(/\.[^.]+$/, '')
    return configTheme
  }

  return null
}

/**
 * 补全缺省 ANSI 颜色，保证 xterm.js 获得完整 16 色调色板
 */
function fillMissingColors(theme, name = '自定义配色') {
  const fallback = {
    background: '#1e1e2e',
    foreground: '#cdd6f4',
    cursor: '#f5e0dc',
    cursorAccent: '#11111b',
    selectionBackground: 'rgba(99, 102, 241, 0.35)',
    selectionForeground: '#ffffff',
    black: '#45475a',
    red: '#f38ba8',
    green: '#a6e3a1',
    yellow: '#f9e2af',
    blue: '#89b4fa',
    magenta: '#f5c2e7',
    cyan: '#94e2d5',
    white: '#bac2de',
    brightBlack: '#585b70',
    brightRed: '#f38ba8',
    brightGreen: '#a6e3a1',
    brightYellow: '#f9e2af',
    brightBlue: '#89b4fa',
    brightMagenta: '#f5c2e7',
    brightCyan: '#94e2d5',
    brightWhite: '#a6adc8'
  }

  const filled = { ...fallback, ...theme }
  filled.name = name || '自定义配色'
  filled.id = theme.id || `custom-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`
  return filled
}
