package dev.dogsc.core.editor

import dev.dogsc.core.model.RecorderProject

data class UndoEntry(val label: String, val project: RecorderProject)

/** Bounded undo/redo history. Values are immutable project snapshots. */
class UndoStack(initial: RecorderProject, private val capacity: Int = 120) {
    private val undoEntries = ArrayDeque<UndoEntry>()
    private val redoEntries = ArrayDeque<UndoEntry>()
    var current: RecorderProject = initial
        private set

    val canUndo: Boolean get() = undoEntries.isNotEmpty()
    val canRedo: Boolean get() = redoEntries.isNotEmpty()
    val undoLabel: String? get() = undoEntries.lastOrNull()?.label
    val redoLabel: String? get() = redoEntries.lastOrNull()?.label

    fun commit(label: String, project: RecorderProject) {
        if (project == current) return
        undoEntries.addLast(UndoEntry(label, current))
        while (undoEntries.size > capacity) undoEntries.removeFirst()
        redoEntries.clear()
        current = project
    }

    fun undo(): RecorderProject? {
        val entry = undoEntries.removeLastOrNull() ?: return null
        redoEntries.addLast(UndoEntry(entry.label, current))
        current = entry.project
        return current
    }

    fun redo(): RecorderProject? {
        val entry = redoEntries.removeLastOrNull() ?: return null
        undoEntries.addLast(UndoEntry(entry.label, current))
        current = entry.project
        return current
    }

    fun reset(project: RecorderProject) {
        current = project
        undoEntries.clear()
        redoEntries.clear()
    }
}

/**
 * Shared editor command boundary. Every mutation goes through `commit`, so
 * undo/redo, validation and dirty tracking cannot diverge between screens.
 */
class EditorSession(
    initial: RecorderProject,
    sourceDuration: Double,
) {
    private val stack = UndoStack(initial)
    var sourceDuration: Double = sourceDuration
        private set
    var project: RecorderProject = initial
        private set
    var isDirty: Boolean = false
        private set

    /**
     * Transient gesture channel. During a drag the project updates continuously but
     * nothing enters the undo stack; [endGesture] commits the whole drag as one entry
     * measured against the baseline captured at [beginGesture]. This keeps a 200-event
     * drag from flooding history and lets the UI throttle preview reloads while active.
     */
    var gestureBaseline: RecorderProject? = null
        private set
    val isGestureActive: Boolean get() = gestureBaseline != null

    val canUndo: Boolean get() = stack.canUndo
    val canRedo: Boolean get() = stack.canRedo
    val undoLabel: String? get() = stack.undoLabel
    val redoLabel: String? get() = stack.redoLabel
    val outputDuration: Double get() = TimelineEditor.outputDuration(project, sourceDuration)

    fun commit(label: String, transform: (RecorderProject) -> RecorderProject?): Boolean {
        // A discrete edit while a drag is still open must not split history: settle the
        // gesture first so the drag stays one undo entry and this edit becomes the next.
        if (isGestureActive) endGesture("调整")
        val next = transform(project) ?: return false
        if (next == project) return false
        dev.dogsc.core.model.ProjectValidator.validate(next)
        stack.commit(label, next)
        project = next
        isDirty = true
        return true
    }

    /** Opens a transient drag; captures the baseline the eventual undo entry restores to. */
    fun beginGesture() {
        if (gestureBaseline == null) gestureBaseline = project
    }

    /**
     * Updates the project mid-drag without touching the undo stack. Returns true when the
     * value actually changed so the UI can bump its revision and request a preview frame.
     * Invalid transforms are rejected (return false) rather than crashing the drag.
     */
    fun previewGesture(transform: (RecorderProject) -> RecorderProject?): Boolean {
        if (!isGestureActive) beginGesture()
        // The transform itself can throw: model `init` blocks require() valid ranges, and a
        // fast drag may momentarily overshoot them. Reject the sample instead of crashing.
        val next = runCatching { transform(project) }.getOrNull() ?: return false
        if (next == project) return false
        runCatching { dev.dogsc.core.model.ProjectValidator.validate(next) }.onFailure { return false }
        project = next
        isDirty = true
        return true
    }

    /**
     * Closes the drag, committing baseline->final as a single undo entry. A drag that
     * ended where it started produces no entry. Returns true when history changed.
     */
    fun endGesture(label: String): Boolean {
        val baseline = gestureBaseline ?: return false
        gestureBaseline = null
        if (project == baseline) return false
        stack.commit(label, project)
        return true
    }

    /** Abandons an in-flight drag, restoring the baseline (e.g. cancelled gesture). */
    fun cancelGesture() {
        val baseline = gestureBaseline ?: return
        gestureBaseline = null
        project = baseline
    }

    fun undo(): Boolean {
        val next = stack.undo() ?: return false
        project = next
        isDirty = true
        return true
    }

    fun redo(): Boolean {
        val next = stack.redo() ?: return false
        project = next
        isDirty = true
        return true
    }

    fun markSaved() {
        isDirty = false
    }

    fun replaceProject(newProject: RecorderProject, newSourceDuration: Double = sourceDuration) {
        project = newProject
        sourceDuration = newSourceDuration
        stack.reset(newProject)
        isDirty = false
    }
}
