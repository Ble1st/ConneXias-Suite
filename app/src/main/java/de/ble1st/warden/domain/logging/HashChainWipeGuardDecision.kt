package de.ble1st.warden.domain.logging

/**
 * Independent last-sequence/hash check for [de.ble1st.warden.logging.HashChainLogStore], the same
 * idea as [de.ble1st.warden.domain.pin.WardenPinReplayDecision] applied to an append-only chain
 * instead of a single mutable blob. The chain's own hash links (`previousHash` per entry) only
 * prove *internal* consistency among the entries that are actually present — deleting whole
 * archive segments, or the active segment, leaves an internally consistent, just shorter chain,
 * which the structural check alone cannot tell apart from "nothing was ever logged" (an empty
 * chain trivially verifies as `Valid(0)`).
 *
 * A second slot, advanced by [de.ble1st.warden.logging.HashChainLogStore.append] every time it
 * successfully writes an entry (never by this decision itself — this object is a pure read-only
 * comparison), catches exactly that: a wipe that isn't also careful to advance the anchor to
 * match makes the next check [Result.Reject]. Anchor writes happen strictly after the
 * corresponding data write inside the same `@Synchronized append()` call, so a crash between the
 * two can only leave the anchor *behind* the chain — which this decision still accepts (`chain
 * tail sequence > anchor sequence` below) — never ahead of it, so there is no self-inflicted false
 * rejection window.
 *
 * Same accepted limit as the PIN anchor: a same-UID attacker who wipes **both** the chain and
 * this anchor together still wins — this closes "delete only the log files" and "restore an
 * older archive set", not a full same-UID compromise (see
 * [de.ble1st.warden.logging.HashChainLogStore]'s own "Ehrliche Grenze" doc for that accepted
 * trade-off).
 */
object HashChainWipeGuardDecision {

    sealed class Result {
        /** No anchor yet (fresh log, or an anchor-less caller — see the nullable
         * `wipeGuardAnchorFile` on [de.ble1st.warden.logging.HashChainLogStore]), or the chain
         * tail matches/exceeds what the anchor remembers. Nothing missing. */
        data object Accept : Result()

        /** The chain is shorter than, or diverges from, what the anchor remembers. */
        data class Reject(val reason: String) : Result()
    }

    fun evaluate(
        chainPresent: Boolean,
        chainTailSequence: Long,
        chainTailHash: ByteArray,
        anchorPresent: Boolean,
        anchorSequence: Long,
        anchorHash: ByteArray,
    ): Result {
        if (!anchorPresent) {
            return Result.Accept
        }
        if (!chainPresent) {
            return Result.Reject(
                "log chain is empty but a wipe-guard anchor remembers sequence $anchorSequence",
            )
        }
        if (chainTailSequence < anchorSequence) {
            return Result.Reject(
                "log chain tail sequence $chainTailSequence is behind wipe-guard anchor $anchorSequence",
            )
        }
        if (chainTailSequence == anchorSequence && !chainTailHash.contentEquals(anchorHash)) {
            return Result.Reject(
                "log chain tail hash at sequence $chainTailSequence does not match wipe-guard anchor",
            )
        }
        return Result.Accept
    }
}
