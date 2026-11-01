/**
 * 格式化文件大小
 * @param size
 */
export const formatSize = (size?: number) => {
  if (!size) return '未知'
  if (size < 1024) return size + ' B'
  if (size < 1024 * 1024) return (size / 1024).toFixed(2) + ' KB'
  return (size / (1024 * 1024)).toFixed(2) + ' MB'
}

/**
 * 把 id 原样转成后端 Long 类型的请求参数。
 *
 * 后端 JsonConfig 给 Long 注册了 ToStringSerializer，所以所有 Long 字段（含雪花 id）
 * 在 JSON 里是字符串；而 openapi 按 Java 的 long 生成了 number —— 两边对不上。
 * 雪花 id 有 19 位、超过 Number.MAX_SAFE_INTEGER，一旦 Number()/parseInt() 就会丢精度
 * （2103733515547574273 → 2103733515547574300），后端会拿错的 id 去查、返回「请求数据不存在」。
 *
 * 所以这里只骗过类型系统，运行时传的还是字符串。禁止对这个返回值做任何数值运算。
 * @param id 路由参数或接口返回的 id
 */
export const toApiId = (
  id: string | number | null | undefined | (string | null | undefined)[],
): number => {
  // route.query.id 在 ?id=a&id=b 时是数组，取第一个
  const value = Array.isArray(id) ? id[0] : id
  return (value == null ? '' : String(value)) as unknown as number
}

/**
 * 下载图片
 *
 * 参考源码用的是 file-saver 的 saveAs，本项目没有引入该依赖，改用原生 a 标签实现：
 * 同源图片会直接下载；跨域图片（图片存在 COS 域名上）浏览器的 download 属性会被忽略，
 * 会退化成在新标签页打开图片，效果上等同于「查看原图」。
 * @param url 图片下载地址
 * @param fileName 要保存为的文件名
 */
export function downloadImage(url?: string, fileName?: string) {
  if (!url) {
    return
  }
  const link = document.createElement('a')
  link.href = url
  link.download = fileName ?? ''
  link.target = '_blank'
  link.rel = 'noopener'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
}
