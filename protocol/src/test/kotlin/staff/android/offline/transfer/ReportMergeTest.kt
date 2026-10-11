package staff.android.offline.transfer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

abstract class ReportMergeContract {
    abstract fun merge(
        base: ReportVersion, local: ReportFields, remote: ReportVersion,
    ): MergeDecision

    private val base = ReportVersion(
        "report-1", 5, ReportFields("Gate inspection", "Check latch"),
    )

    @Test fun unchangedValuesNeedNoWrite() {
        assertEquals(
            MergeDecision.Ready(base.fields, 5, false),
            merge(base, base.fields, base),
        )
    }

    @Test fun onlyLocalChangeKeepsItsExactValue() {
        val local = base.fields.copy(note = "Replace latch")
        assertEquals(
            MergeDecision.Ready(local, 5, true),
            merge(base, local, base),
        )
    }

    @Test fun onlyRemoteChangeMustNotBeUndone() {
        val remote = base.copy(
            revision = 6, fields = base.fields.copy(note = "Latch repaired"),
        )
        assertEquals(
            MergeDecision.Ready(remote.fields, 6, false),
            merge(base, base.fields, remote),
        )
    }

    @Test fun independentEditsCombineInEitherDirection() {
        val titleChange = base.fields.copy(title = "North gate")
        val noteChange = base.fields.copy(note = "Replace latch")
        val expected = ReportFields("North gate", "Replace latch")
        assertEquals(
            MergeDecision.Ready(expected, 6, true),
            merge(base, titleChange, base.copy(revision = 6, fields = noteChange)),
        )
        assertEquals(
            MergeDecision.Ready(expected, 6, true),
            merge(base, noteChange, base.copy(revision = 6, fields = titleChange)),
        )
    }

    @Test fun identicalConcurrentEditsNeedNoAdditionalWrite() {
        val same = ReportFields("North gate", "Replace latch")
        assertEquals(
            MergeDecision.Ready(same, 7, false),
            merge(base, same, base.copy(revision = 7, fields = same)),
        )
    }

    @Test fun overlappingTitleEditsPreserveAllThreeInputs() {
        val local = base.fields.copy(title = "North gate")
        val remote = base.copy(
            revision = 6, fields = base.fields.copy(title = "South gate"),
        )
        assertEquals(
            MergeDecision.Conflict(
                base, local, remote, setOf(ReportField.Title),
            ),
            merge(base, local, remote),
        )
    }

    @Test fun overlappingBothFieldsNamesBothConflicts() {
        val local = ReportFields("North gate", "Replace latch")
        val remote = base.copy(
            revision = 6, fields = ReportFields("South gate", "Oil latch"),
        )
        assertEquals(
            MergeDecision.Conflict(
                base, local, remote,
                setOf(ReportField.Title, ReportField.Note),
            ),
            merge(base, local, remote),
        )
    }

    @Test fun exactWhitespaceUnicodeAndEmptyStringsArePreserved() {
        val local = base.fields.copy(title = "  Café gate\n")
        val remote = base.copy(
            revision = 6, fields = base.fields.copy(note = ""),
        )
        assertEquals(
            MergeDecision.Ready(ReportFields("  Café gate\n", ""), 6, true),
            merge(base, local, remote),
        )
    }

    @Test fun newerRemoteRevisionIsTheNextPreconditionEvenIfValuesMatchBase() {
        val remote = base.copy(revision = 19)
        val local = base.fields.copy(note = "Replace latch")
        assertEquals(
            MergeDecision.Ready(local, 19, true),
            merge(base, local, remote),
        )
    }

    @Test fun unrelatedOlderOrContradictoryRemoteVersionsAreRejected() {
        for (remote in listOf(
            base.copy(reportId = "report-2", revision = 6),
            base.copy(revision = 4),
            base.copy(fields = base.fields.copy(note = "Different at 5")),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                merge(base, base.fields, remote)
            }
        }
    }

    @Test fun invalidSnapshotIdentityAndRevisionAreRejected() {
        for (id in listOf("", " \t")) {
            assertThrows(IllegalArgumentException::class.java) {
                ReportVersion(id, 5, base.fields)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReportVersion("report-1", -1, base.fields)
        }
    }

}

class ReportMergeReferenceTest : ReportMergeContract() {
    override fun merge(
        base: ReportVersion, local: ReportFields, remote: ReportVersion,
    ) = staff.android.offline.transfer.solution.mergeReport(base, local, remote)
}

@Tag("exercise")
class ReportMergeLearnerTest : ReportMergeContract() {
    override fun merge(
        base: ReportVersion, local: ReportFields, remote: ReportVersion,
    ) = staff.android.offline.transfer.exercise.mergeReport(base, local, remote)
}
