// App 经验库, engine side: after a turn that operated an app, tell the app the turn ended
// (`experience/turn`, it records tap routes and decides whether to reflect), then — only for a
// turn that ended normally — run ONE short tool-free model call over a compact trace of the turn
// and send the 0–3 UI facts it finds back as `experience/notes`. Fire-and-forget: never blocks
// the user, failures are silent.

/** Trace budget (characters) for the reflection prompt; the oldest lines go first. */
export const TRACE_LIMIT = 3000
export const MAX_NOTES = 3
const KINDS = new Set(['entry', 'trap', 'fact'])
const REFLECT_TIMEOUT_MS = 60_000
const MAX_OUTPUT_TOKENS = 1500

const short = (s, n = 120) => {
  const t = String(s ?? '').replace(/\s+/g, ' ').trim()
  return t.length > n ? t.slice(0, n) + '…' : t
}

/** Whether a tool call belongs in the trace: the app and screen tools that operate other apps. */
export const traced = name => typeof name === 'string' && (name.startsWith('screen_') || name.startsWith('phone_') || name.startsWith('app_') || name === 'hand_to_user')

/** Only the arguments that describe the step; typed text and links stay out (they may be personal). */
function argsOf(name, a = {}) {
  switch (name) {
    case 'screen_tap': return [a.text !== undefined ? `"${short(a.text, 30)}"` : a.node, a.kind === 'long' ? 'long' : undefined, a.x !== undefined ? 'at x,y' : undefined]
    case 'screen_key': return [a.key]
    case 'screen_scroll': case 'screen_swipe': return [a.direction]
    case 'screen_type': return ['(typed text)']
    case 'app_launch': case 'app_info': return [a.package]
    case 'app_open_link': return [typeof a.uri === 'string' ? `${a.uri.split(':')[0]}:…` : undefined]
    case 'app_note': return [a.action, a.text !== undefined ? `"${short(a.text, 80)}"` : a.id]
    case 'hand_to_user': return [a.kind]
    default: return []
  }
}

const RESULT_LINE = /^(Tapped|Launched|Opened|Effect:|\[Loop guard]|Noted|Already noted|Confirmed|Updated|Removed)/

/** One trace line: `screen_tap("我的") → Tapped [我的] | Effect: …` or `→ error code: message`. */
export function traceLine(name, args, result) {
  const a = argsOf(name, args ?? {}).filter(v => v !== undefined && v !== '').join(', ')
  const text = (result?.content ?? []).filter(c => c?.type === 'text').map(c => c.text).join('\n')
  let out
  if (result?.isError) out = `error ${result.error?.info?.code ?? ''}: ${short(result.error?.message ?? text, 100)}`.replace('error :', 'error:')
  else out = text.split('\n').map(l => l.trim()).filter(l => RESULT_LINE.test(l)).slice(0, 3).map(l => short(l)).join(' | ') || 'ok'
  return `${name}(${a}) → ${out}`
}

/** Per-root trace of the current turn, capped at [TRACE_LIMIT] characters (oldest dropped). */
export class Trace {
  constructor() { this.lines = [] ; this.size = 0 }
  push(line) {
    this.lines.push(line)
    this.size += line.length + 1
    while (this.size > TRACE_LIMIT && this.lines.length > 1) this.size -= this.lines.shift().length + 1
  }
  text() { return this.lines.join('\n') }
}

/** The reflection request: a system prompt and the trace as the user message. */
export function reflectionPrompt({ label, pkg, trace }) {
  const system = `You review one finished task that an assistant did by operating the Android app ${label} (${pkg}) on a phone, and pick out at most ${MAX_NOTES} UI facts worth remembering for next time.
Keep only facts about the app's interface that would hold for any user and any task:
- entry: a non-obvious place where a feature lives (e.g. "退款在「订单详情」底部「申请售后」里，不在订单列表").
- trap: something that misled or failed before the right way was found (e.g. "首页「+」是发布，不是添加好友").
- fact: another stable fact about the UI.
Never include personal data, names, message or post contents, numbers, prices, or anything specific to this task. Skip the obvious. One sentence each, at most 120 characters, in the language of the app's UI. Fewer is better; none is fine.
Answer with JSON only: {"notes":[{"kind":"entry|trap|fact","text":"..."}]}`
  return { system, text: `Steps of the task (tool → result), oldest first:\n${trace}` }
}

/** The notes in a reflection answer (JSON, possibly fenced or with text around it); invalid entries are dropped. */
export function parseNotes(answer) {
  if (typeof answer !== 'string') return []
  const start = answer.indexOf('{')
  const end = answer.lastIndexOf('}')
  if (start < 0 || end <= start) return []
  let parsed
  try { parsed = JSON.parse(answer.slice(start, end + 1)) } catch { return [] }
  const list = Array.isArray(parsed?.notes) ? parsed.notes : []
  const out = []
  for (const n of list) {
    const text = typeof n?.text === 'string' ? n.text.replace(/\s+/g, ' ').trim() : ''
    if (text.length < 4 || text.length > 200) continue
    out.push({ kind: KINDS.has(n.kind) ? n.kind : 'fact', text })
    if (out.length >= MAX_NOTES) break
  }
  return out
}

/** One tool-free completion with the session's own route; resolves to the text, or undefined on failure. */
async function complete(llm, config, sessionId, { system, text }, onUsage) {
  const signal = AbortSignal.timeout(REFLECT_TIMEOUT_MS)
  const call = await llm.prepareCall({ provider: config.provider, model: config.model, maxTokens: MAX_OUTPUT_TOKENS }, signal)
  let out = ''
  for await (const chunk of call.stream({ ...call.config, messages: [{ role: 'user', content: [{ type: 'text', text }] }], system, sessionId, signal })) {
    if (chunk?.type === 'text-delta') out += chunk.text ?? ''
    else if (chunk?.type === 'usage' && chunk.usage) onUsage(chunk.usage)
    else if (chunk?.type === 'finish' && (chunk.reason?.kind === 'error' || chunk.reason?.kind === 'aborted')) return undefined
  }
  return out
}

/**
 * Wires the trace and the turn-end hook. [rootOf](agent) gives the app's session id of an agent;
 * [meter](root, usage, model) counts the reflection's tokens against the session's budget, which
 * [withinBudget](root) must allow.
 */
export function setupExperience(ctx, { bridge, rootOf, meter = () => {}, withinBudget = () => true, log = () => {} }) {
  /** root session id → Trace of the running turn. */
  const traces = new Map()

  ctx.on('tools/result', (exec, result) => {
    if (!traced(exec?.name)) return
    const root = rootOf(exec.agent)
    if (root === undefined) return
    let t = traces.get(root)
    if (t === undefined) { t = new Trace(); traces.set(root, t) }
    t.push(traceLine(exec.name, exec.arguments, result))
  })

  const onTurnEnd = async (session, sessionId, ok, trace) => {
    const b = bridge()
    if (typeof b?.request !== 'function') return
    const reply = await b.request('experience/turn', { sessionId, ok }, { timeoutMs: 10_000 })
    if (!ok || reply?.reflect !== true || typeof reply.package !== 'string' || !withinBudget(sessionId)) return
    const llm = ctx.get('llm')
    const config = session.requestHeader?.()?.config
    if (typeof llm?.prepareCall !== 'function' || !config?.provider || !config?.model) return
    const prompt = reflectionPrompt({ label: reply.label ?? reply.package, pkg: reply.package, trace: trace.text() })
    const answer = await complete(llm, config, session.id, prompt, usage => meter(sessionId, usage, config.model))
    const notes = parseNotes(answer)
    log(ctx, 'info', `experience: ${notes.length} note(s) for ${reply.package}`)
    if (notes.length > 0) b.notify?.('experience/notes', { sessionId, package: reply.package, notes })
  }

  ctx.on('session/event', (session, event) => {
    if (event?.type !== 'turn/end' || session?.header?.parentSession !== undefined) return
    const sessionId = String(session.id)
    const trace = traces.get(sessionId)
    traces.delete(sessionId)
    if (trace === undefined || trace.lines.length === 0) return
    onTurnEnd(session, sessionId, event.data?.reason?.kind === 'completed', trace)
      .catch(error => log(ctx, 'info', `experience: skipped (${error?.message ?? error})`))
  })
}
