/**
 * 图片地址处理 / 图片读取工具
 *
 * ## 为什么需要这个文件
 *
 * 图片编辑（裁剪、旋转）的最后一步是把 canvas 导出成文件（vue-cropper 的 getCropBlob），
 * 而浏览器的安全策略规定：只要画布被「跨域且未授权」的图片污染过，导出就会直接抛错。
 *
 * 本项目所有图片都存在腾讯云 COS 上（和前端不是同一个域名），COS 也没有开 CORS，
 * 于是编辑时必然失败。这里用两种方式绕开：
 *
 * 1）开发环境：vite.config.ts 里配了代理，把图片域名改写成前端地址，由 dev server 转发，
 *    浏览器看到的就是同源图片；
 * 2）生产环境：需要给对象存储配置 CORS（允许自己的域名），或者由网关做同样的转发。
 *    两种方式下面的 fetchImageBlob 都会自动试一遍，不用改代码。
 *
 * 另外，vue-cropper 内部会再发一次请求去读图片的 EXIF（lib/exif-js-min.js），
 * 拿到 http 地址时同样会踩跨域。所以这里统一把图片先读成 base64（data: 开头），
 * 组件内部两条路径都会走「不联网」的分支，彻底避开跨域。
 */

import { IMAGE_HOST } from '@/constants/image.ts'

/**
 * 把对象存储的图片地址换成「同源」地址，交给 dev server / 网关转发
 *
 * 后端存的对象存储 key 只有两种前缀（见 PictureServiceImpl）：
 *   public/{userId}/xxx.webp  公共图库
 *   space/{spaceId}/xxx.webp  空间图片
 * 前端配合 vite.config.ts 里配好的 /public、/space 代理即可，所以这里只做「去掉域名」。
 *
 * - 绝对地址（后端返回的 picture.url 就是这种）→ /public/xxx.webp
 * - 相对地址或别的域名 → 原样返回（别的域名需要自己再配一条代理，见 README 说明）
 */
export const toSameOriginImageUrl = (url?: string): string | undefined => {
  if (!url) {
    return undefined
  }
  if (url.startsWith(IMAGE_HOST)) {
    return url.slice(IMAGE_HOST.length) || '/'
  }
  return url
}

/**
 * 响应体是不是一张图片
 *
 * 特意放行 application/octet-stream：对象存储对没有后缀的文件会返回这个类型，
 * 不能只认 image/*，否则会把正常图片拦下来。而「代理没配好、请求落到前端路由上」
 * 返回的是一段 text/html，正好被挡在门外。
 */
const isImageBlob = (blob: Blob) =>
  blob.type.startsWith('image/') || blob.type === 'application/octet-stream'

/**
 * 读取图片的 blob
 *
 * 先读同源地址（dev server 代理 / 网关转发），失败再退回原始地址：
 * 对象存储自己配了 CORS 时，用绝对地址也能读 —— 这样两种部署方案都不用改代码。
 */
export const fetchImageBlob = async (url: string): Promise<Blob> => {
  const sameOriginUrl = toSameOriginImageUrl(url)
  if (!sameOriginUrl) {
    throw new Error('图片地址为空')
  }
  // 本来就是同源地址（或站内相对地址）时只试一次，不必重复请求
  const candidates = sameOriginUrl === url ? [url] : [sameOriginUrl, url]
  let firstError: unknown
  for (const candidate of candidates) {
    try {
      const response = await fetch(candidate)
      if (!response.ok) {
        throw new Error(`图片下载失败（HTTP ${response.status}）`)
      }
      const blob = await response.blob()
      if (!isImageBlob(blob)) {
        throw new Error('该地址返回的不是图片')
      }
      return blob
    } catch (error) {
      // 保留第一次（同源地址）的失败原因：它才是正常情况下该走的那条路
      firstError = firstError ?? error
    }
  }
  throw new Error(firstError instanceof Error ? firstError.message : '图片读取失败')
}

/**
 * 把 blob 读成 base64（data:image/xxx;base64,...）
 *
 * 用 FileReader 而不是 canvas.toDataURL：后者要先解码再重新编码，
 * 大图会明显更慢，而且会丢掉原始压缩质量。
 */
export const blobToDataUrl = (blob: Blob): Promise<string> =>
  new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as string)
    reader.onerror = () => reject(new Error('图片读取失败'))
    reader.readAsDataURL(blob)
  })

/**
 * 把图片地址读成 base64，失败时抛错（调用方负责提示用户）
 *
 * @param url 图片地址（对象存储的绝对地址或站内相对地址都可以）
 */
export const fetchImageAsDataUrl = async (url: string): Promise<string> =>
  blobToDataUrl(await fetchImageBlob(url))

/**
 * 按任意角度旋转图片，返回旋转后的 base64
 *
 * vue-cropper 自带的 rotateLeft / rotateRight 每次只能转 90°，不支持任意角度；
 * 而它的内部坐标计算又完全按「只转 90° 的整数倍」来写的（rotate 字段只取 -3 ~ 3），
 * 硬塞一个角度进去会让截图框和实际画面错位。
 *
 * 所以这里换个思路：直接用 canvas 把原图转好，再把结果当成一张新图片交给组件。
 * 好处是组件完全不知道有旋转这回事，坐标计算依然正确；代价是每次旋转都要重新编码一次图片，
 * 但这是用户点按钮才触发的操作，可以接受。
 *
 * @param source 原图（base64、对象地址都行）
 * @param degree 顺时针角度，0 ~ 359
 */
export const rotateImage = (source: string, degree: number): Promise<string> =>
  new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => {
      const radian = (degree * Math.PI) / 180
      const sin = Math.abs(Math.sin(radian))
      const cos = Math.abs(Math.cos(radian))
      // 旋转后画布的尺寸：按外接矩形算，否则四个角会被裁掉
      const width = Math.round(image.width * cos + image.height * sin)
      const height = Math.round(image.width * sin + image.height * cos)
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const context = canvas.getContext('2d')
      if (!context) {
        reject(new Error('当前浏览器不支持 canvas'))
        return
      }
      // 先把原点移到画布中心，再旋转、再把图片画在中心，避免旋转后跑出画布
      context.translate(width / 2, height / 2)
      context.rotate(radian)
      context.drawImage(image, -image.width / 2, -image.height / 2)
      resolve(canvas.toDataURL('image/png'))
    }
    image.onerror = () => reject(new Error('图片解码失败，无法旋转'))
    image.src = source
  })
