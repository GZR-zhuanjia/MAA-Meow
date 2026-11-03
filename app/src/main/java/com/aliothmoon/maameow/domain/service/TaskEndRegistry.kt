package com.aliothmoon.maameow.domain.service

import com.aliothmoon.maameow.domain.state.MaaExecutionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 任务结束唯一订阅点，避免多处各自 collect 边沿 */
class TaskEndRegistry(
    private val compositionService: MaaCompositionService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** 全局「任务自动结束时关闭游戏」；放进程级，没拉起界面的轮次也要关 */
    private val closeGameOnEnd: () -> Boolean = { false },
) {
    enum class Reason {
        /** RUNNING → IDLE/ERROR */
        NATURAL,

        /** STOPPING → IDLE/ERROR，用户手动停止 */
        MANUAL,

        /** STOPPING → IDLE/ERROR，回调侧（掉线等）中止 */
        ABORTED,

        /** STOPPING → IDLE/ERROR，到达运行时长上限，按自动结束执行收尾 */
        DURATION_LIMIT,
    }

    fun interface PendingAction {
        suspend fun run(reason: Reason)
    }

    private val _taskEnded = MutableSharedFlow<Reason>(extraBufferCapacity = 4)

    /** 常驻广播，随订阅方 scope 自动取消 */
    val taskEnded: SharedFlow<Reason> = _taskEnded.asSharedFlow()

    data class RoundEnd(val reason: Reason, val completedNormally: Boolean)

    class Round internal constructor(internal val afterGeneration: Long) {
        internal val completion = CompletableDeferred<RoundEnd>()
        suspend fun await(): RoundEnd = completion.await()
    }

    private val round = AtomicReference<Round?>(null)
    private val suppressedThrough = AtomicLong(-1)

    /** 启动前登记，不占用旧单配置的 armOnce；整轮全局收尾由链的所有者处理。 */
    fun beginRound(): Round = Round(compositionService.runGeneration).also {
        check(round.compareAndSet(null, it)) { "A profile round is already registered" }
    }

    fun releaseRound(expected: Round) {
        if (round.get() === expected) {
            suppressedThrough.accumulateAndGet(compositionService.runGeneration, ::maxOf)
            round.compareAndSet(expected, null)
        }
    }

    private val pending = AtomicReference<PendingAction?>(null)
    private val started = AtomicBoolean(false)

    /** 收集协程处理到的状态与最近一次结束原因，补跑据此判断 */
    @Volatile
    private var observed: MaaExecutionState? = null

    @Volatile
    private var lastReason = Reason.NATURAL

    /** 登记本次收尾；重复登记覆盖前一次 */
    fun armOnce(action: PendingAction) {
        pending.set(action)
        // 边沿已过：收集协程处理过的按那次原因补跑，没处理到的交给 dispatch；与 dispatch 共用 getAndSet
        val now = compositionService.state.value
        if (isTerminal(now) && (observed == now || !started.get())) {
            val reason = lastReason
            scope.launch { runPending(reason) }
        }
    }

    /** 抢占前撤掉，防止旧 autoSleep 落在新一轮 */
    fun disarmOnce() {
        pending.set(null)
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        observed = compositionService.state.value
        scope.launch {
            compositionService.runTransitions.collect { transition ->
                val prev = transition.previous
                val cur = transition.current
                val reason = reasonFor(prev, cur, transition.stopOrigin)
                // 先记下再 dispatch，armOnce 才不会漏补跑
                if (isTerminal(cur) && !isTerminal(prev)) lastReason = reason ?: Reason.NATURAL
                observed = cur
                if (reason != null || (isTerminal(cur) && round.get() != null && prev == MaaExecutionState.STARTING)) {
                    dispatch(reason ?: Reason.NATURAL, transition)
                }
            }
        }
        Timber.i("TaskEndRegistry: started observing task end")
    }

    private fun isTerminal(state: MaaExecutionState) =
        state == MaaExecutionState.IDLE || state == MaaExecutionState.ERROR

    private fun reasonFor(
        prev: MaaExecutionState,
        cur: MaaExecutionState,
        origin: MaaCompositionService.StopOrigin,
    ): Reason? {
        if (!isTerminal(cur)) return null
        return when (prev) {
            MaaExecutionState.RUNNING -> Reason.NATURAL
            MaaExecutionState.STOPPING -> when (origin) {
                MaaCompositionService.StopOrigin.CALLBACK -> Reason.ABORTED
                MaaCompositionService.StopOrigin.USER -> Reason.MANUAL
                MaaCompositionService.StopOrigin.RUN_DURATION_LIMIT -> Reason.DURATION_LIMIT
            }
            else -> null
        }
    }

    private suspend fun dispatch(reason: Reason, transition: MaaCompositionService.RunTransition) {
        val owner = round.get()?.takeIf { transition.generation > it.afterGeneration }
        Timber.i("TaskEndRegistry: task ended, reason=%s", reason)
        val ownedByChain = owner != null || transition.generation <= suppressedThrough.get()
        if (!ownedByChain) runPending(reason)
        // 手动停止不关游戏，其余结束（自然完成 / 掉线中止 / 到达时长上限）都关
        if (!ownedByChain && reason != Reason.MANUAL && closeGameOnEnd()) {
            Timber.i("TaskEndRegistry: auto closing game")
            try {
                compositionService.stopVirtualDisplay()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "TaskEndRegistry: close game failed")
            }
        }
        _taskEnded.tryEmit(reason)
        // 上一轮 dispatch 已完成后才允许切配置；不能从 pending 回调里启动下一轮。
        owner?.completion?.complete(RoundEnd(
            reason,
            reason == Reason.NATURAL && transition.current == MaaExecutionState.IDLE
                    && transition.completedNormally,
        ))
    }

    /** 只跑 pending，不广播（补跑与 dispatch 共用） */
    private suspend fun runPending(reason: Reason) {
        val action = pending.getAndSet(null) ?: return
        try {
            action.run(reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "TaskEndRegistry: pending action failed")
        }
    }
}
