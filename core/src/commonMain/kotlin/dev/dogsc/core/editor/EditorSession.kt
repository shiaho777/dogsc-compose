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

    val canUndo: Boolean get() = stack.canUndo
    val canRedo: Boolean get() = stack.canRedo
    val undoLabel: String? get() = stack.undoLabel
    val redoLabel: String? get() = stack.redoLabel
    val outputDuration: Double get() = TimelineEditor.outputDuration(project, sourceDuration)

    fun commit(label: String, transform: (RecorderProject) -> RecorderProject?): Boolean {
        val next = transform(project) ?: return false
        if (next == project) return false
        dev.dogsc.core.model.ProjectValidator.validate(next)
        stack.commit(label, next)
        project = next
        isDirty = true
        return true
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
