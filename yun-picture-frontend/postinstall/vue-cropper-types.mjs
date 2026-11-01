/**
 * 给 vue-cropper 补一个 .vue 类型声明，修复 `npm run type-check` 报的 TS7016
 *
 * 起因：vue-cropper 的 package.json 把 `typings` 指到 lib/typings/index.d.ts，
 * 而它内部用相对路径 `import VueCropper from '../vue-cropper.vue'` 引源码；
 * `declare module '*.vue'` 不匹配相对说明符，于是解析不到声明、整个项目的类型检查直接失败。
 *
 * 解决办法是把 postinstall/vue-cropper-vue.d.ts 拷到包内，让那个相对引用解析得到。
 * 因为改的是 node_modules，重装依赖会被冲掉，所以挂在 postinstall 上自动执行。
 *
 * 说明：
 * - 依赖没装（比如只装了后端依赖）或包不存在时静默跳过，不能让 npm install 失败；
 * - 目标文件已存在就覆盖，保证内容和仓库里的补丁保持一致。
 */
import { copyFile, mkdir } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = dirname(fileURLToPath(new URL('.', import.meta.url)))
const source = join(projectRoot, 'postinstall', 'vue-cropper-vue.d.ts')
const target = join(projectRoot, 'node_modules', 'vue-cropper', 'lib', 'vue-cropper.vue.d.ts')

try {
  await mkdir(dirname(target), { recursive: true })
  await copyFile(source, target)
  console.log('[postinstall] 已补充 vue-cropper 的 .vue 类型声明')
} catch (error) {
  // 依赖没装、包被换掉都不影响项目本身，只提示一下
  console.warn('[postinstall] 跳过 vue-cropper 类型补丁：', error.message)
}
