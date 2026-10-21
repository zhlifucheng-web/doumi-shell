// Vue may replace a chart's element while preserving its logical card ID.
export function chartForElement(charts, key, element, init) {
  let chart = charts.get(key)
  if (chart && (chart.isDisposed() || chart.getDom() !== element)) {
    if (!chart.isDisposed()) chart.dispose()
    charts.delete(key)
    chart = null
  }
  if (!chart && element?.clientWidth > 0 && element?.clientHeight > 0) {
    chart = init(element)
    charts.set(key, chart)
  }
  return chart
}
