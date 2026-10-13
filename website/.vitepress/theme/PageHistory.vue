<script setup>
import { computed } from 'vue'
import { useData } from 'vitepress'

const { page, lang } = useData()
const isChinese = computed(() => lang.value === 'zh-CN')
const historyUrl = computed(() =>
  'https://github.com/AltairCardinal/mihon-desktop/commits/main/website/' +
  page.value.relativePath.split('/').map(encodeURIComponent).join('/'),
)
</script>

<template>
  <div class="page-history">
    <span>{{ isChinese ? '文档会随项目演进。' : 'Documentation evolves with the project.' }}</span>
    <a :href="historyUrl" target="_blank" rel="noreferrer">
      {{ isChinese ? '查看本页历史 ↗' : 'View this page’s history ↗' }}
    </a>
  </div>
</template>
