import { h } from 'vue'
import DefaultTheme from 'vitepress/theme'
import PageHistory from './PageHistory.vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  Layout: () => h(DefaultTheme.Layout, null, {
    'doc-footer-before': () => h(PageHistory),
  }),
  enhanceApp({ app }) {
    app.component('PageHistory', PageHistory)
  },
}
