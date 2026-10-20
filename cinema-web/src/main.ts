import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// EP 官方暗色变量表(选择器写死 html.dark, 由主题模块挂类名激活)。
// 顺序要求: 必须在 dist/index.css 之后、main.css 之前 ——
// 我们自己的 --el-* 暗色覆盖要赢过 EP 的暗色默认色板。
import 'element-plus/theme-chalk/dark/css-vars.css'
import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import { initTheme } from './theme'
import './styles/main.css'

// 首帧主题已由 index.html 的内联脚本定好(防白闪);
// 这里补齐 favicon 切换、持久化偏好与「跟随系统」的实时响应。
initTheme()

createApp(App).use(createPinia()).use(router).use(ElementPlus).use(i18n).mount('#app')
