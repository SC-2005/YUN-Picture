/**
 * 图片审核状态
 *
 * 必须和后端 PictureReviewStatusEnum 一一对应，改这里要同步改后端。
 * 0 待审核 / 1 通过 / 2 拒绝
 */
export const PIC_REVIEW_STATUS_ENUM = {
  REVIEWING: 0,
  PASS: 1,
  REJECT: 2,
} as const

/**
 * 图片审核状态文案
 */
export const PIC_REVIEW_STATUS_MAP: Record<number, string> = {
  0: '待审核',
  1: '通过',
  2: '拒绝',
}

/**
 * 图片审核状态下拉选项
 *
 * 注意 value 用的是 number：后端 PictureQueryRequest.reviewStatus 是 Integer，
 * 前端 API.PictureQueryRequest.reviewStatus 也声明成 number。
 * 参考源码用 Object.keys 映射出的是字符串 "0"/"1"/"2"，运行时靠 Jackson 的宽松转换
 * 才没出错，但类型上一直是对不上的 —— 这里直接给 number，行为一致且类型正确。
 */
export const PIC_REVIEW_STATUS_OPTIONS = Object.entries(PIC_REVIEW_STATUS_MAP).map(
  ([value, label]) => ({
    label,
    value: Number(value),
  }),
)

/**
 * 图片格式下拉选项
 *
 * 后端 getQueryWrapper 对 picFormat 用的是 like（模糊匹配），所以这里给常见的几种即可，
 * value 用小写 —— 对象存储回传的 format 就是小写（jpg / png）。
 * jpg 和 jpeg 要分开列：'%jpg%' 匹配不到 'jpeg'。
 */
export const PIC_FORMAT_OPTIONS = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp'].map((format) => ({
  label: format.toUpperCase(),
  value: format,
}))

/**
 * 图片尺寸预设
 *
 * 每个预设对应一组「最大宽度 + 最大高度」，后端 getQueryWrapper 用 picWidthMax /
 * picHeightMax 走 <= 过滤（宽和高都不超过预设值才算命中）。
 * 想自己填数值就不用预设，直接改最大宽度 / 最大高度两个输入框。
 */
export const PIC_SIZE_PRESETS = [
  { value: 'small', label: '小', maxWidth: 400, maxHeight: 400 },
  { value: 'medium', label: '中', maxWidth: 1000, maxHeight: 1000 },
  { value: 'large', label: '大', maxWidth: 2000, maxHeight: 2000 },
  { value: 'xlarge', label: '特大', maxWidth: 5000, maxHeight: 5000 },
]
