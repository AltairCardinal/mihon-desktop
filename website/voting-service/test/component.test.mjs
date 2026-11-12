import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes, createRequire } from 'node:module'
import { DatabaseSync } from 'node:sqlite'
import { test } from 'node:test'
import { pathToFileURL } from 'node:url'
import { createRenderer, nextTick, ref } from 'vue'
import worker from '../worker.mjs'

const require = createRequire(import.meta.url)
const { parse, compileScript } = require('@vue/compiler-sfc')
const componentUrl = new URL('../../.vitepress/theme/FeatureVote.vue', import.meta.url)
const { descriptor } = parse(readFileSync(componentUrl, 'utf8'))
let compiled = compileScript(descriptor, { id: 'feature-vote-test', inlineTemplate: true }).content
compiled = compiled.replaceAll('from "vue"', `from ${JSON.stringify(pathToFileURL(require.resolve('vue')).href)}`)
  .replaceAll("from 'vue'", `from ${JSON.stringify(pathToFileURL(require.resolve('vue')).href)}`)
  .replace("from 'vitepress'", `from ${JSON.stringify(new URL('./vue-environment.mjs', import.meta.url).href)}`)
  .replace("from './voting-client.mjs'", `from ${JSON.stringify(new URL('../../.vitepress/theme/voting-client.mjs', import.meta.url).href)}`)
const { default: Component } = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(compiled)).toString('base64'))

function node(type, text = '') { return { type, text, children: [], props: {}, parent: null } }
const renderer = createRenderer({
  createElement: type => node(type), createText: text => node('#text', text), createComment: text => node('#comment', text),
  setText: (target, text) => { target.text = text }, setElementText: (target, text) => { target.text = text; target.children = [] },
  parentNode: target => target.parent,
  nextSibling: target => target.parent?.children[target.parent.children.indexOf(target) + 1] ?? null,
  patchProp: (target, key, previous, value) => { target.props[key] = value },
  insert(target, parent, anchor = null) {
    if (target.parent) this.remove(target)
    target.parent = parent
    const index = anchor ? parent.children.indexOf(anchor) : -1
    if (index < 0) parent.children.push(target); else parent.children.splice(index, 0, target)
  },
  remove(target) {
    if (target.parent) target.parent.children.splice(target.parent.children.indexOf(target), 1)
    target.parent = null
  },
})
function find(target, predicate) {
  if (predicate(target)) return target
  for (const child of target.children) { const found = find(child, predicate); if (found) return found }
}
function allText(target) { return target.text + target.children.map(allText).join(' ') }
async function settle() {
  for (let index = 0; index < 15; index++) {
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()
  }
}

test('mounted Vue vote button reaches the real client, Worker and SQL, then disables and survives remount', async () => {
  const db = new DatabaseSync(':memory:')
  db.exec(readFileSync(new URL('../migrations/0001_votes.sql', import.meta.url), 'utf8'))
  const env = { VOTE_HMAC_SECRET: 'test-component-secret-at-least-thirty-two-characters', DB: {
    prepare(sql) {
      let args = []
      return { bind(...values) { args = values; return this },
        async first() { return db.prepare(sql).get(...args) },
        async run() { const result = db.prepare(sql).run(...args); return { success: true, meta: { changes: result.changes } } },
      }
    },
  } }
  const originalFetch = globalThis.fetch
  globalThis.fetch = async (url, options = {}) => {
    if (url === '/mihon-desktop/voting-config.json') return Response.json({ apiBaseUrl: 'https://votes.example' })
    return worker.fetch(new Request(url, { ...options, headers: { Origin: 'https://altaircardinal.github.io', 'CF-Connecting-IP': '203.0.113.12' } }), env)
  }
  const mount = () => {
    const root = node('root')
    const app = renderer.createApp(Component, { featureId: 'desktop-read-deletion-parity' })
    app.provide('test-language', ref('zh-CN'))
    app.mount(root)
    return { root, app }
  }
  let mounted
  try {
    mounted = mount()
    await settle()
    const button = find(mounted.root, target => target.type === 'button')
    assert.ok(button)
    assert.equal(button.props.disabled, false)
    assert.match(allText(mounted.root), /支持这项规划/)
    await button.props.onClick()
    await settle()
    assert.equal(find(mounted.root, target => target.type === 'button').props.disabled, true)
    assert.match(allText(mounted.root), /此 IP 已投票/)
    assert.equal(db.prepare('SELECT COUNT(*) AS n FROM feature_votes').get().n, 1)
    mounted.app.unmount()
    mounted = mount()
    await settle()
    assert.equal(find(mounted.root, target => target.type === 'button').props.disabled, true)
    assert.match(allText(mounted.root), /此 IP 已投票/)
  } finally { mounted?.app.unmount(); globalThis.fetch = originalFetch; db.close() }
})

test('mounted component with no live service shows unopened status without a fake count or vote button', async () => {
  const originalFetch = globalThis.fetch
  globalThis.fetch = async () => Response.json({ apiBaseUrl: '' })
  const root = node('root')
  const app = renderer.createApp(Component, { featureId: 'deferred-read-deletion' })
  app.provide('test-language', ref('zh-CN'))
  try {
    app.mount(root)
    await settle()
    assert.match(allText(root), /投票尚未开放/)
    assert.equal(find(root, target => target.type === 'button'), undefined)
    assert.equal(find(root, target => target.type === 'strong'), undefined)
  } finally { app.unmount(); globalThis.fetch = originalFetch }
})
