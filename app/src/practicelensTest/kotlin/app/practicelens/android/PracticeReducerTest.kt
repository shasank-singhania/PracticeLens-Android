package app.practicelens.android

import app.practicelens.android.core.EvaluationState
import app.practicelens.android.core.EvaluationResult
import app.practicelens.android.core.MonotonicClock
import app.practicelens.android.core.PracticeAttemptState
import app.practicelens.android.core.PracticeOption
import app.practicelens.android.core.PracticeQuestion
import app.practicelens.android.core.PracticeReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PracticeReducerTest {
    private val clock = FakeClock()
    private val reducer = PracticeReducer(clock)
    private val question = PracticeQuestion(
        id = "q1",
        prompt = "Question?",
        options = listOf(PracticeOption("A", "Alpha"), PracticeOption("B", "Beta"), PracticeOption("C", "Gamma")),
    )

    @Test fun `A to B to C produces one lock for C`() {
        var state = PracticeAttemptState(question = question, createdAtMs = 0)
        state = reducer.selectOption(state, "A")
        state = reducer.selectOption(state, "B")
        state = reducer.selectOption(state, "C")
        val stale = reducer.tryLockForEvaluation(state, "q1", 1, "A")
        assertEquals(EvaluationState.GRACE_PERIOD, stale.evaluationState)
        val locked = reducer.tryLockForEvaluation(state, "q1", state.selectionVersion, "C")
        assertEquals(EvaluationState.IN_FLIGHT, locked.evaluationState)
        assertEquals("C", locked.lockedAttempt?.selectedOptionId)
        assertEquals(2, locked.answerChangeCount)
    }

    @Test fun `boundary time stale selection cannot evaluate`() {
        var state = PracticeAttemptState(question = question, createdAtMs = 0)
        state = reducer.selectOption(state, "A")
        val oldVersion = state.selectionVersion
        state = reducer.selectOption(state, "B")
        val result = reducer.tryLockForEvaluation(state, "q1", oldVersion, "A")
        assertNull(result.lockedAttempt)
    }

    @Test fun `valid result completes locally`() {
        var state = PracticeAttemptState(question = question, createdAtMs = 0)
        state = reducer.selectOption(state, "A")
        state = reducer.tryLockForEvaluation(state, "q1", state.selectionVersion, "A")
        state = reducer.complete(state, EvaluationResult("B", "Because beta is correct.", 0.8, false))
        assertEquals(EvaluationState.COMPLETE, state.evaluationState)
        assertEquals("A", state.finalSelectedOptionId)
        assertEquals("B", state.result?.correctOptionId)
    }
}

private class FakeClock : MonotonicClock {
    private var now = 0L
    override fun nowMs(): Long = now++
}
