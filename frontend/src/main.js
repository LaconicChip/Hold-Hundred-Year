import { createApp } from 'vue'
import { createPinia } from 'pinia'

// Element Plus 按需引入：全站只用 el-dialog / el-input-number 两个模板组件（在各自组件里 import），
// 加上 ElMessage / ElMessageBox 两个命令式 API——它们没有使用处可以挂样式，所以在这里统一引一次。
// 不再 app.use(ElementPlus)：那会把 100+ 组件与全部图标（约 960 kB min）塞进首屏。
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'

// 引入设计令牌（全局样式唯一入口）
import './styles/tokens.css'

import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')

// 告诉 index.html 的启动兜底脚本：应用已经挂载，不要再自动刷新
window.__hhyMounted = true
sessionStorage.removeItem('hhy-boot-reloaded')
