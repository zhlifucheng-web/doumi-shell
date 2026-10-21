import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  parseJsonColors,
  parseShellScriptColors,
  parseConfigColors,
  parseTerminalColorScheme
} from '../src/utils/terminalThemeParser.js'

test('parseJsonColors: correctly parses Windows Terminal / xterm JSON theme', () => {
  const json = JSON.stringify({
    name: 'Dracula',
    background: '#282a36',
    foreground: '#f8f8f2',
    cursorColor: '#f8f8f2',
    black: '#21222c',
    red: '#ff5555',
    green: '#50fa7b',
    yellow: '#f1fa8c',
    blue: '#bd93f9',
    purple: '#ff79c6',
    cyan: '#8be9fd',
    white: '#f8f8f2'
  })

  const res = parseJsonColors(json)
  assert.ok(res)
  assert.equal(res.name, 'Dracula')
  assert.equal(res.background, '#282a36')
  assert.equal(res.foreground, '#f8f8f2')
  assert.equal(res.red, '#ff5555')
  assert.equal(res.green, '#50fa7b')
  assert.equal(res.magenta, '#ff79c6') // purple mapped to magenta
})

test('parseShellScriptColors: correctly parses Gogh / Zsh export theme script', () => {
  const script = `
    export THEME_NAME="One Dark"
    export BACKGROUND_COLOR="#1e1e1e"
    export FOREGROUND_COLOR="#abb2bf"
    export CURSOR_COLOR="#528bff"
    export COLOR_01="#282c34"
    export COLOR_02="#e06c75"
    export COLOR_03="#98c379"
    export COLOR_04="#e5c07b"
    export COLOR_05="#61afef"
    export COLOR_06="#c678dd"
    export COLOR_07="#56b6c2"
    export COLOR_08="#abb2bf"
  `

  const res = parseShellScriptColors(script)
  assert.ok(res)
  assert.equal(res.name, 'One Dark')
  assert.equal(res.background, '#1e1e1e')
  assert.equal(res.foreground, '#abb2bf')
  assert.equal(res.cursor, '#528bff')
  assert.equal(res.black, '#282c34')
  assert.equal(res.red, '#e06c75')
  assert.equal(res.green, '#98c379')
})

test('parseConfigColors: parses Xresources and Kitty formats', () => {
  const xres = `
    *.background: #002b36
    *.foreground: #839496
    *.cursorColor: #93a1a1
    *.color0: #073642
    *.color1: #dc322f
    *.color2: #859900
    *.color3: #b58900
  `
  const resX = parseConfigColors(xres)
  assert.ok(resX)
  assert.equal(resX.background, '#002b36')
  assert.equal(resX.foreground, '#839496')
  assert.equal(resX.red, '#dc322f')

  const kitty = `
    background #282828
    foreground #ebdbb2
    cursor #ebdbb2
    color0 #282828
    color1 #cc241d
    color2 #98971a
    color3 #d79921
  `
  const resK = parseConfigColors(kitty)
  assert.ok(resK)
  assert.equal(resK.background, '#282828')
  assert.equal(resK.foreground, '#ebdbb2')
  assert.equal(resK.red, '#cc241d')
})

test('parseTerminalColorScheme: auto-detects format from text and filename', () => {
  const jsonStr = '{"name":"Nord","background":"#2e3440","foreground":"#d8dee9","red":"#bf616a","green":"#a3be8c"}'
  const res = parseTerminalColorScheme(jsonStr, 'nord.json')
  assert.ok(res)
  assert.equal(res.name, 'Nord')
  assert.equal(res.background, '#2e3440')
})
