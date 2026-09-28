<template>
  <!-- P4.1：全量注册 Element Plus 已去除，中文语言包改由 el-config-provider 下发
       （el-config-provider 由 unplugin-vue-components 自动解析） -->
  <el-config-provider :locale="zhCn">
    <router-view />
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
  background-color: var(--paper, #f5f3ed);
}
</style>
