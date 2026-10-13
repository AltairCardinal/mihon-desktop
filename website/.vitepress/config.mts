import { defineConfig, type DefaultTheme } from 'vitepress'

const repository = 'https://github.com/AltairCardinal/mihon-desktop'

function navigation(zh: boolean): DefaultTheme.NavItem[] {
  const prefix = zh ? '/zh' : ''
  return [
    { text: zh ? '功能' : 'Features', link: `${prefix}/features` },
    { text: zh ? '开始体验' : 'Get started', link: `${prefix}/getting-started` },
    { text: zh ? '变更历史' : 'Changelog', link: `${prefix}/changelog` },
    { text: zh ? '参与贡献' : 'Contribute', link: `${prefix}/contributing` },
  ]
}

export default defineConfig({
  title: 'Mihon Desktop',
  description: 'An independent Mihon fork for desktop reading, smarter spreads, device sync, and author discovery.',
  base: '/mihon-desktop/',
  cleanUrls: false,
  lastUpdated: true,
  ignoreDeadLinks: false,
  head: [
    ['link', { rel: 'icon', type: 'image/png', href: '/mihon-desktop/mihon-desktop.png' }],
    ['meta', { name: 'theme-color', content: '#725299' }],
  ],
  sitemap: { hostname: 'https://altaircardinal.github.io/mihon-desktop/' },
  locales: {
    root: {
      label: 'English',
      lang: 'en',
      themeConfig: {
        nav: navigation(false),
        sidebar: [{ text: 'Explore the fork', items: navigation(false) as DefaultTheme.SidebarItem[] }],
        editLink: { pattern: `${repository}/edit/main/website/:path`, text: 'Suggest an edit on GitHub' },
        footer: {
          message: `An independent fork of <a href="https://github.com/mihonapp/mihon">Mihon</a>. Built by readers, in the open.`,
          copyright: `Apache-2.0 · <a href="${repository}/blob/main/LICENSE">License & attribution</a>`,
        },
      },
    },
    zh: {
      label: '简体中文',
      lang: 'zh-CN',
      description: '独立维护的 Mihon 分支：桌面阅读、智能双页、多端同步与作者作品发现。',
      themeConfig: {
        nav: navigation(true),
        sidebar: [{ text: '了解这个分支', items: navigation(true) as DefaultTheme.SidebarItem[] }],
        outline: { label: '本页目录', level: [2, 3] },
        docFooter: { prev: '上一页', next: '下一页' },
        darkModeSwitchLabel: '外观',
        lightModeSwitchTitle: '切换到浅色主题',
        darkModeSwitchTitle: '切换到深色主题',
        sidebarMenuLabel: '目录',
        returnToTopLabel: '返回顶部',
        langMenuLabel: '选择语言',
        lastUpdated: { text: '最后更新' },
        editLink: { pattern: `${repository}/edit/main/website/:path`, text: '在 GitHub 上建议修改' },
        footer: {
          message: `独立维护的 <a href="https://github.com/mihonapp/mihon">Mihon</a> 分支，与读者一起开放构建。`,
          copyright: `Apache-2.0 · <a href="${repository}/blob/main/LICENSE">许可证与归属</a>`,
        },
      },
    },
  },
  themeConfig: {
    logo: { src: '/mihon-desktop.png', alt: 'Mihon Desktop' },
    outline: { level: [2, 3] },
    socialLinks: [{ icon: 'github', link: repository }],
    search: {
      provider: 'local',
      options: {
        locales: {
          zh: {
            translations: {
              button: { buttonText: '搜索文档', buttonAriaLabel: '搜索文档' },
              modal: {
                displayDetails: '显示详情',
                resetButtonTitle: '清除搜索',
                backButtonTitle: '关闭搜索',
                noResultsText: '未找到相关结果',
                footer: { selectText: '选择', navigateText: '切换', closeText: '关闭' },
              },
            },
          },
        },
      },
    },
  },
})
