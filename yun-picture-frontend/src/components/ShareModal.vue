<template>
  <a-modal v-model:visible="visible" :title="title" :footer="false" @cancel="closeModal">
    <h4>复制分享链接</h4>
    <!-- a-typography-link 自带 copyable，点击复制图标即可复制 link 文本 -->
    <a-typography-link copyable>{{ link }}</a-typography-link>
    <div style="margin-bottom: 16px" />
    <h4>手机扫码查看</h4>
    <a-qrcode :value="link" />
  </a-modal>
</template>
<script lang="ts" setup>
import { ref } from 'vue'

interface Props {
  /** 弹窗标题 */
  title?: string
  /** 要分享的地址，必须由父组件在打开弹窗前赋值 */
  link?: string
}

// link 默认给空串而不是某个示例地址：分享一个写死的地址比分享空链接更容易让人误会。
// 这里不用接收返回值，props 只在模板里通过 title / link 使用
withDefaults(defineProps<Props>(), {
  title: '分享图片',
  link: '',
})

// 是否可见
const visible = ref(false)

// 打开弹窗
const openModal = () => {
  visible.value = true
}

// 关闭弹窗（@cancel 走这里，只负责收起来，不清理状态）
const closeModal = () => {
  visible.value = false
}

// 暴露函数给父组件
defineExpose({
  openModal,
})
</script>
