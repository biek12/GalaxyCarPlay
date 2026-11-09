import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Antd from 'ant-design-vue'
import App from './App.vue'
import router from './router'
import { useAppStore } from './stores/app'
import { useAuthStore } from './stores/auth'
import 'ant-design-vue/dist/reset.css'
import './styles.css'
import 'uno.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(Antd)

const appStore = useAppStore()
appStore.init()

const auth = useAuthStore()
auth.initUnauthorizedHandler(router)

app.mount('#app')
