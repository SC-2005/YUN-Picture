<template>
  <div class="picture-grid-list">
    <!-- 图片列表：只负责展示，数据由外层页面查询后传进来，分页器也在外层 -->
    <a-list
      :grid="{ gutter: 16, xs: 1, sm: 2, md: 3, lg: 4, xl: 5, xxl: 6 }"
      :data-source="dataList"
      :loading="loading"
    >
      <template #renderItem="{ item: picture }">
        <a-list-item style="padding: 0">
          <!-- 单张图片 -->
          <a-card hoverable @click="doClickPicture(picture)">
            <template #cover>
              <img
                :alt="picture.name ?? '图片'"
                :src="picture.thumbnailUrl ?? picture.url"
                loading="lazy"
                style="height: 180px; object-fit: cover"
              />
            </template>
            <a-card-meta :title="picture.name ?? '未命名'">
              <template #description>
                <a-flex>
                  <a-tag color="green">
                    {{ picture.category ?? '默认' }}
                  </a-tag>
                  <a-tag v-for="tag in picture.tags ?? []" :key="tag">
                    {{ tag }}
                  </a-tag>
                </a-flex>
              </template>
            </a-card-meta>
            <!-- 快捷操作栏：主页不展示（showOp 为 false），只有空间详情页这类管理场景才展示 -->
            <template v-if="showOp" #actions>
              <edit-outlined v-if="canEdit" @click="(e: MouseEvent) => doEdit(picture, e)" />
              <delete-outlined v-if="canDelete" @click="(e: MouseEvent) => doDelete(picture, e)" />
            </template>
          </a-card>
        </a-list-item>
      </template>
    </a-list>
  </div>
</template>

<script setup lang="ts">
import { DeleteOutlined, EditOutlined } from '@ant-design/icons-vue'
import { Modal, message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { deletePictureUsingPost } from '@/api/pictureController.ts'

interface Props {
  dataList?: API.PictureVO[]
  loading?: boolean
  /** 是否展示卡片下方的快捷操作栏 */
  showOp?: boolean
  canEdit?: boolean
  canDelete?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  dataList: () => [],
  loading: false,
  showOp: false,
  canEdit: false,
  canDelete: false,
})

/**
 * 删除成功后通知外层刷新。
 *
 * 教程里用的是 `:onReload="fetchData"` 这种「函数当 prop 传」的写法，
 * 这里改成 emit，和本项目已有的 PictureList 保持一致（同样是列表组件、同样不负责查询）。
 */
const emit = defineEmits<{ reload: [] }>()

const router = useRouter()

// 跳转至图片详情页
// picture.id 在运行时时字符串（后端 Long 序列化成了字符串），直接拼接即可，
// 千万不要在这里做 Number() 转换 —— 雪花 id 会丢精度
const doClickPicture = (picture: API.PictureVO) => {
  router.push({
    path: `/picture/${picture.id}`,
  })
}

// 编辑：跳转时一定要携带 spaceId，否则图片会被当成公共图库的图重新上传
const doEdit = (picture: API.PictureVO, e: MouseEvent) => {
  // 阻止冒泡，否则会同时触发卡片的点击事件、跳到图片详情页
  e.stopPropagation()
  if (!props.canEdit) {
    return
  }
  router.push({
    path: '/add_picture',
    query: {
      id: picture.id,
      spaceId: picture.spaceId,
    },
  })
}

/**
 * 删除
 *
 * 和其它页面保持一致：删除不可恢复，先弹确认框，点「确认删除」才真正调接口。
 * onOk 返回 Promise，antd 会在接口返回前把确定按钮置为 loading，连点也不会重复提交。
 */
const doDelete = (picture: API.PictureVO, e: MouseEvent) => {
  e.stopPropagation()
  if (!props.canDelete) {
    return
  }
  const id = picture.id
  if (!id) {
    return
  }
  Modal.confirm({
    title: '确认删除这张图片吗？',
    content: '删除后不可恢复，图片将从列表中移除。',
    okText: '确认删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      const res = await deletePictureUsingPost({ id })
      if (res.data.code === 0) {
        message.success('删除成功')
        // 让外层重新查询（删除会同时释放空间额度，外层还要顺带刷新空间信息）
        emit('reload')
      } else {
        message.error('删除失败，' + res.data.message)
        // 失败时把 Promise 抛出去，让确认框保持打开，用户能重试
        throw new Error(res.data.message)
      }
    },
  })
}
</script>

<style scoped>
.picture-grid-list {
  margin-bottom: 16px;
}
</style>
