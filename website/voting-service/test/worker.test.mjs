import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { DatabaseSync } from 'node:sqlite'
import { test } from 'node:test'
import worker from '../worker.mjs'

const origin = 'https://altaircardinal.github.io'
const parity = 'desktop-read-deletion-parity'
const delayed = 'deferred-read-deletion'

function fixture() {
  const sqlite = new DatabaseSync(':memory:')
  sqlite.exec(readFileSync(new URL('../migrations/0001_votes.sql', import.meta.url), 'utf8'))
  const DB = {
    prepare(sql) {
      let values = []
      return {
        bind(...args) { values = args; return this },
        async all() { return { results: sqlite.prepare(sql).all(...values) } },
        async first() { return sqlite.prepare(sql).get(...values) ?? null },
        async run() {
          const result = sqlite.prepare(sql).run(...values)
          return { success: true, meta: { changes: result.changes } }
        },
      }
    },
  }
  const env = { DB, VOTE_HMAC_SECRET: 'test-secret-with-at-least-thirty-two-characters', ALLOWED_ORIGIN: origin }
  async function request(path = '/features', { method = 'GET', ip = '203.0.113.12', headers = {}, body } = {}) {
    const response = await worker.fetch(new Request(`https://votes.example${path}`, {
      method,
      headers: { Origin: origin, 'CF-Connecting-IP': ip, ...headers },
      body,
    }), env)
    return { response, data: await response.json() }
  }
  return { sqlite, env, request }
}

test('the first vote is persisted, a repeat is rejected and another feature stays independent', async () => {
  const { request, sqlite } = fixture()
  const first = await request(`/votes/${parity}`, { method: 'POST' })
  assert.equal(first.response.status, 201)
  assert.equal(first.data.count, 1)
  assert.equal(first.data.voted, true)
  const duplicate = await request(`/votes/${parity}`, { method: 'POST' })
  assert.equal(duplicate.response.status, 409)
  assert.equal(duplicate.data.count, 1)
  assert.equal((await request(`/votes/${delayed}`, { method: 'POST' })).response.status, 201)
  assert.equal(sqlite.prepare('SELECT COUNT(*) AS n FROM feature_votes').get().n, 2)
})

test('simultaneous requests from the same IP cannot create extra votes', async () => {
  const { request } = fixture()
  const results = await Promise.all(Array.from({ length: 16 }, () => request(`/votes/${parity}`, { method: 'POST' })))
  assert.equal(results.filter(({ response }) => response.status === 201).length, 1)
  assert.equal(results.filter(({ response }) => response.status === 409).length, 15)
  const { data } = await request()
  assert.deepEqual(data.features.find(({ id }) => id === parity), { id: parity, count: 1, voted: true })
})

test('separate public IPs can vote and only keyed hashes are stored', async () => {
  const { request, sqlite } = fixture()
  await request(`/votes/${parity}`, { method: 'POST' })
  await request(`/votes/${parity}`, { method: 'POST', ip: '203.0.113.13' })
  const { data } = await request('/features', { ip: '203.0.113.14' })
  assert.deepEqual(data.features.find(({ id }) => id === parity), { id: parity, count: 2, voted: false })
  const rows = sqlite.prepare('SELECT * FROM feature_votes').all()
  assert.equal(rows[0].voter_hash.length, 64)
  assert.equal(JSON.stringify(rows).includes('203.0.113.'), false)
  assert.equal(JSON.stringify(data).includes('voter_hash'), false)
})

test('IPv6 representations and IPv4-mapped addresses share their canonical identity', async () => {
  const { request } = fixture()
  assert.equal((await request(`/votes/${parity}`, { method: 'POST', ip: '2001:db8:0:0:0:0:0:1' })).response.status, 201)
  assert.equal((await request(`/votes/${parity}`, { method: 'POST', ip: '2001:db8::1' })).response.status, 409)
  assert.equal((await request(`/votes/${parity}`, { method: 'POST', ip: '::ffff:203.0.113.12' })).response.status, 201)
  assert.equal((await request(`/votes/${parity}`, { method: 'POST', ip: '203.0.113.12' })).response.status, 409)
})

test('client payloads and forwarded IP headers cannot bypass the trusted edge IP', async () => {
  const { request } = fixture()
  await request(`/votes/${parity}`, { method: 'POST', body: JSON.stringify({ ip: '1.2.3.4' }) })
  const second = await request(`/votes/${parity}`, {
    method: 'POST', headers: { 'X-Forwarded-For': '5.6.7.8', 'X-Real-IP': '9.10.11.12' },
  })
  assert.equal(second.response.status, 409)
})

test('invalid feature, disallowed origin, absent IP and unavailable storage cannot record votes', async () => {
  const { request, env, sqlite } = fixture()
  assert.equal((await request('/votes/not-a-feature', { method: 'POST' })).response.status, 404)
  assert.equal((await request(`/votes/${parity}`, { method: 'POST', headers: { Origin: 'https://other.example' } })).response.status, 403)
  assert.equal((await request(`/votes/${parity}`, { method: 'POST', ip: '' })).response.status, 503)
  assert.equal((await request(`/votes/${parity}`, { method: 'POST', ip: 'not-an-ip' })).response.status, 503)
  env.DB = { prepare() { throw new Error('private database failure') } }
  const failed = await request(`/votes/${parity}`, { method: 'POST' })
  assert.equal(failed.response.status, 503)
  assert.equal(JSON.stringify(failed.data).includes('private database'), false)
  assert.equal(sqlite.prepare('SELECT COUNT(*) AS n FROM feature_votes').get().n, 0)
})

test('CORS preflight and responses are uncached and restricted to the public documentation origin', async () => {
  const { env, request } = fixture()
  const preflight = await worker.fetch(new Request('https://votes.example/votes/' + parity, {
    method: 'OPTIONS', headers: { Origin: origin, 'Access-Control-Request-Method': 'POST' },
  }), env)
  assert.equal(preflight.status, 204)
  assert.equal(preflight.headers.get('Access-Control-Allow-Origin'), origin)
  const { response } = await request()
  assert.equal(response.headers.get('Cache-Control'), 'no-store')
  assert.equal(response.headers.get('Access-Control-Allow-Origin'), origin)
})

test('Cloudflare cross-zone Worker placeholder cannot be mistaken for a visitor IP', async () => {
  const { request, sqlite } = fixture()
  const result = await request(`/votes/${parity}`, { method: 'POST', ip: '2a06:98c0:3600::103' })
  assert.equal(result.response.status, 503)
  assert.equal(sqlite.prepare('SELECT COUNT(*) AS n FROM feature_votes').get().n, 0)
})
