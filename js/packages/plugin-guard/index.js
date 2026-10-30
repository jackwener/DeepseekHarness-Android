// @dsh-android/plugin-guard — policy floor for DSH-Android (ENGINE-CONTRACT §4):
//  1. Hardline command floor for shell tools: blocked before approval and not
//     overridable (a prepended `tools/pre-execute` deny plus a monotonic
//     `ctx.tools.guard` backstop that no listener can bypass).
//  2. Approvals. Android runs without an OS sandbox, so mutating built-ins ask;
//     to keep friction low the guard auto-allows read-only shell commands and
//     file writes inside the app's own HOME/TMPDIR, never asks for `sensitive`
//     device reads in a conversation, applies the conversation's
//     permission preset (`android/session/permission`, 仅可查看 refuses up front),
//     and resolves every `ask` itself so a refusal tells the model why.
//  3. Token / cost budgets from the bridge's `budget/update`, metered from the
//     durable usage that dsh records on each assistant settlement (the same
//     source dsh-token-meter folds); a turn over budget is refused at
//     `agent/pre-step`.
//  4. System-prompt section describing the phone context (lock-screen claims
//     derived from the device tools' metadata), plus a runtime-context note for
//     unattended automation runs (`auto_…` sessions), whose questions are
//     answered automatically.
//  5. dsh runtime warnings/errors (ctx.logger) mirrored to stderr = engine.log.
import { mkdirSync, readFileSync, realpathSync, renameSync, writeFileSync } from 'node:fs'
import { homedir, tmpdir } from 'node:os'
import { basename, dirname, join, posix } from 'node:path'
import { format } from 'node:util'
import { checkCommand, isReadOnlyCommand, refusalText } from './hardline.js'
import { setupExperience } from './experience.js'

export { checkCommand, isReadOnlyCommand, refusalText } from './hardline.js'

export const name = 'android-guard'
export const inject = ['tools']

/** Shell-like tools whose `command` (or `text`) argument is checked. */
const SHELL_TOOLS = new Map([
  ['bash', 'command'], ['pwsh', 'command'], ['terminal_send', 'text'], ['terminal_open', 'command'],
])
/** Built-in file tools whose `file_path` may be auto-allowed inside the app's own directories. */
const FILE_WRITE_TOOLS = new Set(['write', 'edit'])

/** dsh / app permission presets (app PermissionPreset ids). */
export const PRESETS = Object.freeze({ readOnly: 'read-only', ask: 'workspace-write', full: 'danger-full-access' })
const PRESET_IDS = new Set(Object.values(PRESETS))
const AUTO_PREFIX = 'auto_'

/** Estimated CNY per 1M tokens; override with the `prices` config. */
const DEFAULT_PRICES = { default: { input: 2, cacheRead: 0.2, output: 3 } }
const LEDGER_VERSION = 2

// SDK mode reserves stdout for JSON-RPC; plugin diagnostics go to stderr (the app logs it).
function log(_ctx, level, message) {
  process.stderr.write(`[android-guard] ${level === 'info' ? '' : level + ': '}${message}\n`)
}

function localDay(date = new Date()) {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

/** The id the app knows for a (possibly subagent) session: its root. */
function rootSessionId(ctx, agent) {
  let session = agent?.session
  if (session === undefined) return undefined
  const seen = new Set()
  while (session.header?.parentSession !== undefined && !seen.has(String(session.id))) {
    seen.add(String(session.id))
    const parentId = String(session.header.parentSession)
    const parent = ctx.get('agents')?.list?.().find(a => String(a.session.id) === parentId)
    if (parent === undefined) return parentId
    session = parent.session
  }
  return String(session.id)
}

function rootOfSession(ctx, session) {
  const agent = ctx.get('agents')?.list?.().find(a => a.session === session || String(a.session.id) === String(session.id))
  if (agent !== undefined) return rootSessionId(ctx, agent)
  return session.header?.parentSession !== undefined ? String(session.header.parentSession) : String(session.id)
}

function usageOf(event) {
  if (event?.type !== 'assistant/message' && event?.type !== 'assistant/attempt') return undefined
  const data = event.data ?? {}
  if (data.usage !== undefined) return data.usage
  const chunks = Array.isArray(data.stream) ? data.stream : Array.isArray(data.stream?.chunks) ? data.stream.chunks : []
  for (let i = chunks.length - 1; i >= 0; i -= 1) {
    const c = chunks[i]
    if (c?.type === 'usage' && c.usage) return c.usage
  }
  return undefined
}

/** Persistent per-day and per-session usage tally ($DSH_HOME/android/budget.json). */
class Ledger {
  constructor(file) {
    this.file = file
    this.state = { v: LEDGER_VERSION, day: localDay(), dailyTokens: 0, dailyCostCny: 0, sessions: {} }
    try {
      const loaded = JSON.parse(readFileSync(file, 'utf8'))
      if (loaded && typeof loaded === 'object') {
        // v1 metered cache reads at full weight; its token tallies are not comparable (cost is).
        const tokens = loaded.v === LEDGER_VERSION
        const sessions = Object.fromEntries(Object.entries(loaded.sessions ?? {}).map(([id, e]) => [id, tokens ? e : { ...e, tokens: 0 }]))
        this.state = { ...this.state, ...loaded, v: LEDGER_VERSION, dailyTokens: tokens ? loaded.dailyTokens ?? 0 : 0, sessions }
      }
    } catch {}
    this.rollover()
    this.timer = undefined
  }

  rollover() {
    const today = localDay()
    if (this.state.day !== today) {
      this.state.day = today
      this.state.dailyTokens = 0
      this.state.dailyCostCny = 0
    }
  }

  add(sessionId, tokens, costCny) {
    this.rollover()
    this.state.dailyTokens += tokens
    this.state.dailyCostCny += costCny
    const entry = this.state.sessions[sessionId] ?? { tokens: 0, costCny: 0, at: 0 }
    entry.tokens += tokens
    entry.costCny += costCny
    entry.at = Date.now()
    this.state.sessions[sessionId] = entry
    const ids = Object.keys(this.state.sessions)
    if (ids.length > 500) {
      ids.sort((a, b) => this.state.sessions[a].at - this.state.sessions[b].at)
      for (const id of ids.slice(0, ids.length - 500)) delete this.state.sessions[id]
    }
    this.scheduleSave()
  }

  session(sessionId) { return this.state.sessions[sessionId] ?? { tokens: 0, costCny: 0 } }

  scheduleSave() {
    if (this.timer !== undefined) return
    this.timer = setTimeout(() => { this.timer = undefined; this.save() }, 1000)
    this.timer.unref?.()
  }

  save() {
    if (this.timer !== undefined) { clearTimeout(this.timer); this.timer = undefined }
    try {
      mkdirSync(dirname(this.file), { recursive: true })
      const tmp = `${this.file}.${process.pid}.tmp`
      writeFileSync(tmp, JSON.stringify(this.state))
      renameSync(tmp, this.file)
    } catch {}
  }
}


// ── model-facing text ────────────────────────────────────────────────────────

/** Groups whose tools drive the UI (always need the screen) — never examples of lock-free tools. */
const UI_GROUPS = new Set(['screen', 'browser'])
/** Common phone tasks, preferred as examples when the descriptor allows them. */
const EXAMPLE_ORDER = ['alarm_set', 'volume_set', 'calendar_add', 'contacts_search', 'file_list', 'dnd_set', 'flashlight', 'wifi_toggle', 'media_control', 'rule_create']

/** Up to `max` registered device tools that run while the phone is locked (needsUnlock:false), from metadata. */
export function lockFreeExamples(descriptors, max = 5) {
  const free = descriptors.filter(d => d.needsUnlock !== true && !UI_GROUPS.has(d.group) && d.risk !== 'dangerous' && !d.name.startsWith('file_')).map(d => d.name)
  const ranked = [...EXAMPLE_ORDER.filter(n => free.includes(n)), ...free.filter(n => !EXAMPLE_ORDER.includes(n)).sort()]
  return ranked.slice(0, max)
}

/** How to operate other apps' screens; only when the screen tools are registered. */
export function screenSection(descriptors = []) {
  if (!descriptors.some(d => d.group === 'screen')) return ''
  const apps = descriptors.some(d => d.group === 'apps')
  const where = apps
    ? 'You work on your own background screen by default: app_launch / app_open_link open apps there and screen_* tools act there, so the user keeps using the phone.'
    : 'Once this conversation has its own background screen, screen_* tools act there by default, so the user keeps using the phone.'
  const guide = descriptors.some(d => d.name === 'app_guide')
    ? 'The first time you enter an app DSH has a guide for, the guide (facts about its UI) is appended to that result; app_guide(app) looks one up at any time.'
    : 'The first time you enter an app DSH has a guide for, the guide (facts about its UI) is appended to that result.'
  const notes = descriptors.some(d => d.name === 'app_note')
    ? '- App notes are UI facts earlier runs recorded on this phone; they are shown with the guide. After you find a non-obvious entry or hit a trap in an app, record ONE concise factual note with app_note (UI facts only: no personal data, nothing specific to this task). ' +
      'When you used a note, confirm it if it held, update or remove it if it was wrong.\n'
    : ''
  const compound = descriptors.some(d => d.name === 'phone_run')
    ? '- Use phone_run for a short, predictable sequence of native app steps. Match targets by text/resourceId/role; add wait/assert at asynchronous boundaries. It stops at uncertainty and reports completed steps: inspect the current screen and plan only what remains, never replay the whole sequence.\n'
    : ''
  const collect = descriptors.some(d => d.name === 'phone_collect')
    ? '- Use phone_collect for reading a scrolling native app list across screens. Set the content region/container and bounds; inspect stopReason/truncated before claiming the list is complete.\n'
    : ''
  return `Operating apps on screen:
- ${where} Pass screen: "main" only to see or act on what the user is looking at ("how do I reply to this?"). Without Shizuku everything happens on the user's screen; the result says so.
- Login, captcha, payment, a page that blocks screenshots, or a choice only the user can make: call hand_to_user(reason, kind) instead of guessing or typing credentials, then read the screen again when it returns.
- Every action reports its effect. "Effect: nothing changed on screen." means the action did nothing: pick another element or approach, do not repeat it. If the app says you are going too fast or hit a limit, stop and tell the user. "Effect: switched to <app>" means another app came to the front.
- A loop-guard warning in a result means you are repeating yourself without progress: change approach, or stop and report what blocks you. A refused call means stop and report.
- Waits before sending, posting or replying are deliberate pacing so the app does not flag you as a bot; they are normal.
- ${guide}
${compound}${collect}${notes}
`
}

/** The English system-prompt section (the model replies in the user's language). */
export function systemPromptSection(locale, descriptors = [], { homeWrites = false, shell = true, deviceTools = true } = {}) {
  const lang = locale ? `The phone's language is ${locale}. Reply in the language the user writes in; when unsure, use ${locale}.` : 'Reply in the language the user writes in.'
  const examples = lockFreeExamples(descriptors)
  if (descriptors.some(d => d.name.startsWith('file_') && d.needsUnlock !== true)) examples.push('file_*')
  const tier1 = examples.length > 0
    ? `System API tools (e.g. ${examples.join(', ')}): the direct way; those whose description does not say "needs the phone unlocked" also work while the phone is locked.`
    : 'System API tools (files, settings, contacts, calendar, …): the direct way; those whose description does not say "needs the phone unlocked" also work while the phone is locked.'
  return `# Running on the user's Android phone

You run inside the DSH app on the user's own Android phone, not on a server or developer machine. ${deviceTools ? `The device tools (files, apps, screen, system settings, notifications, contacts/messages, media, clipboard, automation rules) act on this phone through the app; some groups may be missing because the user has not enabled them.

Tool priority — always prefer the most direct, least fragile path:
1. ${tier1}
2. Intents and deep links (app_open_link, app_send_intent, share_to_app).
3. Shizuku-backed tools (wifi_toggle, bluetooth_toggle, setting_put) when available.
4. Screen automation (screen_read, screen_tap, screen_type, …) only when nothing above can do the job; it needs the phone unlocked and is slow and brittle.
Each device tool's description ends with its policy line. A tool marked "needs the phone unlocked" waits until the user unlocks the phone; tell the user when that happens instead of reporting the task as done.` : 'This conversation has no device tools: you cannot act on the phone here.'}

Approvals and safety:
- Read-only tools and read-only shell commands run directly${homeWrites ? ', and so do file writes inside your private home and temp folders (except in 仅可查看 mode)' : ''}. Other actions that change the phone ask the user, depending on the permission mode the user picked for this conversation; a read of private data (messages, contacts, notifications, clipboard, …) asks once per conversation.
- If the user rejects a call, do not retry it — ask what they want instead. If a call is refused because the conversation is in 仅可查看 (read-only) mode, tell the user and ask them to switch the permission mode if they want the change made.
- Before deleting, moving or overwriting files, state exactly which files and folders are affected (list them, or give the count and paths) and confirm the scope with the user unless they already named the exact items. Prefer the narrowest scope; never act on whole storage roots.
- The app hard-blocks some shell commands (wiping storage roots or system directories, formatting or writing block devices, reboot/shutdown, fork bombs, executing decoded payloads, uninstalling system packages, deleting system settings): such a call is refused before it runs and returns HARDLINE_BLOCKED. Never try to work around a block.

${screenSection(descriptors)}${shell ? "Shell: the bash tool runs Android's /system/bin/sh (mksh) with toybox utilities, not GNU bash/coreutils; there is no root. " : ''}The user's shared storage is /sdcard (= /storage/emulated/0). Your working directory is the app's private home folder.

${lang} Keep replies short and suited to a phone screen.`
}

/** Runtime-context note for an unattended automation run (`auto_…` session). */
export function unattendedContext(descriptors = []) {
  const locked = descriptors.filter(d => d.needsUnlock === true).map(d => d.name).sort()
  return `Unattended automation run: an automation rule started this conversation and nobody is watching it.
- Do not ask the user anything; questions are answered automatically with "nobody is available". Decide with the information you have and state any assumption in your result.
- Only the tools the rule's owner approved run; any other call that needs approval is refused automatically. Do not retry a refused call — finish without it and say it was needed.
- ${locked.length > 0 ? `Tools that need the phone unlocked (${locked.join(', ')}) wait until someone unlocks it, possibly for many minutes;` : 'Tools that need the phone unlocked wait until someone unlocks it, possibly for many minutes;'} use them only when the task cannot be done otherwise.
- Finish with one short paragraph saying what was done (or why not); it is shown to the user as a notification.`
}

const UNATTENDED_ANSWER = 'Nobody is available to answer: this is an unattended automation run. Proceed with your best judgment and state your assumption in the result, or stop if the task cannot be done safely without an answer.'

export function readOnlyRefusal(toolName) {
  return `The conversation's permission mode is 仅可查看 (read-only): it does not allow "${toolName}" or any other action that changes files or the phone or reads private data, so the call was not run. ` +
    'Do not retry it or work around it with another tool. Tell the user what you need to do and ask them to switch the permission mode (操作需确认 or 完全权限, in the composer) if they want it done.'
}

function rejectionText(toolName, { unattended, preset }) {
  if (unattended) return `This unattended automation run may only use the tools its rule was approved for; "${toolName}" is not one of them, so the call was refused. Do not retry it; finish without it and say in your result that it was needed.`
  if (preset === PRESETS.readOnly) return readOnlyRefusal(toolName)
  return `The call to "${toolName}" was not approved: the user declined it${preset === undefined ? ', or the conversation is in 仅可查看 (read-only) mode, which refuses every action that needs approval' : ''}. ` +
    'Do not retry it. Tell the user what you could not do and ask what they want instead (if the mode is read-only, they can switch it to 操作需确认 or 完全权限).'
}

// ── helpers ──────────────────────────────────────────────────────────────────

/** Resolve symlinks of the deepest existing ancestor so `home/link/x` cannot escape a root. */
function realish(p) {
  const rest = []
  let cur = p
  for (;;) {
    try { return join(realpathSync(cur), ...rest.reverse()) } catch {}
    const parent = dirname(cur)
    if (parent === cur) return p
    rest.push(basename(cur))
    cur = parent
  }
}

const isUnder = (p, dir) => p === dir || p.startsWith(dir.endsWith('/') ? dir : dir + '/')

function argText(args) {
  let text = ''
  try { text = typeof args?.command === 'string' ? args.command : JSON.stringify(args ?? {}) } catch {}
  return text.length > 2000 ? text.slice(0, 2000) + '…' : text
}

/** Bounded Map: drops the oldest entries past `max`. */
function remember(map, key, value, max = 500) {
  map.delete(key)
  map.set(key, value)
  while (map.size > max) map.delete(map.keys().next().value)
}

export function apply(ctx, config = {}) {
  const askTools = new Set(Array.isArray(config.askTools) ? config.askTools : ['bash', 'write', 'edit'])
  const prices = { ...DEFAULT_PRICES, ...(config.prices ?? {}) }
  const locale = typeof config.locale === 'string' ? config.locale : (process.env.DSH_ANDROID_LOCALE ?? '')
  const home = process.env.DSH_HOME || join(homedir(), '.dsh')
  const ledger = new Ledger(config.ledgerPath ?? join(home, 'android', 'budget.json'))
  const writableRoots = [...new Set([process.env.HOME, tmpdir()].filter(d => typeof d === 'string' && d.startsWith('/')).map(d => realish(d)))]
  let budget = {}
  /** root session id → permission preset (from the app; unknown until it says). */
  const presets = new Map()
  /** Whether the app reports permission modes at all (HOME writes are only auto-allowed then). */
  let presetsReported = false

  const bridge = () => ctx.get('androidBridge')
  const descriptors = () => { try { return bridge()?.tools?.() ?? [] } catch { return [] } }
  const isUnattended = root => typeof root === 'string' && root.startsWith(AUTO_PREFIX)
  const sessionCwd = agent => {
    try { return agent === undefined ? undefined : ctx.get('workingDirectory')?.get(agent.session) } catch { return undefined }
  }

  // ── 5. dsh runtime warnings → engine.log ───────────────────────────────────
  // SDK mode has no console exporter; without this, retries, compaction / spill / attachment
  // failures and fallbacks are invisible in the log users send back.
  ctx.logger?.exporter?.({
    levels: { default: 2 },
    export: message => {
      if (message.type !== 'warn' && message.type !== 'error') return
      let text
      try { text = format(...message.args) } catch { text = String(message.args?.[0]) }
      if (text.length > 4000) text = text.slice(0, 4000) + '…'
      process.stderr.write(`[dsh:${message.name}] ${message.type}: ${text}\n`)
    },
  })

  // ── 2a. permission preset from the app (SDK extension, patch 10) ───────────
  const methods = new Map([
    ['android/session/permission', params => {
      const sessionId = params?.sessionId
      const preset = params?.preset
      if (typeof sessionId !== 'string' || sessionId === '') throw new TypeError('android/session/permission requires a sessionId string')
      if (preset !== null && !PRESET_IDS.has(preset)) throw new TypeError(`android/session/permission: unknown preset ${JSON.stringify(preset)}`)
      if (preset === null) presets.delete(sessionId)
      else remember(presets, sessionId, preset)
      presetsReported = true
      return {}
    }],
  ])
  ctx.on('sdk-extensions/resolve', method => methods.get(method))

  // ── 1. Hardline floor ──────────────────────────────────────────────────────
  const hardlineVerdict = exec => {
    const field = SHELL_TOOLS.get(exec.name)
    if (field === undefined) return undefined
    const text = exec.arguments?.[field]
    if (typeof text !== 'string') return undefined
    const base = sessionCwd(exec.agent) ?? process.env.HOME
    const workdir = exec.arguments?.workdir
    const cwd = typeof workdir === 'string' && workdir !== '' ? posix.resolve(base ?? '/', workdir) : base
    return checkCommand(text, { cwd })
  }

  /** Mutating built-ins that need no approval: read-only shell, writes inside HOME/TMPDIR. */
  const autoAllowed = (exec, preset, unattended) => {
    const field = SHELL_TOOLS.get(exec.name)
    if (field !== undefined) return isReadOnlyCommand(exec.arguments?.[field])
    if (FILE_WRITE_TOOLS.has(exec.name) && !unattended && (preset === PRESETS.ask || preset === PRESETS.full)) {
      const file = exec.arguments?.file_path
      if (typeof file !== 'string' || file.trim() === '') return false
      const abs = posix.resolve(sessionCwd(exec.agent) ?? process.env.HOME ?? '/', file)
      const real = realish(abs)
      return writableRoots.some(root => isUnder(real, root))
    }
    return false
  }

  /** Resolve an `ask` through dsh's approval service here, so a refusal can say why. */
  const resolveAsk = async (exec, ask, { root, unattended, preset }) => {
    const approval = ctx.get('approval')
    if (approval === undefined || exec.agent === undefined) return ask // dsh's own degrade path
    const reason = ask.reason
    const outcome = await approval.request({
      agent: exec.agent,
      toolName: exec.name,
      callId: exec.callId,
      ...reason === undefined ? {} : { reason },
      ...ask.displayReason === undefined ? {} : { displayReason: ask.displayReason },
      signal: exec.signal,
    })
    switch (outcome) {
      case 'allowed-once':
        return { kind: 'allow' }
      case 'rejected':
        return { kind: 'deny', reason: rejectionText(exec.name, { unattended, preset: presets.get(root) ?? preset }) }
      case 'cancelled':
        return exec.signal?.aborted ? { kind: 'cancel' } : { kind: 'deny', reason: `approval for tool "${exec.name}" was cancelled` }
      default:
        return { kind: 'deny', reason: `tool "${exec.name}" requires approval, but no approval channel is available` }
    }
  }

  ctx.on('tools/pre-execute', async (exec, next) => {
    const v = hardlineVerdict(exec)
    if (v !== undefined) {
      log(ctx, 'warn', `hardline blocked ${exec.name} (${v.rule})`)
      return { kind: 'deny', reason: refusalText(v), info: { name: 'HardlineBlocked', code: 'HARDLINE_BLOCKED' } }
    }
    // ── 2. approvals ──────────────────────────────────────────────────────────
    const decision = await next()
    const root = rootSessionId(ctx, exec.agent)
    const unattended = isUnattended(root)
    const preset = unattended ? undefined : presets.get(root)
    let ask
    if (decision?.kind === 'ask') ask = decision
    else if (decision?.kind === 'allow' && askTools.has(exec.name)) {
      if (autoAllowed(exec, preset, unattended)) return decision
      ask = { kind: 'ask', reason: `${exec.name}: ${argText(exec.arguments)}` }
    } else return decision
    const sensitive = bridge()?.descriptorOf?.(exec.name)?.risk === 'sensitive'
    // Reading the phone's own data (location, notifications, …) never asks in a conversation.
    if (sensitive && !unattended) return { kind: 'allow' }
    if (preset === PRESETS.readOnly) return { kind: 'deny', reason: readOnlyRefusal(exec.name) }
    return resolveAsk(exec, ask, { root, unattended, preset })
  }, { prepend: true })
  // Backstop: guards run after every pre-execute decision and cannot be overridden.
  if (typeof ctx.tools.guard === 'function') {
    ctx.tools.guard(exec => {
      const v = hardlineVerdict(exec)
      return v === undefined ? undefined : refusalText(v)
    })
  }

  // Unattended runs: nobody can answer a question (ask_user_question, plan mode, …).
  ctx.on('user-questions/request', async (request, next) => {
    if (!isUnattended(rootSessionId(ctx, request?.agent))) return next()
    log(ctx, 'info', `auto-answered ${request.questions?.length ?? 0} question(s) in an unattended run`)
    return { answers: (request.questions ?? []).map(q => ({ id: q.id, selected: [], custom: UNATTENDED_ANSWER })) }
  }, { prepend: true })

  // ── 3. Budgets ─────────────────────────────────────────────────────────────
  // Tokens are metered input-equivalent: uncached input + cache writes + output at full weight,
  // cache reads weighted by their price ratio (cacheRead / input, 0.1 by default). A cached
  // prefix re-read on every step would otherwise count ~10× its cost (ENGINE-CONTRACT §3).
  const priceFor = model => prices[model] ?? prices.default
  const overBudget = sessionId => {
    ledger.rollover()
    const s = ledger.session(sessionId)
    if (budget.sessionTokenLimit !== undefined && s.tokens >= budget.sessionTokenLimit) return { kind: 'sessionTokenLimit', used: s.tokens, limit: budget.sessionTokenLimit }
    if (budget.dailyTokenLimit !== undefined && ledger.state.dailyTokens >= budget.dailyTokenLimit) return { kind: 'dailyTokenLimit', used: ledger.state.dailyTokens, limit: budget.dailyTokenLimit }
    if (budget.dailyCostLimitCny !== undefined && ledger.state.dailyCostCny >= budget.dailyCostLimitCny) return { kind: 'dailyCostLimitCny', used: Math.round(ledger.state.dailyCostCny * 1e4) / 1e4, limit: budget.dailyCostLimitCny }
    return undefined
  }
  const meter = (root, usage, model) => {
    const input = Number(usage.inputTokens ?? 0)
    const output = Number(usage.outputTokens ?? 0)
    const cacheRead = Number(usage.cacheReadTokens ?? 0)
    const cacheWrite = Number(usage.cacheWriteTokens ?? 0)
    const p = priceFor(model)
    const cacheWeight = p.input > 0 ? Math.min(1, Math.max(0, p.cacheRead / p.input)) : 1
    const tokens = Math.round(input + cacheWrite + output + cacheRead * cacheWeight)
    if (!Number.isFinite(tokens) || tokens <= 0) return
    const cost = ((input + cacheWrite) * p.input + cacheRead * p.cacheRead + output * p.output) / 1e6
    ledger.add(root, tokens, Number.isFinite(cost) ? cost : 0)
  }
  ctx.on('session/event', (session, event) => {
    const usage = usageOf(event)
    if (usage !== undefined) meter(rootOfSession(ctx, session), usage, session.requestHeader?.()?.config?.model)
  })
  // ── 3b. App 经验库: routes and post-turn reflection (experience.js) ──────────
  setupExperience(ctx, { bridge, rootOf: agent => rootSessionId(ctx, agent), meter, withinBudget: root => overBudget(root) === undefined, log })
  ctx.on('agent/pre-step', async (input, next) => {
    const sessionId = rootSessionId(ctx, input.agent)
    const over = sessionId === undefined ? undefined : overBudget(sessionId)
    if (over === undefined) return next()
    log(ctx, 'warn', `budget exceeded for ${sessionId}: ${over.kind} ${over.used} >= ${over.limit}; refusing the step`)
    try {
      bridge()?.notify?.('budget/exceeded', { sessionId, ...over })
    } catch {}
    return { kind: 'reject' }
  }, { prepend: true })
  ctx.inject(['androidBridge'], child => {
    const b = child.get('androidBridge')
    budget = b.budget ?? {}
    const off = b.onBudget(next => { budget = next })
    child.effect(() => off, 'android-guard.budget')
  })
  ctx.effect(() => () => ledger.save(), 'android-guard.ledger')

  // ── 4. System prompt ───────────────────────────────────────────────────────
  ctx.inject(['systemPrompt'], child => {
    const sp = child.get('systemPrompt')
    // The section text depends only on descriptor metadata and on which tools the calling agent
    // sees (a session mode, plugin-mode, may hide device groups or the shell), so it is stable
    // while the tool set is.
    sp.variable('android_phone_context', context => {
      const scope = context?.scope
      const visible = name => { try { return ctx.tools.get(name, scope) !== undefined } catch { return true } }
      const all = descriptors()
      const shown = scope === undefined ? all : all.filter(d => visible(d.name))
      return systemPromptSection(locale, shown, {
        homeWrites: presetsReported,
        shell: scope === undefined || visible('bash'),
        deviceTools: shown.length > 0 || all.length === 0,
      })
    })
    sp.section({ name: 'android:phone-context', order: 130, text: '{{android_phone_context}}' })
    sp.context({
      name: 'android:unattended',
      order: 130,
      interpolate: false,
      text: context => context.agent !== undefined && isUnattended(rootSessionId(ctx, context.agent)) ? unattendedContext(descriptors()) : '',
    })
  })
}
