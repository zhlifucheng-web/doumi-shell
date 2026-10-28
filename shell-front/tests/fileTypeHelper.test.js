import test from 'node:test'
import assert from 'node:assert/strict'
import {
  getFileExtension,
  isDefaultEditableFile,
  isBinaryFile,
  isTextFile,
  isParentPath,
  getLanguageName,
  detectFileEncoding,
  decodeFileBuffer,
  base64ToUint8Array,
  ENCODING_OPTIONS
} from '../src/utils/fileTypeHelper.js'

test('getFileExtension correctly extracts file extensions in lowercase', () => {
  assert.equal(getFileExtension('app.py'), 'py')
  assert.equal(getFileExtension('CONFIG.YML'), 'yml')
  assert.equal(getFileExtension('archive.tar.gz'), 'gz')
  assert.equal(getFileExtension('.bashrc'), '')
  assert.equal(getFileExtension('Dockerfile'), '')
  assert.equal(getFileExtension('noext'), '')
  assert.equal(getFileExtension(''), '')
  assert.equal(getFileExtension(null), '')
  assert.equal(getFileExtension('/var/log/nginx/access.log'), 'log')
})

test('isDefaultEditableFile identifies standard editable files', () => {
  // Common script & code files
  assert.equal(isDefaultEditableFile('deploy.sh'), true)
  assert.equal(isDefaultEditableFile('server.js'), true)
  assert.equal(isDefaultEditableFile('app.py'), true)
  assert.equal(isDefaultEditableFile('config.yml'), true)
  assert.equal(isDefaultEditableFile('package.json'), true)
  assert.equal(isDefaultEditableFile('nginx.conf'), true)
  assert.equal(isDefaultEditableFile('application.properties'), true)
  assert.equal(isDefaultEditableFile('schema.sql'), true)
  assert.equal(isDefaultEditableFile('README.md'), true)

  // Dotfiles and special filenames
  assert.equal(isDefaultEditableFile('.bashrc'), true)
  assert.equal(isDefaultEditableFile('.env'), true)
  assert.equal(isDefaultEditableFile('.gitignore'), true)
  assert.equal(isDefaultEditableFile('Dockerfile'), true)
  assert.equal(isDefaultEditableFile('Makefile'), true)
  assert.equal(isDefaultEditableFile('hosts'), true)

  // Files without extension under 2MB
  assert.equal(isDefaultEditableFile('my-custom-script', 1024), true)

  // Oversized editable file (> 5MB) should not be default auto-opened
  assert.equal(isDefaultEditableFile('huge.log', 6 * 1024 * 1024), false)
})

test('isDefaultEditableFile rejects binary files', () => {
  assert.equal(isDefaultEditableFile('backup.tar.gz'), false)
  assert.equal(isDefaultEditableFile('photo.png'), false)
  assert.equal(isDefaultEditableFile('app.exe'), false)
  assert.equal(isDefaultEditableFile('libjvm.so'), false)
  assert.equal(isDefaultEditableFile('report.pdf'), false)
  assert.equal(isDefaultEditableFile('video.mp4'), false)
  assert.equal(isDefaultEditableFile('font.woff2'), false)
})

test('isBinaryFile detects known binary extensions', () => {
  assert.equal(isBinaryFile('test.zip'), true)
  assert.equal(isBinaryFile('image.png'), true)
  assert.equal(isBinaryFile('program.exe'), true)
  assert.equal(isBinaryFile('test.txt'), false)
  assert.equal(isBinaryFile('build.sh'), false)
})

test('getLanguageName resolves proper CodeMirror mode identifier', () => {
  assert.equal(getLanguageName('run.sh'), 'shell')
  assert.equal(getLanguageName('.bashrc'), 'shell')
  assert.equal(getLanguageName('index.js'), 'javascript')
  assert.equal(getLanguageName('data.json'), 'javascript')
  assert.equal(getLanguageName('main.py'), 'python')
  assert.equal(getLanguageName('docker-compose.yaml'), 'yaml')
  assert.equal(getLanguageName('nginx.conf'), 'nginx')
  assert.equal(getLanguageName('init.sql'), 'sql')
  assert.equal(getLanguageName('.env'), 'properties')
  assert.equal(getLanguageName('Dockerfile'), 'dockerfile')
  assert.equal(getLanguageName('App.java'), 'clike')
  assert.equal(getLanguageName('main.go'), 'go')
  assert.equal(getLanguageName('main.rs'), 'rust')
  assert.equal(getLanguageName('README.md'), 'markdown')
  assert.equal(getLanguageName('unknown.xyz'), 'text')
})

test('detectFileEncoding identifies UTF-8, BOM and GBK buffers', () => {
  // UTF-8 normal text
  const utf8Buf = new TextEncoder().encode('Hello, 世界!')
  assert.equal(detectFileEncoding(utf8Buf), 'UTF-8')

  // UTF-8 with BOM
  const bomUtf8 = new Uint8Array([0xef, 0xbb, 0xbf, 0x61, 0x62, 0x63])
  assert.equal(detectFileEncoding(bomUtf8), 'UTF-8')

  // UTF-16LE BOM
  const bom16Le = new Uint8Array([0xff, 0xfe, 0x61, 0x00])
  assert.equal(detectFileEncoding(bom16Le), 'UTF-16LE')

  // UTF-16BE BOM
  const bom16Be = new Uint8Array([0xfe, 0xff, 0x00, 0x61])
  assert.equal(detectFileEncoding(bom16Be), 'UTF-16BE')

  // GBK bytes: '你好' -> [0xc4, 0xe3, 0xba, 0xc3]
  const gbkBuf = new Uint8Array([0xc4, 0xe3, 0xba, 0xc3])
  assert.equal(detectFileEncoding(gbkBuf), 'GBK')
})

test('decodeFileBuffer decodes text using specified encoding', () => {
  // UTF-8
  const utf8Buf = new TextEncoder().encode('测试文本 UTF-8')
  assert.equal(decodeFileBuffer(utf8Buf, 'UTF-8'), '测试文本 UTF-8')

  // GBK
  const gbkBuf = new Uint8Array([0xc4, 0xe3, 0xba, 0xc3])
  assert.equal(decodeFileBuffer(gbkBuf, 'GBK'), '你好')

  // Big5: '你好' -> [0xa7, 0x41, 0xa6, 0x6e]
  const big5Buf = new Uint8Array([0xa7, 0x41, 0xa6, 0x6e])
  assert.equal(decodeFileBuffer(big5Buf, 'Big5'), '你好')

  // Fallback on invalid encoding
  assert.equal(decodeFileBuffer(utf8Buf, 'INVALID_ENC'), '测试文本 UTF-8')
})

test('base64ToUint8Array converts base64 to typed array', () => {
  const text = 'Hello SFTP Chunk'
  const b64 = Buffer.from(text).toString('base64')
  const u8 = base64ToUint8Array(b64)
  assert.equal(new TextDecoder().decode(u8), text)
  assert.equal(base64ToUint8Array('').length, 0)
})

test('isTextFile identifies text and log files regardless of size', () => {
  assert.equal(isTextFile('app.log'), true)
  assert.equal(isTextFile('catalina.out'), true)
  assert.equal(isTextFile('nginx.conf'), true)
  assert.equal(isTextFile('data.sql'), true)
  assert.equal(isTextFile('.bashrc'), true)
  assert.equal(isTextFile('archive.tar.gz'), false)
  assert.equal(isTextFile('video.mp4'), false)
  assert.equal(isTextFile('photo.png'), false)
})

test('isParentPath correctly identifies parent and ancestor paths', () => {
  assert.equal(isParentPath('/', '/var'), true)
  assert.equal(isParentPath('/', '/var/log'), true)
  assert.equal(isParentPath('/var', '/var/log'), true)
  assert.equal(isParentPath('/var', '/var/log/nginx'), true)

  // Not parent / going down / equal / sibling
  assert.equal(isParentPath('/var', '/var'), false)
  assert.equal(isParentPath('/var/log', '/var'), false)
  assert.equal(isParentPath('/var', '/var_backup'), false)
  assert.equal(isParentPath('/var', '/etc'), false)
  assert.equal(isParentPath('', '/var'), false)
  assert.equal(isParentPath('/var', ''), false)
})

test('standalone viewer query parameters handle special characters and paths cleanly', () => {
  const filePath = '/var/log/my test file (v1.0) & special.log'
  const token = 'tok_123456_abc'
  const params = new URLSearchParams({
    token,
    path: filePath,
    name: 'my test file (v1.0) & special.log',
    size: String(1048576),
    enc: 'GBK'
  })

  const qs = params.toString()
  const parsed = new URLSearchParams(qs)
  assert.equal(parsed.get('token'), token)
  assert.equal(parsed.get('path'), filePath)
  assert.equal(parsed.get('name'), 'my test file (v1.0) & special.log')
  assert.equal(parsed.get('size'), '1048576')
  assert.equal(parsed.get('enc'), 'GBK')
})

test('GB18030 four-byte characters are not mislabeled as GBK before editing', () => {
  const bytes = Uint8Array.of(0x94, 0x39, 0xfc, 0x36)
  assert.equal(detectFileEncoding(bytes), 'GB18030')
  assert.equal(decodeFileBuffer(bytes, detectFileEncoding(bytes)), '😀')
})
