package com.daybloom.features.tasks

import androidx.lifecycle.ViewModel
import com.daybloom.domain.models.Priority
import com.daybloom.domain.models.TaskCategory
import com.daybloom.domain.services.CaptureKind
import com.daybloom.domain.services.ParsedCapture
import com.daybloom.domain.services.QuickCaptureParser
import com.daybloom.domain.usecases.AppointmentDraft
import com.daybloom.domain.usecases.CreateTaskUseCase
import com.daybloom.domain.usecases.SaveAppointmentUseCase
import com.daybloom.domain.usecases.TaskDraft
import com.daybloom.domain.usecases.TaskResult
import com.daybloom.features.common.AppMessages
import com.daybloom.features.common.DraftHandoff
import com.daybloom.features.common.runAction
import com.daybloom.platform.PlatformDateTimeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalTime

data class QuickCaptureState(
    val text: String = "",
    val parsed: ParsedCapture? = null,
    /** True once the user pressed Add on input that needs confirmation. */
    val confirming: Boolean = false,
    val saving: Boolean = false,
    val done: Boolean = false,
    val error: String? = null,
)

/** What the editor should open after "More options". */
sealed interface CaptureHandoff {
    data object Task : CaptureHandoff
    data object Appointment : CaptureHandoff
}

class QuickCaptureViewModel(
    private val parser: QuickCaptureParser,
    private val createTask: CreateTaskUseCase,
    private val saveAppointment: SaveAppointmentUseCase,
    private val handoff: DraftHandoff,
    private val messages: AppMessages,
    private val clock: PlatformDateTimeService,
) : ViewModel() {
    private val _state = MutableStateFlow(QuickCaptureState())
    val state: StateFlow<QuickCaptureState> = _state.asStateFlow()

    fun onTextChange(text: String) {
        val clipped = text.take(300)
        val parsed = clipped.takeIf { it.isNotBlank() }?.let { parser.parse(it, clock.localNow()) }
        _state.update { it.copy(text = clipped, parsed = parsed, confirming = false, error = null) }
    }

    fun submit() {
        val parsed = _state.value.parsed ?: return
        if (!parsed.isValid) {
            _state.update { it.copy(error = "Add a few words describing the task") }
            return
        }
        if (parsed.needsConfirmation && !_state.value.confirming) {
            _state.update { it.copy(confirming = true) }
            return
        }
        save(parsed)
    }

    fun backToEditing() = _state.update { it.copy(confirming = false) }

    /** Moves the parsed result into the full editor. Returns which editor to open. */
    fun handOff(): CaptureHandoff {
        val parsed = _state.value.parsed
        return if (parsed != null && parsed.kind == CaptureKind.APPOINTMENT && parsed.date != null && parsed.time != null) {
            handoff.putAppointment(appointmentDraft(parsed, parsed.time))
            reset()
            CaptureHandoff.Appointment
        } else {
            parsed?.let { handoff.putTask(taskDraft(it)) }
            reset()
            CaptureHandoff.Task
        }
    }

    fun reset() = _state.update { QuickCaptureState() }

    private fun save(parsed: ParsedCapture) {
        _state.update { it.copy(saving = true) }
        runAction(messages, "Couldn't save that. Please try again.") {
            val time = parsed.time
            if (parsed.kind == CaptureKind.APPOINTMENT && parsed.date != null && time != null) {
                when (val result = saveAppointment(appointmentDraft(parsed, time))) {
                    is SaveAppointmentUseCase.Result.Saved -> messages.post("Appointment added: ${result.appointment.title}")
                    is SaveAppointmentUseCase.Result.Invalid -> {
                        _state.update { it.copy(saving = false, error = result.errors.values.first()) }
                        return@runAction
                    }
                }
            } else {
                when (val result = createTask(taskDraft(parsed))) {
                    is TaskResult.Success -> messages.post("Added “${result.task.title}”")
                    is TaskResult.Invalid -> {
                        _state.update { it.copy(saving = false, error = result.errors.values.first()) }
                        return@runAction
                    }
                    TaskResult.NotFound -> Unit
                }
            }
            _state.update { QuickCaptureState(done = true) }
        }.invokeOnCompletion { _state.update { s -> s.copy(saving = false) } }
    }

    fun consumeDone() = _state.update { it.copy(done = false) }

    private fun taskDraft(p: ParsedCapture) = TaskDraft(
        title = p.title,
        category = p.category ?: TaskCategory.PERSONAL,
        priority = p.priority ?: Priority.NORMAL,
        dueDate = p.date,
        dueTime = p.time,
        estimatedMinutes = p.durationMinutes,
        recurrence = p.recurrence,
        tags = p.tags,
        reminderOffsetsMinutes = if (p.time != null && p.date != null) listOf(0) else emptyList(),
    )

    private fun appointmentDraft(p: ParsedCapture, start: LocalTime): AppointmentDraft {
        val endMinutes = (start.toSecondOfDay() / 60 + (p.durationMinutes ?: 60)).coerceAtMost(23 * 60 + 59)
        return AppointmentDraft(
            title = p.title,
            date = requireNotNull(p.date),
            startTime = start,
            endTime = LocalTime(endMinutes / 60, endMinutes % 60),
            recurrence = p.recurrence,
        )
    }
}
