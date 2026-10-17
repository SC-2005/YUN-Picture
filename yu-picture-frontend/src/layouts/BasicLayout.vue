<template>
  <div id="basicLayout">
    <a-layout style="min-height: 100vh">
      <a-layout-header class="header"><GlobalHeader /></a-layout-header>
      <!-- 顶部栏下面再套一层布局，形成「顶部 - 侧边」结构（a-layout 检测到 sider 子元素会自动变成横向） -->
      <a-layout>
        <GlobalSider />
        <a-layout-content class="content">
          <router-view />
        </a-layout-content>
      </a-layout>
      <a-layout-footer class="footer">
        <a href="3445166212" target="_blank"> 图协云 作者:常念 </a>
      </a-layout-footer>
    </a-layout>
  </div>
</template>

<script setup lang="ts">
import GlobalHeader from '@/components/GlobalHeader.vue'
import GlobalSider from '@/components/GlobalSider.vue'
</script>

<style scoped>
#basicLayout .header {
  padding-inline: 20px;
  background: white;
  color: unset;
  /* 侧边栏自带左边框，这里留 1px 就够，避免和侧边栏出现双线 */
  margin-bottom: 1px;
}

#basicLayout .content {
  padding: 28px;
  /* 页脚是 position: fixed，会盖住内容底部，margin 对 fixed 元素不起作用。
     这里必须用 padding 把底部让出来，否则首页的下拉加载哨兵和「加载中」提示
     会被永久压在页脚下面，用户看不到任何加载反馈。页脚高约 53px，留 80px。 */
  padding-bottom: 80px;
  background: linear-gradient(to right, #fefefe, #fff);
}

#basicLayout .footer {
  background: #efefef;
  padding: 16px;
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  text-align: center;
}
</style>
