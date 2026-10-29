/**
 * 图片协同编辑的 WebSocket 封装
 *
 * 原生 WebSocket 只提供 onopen / onmessage 这类单回调接口，组件里想监听多种
 * 业务消息就得在一个 onmessage 里写 switch。这里加了一层「按消息类型注册处理器」
 * 的小机制：组件用 on('ENTER_EDIT', fn) 注册，收到消息后按 type 分发给对应的 fn。
 *
 * 和后端的连接约定（见 PictureEditHandler / WebSocketConfig）：
 * - 路径：/api/ws/picture/edit?pictureId=xxx（/api 是后端 context-path）
 * - 握手时会带上登录 Cookie，后端在 WsHandshakeInterceptor 里校验登录态和图片编辑权限
 * - 消息体是 JSON：{ type, message, editAction, user }
 */

/**
 * WebSocket 服务地址
 *
 * 和 src/request.ts 里的 baseURL 指向同一个后端，改端口时两处要一起改。
 * 协议头要单独写 ws:// —— 不能直接拿 http 的地址替换字符串，也不走 vite 代理。
 */
const WS_BASE_URL = 'ws://localhost:8123'

/**
 * 服务端下发的消息结构（见后端 PictureEditResponseMessage）
 *
 * 这里声明成可选字段的字面量类型，而不是用 any：组件里拿到消息后要读 message /
 * editAction / user，有类型约束才能被 TS 检查到拼写错误。
 * 注意 id / userId 这类 Long 字段后端转成了字符串，类型上仍按 number 声明（和历史接口一致）。
 */
export interface PictureEditResponseMessage {
  /** INFO / ERROR / ENTER_EDIT / EXIT_EDIT / EDIT_ACTION */
  type: string
  /** 提示文案 */
  message?: string
  /** 编辑动作标识，只有 EDIT_ACTION 消息有 */
  editAction?: string
  /** 触发消息的用户 */
  user?: API.UserVO
}

/** 注册到 on() 上的消息处理函数 */
type PictureEditMessageHandler = (data: PictureEditResponseMessage) => void

export default class PictureEditWebSocket {
  /** 当前编辑的图片 ID */
  private pictureId: number | string
  /** WebSocket 实例 */
  private socket: WebSocket | null
  /** 自定义事件处理器：key 是消息类型，value 是该类型下注册的所有回调 */
  private eventHandlers: Record<string, PictureEditMessageHandler[]>

  constructor(pictureId: number | string) {
    this.pictureId = pictureId
    this.socket = null
    this.eventHandlers = {}
  }

  /**
   * 初始化 WebSocket 连接
   *
   * 注意：浏览器的 WebSocket 握手会自动带上同站（localhost）的 Cookie，
   * 不需要也不能在构造函数里手动设置请求头。
   */
  connect() {
    // 雪花 id 是 19 位，前端一直当字符串传，这里不要做 Number() 转换
    const url = `${WS_BASE_URL}/api/ws/picture/edit?pictureId=${encodeURIComponent(
      String(this.pictureId),
    )}`
    this.socket = new WebSocket(url)

    // 监听连接成功事件
    this.socket.onopen = () => {
      console.log('WebSocket 连接已建立')
      this.triggerEvent('open')
    }

    // 监听消息事件
    this.socket.onmessage = (event) => {
      const message = JSON.parse(event.data)
      console.log('收到消息:', message)

      // 根据消息类型触发对应事件
      const type = message.type
      this.triggerEvent(type, message)
    }

    // 监听连接关闭事件
    this.socket.onclose = (event) => {
      console.log('WebSocket 连接已关闭:', event)
      // 连接状态类事件只用于调试，不需要把原生 Event 塞给业务处理器
      this.triggerEvent('close')
    }

    // 监听错误事件（握手被后端拒绝时也会走到这里）
    this.socket.onerror = (error) => {
      console.error('WebSocket 发生错误:', error)
      this.triggerEvent('error')
    }
  }

  /**
   * 关闭 WebSocket 连接
   *
   * 置空 socket 是为了让 sendMessage 在关闭后明确走「未连接」分支，
   * 也避免同一个实例被重复 connect 时还持有旧连接。
   */
  disconnect() {
    if (this.socket) {
      this.socket.close()
      this.socket = null
      console.log('WebSocket 连接已手动关闭')
    }
  }

  /**
   * 发送消息到后端
   * @param message 消息对象
   */
  sendMessage(message: object) {
    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(message))
      console.log('消息已发送:', message)
    } else {
      console.error('WebSocket 未连接，无法发送消息:', message)
    }
  }

  /**
   * 添加自定义事件监听
   * @param type 消息类型（INFO / ERROR / ENTER_EDIT / EXIT_EDIT / EDIT_ACTION）
   * @param handler 消息处理函数
   */
  on(type: string, handler: PictureEditMessageHandler) {
    if (!this.eventHandlers[type]) {
      this.eventHandlers[type] = []
    }
    this.eventHandlers[type].push(handler)
  }

  /**
   * 触发事件：把消息分发给该类型下注册的所有处理器
   * @param type 消息类型
   * @param data 消息数据
   */
  triggerEvent(type: string, data?: PictureEditResponseMessage) {
    const handlers = this.eventHandlers[type]
    // 原生连接事件（open / close / error）不带业务消息体，没有 data 时不分发
    if (handlers && data !== undefined) {
      handlers.forEach((handler) => handler(data))
    }
  }
}
