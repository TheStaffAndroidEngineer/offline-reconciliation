package staff.android.offline.transfer.exercise

import staff.android.offline.transfer.MergeDecision
import staff.android.offline.transfer.ReportFields
import staff.android.offline.transfer.ReportVersion
import staff.android.offline.transfer.requireMergeInputs

// Deliberately incomplete: every valid input chooses the local fields.
fun mergeReport(
    base: ReportVersion,
    local: ReportFields,
    remote: ReportVersion,
): MergeDecision {
    requireMergeInputs(base, remote)
    return MergeDecision.Ready(
        local, remote.revision, local != remote.fields,
    )
}
