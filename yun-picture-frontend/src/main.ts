import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import Antd from 'ant-design-vue'
import { VueCropper } from 'vue-cropper'
import 'vue-cropper/dist/index.css'
import 'ant-design-vue/dist/reset.css'
import '@/access.ts'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(Antd)
// 图片裁切（基础图片编辑用），全局注册后模板里可直接写 <vue-cropper>
app.component('VueCropper', VueCropper)

app.mount('#app')





