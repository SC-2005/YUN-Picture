/**
 * vue-cropper 的类型声明
 *
 * 这个包自己带了 typings，但那份声明里用相对路径 import 了 .vue 源码，
 * 解析不到声明文件（细节见 postinstall/vue-cropper-vue.d.ts）。
 * 补丁只补了「.vue 能被解析」这一件事，组件本身的 props / 方法还是没有类型，
 * 所以在项目里额外补一份我们真正用得到的：
 *
 * 1）模板里用到的 props：类型写清楚，写错属性名 / 类型时能直接报出来；
 * 2）ref 上调用的方法：`cropperRef.value?.getCropBlob(...)` 这类调用有补全和检查。
 *
 * 注意这份声明不覆盖 'vue-cropper' 模块本身（那样会和包的声明冲突），只是导出类型给组件用。
 */

/** vue-cropper 实例上可以通过 ref 调用的方法和属性 */
export interface VueCropperInstance {
  /** 向左旋转 90° */
  rotateLeft: () => void
  /** 向右旋转 90° */
  rotateRight: () => void
  /** 缩放：正数放大，负数缩小 */
  changeScale: (num: number) => void
  /** 清除旋转角度 */
  rotateClear: () => void
  /** 重新加载图片 */
  refresh: () => void
  /** 按当前 fixedNumber 重新生成裁切框（切换比例后要手动调一次） */
  goAutoCrop: () => void
  /** 获取裁切框内图片的 base64 */
  getCropData: (cb: (data: string) => void) => void
  /** 获取裁切框内图片的 blob（点「确认」就是用它拿文件） */
  getCropBlob: (cb: (blob: Blob) => void) => void
  /** 截图框宽度 */
  cropW: number
  /** 截图框高度 */
  cropH: number
  /**
   * 当前缩放倍率（1 表示按图片原始像素渲染）
   *
   * vue-cropper 初始化时会按 mode 先缩一次（默认 contain，把图片塞进容器），
   * 所以刚加载完它不一定是 1；changeScale 内部也可能把它夹到「刚好盖住裁切框」的最小值，
   * 缩略图滑动条要回读这个值才和真实情况对得上。
   */
  scale: number
  /** 图片原始宽度（vue-cropper 自己取到的 naturalWidth） */
  trueWidth: number
  /** 图片原始高度 */
  trueHeight: number
}

/**
 * 组件 props
 *
 * 字段名用官方的 camelCase 写法；模板里可以写 kebab-case（Vue 会自己转换）。
 * 参考 README 「二、文档 - 1. props」。
 */
export interface VueCropperProps {
  /** 要裁剪的图片，支持 url / base64 / blob */
  img?: string | Blob | File | null
  /** 输出图片质量，0.1 ~ 1 */
  outputSize?: number
  /** 输出图片格式，jpg 要传 jpeg */
  outputType?: 'jpeg' | 'png' | 'webp'
  /** 是否显示裁切框尺寸信息 */
  info?: boolean
  /** 是否允许滚轮缩放 */
  canScale?: boolean
  /** 是否自动生成裁切框 */
  autoCrop?: boolean
  /** 裁切框默认宽度，默认是容器的 80% */
  autoCropWidth?: number | string
  /** 裁切框默认高度，默认是容器的 80% */
  autoCropHeight?: number | string
  /** 是否固定裁切框宽高比 */
  fixed?: boolean
  /** 裁切框宽高比 [宽, 高]，[0, 0] 表示不限制 */
  fixedNumber?: number[]
  /** 是否固定裁切框大小 */
  fixedBox?: boolean
  /** 是否输出原图比例的截图 */
  full?: boolean
  /** 图片是否可拖动 */
  canMove?: boolean
  /** 裁切框是否可拖动 */
  canMoveBox?: boolean
  /** 图片是否按原始比例渲染 */
  original?: boolean
  /** 裁切框是否限制在图片内 */
  centerBox?: boolean
  /** 是否按设备 dpr 输出高清图 */
  high?: boolean
  /** 尺寸信息是否展示真实输出宽高 */
  infoTrue?: boolean
  /** 限制图片最大宽高，默认 2000 */
  maxImgSize?: number | string
  /** 截图框输出倍数 */
  enlarge?: number | string
  /** 图片渲染方式，效果和 css background-size 类似 */
  mode?: string
  /** 裁切框最小尺寸 */
  limitMinSize?: number | string | number[]
  /** 导出时填充的背景色 */
  fillColor?: string
}
