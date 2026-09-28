import { createApp } from 'vue'
import { createPinia } from 'pinia'
// P4.1：不再全量注册 Element Plus、不再引 element-plus/dist/index.css；
// 组件/指令由 unplugin-vue-components 按需解析，命令式 API 的样式在此显式引一次。
import 'element-plus/theme-chalk/el-message.css'
import 'element-plus/theme-chalk/el-message-box.css'
import 'element-plus/theme-chalk/el-loading.css'
import './styles/theme.css'
import router from './router'
import App from './App.vue'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
