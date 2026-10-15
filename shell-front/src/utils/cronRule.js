const integer = (value, min, max) => (typeof value === 'number' || typeof value === 'string' && /^\d+$/.test(value)) && Number.isInteger(Number(value)) && Number(value) >= min && Number(value) <= max
const numbers = (values, min, max) => Array.isArray(values) && values.length && values.every(value => integer(value, min, max))
const list = values => [...new Set(values.map(Number))].sort((a, b) => a - b).join(',')
export const defaultCronRule = () => ({ frequency: 'daily', interval: 5, minute: 0, second: 0, time: '09:00:00', weekdays: [1, 2, 3, 4, 5], monthDays: [1] })

export function buildCronRule(rule) {
  if (rule.frequency === 'minutes') return integer(rule.second, 0, 59) && integer(rule.interval, 1, 59) ? `${Number(rule.second)} */${Number(rule.interval)} * * * *` : ''
  if (rule.frequency === 'hours') return integer(rule.second, 0, 59) && integer(rule.interval, 1, 23) && integer(rule.minute, 0, 59) ? `${Number(rule.second)} ${Number(rule.minute)} */${Number(rule.interval)} * * *` : ''
  if (!/^\d{2}:\d{2}:\d{2}$/.test(rule.time || '')) return ''
  const [hour, minute, second] = rule.time.split(':').map(Number)
  if (!integer(hour, 0, 23) || !integer(minute, 0, 59) || !integer(second, 0, 59)) return ''
  const prefix = `${second} ${minute} ${hour}`
  if (rule.frequency === 'daily') return `${prefix} * * *`
  if (rule.frequency === 'weekly') return numbers(rule.weekdays, 0, 6) ? `${prefix} * * ${list(rule.weekdays)}` : ''
  if (rule.frequency === 'monthly') return numbers(rule.monthDays, 1, 31) ? `${prefix} ${list(rule.monthDays)} * *` : ''
  return ''
}

export function parseCronRule(expression) {
  if (typeof expression !== 'string' || !expression.trim()) return null
  const parts = expression.trim().split(/\s+/)
  if (parts.length === 5) parts.unshift('0')
  if (parts.length !== 6) return null
  const [s, m, h, day, month, week] = parts
  const wildcard = value => value === '*' || value === '?'
  if (!integer(s, 0, 59) || month !== '*') return null
  const rule = { ...defaultCronRule(), second: Number(s) }
  if (wildcard(day) && wildcard(week)) {
    if (h === '*' && (m === '*' || /^\*\/\d+$/.test(m))) {
      rule.frequency = 'minutes'; rule.interval = m === '*' ? 1 : Number(m.slice(2))
      return buildCronRule(rule) ? rule : null
    }
    if (/^\*\/\d+$/.test(h) && integer(m, 0, 59)) {
      rule.frequency = 'hours'; rule.interval = Number(h.slice(2)); rule.minute = Number(m)
      return buildCronRule(rule) ? rule : null
    }
  }
  if (!integer(m, 0, 59) || !integer(h, 0, 23)) return null
  rule.time = [h, m, s].map(value => String(Number(value)).padStart(2, '0')).join(':')
  if (wildcard(day) && wildcard(week)) return rule
  if (wildcard(day) && !wildcard(week)) {
    const aliases = { SUN: 0, MON: 1, TUE: 2, WED: 3, THU: 4, FRI: 5, SAT: 6 }
    const days = week.toUpperCase().split(',').map(value => Object.hasOwn(aliases, value) ? aliases[value] : /^\d+$/.test(value) ? Number(value) : NaN)
    if (!numbers(days, 0, 7)) return null
    rule.frequency = 'weekly'; rule.weekdays = [...new Set(days.map(value => value === 7 ? 0 : value))]
    return rule
  }
  if (wildcard(week) && /^\d+(,\d+)*$/.test(day)) {
    rule.frequency = 'monthly'; rule.monthDays = day.split(',').map(Number)
    return buildCronRule(rule) ? rule : null
  }
  return null
}
