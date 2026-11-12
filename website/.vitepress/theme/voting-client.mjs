function endpoint(base) {
  if (!base) throw new Error('not_configured')
  let url
  try { url = new URL(base) } catch { throw new Error('invalid_endpoint') }
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash) throw new Error('invalid_endpoint')
  return url.href.replace(/\/$/, '')
}

function validVote(value) {
  return value && typeof value.id === 'string' && Number.isSafeInteger(value.count) && value.count >= 0 && typeof value.voted === 'boolean'
}

export async function readVotes(base, fetcher = fetch) {
  const response = await fetcher(`${endpoint(base)}/features`, { cache: 'no-store', credentials: 'omit', signal: AbortSignal.timeout(10000) })
  if (!response.ok) throw new Error('service_unavailable')
  const data = await response.json()
  if (!Array.isArray(data.features) || !data.features.every(validVote)) throw new Error('invalid_response')
  return data.features
}

export async function castVote(base, id, fetcher = fetch) {
  if (!/^[a-z0-9-]+$/.test(id)) throw new Error('invalid_feature')
  const response = await fetcher(`${endpoint(base)}/votes/${id}`, { method: 'POST', credentials: 'omit', signal: AbortSignal.timeout(10000) })
  if (response.status !== 201 && response.status !== 409) throw new Error('service_unavailable')
  const result = await response.json()
  if (!validVote(result) || result.id !== id || !result.voted) throw new Error('invalid_response')
  return result
}
