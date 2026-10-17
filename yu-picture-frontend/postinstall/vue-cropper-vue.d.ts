/**
 * vue-cropper 里 .vue 源码的类型补丁
 *
 * 背景：vue-cropper 的 package.json 把 `typings` 指到了 lib/typings/index.d.ts，
 * 而这个文件里写的是 `import VueCropper from '../vue-cropper.vue'` —— 用的是**相对路径**。
 * TypeScript 的 `declare module '*.vue'` 只匹配非相对说明符，所以 node_modules 里这个相对
 * 引用解析不到任何声明，`npm run type-check` 就报：
 *
 *   node_modules/vue-cropper/lib/index.ts(1,24): error TS7016:
 *   Could not find a declaration file for module './vue-cropper.vue'
 *
 * 这个文件就是补上那个缺失的声明。它必须放在 vue-cropper 包内部（相对路径只能这样解），
 * 所以由 package.json 的 postinstall 脚本 postinstall/vue-cropper-types.mjs 自动拷贝过去 ——
 * 重新 npm install 之后不用手工再补一遍。
 *
 * 类型细节（props / 方法）在 src/types/vue-cropper.d.ts 里维护。
 */
import type { DefineComponent } from 'vue'

declare const VueCropper: DefineComponent<Record<string, unknown>>
export default VueCropper
