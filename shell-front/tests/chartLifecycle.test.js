import test from 'node:test'
import assert from 'node:assert/strict'
import { chartForElement } from '../src/utils/chartLifecycle.js'

function fixture() {
  const charts = new Map()
  const created = []
  const init = element => {
    let disposed = false
    const chart = { getDom: () => element, isDisposed: () => disposed, dispose: () => { disposed = true } }
    created.push(chart)
    return chart
  }
  return { charts, created, init }
}
const element = () => ({ clientWidth: 300, clientHeight: 180 })

test('switching panels recreates a chart for the new DOM element under the same card ID', () => {
  const { charts, init } = fixture()
  const oldChart = chartForElement(charts, 'card', element(), init)
  const nextElement = element()
  const nextChart = chartForElement(charts, 'card', nextElement, init)
  assert.equal(oldChart.isDisposed(), true)
  assert.notEqual(nextChart, oldChart)
  assert.equal(nextChart.getDom(), nextElement)
})

test('hidden charts wait for a measurable container before initialization', () => {
  const { charts, created, init } = fixture()
  const el = { clientWidth: 0, clientHeight: 180 }
  assert.equal(chartForElement(charts, 'card', el, init), undefined)
  assert.equal(created.length, 0)
  el.clientWidth = 300
  assert.ok(chartForElement(charts, 'card', el, init))
  assert.equal(created.length, 1)
})

test('a live chart on the same element is reused and a disposed chart is recreated', () => {
  const { charts, init } = fixture()
  const el = element()
  const chart = chartForElement(charts, 'card', el, init)
  assert.equal(chartForElement(charts, 'card', el, init), chart)
  chart.dispose()
  assert.notEqual(chartForElement(charts, 'card', el, init), chart)
})
