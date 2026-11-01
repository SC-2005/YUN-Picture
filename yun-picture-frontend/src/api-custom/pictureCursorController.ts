import type { AxiosRequestConfig } from 'axios'
import request from '@/request'

/**
 * ⚠️ 这个文件为什么不放在 src/api/ 下：
 *
 * src/api/ 是 `npm run openapi`（@umijs/openapi 的 generateService）的生成目录。
 * 它每次生成前会 rimraf 掉目录下的**所有**文件（serviceGenerator.js 的 genFile()：
 * 先用 glob 把输出目录下每个条目都扫出来逐个删除），不认识的、手写的文件一并清掉。
 * 之前这个文件放在 src/api/ 里，于是每次重新生成接口就消失一次，Vite 随即报
 * "Failed to resolve import @/api/pictureCursorController.ts"。
 *
 * 所以手写的 API 封装一律放到 src/api-custom/，这里不会被打扫。
 *
 * ----------------------------------------------------------------------------
 *
 * 游标分页（POST /api/picture/list/cursor）的封装
 *
 * 为什么不直接用 openapi 生成的 pictureController.ts 里那个同名函数：
 * 后端 PictureQueryRequest.cursor 和 CursorPage.nextCursor 都是 Long，而 JsonConfig
 * 给 Long 注册了 ToStringSerializer —— JSON 里它们是字符串，openapi 却按 Java 的 long
 * 生成了 number。两边类型对不上，运行时又真的会出事：
 * 雪花 id 有 19 位、超过 Number.MAX_SAFE_INTEGER，response 经 JSON.parse 成 number 后
 * 精度就丢了（2103733515547574273 → 2103733515547574300），拿这个游标翻下一页
 * 要么漏行、要么一直返回同一批数据。
 *
 * 所以这里把游标一律声明成 string：
 * - 请求：cursor 以 JSON 字符串发出，Jackson 反序列化 Long 时能正常接受数字字符串
 * - 响应：nextCursor 声明成 string，axios 的 JSON.parse 也就不会去动它（本来就是字符串）
 *
 * 代价是这两个类型没法直接复用生成的 API.PictureQueryRequest / API.CursorPagePictureVO_，
 * 各写一份。后端改字段时要同步改这里。
 */

/** 请求参数：在生成的 PictureQueryRequest 基础上把游标换成字符串，并去掉游标分页用不到的 current */
export type PictureCursorQueryRequest = Omit<API.PictureQueryRequest, 'cursor' | 'current'> & {
  /** 上一页最后一条的 id。不传 / null 表示取第一页 */
  cursor?: string | null
}

/** 响应数据：在生成的 CursorPagePictureVO_ 基础上把 nextCursor 换成字符串 */
export type PictureCursorPage = Omit<API.CursorPagePictureVO_, 'nextCursor'> & {
  /** 下一页的游标，为 null 表示没有更多了 */
  nextCursor?: string | null
}

/**
 * 响应体
 *
 * 不能复用 API.BaseResponseCursorPagePictureVO_ —— 它的 data 是 CursorPagePictureVO_，
 * 里面 nextCursor 还是 number，等于白改
 */
interface PictureCursorResponse {
  code?: number
  data?: PictureCursorPage
  message?: string
}

/**
 * 游标获取图片列表（封装类）
 *
 * records 里的 id 同样是雪花 id 字符串，需要当 id 用时按字符串处理，
 * 不要 Number() —— 理由见 utils/index.ts 的 toApiId。
 *
 * @param body 查询条件
 * @param options 额外的 axios 配置。首页用它传 AbortSignal，筛选条件一变就取消在途请求
 */
export async function listPictureVoByCursorUsingPost(
  body: PictureCursorQueryRequest,
  options?: AxiosRequestConfig,
) {
  return request<PictureCursorResponse>('/api/picture/list/cursor', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
