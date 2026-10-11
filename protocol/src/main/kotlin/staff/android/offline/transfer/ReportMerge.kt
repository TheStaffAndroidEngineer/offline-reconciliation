package staff.android.offline.transfer

// Fields are independent exact strings; this decision does not validate
// content, perform HTTP, or acknowledge an existing outbox operation.
data class ReportFields(val title: String, val note: String)

data class ReportVersion(
    val reportId: String,
    val revision: Long,
    val fields: ReportFields,
) {
    init {
        require(reportId.isNotBlank())
        require(revision >= 0)
    }
}

enum class ReportField { Title, Note }

sealed interface MergeDecision {
    data class Ready(
        val fields: ReportFields,
        val expectedRevision: Long,
        val needsWrite: Boolean,
    ) : MergeDecision

    data class Conflict(
        val base: ReportVersion,
        val local: ReportFields,
        val remote: ReportVersion,
        val fields: Set<ReportField>,
    ) : MergeDecision
}

fun requireMergeInputs(base: ReportVersion, remote: ReportVersion) {
    require(base.reportId == remote.reportId)
    require(remote.revision >= base.revision)
    require(remote.revision != base.revision || remote.fields == base.fields)
}
