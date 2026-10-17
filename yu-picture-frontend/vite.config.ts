import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
// 图片对象存储的域名。从 src/constants/image.ts 引进来，和前端读图（utils/image.ts）
// 用的是同一个值 —— 两处各写一份的话，改了一处忘了另一处就会静默失效。
import { IMAGE_HOST } from './src/constants/image'

/**
 * 对象存储里的两种路径前缀，和后端 PictureServiceImpl 拼出来的 key 一致：
 *   公共图库 public/{userId}/{文件名}
 *   空间图片 space/{spaceId}/{文件名}
 *
 * 这里只做「前缀转发」，不做 rewrite —— 对象存储里 key 本来就带这些前缀，
 * 原样转发过去就能命中文件，前端也能靠前缀区分公共图和空间图。
 *
 * 注意：/public 和 vite 自己的 publicDir（前端 public 目录）重合，但 vite 的代理中间件
 * 排在静态文件中间件之前，配置了代理就会先走代理，所以前端 public 下的静态资源不受影响。
 */
const IMAGE_PATH_PREFIXES = ['/public', '/space']

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue(), vueDevTools()],
  server: {
    proxy: Object.fromEntries(
      IMAGE_PATH_PREFIXES.map((prefix) => [
        prefix,
        {
          target: IMAGE_HOST,
          changeOrigin: true,
          ws: false,
        },
      ]),
    ),
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
})
