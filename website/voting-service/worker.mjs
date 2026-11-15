const features = ['deferred-read-deletion']

function canonicalIp(raw) {
  if (!raw || raw !== raw.trim()) return null
  if (/^(?:\d{1,3}\.){3}\d{1,3}$/.test(raw)) {
    const parts = raw.split('.')
    return parts.every(part => Number(part) <= 255 && String(Number(part)) === part) ? raw : null
  }
  if (!raw.includes(':') || /[^a-fA-F0-9:.]/.test(raw)) return null
  try {
    const normalized = new URL(`http://[${raw}]/`).hostname.slice(1, -1)
    const mapped = /^::ffff:([a-f0-9]{1,4}):([a-f0-9]{1,4})$/.exec(normalized)
    if (!mapped) return normalized
    const high = Number.parseInt(mapped[1], 16)
    const low = Number.parseInt(mapped[2], 16)
    return [high >>> 8, high & 255, low >>> 8, low & 255].join('.')
  } catch { return null }
}

async function voterHash(secret, featureId, ip) {
  const encoder = new TextEncoder()
  const key = await crypto.subtle.importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'])
  const signature = await crypto.subtle.sign('HMAC', key, encoder.encode(`${featureId}\n${ip}`))
  return [...new Uint8Array(signature)].map(byte => byte.toString(16).padStart(2, '0')).join('')
}

export default {
  async fetch(request, env) {
    const origin = request.headers.get('Origin')
    const allowedOrigin = env.ALLOWED_ORIGIN || 'https://altaircardinal.github.io'
    const headers = { 'Cache-Control': 'no-store', Vary: 'Origin', 'X-Content-Type-Options': 'nosniff' }
    if (origin === allowedOrigin) headers['Access-Control-Allow-Origin'] = allowedOrigin
    const json = (body, status = 200) => Response.json(body, { status, headers })
    if (origin && origin !== allowedOrigin) return json({ error: 'origin_not_allowed' }, 403)
    if (request.method === 'OPTIONS') {
      if (origin !== allowedOrigin) return json({ error: 'origin_required' }, 403)
      return new Response(null, { status: 204, headers: {
        ...headers, 'Access-Control-Allow-Methods': 'GET, POST, OPTIONS', 'Access-Control-Allow-Headers': 'Content-Type',
      } })
    }
    const path = new URL(request.url).pathname
    const featureId = /^\/votes\/([a-z0-9-]+)$/.exec(path)?.[1]
    if (!(path === '/features' || (featureId && features.includes(featureId)))) return json({ error: 'not_found' }, 404)
    if ((path === '/features' && request.method !== 'GET') || (featureId && request.method !== 'POST')) {
      return json({ error: 'method_not_allowed' }, 405)
    }
    if (request.method === 'POST' && origin !== allowedOrigin) return json({ error: 'origin_required' }, 403)
    // Deploy directly behind Cloudflare's public edge. Never use client JSON or X-Forwarded-For.
    const ip = canonicalIp(request.headers.get('CF-Connecting-IP'))
    if (!ip || ip === '2a06:98c0:3600::103' || !env.DB || typeof env.VOTE_HMAC_SECRET !== 'string' || env.VOTE_HMAC_SECRET.length < 32) {
      return json({ error: 'voting_unavailable' }, 503)
    }
    try {
      if (path === '/features') {
        const results = await Promise.all(features.map(async id => {
          const hash = await voterHash(env.VOTE_HMAC_SECRET, id, ip)
          const row = await env.DB.prepare(
            'SELECT COUNT(*) AS count, COALESCE(MAX(voter_hash = ?), 0) AS voted FROM feature_votes WHERE feature_id = ?',
          ).bind(hash, id).first()
          return { id, count: Number(row.count), voted: Boolean(row.voted) }
        }))
        return json({ features: results })
      }
      const hash = await voterHash(env.VOTE_HMAC_SECRET, featureId, ip)
      const result = await env.DB.prepare(
        'INSERT INTO feature_votes (feature_id, voter_hash, created_at) VALUES (?, ?, ?) ON CONFLICT (feature_id, voter_hash) DO NOTHING',
      ).bind(featureId, hash, Date.now()).run()
      if (!result.success) return json({ error: 'voting_unavailable' }, 503)
      const row = await env.DB.prepare('SELECT COUNT(*) AS count FROM feature_votes WHERE feature_id = ?').bind(featureId).first()
      return json({ id: featureId, count: Number(row.count), voted: true }, result.meta.changes === 1 ? 201 : 409)
    } catch {
      return json({ error: 'voting_unavailable' }, 503)
    }
  },
}
