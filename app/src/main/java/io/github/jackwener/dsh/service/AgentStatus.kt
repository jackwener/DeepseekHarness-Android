package io.github.jackwener.dsh.service

import io.github.jackwener.dsh.core.capability.LiveCall
import io.github.jackwener.dsh.core.session.Block
import io.github.jackwener.dsh.core.session.LoggedEvent
import io.github.jackwener.dsh.core.session.Projector
import io.github.jackwener.dsh.core.session.RowStatus
import io.github.jackwener.dsh.core.session.SessionLive
import io.github.jackwener.dsh.core.session.ToolState
import io.github.jackwener.dsh.core.session.Turn
import io.github.jackwener.dsh.core.session.TurnStatus
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What the floating ball's status capsule says about a conversation (the service turns it into text). */
sealed interface CapsuleStatus {
    enum class Need { Takeover, Question, Permission, Approval }
    enum class End { Done, Stopped, Failed }

    /** A card waits for the user; [detail]: the takeover reason, the question, or the tool to approve. */
    data class NeedsYou(val need: Need, val detail: String?) : CapsuleStatus
    /** [step]: the tool executing now (null: the model is thinking); [background]: the turn works on its agent screen. */
    data class Working(val step: Block.Tool?, val background: Boolean) : CapsuleStatus
    data class Ended(val end: End) : CapsuleStatus
}

/** Status selection for the floating ball's capsule and the control border (FloatingBallService). */
object AgentStatus {
    /** How long the capsule shows a turn's outcome before it collapses back into the ball. */
    const val ENDED_MS = 4_000L
    /** The border stays between steps that act on the user's screen (the model thinks in between). */
    const val BORDER_LINGER_MS = 10_000L

    private val BACKGROUND_TOOLS = setOf("app_launch", "app_open_link", "app_send_intent")

    /** Status per conversation: a subagent's (`root/child`) counts for its root; the most urgent wins. */
    fun roots(status: Map<String, RowStatus>): Map<String, RowStatus> {
        val out = HashMap<String, RowStatus>()
        status.forEach { (id, st) -> out.merge(id.substringBefore('/'), st) { a, b -> if (b.ordinal < a.ordinal) b else a } }
        return out
    }

    private fun waiting(s: RowStatus?) = s == RowStatus.Approval || s == RowStatus.Question

    /** The conversation the capsule follows: one waiting for the user first, else the running one it already shows. */
    fun focus(status: Map<String, RowStatus>, previous: String?): String? {
        val roots = roots(status)
        if (waiting(roots[previous])) return previous
        roots.entries.firstOrNull { waiting(it.value) }?.let { return it.key }
        if (roots[previous] == RowStatus.Running) return previous
        return roots.entries.firstOrNull { it.value == RowStatus.Running }?.key
    }

    /** [hasAgentScreen]: the conversation has a live agent screen. Null when there is nothing to say. */
    fun of(live: SessionLive, hasAgentScreen: Boolean): CapsuleStatus? {
        live.takeover?.let { return CapsuleStatus.NeedsYou(CapsuleStatus.Need.Takeover, it.reason) }
        live.question?.let { q -> return CapsuleStatus.NeedsYou(CapsuleStatus.Need.Question, q.questions.firstOrNull()?.text) }
        live.permission?.let { return CapsuleStatus.NeedsYou(CapsuleStatus.Need.Permission, null) }
        live.approval?.let { return CapsuleStatus.NeedsYou(CapsuleStatus.Need.Approval, it.toolName) }
        val turn = lastTurn(live.events, live.running)
        if (!live.running) return when (turn?.status) {
            null, TurnStatus.Running -> null
            TurnStatus.Completed -> CapsuleStatus.Ended(CapsuleStatus.End.Done)
            TurnStatus.Stopped -> CapsuleStatus.Ended(CapsuleStatus.End.Stopped)
            TurnStatus.Failed, TurnStatus.MaxTokens -> CapsuleStatus.Ended(CapsuleStatus.End.Failed)
        }
        val tools = turn?.blocks.orEmpty().filterIsInstance<Block.Tool>()
        val last = tools.lastOrNull()
        val background = hasAgentScreen && last != null && (last.name.startsWith("screen_") || last.name.startsWith("phone_") || last.name in BACKGROUND_TOOLS) &&
            (last.args?.get("screen") as? JsonPrimitive)?.contentOrNull != "main"
        return CapsuleStatus.Working(tools.lastOrNull { it.state == ToolState.Running }, background)
    }

    /** The newest turn, projected from its own events only (the log can be long). */
    fun lastTurn(events: List<LoggedEvent>, running: Boolean): Turn? {
        val from = events.indexOfLast { it.type == "turn/start" }.coerceAtLeast(0)
        return Projector.conversation(events.subList(from, events.size), running).turns.lastOrNull()
    }

    /**
     * Until when (elapsedRealtime) the control border shows, null when it is off: while a screen call
     * works on the user's screen, and [BORDER_LINGER_MS] after the last one that acted there
     * ([lastActed]: conversation → end time), as long as that turn runs and does not wait for the user.
     * Calls without a session (automation steps) count only while they execute.
     */
    fun borderUntil(calls: Collection<LiveCall>, lastActed: Map<String, Long>, status: Map<String, RowStatus>, now: Long): Long? {
        val roots = roots(status)
        if (calls.any { it.mainScreen && (it.sessionId.isEmpty() || roots[it.sessionId.substringBefore('/')] == RowStatus.Running) }) return Long.MAX_VALUE
        return lastActed.entries.filter { roots[it.key] == RowStatus.Running }.maxOfOrNull { it.value + BORDER_LINGER_MS }?.takeIf { it > now }
    }
}
