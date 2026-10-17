/**
 * 空间级别
 *
 * 必须和后端 SpaceLevelEnum 一一对应，改这里要同步改后端。
 * 0 普通版 / 1 专业版 / 2 旗舰版
 */
export const SPACE_LEVEL_ENUM = {
  COMMON: 0,
  PROFESSIONAL: 1,
  FLAGSHIP: 2,
} as const

/**
 * 空间级别文案
 */
export const SPACE_LEVEL_MAP: Record<number, string> = {
  0: '普通版',
  1: '专业版',
  2: '旗舰版',
}

/**
 * 空间级别下拉选项
 *
 * 和 PIC_REVIEW_STATUS_OPTIONS 一样用 Object.entries + Number：
 * 后端 spaceLevel 是 Integer，直接给 number 才能和 API.SpaceQueryRequest 的类型对上。
 */
export const SPACE_LEVEL_OPTIONS = Object.entries(SPACE_LEVEL_MAP).map(([value, label]) => ({
  label,
  value: Number(value),
}))

/**
 * 空间级别图标
 *
 * 只是视觉标识，用 emoji 而不是引静态图 / iconfont：不用加依赖、不用管打包路径，
 * 级别越高越"贵重"：🌱 普通 → 🚀 专业 → 💎 旗舰
 */
export const SPACE_LEVEL_ICON_MAP: Record<number, string> = {
  0: '🌱',
  1: '🚀',
  2: '💎',
}

/**
 * 空间级别标签配色，取值是 antd 的预设色名
 */
export const SPACE_LEVEL_COLOR_MAP: Record<number, string> = {
  0: 'default',
  1: 'blue',
  2: 'gold',
}

/**
 * 按级别取图标
 *
 * 写成函数而不是直接在模板里索引 map：spaceLevel 在 API 类型里是可选字段
 * （number | undefined），直接索引会因为 undefined 过不了类型检查；
 * 顺带把「未知级别」兜底成一个通用的标签图标，总比显示 undefined 强。
 * @param level 后端返回的空间级别
 */
export const getSpaceLevelIcon = (level?: number) => {
  return level == null ? '' : (SPACE_LEVEL_ICON_MAP[level] ?? '🏷️')
}

/**
 * 按级别取标签配色，未知级别一律用默认色
 * @param level 后端返回的空间级别
 */
export const getSpaceLevelColor = (level?: number) => {
  return level == null ? 'default' : (SPACE_LEVEL_COLOR_MAP[level] ?? 'default')
}
