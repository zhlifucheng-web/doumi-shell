import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

const markdown = new MarkdownIt({ html: false, linkify: true, breaks: true })
const defaultLink = markdown.renderer.rules.link_open || ((tokens, index, options, env, renderer) => renderer.renderToken(tokens, index, options))
markdown.renderer.rules.link_open = (tokens, index, options, env, renderer) => {
  tokens[index].attrSet('target', '_blank')
  tokens[index].attrSet('rel', 'noopener noreferrer')
  return defaultLink(tokens, index, options, env, renderer)
}
const defaultFence = markdown.renderer.rules.fence
markdown.renderer.rules.fence = (...args) => `<div class="md-code-block"><button type="button" class="md-copy-code">复制代码</button>${defaultFence(...args)}</div>`

export function renderAiMarkdown(text) {
  return DOMPurify.sanitize(markdown.render(String(text || '')), {
    ALLOWED_TAGS: ['p', 'br', 'strong', 'em', 's', 'a', 'code', 'pre', 'blockquote', 'ul', 'ol', 'li', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'hr', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'div', 'button'],
    ALLOWED_ATTR: ['href', 'title', 'target', 'rel', 'class', 'type', 'start', 'align', 'src', 'alt'],
    ALLOW_DATA_ATTR: false
  })
}
