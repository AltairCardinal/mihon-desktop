<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useData, withBase } from 'vitepress'
import { castVote, readVotes } from './voting-client.mjs'

const props = defineProps<{ featureId: string }>()
const { lang } = useData()
const zh = computed(() => lang.value.startsWith('zh'))
const apiBase = ref('')
const count = ref<number | null>(null)
const voted = ref(false)
const phase = ref<'loading' | 'ready' | 'sending' | 'error' | 'unconfigured'>('loading')
const text = computed(() => zh.value ? {
  loading: '正在读取投票…', sending: '正在提交…', vote: '支持这项规划', voted: '此 IP 已投票',
  retry: '重新查询', error: '暂时无法确认投票结果，请重新查询。',
  unconfigured: '投票尚未开放。', count: '票支持',
} : {
  loading: 'Loading votes…', sending: 'Submitting…', vote: 'Support this feature', voted: 'This IP has voted',
  retry: 'Check again', error: 'The voting result could not be confirmed. Please check again.',
  unconfigured: 'Voting is not open yet.', count: 'votes of support',
})

async function load() {
  if (phase.value === 'sending') return
  phase.value = 'loading'
  try {
    const configResponse = await fetch(withBase('/voting-config.json'), { cache: 'no-store', signal: AbortSignal.timeout(10000) })
    if (!configResponse.ok) throw new Error('configuration_unavailable')
    const config = await configResponse.json()
    if (!config.apiBaseUrl) { phase.value = 'unconfigured'; return }
    apiBase.value = config.apiBaseUrl
    const result = (await readVotes(apiBase.value)).find(item => item.id === props.featureId)
    if (!result) throw new Error('feature_unavailable')
    count.value = result.count
    voted.value = result.voted
    phase.value = 'ready'
  } catch {
    phase.value = 'error'
  }
}

async function vote() {
  if (phase.value !== 'ready' || voted.value) return
  phase.value = 'sending'
  try {
    const result = await castVote(apiBase.value, props.featureId)
    count.value = result.count
    voted.value = result.voted
    phase.value = 'ready'
  } catch {
    phase.value = 'error'
  }
}

onMounted(load)
</script>

<template>
  <div class="feature-vote" :data-feature-id="featureId" :aria-busy="phase === 'loading' || phase === 'sending'">
    <template v-if="phase === 'ready' || phase === 'sending'">
      <span class="feature-vote-count" role="status"><strong>{{ count }}</strong> {{ text.count }}</span>
      <button type="button" :disabled="voted || phase === 'sending'" @click="vote">
        {{ phase === 'sending' ? text.sending : voted ? text.voted : text.vote }}
      </button>
    </template>
    <span v-else-if="phase === 'loading'" role="status">{{ text.loading }}</span>
    <span v-else-if="phase === 'unconfigured'" role="status">{{ text.unconfigured }}</span>
    <template v-else>
      <span role="status">{{ text.error }}</span>
      <button type="button" class="feature-vote-retry" @click="load">{{ text.retry }}</button>
    </template>
  </div>
</template>

<style scoped>
.feature-vote { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 14px; margin: 24px 0 36px; padding: 18px 20px; border: 1px solid var(--vp-c-divider); border-radius: 12px; background: var(--vp-c-bg-soft); color: var(--vp-c-text-2); font-size: 14px; }
.feature-vote-count strong { margin-right: 4px; color: var(--vp-c-text-1); font-size: 24px; font-variant-numeric: tabular-nums; }
button { min-height: 42px; padding: 8px 18px; border-radius: 8px; background: var(--vp-c-brand-1); color: var(--vp-c-bg); font: inherit; font-weight: 600; cursor: pointer; }
button:disabled { background: var(--vp-c-default-soft); color: var(--vp-c-text-2); cursor: default; }
button:focus-visible { outline: 3px solid var(--vp-c-brand-2); outline-offset: 3px; }
.feature-vote-retry { background: var(--vp-c-default-soft); color: var(--vp-c-brand-1); }
</style>
