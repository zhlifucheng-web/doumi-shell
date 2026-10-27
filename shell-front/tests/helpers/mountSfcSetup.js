import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'

// Run the complete production setup inside Vue without requiring a browser DOM.
export function mountSfcSetup(filename, { modules = {}, globals = {}, props = {} } = {}) {
  const { descriptor } = parse(readFileSync(filename, 'utf8'), { filename: filename.pathname })
  const compiled = compileScript(descriptor, { id: filename.pathname }).content
  const executable = compiled.replace(/import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]/g, (_, bindings, source) => {
    const declaration = bindings.trim().startsWith('{')
      ? bindings.replace(/\bas\b/g, ':')
      : `{ default: ${bindings.trim()} }`
    return `const ${declaration} = modules[${JSON.stringify(source)}] || {};`
  }).replace('export default', 'return')
  const component = new Function('modules', ...Object.keys(globals), executable)({ vue, ...modules }, ...Object.values(globals))
  const reactiveProps = vue.reactive(props)
  let state
  const renderer = vue.createRenderer({
    createComment: () => ({}), insert: () => {}, remove: () => {}, parentNode: () => null, nextSibling: () => null
  })
  const setupComponent = {
    ...component,
    setup(props, context) {
      state = component.setup(props, context)
      return () => null
    }
  }
  const app = renderer.createApp({ render: () => vue.h(setupComponent, reactiveProps) })
  app.config.errorHandler = error => { throw error }
  app.mount({})
  return { get state() { return state }, props: reactiveProps, unmount: () => app.unmount() }
}
