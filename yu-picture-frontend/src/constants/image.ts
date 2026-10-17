/**
 * 图片对象存储的域名
 *
 * 有两处要用到它，必须保持一致：
 * 1）src/utils/image.ts —— 把图片地址改写成同源地址，绕开 canvas 的跨域限制；
 * 2）vite.config.ts —— 开发服务器把 /public、/space 转发到对象存储。
 *
 * 之所以单独放一个文件、而不是写在 utils/image.ts 里：vite.config.ts 编译时走的是
 * tsconfig.node.json（lib 里只有 es2023，没有 DOM），import 一个用了 document / Image
 * 的前端文件会直接报类型错误。这里只有常量，两边都能安全引用。
 */
export const IMAGE_HOST = 'https://yu-pic-1484454364.cos.ap-shanghai.myqcloud.com'
