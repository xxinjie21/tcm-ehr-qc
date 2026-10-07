<template>
  <!-- P4.1：全量注册 Element Plus 已去除，中文语言包改由 el-config-provider 下发
       （el-config-provider 由 unplugin-vue-components 自动解析） -->
  <el-config-provider :locale="zhCn">
    <!-- 顶层路由过渡：只负责「登录/注册 ↔ 应用外壳」这一层（外壳整个换掉）。
         应用内子页面之间的切换发生在 MainLayout 的嵌套 router-view 里，见那边。

         ⚠️ :key 取 matched[0].path（即「外壳」标识：/login、/register、/），
         **不能取 route.path** —— 取 path 的话，/dashboard → /review 也会被判定成
         「换了组件」，于是 MainLayout 被卸载重建：侧栏滚动位置、通知已读状态、
         AI 助手面板与对话、LLM 配置弹窗状态全部丢失，且每次切页都重新拉一遍
         菜单与通知接口。按外壳取 key 后，应用内切页 key 恒为 '/'，顶层过渡不触发，
         只由内层过渡负责，外壳状态得以保留。

         这里用 <Transition mode="out-in"> 是**经过真机验证**的：
         登录/注册 ↔ 外壳是「整屏换屏」，必须先进后出（out-in），否则新旧两屏
         会在同一帧叠在一起；实测确有淡出 120ms（--dur-fast）→ 淡入 200ms
         （--dur-base）的完整过程，且**没有**出现内层那种卡死。
         内层 router-view 为什么不能用 out-in，见 theme.css ①b 的实测记录。 -->
    <router-view v-slot="{ Component, route }">
      <Transition name="page-fade" mode="out-in">
        <component :is="Component" :key="route.matched[0]?.path || route.path" />
      </Transition>
    </router-view>
  </el-config-provider>
</template>

<script setup>
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: 'Microsoft YaHei', 'PingFang SC', sans-serif;
  background-color: var(--paper, var(--paper));
}
</style>
