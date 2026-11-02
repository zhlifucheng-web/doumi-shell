export const RDP_RESOLUTIONS = ['1280x720', '1600x900', '1920x1080', '2560x1440', '3840x2160']

function dimensions(value) {
  if (typeof value !== 'string' || !/^\d{3,4}x\d{3,4}$/.test(value)) return null
  const [width, height] = value.split('x').map(Number)
  return width >= 640 && width <= 3840 && width % 4 === 0 && height >= 480 && height <= 2160 ? [width, height] : null
}

export function rdpResolutionForm(value) {
  const size = dimensions(value)
  return {
    resolution: size ? (RDP_RESOLUTIONS.includes(value) ? value : 'custom') : 'window',
    customWidth: size?.[0] ?? 1920, customHeight: size?.[1] ?? 1080
  }
}

export function rdpResolutionValue({ resolution, customWidth, customHeight }) {
  if (resolution === 'window') return 'window'
  const value = resolution === 'custom' ? `${Number(customWidth)}x${Number(customHeight)}` : resolution
  if (!dimensions(value)) throw new Error('分辨率宽度须为 640–3840 且为 4 的倍数，高度须为 480–2160')
  return value
}
