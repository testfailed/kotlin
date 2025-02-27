// WITH_STDLIB
// WITH_COROUTINES
// CHECK_BYTECODE_LISTING
// CHECK_NEW_COUNT: function=suspendHere count=0
// FIXME: Coroutine inlining
// CHECK_NEW_COUNT: function=complexSuspend count=0 TARGET_BACKENDS=JS
import helpers.*
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

inline suspend fun suspendThere(v: String): String = suspendCoroutineUninterceptedOrReturn { x ->
    x.resume(v)
    COROUTINE_SUSPENDED
}

// TODO: Somehow we still generate continuations for tail-call function, but we don't use them.
suspend fun suspendHere(): String = suspendThere("O")

// There is a kind of redundant state machine generated for complexSuspend:
// it's basically has the only suspend call just before return, but there is
// a redundant CHECKCAST String in the run's lambda, so we have to insert the state machine.
// TODO: Think of avoiding such redundant casts
suspend fun complexSuspend(): String {
    return run {
        suspendThere("K")
    }
}

fun builder(c: suspend () -> Unit) {
    c.startCoroutine(EmptyContinuation)
}

fun box(): String {
    var result = ""

    builder {
        result = suspendHere() + complexSuspend()
    }

    return result
}
