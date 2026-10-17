import pluginVue from 'eslint-plugin-vue'
import vueTsEslintConfig from '@vue/eslint-config-typescript'
import skipFormatting from '@vue/eslint-config-prettier/skip-formatting'

export default [
  {
    name: 'app/files-to-lint',
    files: ['**/*.{ts,mts,tsx,vue}'],
  },

  {
    name: 'app/files-to-ignore',
    // src/api 是 `npm run openapi` 的生成产物，别 lint：
    // 它几乎每个文件都带 `// @ts-ignore` 头（模板固定输出的），改完下次生成又变回来。
    // 手写的 API 封装在 src/api-custom/，那个是要 lint 的。
    ignores: ['**/dist/**', '**/dist-ssr/**', '**/coverage/**', 'src/api/**'],
  },

  ...pluginVue.configs['flat/essential'],
  ...vueTsEslintConfig(),
  skipFormatting,
]
