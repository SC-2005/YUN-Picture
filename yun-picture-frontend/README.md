# yu-picture-frontend

This template should help get you started developing with Vue 3 in Vite.

## Recommended IDE Setup

[VSCode](https://code.visualstudio.com/) + [Volar](https://marketplace.visualstudio.com/items?itemName=Vue.volar) (and disable Vetur).

## Type Support for `.vue` Imports in TS

TypeScript cannot handle type information for `.vue` imports by default, so we replace the `tsc` CLI with `vue-tsc` for type checking. In editors, we need [Volar](https://marketplace.visualstudio.com/items?itemName=Vue.volar) to make the TypeScript language service aware of `.vue` types.

## Customize configuration

See [Vite Configuration Reference](https://vite.dev/config/).

## Project Setup

```sh
npm install
```

### 同步后端接口（生成 API 客户端）

```sh
# 需要后端已启动（openapi.config.js 里 schemaPath 指向 http://localhost:8123/api/v2/api-docs）
npm run openapi
```

> ⚠️ **`src/api/` 是生成目录，不要往里加任何手写文件。**
>
> `npm run openapi` 在写入前会把 `src/api/` 下的**所有**条目先删掉
> （`@umijs/openapi` 的 `generateService` → `genFile()` 里的 `rimraf.sync`），
> 手写的文件每次都会被清空。曾经 `pictureCursorController.ts` 放在这里，
> 于是每生成一次接口就消失一次，Vite 报
> `Failed to resolve import "@/api/pictureCursorController.ts"`。
>
> 手写的 API 封装请放到 **`src/api-custom/`**。那里不会被生成流程碰到。

### Compile and Hot-Reload for Development

```sh
npm run dev
```

### Type-Check, Compile and Minify for Production

```sh
npm run build
```

### Lint with [ESLint](https://eslint.org/)

```sh
npm run lint
```
