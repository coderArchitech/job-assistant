package com.coderarchitech.jobassistant.core.model

/** Whether a portal ships with the app or was added by the user. */
enum class PortalType { BUILT_IN, CUSTOM }

/** How the portal signs in. */
enum class LoginMethod { PASSWORD, OTP }

/** Result of the last session check, shown in the Portals screen. */
enum class SessionStatus { UNKNOWN, LOGGED_IN, EXPIRED, OTP_NEEDED, FAILED }

/**
 * A job portal the agent searches and applies on (PRD FR8). Credentials are never part of this
 * type: they live in the vault under [vaultKey].
 */
data class Portal(
    val id: Long,
    val name: String,
    val type: PortalType,
    /** The adapter bundle that drives this portal; null for custom portals run by the generic agent. */
    val adapterId: String?,
    val loginUrl: String,
    val searchUrl: String?,
    val loginMethod: LoginMethod,
    val dailyCap: Int = DEFAULT_DAILY_CAP,
    val enabled: Boolean = true,
    /** Auto-submit High-match jobs on this portal instead of stopping before submit. */
    val autoSubmitHigh: Boolean = false,
    val lastSessionStatus: SessionStatus = SessionStatus.UNKNOWN,
) {
    init {
        require(dailyCap >= 0) { "A daily cap cannot be negative" }
        require(type == PortalType.CUSTOM || adapterId != null) { "Built-in portals need an adapter" }
    }

    /** Vault slot holding this portal's username and password. */
    val vaultKey: String
        get() = "portal/$id"

    companion object {
        /** PRD FR6: default apply cap per portal per day. */
        const val DEFAULT_DAILY_CAP = 10
    }
}
