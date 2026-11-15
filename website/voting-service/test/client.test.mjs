import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readVotes, castVote } from '../../.vitepress/theme/voting-client.mjs'

const base = 'https://votes.example'
const id = 'deferred-read-deletion'

test('reads actual counts and IP voting state without local browser storage', async () => {
  const fetcher = async url => {
    assert.equal(url, base + '/features')
    return Response.json({ features: [{ id, count: 7, voted: true }] })
  }
  assert.deepEqual(await readVotes(base, fetcher), [{ id, count: 7, voted: true }])
})

test('an accepted vote and a server duplicate both return the authoritative result', async () => {
  for (const status of [201, 409]) {
    assert.deepEqual(await castVote(base, id, async (url, options) => {
      assert.equal(url, base + '/votes/' + id)
      assert.equal(options.method, 'POST')
      assert.equal(options.credentials, 'omit')
      return Response.json({ id, count: 8, voted: true }, { status })
    }), { id, count: 8, voted: true })
  }
})

test('unconfigured service does not make requests or fabricate a zero count', async () => {
  let requests = 0
  await assert.rejects(readVotes('', async () => { requests++; return Response.json({}) }), /not_configured/)
  assert.equal(requests, 0)
})

test('HTTP, network and malformed-success failures remain visible failures', async () => {
  await assert.rejects(readVotes(base, async () => Response.json({ error: 'unavailable' }, { status: 503 })))
  await assert.rejects(readVotes(base, async () => { throw new Error('offline') }), /offline/)
  await assert.rejects(readVotes(base, async () => Response.json({ features: [{ id, count: -1, voted: false }] })))
  await assert.rejects(castVote(base, id, async () => Response.json({ id, count: 1, voted: false }, { status: 201 })))
})

test('non-HTTPS service addresses cannot receive a vote', async () => {
  for (const url of ['http://votes.example', 'javascript:alert(1)', 'https://user:password@votes.example']) {
    await assert.rejects(castVote(url, id, async () => { throw new Error('must not fetch') }), /invalid_endpoint/)
  }
})
