<template>
  <div id="addPictureBatchPage">
    <h2 style="margin-bottom: 16px">批量创建</h2>
    <!-- 图片信息表单 -->
    <a-form name="formData" layout="vertical" :model="formData" @finish="handleSubmit">
      <a-form-item name="searchText" label="关键词">
        <a-input v-model:value="formData.searchText" placeholder="请输入关键词" allow-clear />
      </a-form-item>
      <a-form-item name="category" label="分类">
        <!-- 选具体分类 = 整批图片都用它；选「自动分类」= 后端按搜索词推断（见 autoCategorize） -->
        <a-select
          v-model:value="categoryValue"
          placeholder="请选择分类"
          :options="categorySelectOptions"
          allow-clear
        />
      </a-form-item>
      <a-form-item name="count" label="抓取数量">
        <!-- 上限 30 和后端一致：service 里 count > 30 直接抛 PARAMS_ERROR -->
        <a-input-number
          v-model:value="formData.count"
          placeholder="请输入数量"
          style="min-width: 180px"
          :min="1"
          :max="30"
          allow-clear
        />
      </a-form-item>
      <a-form-item name="namePrefix" label="名称前缀">
        <a-input
          v-model:value="formData.namePrefix"
          placeholder="请输入名称前缀，会自动补充序号"
          allow-clear
        />
      </a-form-item>
      <a-form-item>
        <a-button type="primary" html-type="submit" style="width: 100%" :loading="loading">
          执行任务
        </a-button>
      </a-form-item>
    </a-form>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  listPictureTagCategoryUsingGet,
  uploadPictureByBatchUsingPost,
} from '@/api/pictureController.ts'
import { useRouter } from 'vue-router'

const router = useRouter()

// 表单数据。count 默认 10，和后端 PictureUploadByBatchRequest 的字段默认值保持一致
const formData = reactive<API.PictureUploadByBatchRequest>({
  count: 10,
})

// 提交任务状态
const loading = ref(false)

/**
 * 「自动分类」在 select 里的占位值。
 * 它只是个 UI 标记，不会真的发给后端 —— 选中它时提交阶段会把 category 置空，
 * 后端约定「category 为空 = 自动分类」，两边靠这个约定对齐，避免各写一套魔法字符串。
 */
const AUTO_CATEGORY = '__AUTO__'

/** 当前选中的分类：默认自动分类 */
const categoryValue = ref<string>(AUTO_CATEGORY)

/** 后端返回的可选分类，拼上「自动分类」作为第一项 */
const categoryOptions = ref<string[]>([])
const categorySelectOptions = computed(() => [
  { value: AUTO_CATEGORY, label: '自动分类（按关键词）' },
  ...categoryOptions.value.map((item) => ({ value: item, label: item })),
])

/**
 * 获取分类选项
 */
const getCategoryOptions = async () => {
  const res = await listPictureTagCategoryUsingGet()
  if (res.data.code === 0 && res.data.data) {
    categoryOptions.value = res.data.data.categoryList ?? []
  } else {
    message.error('获取分类列表失败，' + res.data.message)
  }
}

onMounted(() => {
  getCategoryOptions()
})

/**
 * 提交表单
 */
const handleSubmit = async () => {
  loading.value = true
  try {
    // 选了具体分类就传分类，其余情况（自动分类 / 清空）都留空交给后端推断
    const category =
      !categoryValue.value || categoryValue.value === AUTO_CATEGORY ? undefined : categoryValue.value
    const res = await uploadPictureByBatchUsingPost({
      ...formData,
      category,
    })
    // 操作成功
    if (res.data.code === 0) {
      // 注意不能写成 `&& res.data.data`：一条都没抓到时后端返回 0，会被当成失败
      message.success(`创建成功，共 ${res.data.data ?? 0} 条`)
      // 跳转到主页。本接口是管理员专用（后端带 @AuthCheck(mustRole = ADMIN_ROLE)），
      // 而管理员上传的图片在 fillReviewParams 里自动过审，所以首页能立刻看到抓取结果。
      await router.push({
        path: `/`,
      })
    } else {
      message.error('创建失败，' + res.data.message)
    }
  } finally {
    // 放 finally 里：请求抛异常时也要把按钮解锁，否则页面卡在 loading 态
    loading.value = false
  }
}
</script>

<style scoped>
#addPictureBatchPage {
  max-width: 720px;
  margin: 0 auto;
}
</style>
