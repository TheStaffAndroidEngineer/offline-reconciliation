package staff.android.offline.transfer.solution

import staff.android.offline.transfer.MergeDecision
import staff.android.offline.transfer.ReportField
import staff.android.offline.transfer.ReportFields
import staff.android.offline.transfer.ReportVersion
import staff.android.offline.transfer.requireMergeInputs

fun mergeReport(
    base: ReportVersion,
    local: ReportFields,
    remote: ReportVersion,
): MergeDecision {
    requireMergeInputs(base, remote)
    val title = mergeField(
        base.fields.title, local.title, remote.fields.title,
    )
    val note = mergeField(
        base.fields.note, local.note, remote.fields.note,
    )
    val conflicts = buildSet {
        if (title == null) add(ReportField.Title)
        if (note == null) add(ReportField.Note)
    }
    if (conflicts.isNotEmpty()) {
        return MergeDecision.Conflict(base, local, remote, conflicts)
    }
    val merged = ReportFields(checkNotNull(title), checkNotNull(note))
    return MergeDecision.Ready(
        merged, remote.revision, merged != remote.fields,
    )
}

private fun mergeField(
    base: String, local: String, remote: String,
): String? = when {
    local == remote -> local
    local == base -> remote
    remote == base -> local
    else -> null
}
