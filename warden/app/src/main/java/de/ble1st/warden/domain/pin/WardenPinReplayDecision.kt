package de.ble1st.warden.domain.pin

/**
 * Independent last-counter/hash check for [WardenPinBlob]. The blob's own chain only
 * proves internal consistency; restoring an older still-valid envelope (same KEK) would
 * otherwise reset PIN and anti-hammering. A second slot that is not rewritten together
 * with a naive single-file restore makes that rollback [Result.Reject].
 *
 * Same-UID attackers who restore **both** blob and anchor together still win — that is
 * the accepted single-APK limit. This closes partial restore and "overwrite only the
 * pin envelope".
 */
object WardenPinReplayDecision {

    sealed class Result {
        /** Blob matches the persisted anchor. */
        data object Accept : Result()

        /** First run after upgrade, or the blob advanced before the anchor write finished. */
        data object AcceptAndWriteAnchor : Result()

        /** Missing blob with leftover anchor, older counter, or hash mismatch. */
        data class Reject(val reason: String) : Result()
    }

    fun evaluate(
        blobPresent: Boolean,
        blobCounter: Long,
        blobHash: ByteArray,
        anchorPresent: Boolean,
        anchorCounter: Long,
        anchorHash: ByteArray,
    ): Result {
        if (!blobPresent && !anchorPresent) {
            return Result.Reject("neither PIN blob nor replay anchor present")
        }
        if (!blobPresent && anchorPresent) {
            return Result.Reject("PIN blob missing while replay anchor is present")
        }
        if (!anchorPresent) {
            return Result.AcceptAndWriteAnchor
        }
        if (blobCounter < anchorCounter) {
            return Result.Reject("PIN blob counter $blobCounter is behind replay anchor $anchorCounter")
        }
        if (blobCounter == anchorCounter && !blobHash.contentEquals(anchorHash)) {
            return Result.Reject("PIN blob hash mismatch at counter $blobCounter")
        }
        if (blobCounter > anchorCounter) {
            return Result.AcceptAndWriteAnchor
        }
        return Result.Accept
    }
}
