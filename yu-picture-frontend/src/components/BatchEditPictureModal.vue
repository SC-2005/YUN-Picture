<template>
  <a-modal
    v-model:open="visible"
    title="批量编辑图片"
    :footer="false"
    :destroy-on-close="true"
    @cancel="closeModal"
  >
    <a-typography-paragraph type="secondary">
      * 只对当前页面的图片生效（共 {{ pictureList.length }} 张）
    </a-typography-paragraph>
    <a-form layout="vertical" :model="formData" @finish="handleSubmit">
      <a-form-item name="category" label="分类">
        <a-auto-complete
          v-model:value="formData.category"
          placeholder="请输入分类，不填则不修改"
          :options="categoryOptions"
          allow-clear
        />
      </a-form-item>
      <a-form-item name="tags" label="标签">
        <a-select
          v-model:value="formData.tags"
          mode="tags"
          placeholder="请输入标签，不填则不修改"
          :options="tagOptions"
          allow-clear
        />
      </a-form-item>
      <a-form-item name="nameRule" label="命名规则">
        <a-input
          v-model:value="formData.nameRule"
          placeholder="请输入命名规则，输入 {序号} 可动态生成"
          allow-clear
        />
      </a-form-item>
      <a-form-item>
        <!-- 三项都是「不填就不改」，所以全空时提交等于什么都没做，这里直接禁用 -->
        <a-button
          type="primary"
          html-type="submit"
          block
          :loading="submitting"
          :disabled="!hasAnyChange"
        >
          提交
        </a-button>
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  editPictureByBatchUsingPost,
  listPictureTagCategoryUsingGet,
} from '@/api/pictureController.ts'

interface Props {
  /** 待批量编辑的图片（空间详情页传当前页的数据） */
  pictureList: API.PictureVO[]
  /** 图片所属的空间 id，后端要拿它校验空间权限 */
  spaceId?: number
}

const props = withDefaults(defineProps<Props>(), {
  pictureList: () => [],
})

/**
 * 批量编辑成功后通知外层刷新列表。
 *
 * 教程里用的是 `:onSuccess="onBatchEditPictureSuccess"` 这种「函数当 prop 传」的写法，
 * 这里改成 emit，和本项目已有的 PictureGridList 保持一致。
 */
const emit = defineEmits<{ success: [] }>()

// 是否可见
const visible = ref(false)

/** 提交中：转圈期间按钮自带 loading，连点也不会重复提交 */
const submitting = ref(false)

/** 表单数据，内容对应后端的 PictureEditByBatchRequest */
const formData = reactive<API.PictureEditByBatchRequest>({
  category: undefined,
  tags: undefined,
  nameRule: undefined,
})

const categoryOptions = ref<{ value: string; label: string }[]>([])
const tagOptions = ref<{ value: string; label: string }[]>([])
/** 标签分类选项拉过一次就不再拉：它是全库聚合出来的，短时间不会变 */
const tagCategoryLoaded = ref(false)

/** 有没有填过东西。三项都留空时后端不会改任何字段，所以没必要发请求 */
const hasAnyChange = computed(() => {
  return (
    !!formData.category?.trim() || (formData.tags?.length ?? 0) > 0 || !!formData.nameRule?.trim()
  )
})

/** 获取标签和分类选项（懒加载，第一次打开弹窗时才请求） */
const getTagCategoryOptions = async () => {
  if (tagCategoryLoaded.value) {
    return
  }
  const res = await listPictureTagCategoryUsingGet()
  if (res.data.code === 0 && res.data.data) {
    tagOptions.value = (res.data.data.tagList ?? []).map((data: string) => ({
      value: data,
      label: data,
    }))
    categoryOptions.value = (res.data.data.categoryList ?? []).map((data: string) => ({
      value: data,
      label: data,
    }))
    tagCategoryLoaded.value = true
  } else {
    message.error('获取标签分类列表失败，' + res.data.message)
  }
}

/**
 * 打开弹窗
 *
 * 每次打开都清空上一次填的内容：组件常驻在空间详情页上（不是点了按钮才创建），
 * 不清的话第二次打开会看到上一批的编辑内容，容易误提交
 */
const openModal = () => {
  if (props.pictureList.length === 0) {
    message.warning('当前页面没有图片')
    return
  }
  formData.category = undefined
  formData.tags = undefined
  formData.nameRule = undefined
  visible.value = true
  getTagCategoryOptions()
}

// 关闭弹窗
const closeModal = () => {
  visible.value = false
}

// 暴露函数给父组件
defineExpose({
  openModal,
})

/**
 * 提交
 *
 * 传的是当前页面的图片 id 列表，图片 id 在运行时是字符串（后端 Long 序列化成了字符串），
 * 这里只做透传、不做任何数值转换，否则雪花 id 会丢精度
 */
const handleSubmit = async () => {
  if (props.pictureList.length === 0) {
    message.warning('当前页面没有图片')
    return
  }
  submitting.value = true
  try {
    // 列表里的图片一定有 id，这里只是把「可能没有 id」排除掉，让类型和后端的 List<Long> 对上
    const pictureIdList = props.pictureList
      .map((picture) => picture.id)
      .filter((id): id is number => id !== undefined && id !== null)
    const res = await editPictureByBatchUsingPost({
      pictureIdList,
      spaceId: props.spaceId,
      ...formData,
    })
    if (res.data.code === 0) {
      message.success('操作成功')
      closeModal()
      emit('success')
    } else {
      message.error('操作失败，' + res.data.message)
    }
  } catch (error) {
    message.error('操作失败，' + (error as Error).message)
  } finally {
    submitting.value = false
  }
}
</script>
