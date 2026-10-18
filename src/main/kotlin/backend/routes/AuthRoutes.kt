package backend.routes

import backend.models.AdminEligibilityRequest
import backend.models.AdminGrantRequest
import backend.models.AdminSensitiveVerifyRequest
import backend.models.AdminSensitiveVerifyResponse
import backend.models.EmailApprovalRequest
import backend.models.ExternalAccountApprovalRequest
import backend.models.ChangeOwnPasswordRequest
import backend.models.LoginRequest
import backend.models.PasswordResetRequest
import backend.models.ProfileUpdateRequest
import backend.models.RegisterRequest
import backend.models.ResendVerificationRequest
import backend.models.RoleUpdateRequest
import backend.models.VerifyEmailRequest
import backend.models.UserRole
import backend.models.UserActionResponse
import backend.models.InvitationCreateRequest
import backend.models.InvitationAcceptRequest
import backend.models.AccountStateUpdateRequest
import backend.models.ForgotPasswordRequest
import backend.models.CompletePasswordResetRequest
import backend.models.MfaConfirmRequest
import backend.models.MfaEnrollmentResponse
import backend.models.MfaRecoveryResponse
import backend.models.MfaLoginRequest
import backend.models.ReauthRequest
import backend.models.ReauthResponse
import backend.models.AuthResponse
import backend.security.requireRole
import backend.security.requirePermission
import backend.security.Permission
import backend.security.SessionCookies
import backend.security.userId
import backend.security.principal
import backend.security.requireReauthentication
import backend.security.isAdminPortalRole
import backend.config.Env
import backend.services.AuthService
import backend.services.SessionService
import backend.services.InvitationService
import backend.services.MfaService
import backend.services.ReauthScope
import backend.services.ReauthenticationService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes(authService: AuthService, sessions: SessionService, invitations: InvitationService, mfa: MfaService, reauthentication: ReauthenticationService) {
    route("/api/auth") {
        get("/csrf-token") {
            val session = call.principal()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
            val csrf = sessions.rotateCsrf(session.sessionId)
            val secure = Env.get("APP_ENV").equals("production", true)
            call.response.cookies.append(SessionCookies.csrf(csrf, secure))
            call.respond(mapOf("csrfToken" to csrf))
        }
        post("/register") {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Registration requires an invitation"))
        }
        post("/invitations/accept") {
            val user = invitations.accept(call.receive<InvitationAcceptRequest>())
            val mfaEnrollmentRequired = isAdminPortalRole(user.role)
            if (!mfaEnrollmentRequired) call.establishSession(sessions, user.id)
            call.respond(HttpStatusCode.Created, AuthResponse(user, mfaEnrollmentRequired = mfaEnrollmentRequired))
        }
        post("/verify-email") {
            val req = call.receive<VerifyEmailRequest>()
            val result = authService.verifyEmail(req.email, req.code)
            call.establishSession(sessions, result.user.id, !(result.mfaRequired || result.mfaEnrollmentRequired))
            call.respond(result)
        }
        post("/resend-verification") {
            val req = call.receive<ResendVerificationRequest>()
            call.respond(authService.resendVerification(req.email))
        }
        post("/login") {
            val req = call.receive<LoginRequest>()
            val result = authService.login(req)
            call.principal()?.let { sessions.revoke(it.sessionId) }
            call.establishSession(sessions, result.user.id, !(result.mfaRequired || result.mfaEnrollmentRequired))
            call.respond(result)
        }
        post("/forgot-password") {
            val request = call.receive<ForgotPasswordRequest>()
            authService.forgotPassword(request.email)
            call.respond(mapOf("message" to "If the account is eligible, a reset link will be sent."))
        }
        post("/reset-password") {
            val request = call.receive<CompletePasswordResetRequest>()
            authService.completePasswordReset(request.token, request.newPassword, request.confirmPassword)
            call.respond(mapOf("message" to "Password reset completed. Sign in with your new password."))
        }
        get("/invitations/inspect") {
            val token = call.request.queryParameters["token"].orEmpty()
            val info = invitations.inspect(token) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Invitation is invalid or expired"))
            call.respond(info)
        }
        post("/logout") {
            call.principal()?.let { sessions.revoke(it.sessionId) }
            call.clearSessionCookies()
            call.respond(HttpStatusCode.OK, mapOf("message" to "Logged out"))
        }
        post("/logout-all") {
            val principal = call.principal() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            sessions.revokeAll(principal.user.id)
            call.clearSessionCookies()
            call.respond(mapOf("message" to "All sessions revoked"))
        }
        get("/me") {
            val principal = call.principal() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            call.respond(principal.user)
        }
        get("/security-events") {
            val principal = call.principal() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            call.respond(authService.recentSecurityEvents(principal.user.id))
        }
        post("/reauth") {
            val principal = call.principal() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val request = call.receive<ReauthRequest>()
            val scope = ReauthScope.parse(request.scope) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unsupported reauthentication scope"))
            if (!authService.verifyReauthentication(principal.user.id, request.password, request.mfaCode, request.recoveryCode)) {
                return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Reauthentication failed"))
            }
            val grant = reauthentication.issue(principal.sessionId, principal.user.id, scope)
            call.respond(ReauthResponse(grant.token, grant.scope.wireValue, grant.expiresAt.toString()))
        }
        route("/mfa") {
            get("/status") {
                val principal = call.principal() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                call.respond(mapOf("enabled" to mfa.isEnabled(principal.user.id), "required" to (principal.user.role in setOf(UserRole.ADMIN, UserRole.IT_ADMIN, UserRole.SUPERADMIN))))
            }
            post("/enroll") {
                val principal = call.principal() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                if (mfa.isEnabled(principal.user.id) && !call.requireReauthentication(reauthentication, ReauthScope.MFA_CHANGE)) return@post
                val enrollment = mfa.begin(principal.user.id, principal.user.email)
                call.respond(MfaEnrollmentResponse(enrollment.uri, enrollment.secret, mfa.qrDataUrl(enrollment.uri)))
            }
            post("/confirm") {
                val principal = call.principal() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val recovery = mfa.confirm(principal.user.id, call.receive<MfaConfirmRequest>().code)
                sessions.revoke(principal.sessionId)
                call.establishSession(sessions, principal.user.id, true)
                authService.notifySecurityEvent(principal.user.id, "AITSM MFA enabled", "Multi-factor authentication was enabled and recovery codes were generated for your account.")
                call.respond(MfaRecoveryResponse(recovery))
            }
            post("/complete") {
                val principal = call.principal() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val request = call.receive<MfaLoginRequest>()
                if (!mfa.verifyLogin(principal.user.id, request.code, request.recoveryCode)) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "MFA verification failed"))
                sessions.revoke(principal.sessionId)
                call.establishSession(sessions, principal.user.id, true)
                authService.notifySecurityEvent(principal.user.id, "New AITSM sign-in", "A new session completed multi-factor authentication.")
                call.respond(mapOf("message" to "MFA completed"))
            }
            post("/recovery-codes") {
                val principal = call.principal() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                if (!call.requireReauthentication(reauthentication, ReauthScope.RECOVERY_CODES)) return@post
                if (!mfa.isEnabled(principal.user.id)) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "MFA is not enabled"))
                call.respond(MfaRecoveryResponse(mfa.regenerateRecoveryCodes(principal.user.id)))
            }
            delete {
                val principal = call.principal() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                if (principal.user.role in setOf(UserRole.ADMIN, UserRole.IT_ADMIN, UserRole.SUPERADMIN)) {
                    return@delete call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Administrator MFA can only be reset through the audited recovery procedure"))
                }
                if (!call.requireReauthentication(reauthentication, ReauthScope.MFA_CHANGE)) return@delete
                mfa.disable(principal.user.id)
                sessions.revokeAll(principal.user.id, except = principal.sessionId)
                authService.notifySecurityEvent(principal.user.id, "AITSM MFA disabled", "Multi-factor authentication was disabled and other active sessions were revoked.")
                call.respond(mapOf("message" to "MFA disabled"))
            }
        }
        route("/sessions") {
            get {
                val principal = call.principal() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                call.respond(sessions.list(principal.user.id, principal.sessionId))
            }
            delete("/{id}") {
                val principal = call.principal() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                val id = runCatching { java.util.UUID.fromString(call.parameters["id"]) }.getOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest)
                if (!sessions.revokeOwned(principal.user.id, id)) return@delete call.respond(HttpStatusCode.NotFound)
                call.respond(mapOf("message" to "Session revoked"))
            }
        }
        route("/invitations") {
            get {
                if (!call.requirePermission(Permission.USER_INVITE)) return@get
                call.respond(invitations.list())
            }
            post {
                if (!call.requirePermission(Permission.USER_INVITE)) return@post
                if (!call.requireReauthentication(reauthentication, ReauthScope.ADMIN_ACCESS)) return@post
                val actor = call.principal()!!
                val request = call.receive<InvitationCreateRequest>()
                call.respond(HttpStatusCode.Created, invitations.invite(request.email, request.role, actor.user.id, actor.user.role))
            }
            delete("/{id}") {
                if (!call.requirePermission(Permission.USER_INVITE)) return@delete
                if (!call.requireReauthentication(reauthentication, ReauthScope.ADMIN_ACCESS)) return@delete
                val actor = call.principal()!!
                invitations.revoke(call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest), actor.user.id)
                call.respond(mapOf("message" to "Invitation revoked"))
            }
        }
    }

    route("/api/users") {
        get("/me") {
            val actor = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            call.respond(authService.currentUser(actor))
        }
        put("/me") {
            val actor = call.userId() ?: return@put call.respond(HttpStatusCode.Unauthorized)
            val req = call.receive<ProfileUpdateRequest>()
            call.respond(authService.updateOwnProfile(actor, req))
        }
        put("/me/password") {
            val actor = call.userId() ?: return@put call.respond(HttpStatusCode.Unauthorized)
            val req = call.receive<ChangeOwnPasswordRequest>()
            val updated = authService.changeOwnPassword(actor, req.currentPassword, req.newPassword, req.confirmPassword)
            call.establishSession(sessions, actor)
            call.respond(mapOf("message" to "Password changed successfully", "user" to updated))
        }

        get {
            if (!call.requireRole(UserRole.ADMIN)) return@get
            call.respond(authService.listUsers())
        }
        put("/{id}/role") {
            if (!call.requireRole(UserRole.ADMIN)) return@put
            if (!call.requireReauthentication(reauthentication, ReauthScope.ADMIN_ACCESS)) return@put
            val id = call.parameters["id"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)
            val req = call.receive<RoleUpdateRequest>()
            val updated = authService.updateUserRole(id, req.role, call.userId())
            call.respond(UserActionResponse("Role updated successfully", updated))
        }
        put("/{id}/reset-password") {
            if (!call.requireRole(UserRole.ADMIN)) return@put
            if (!call.requireReauthentication(reauthentication, ReauthScope.ADMIN_ACCESS)) return@put
            val id = call.parameters["id"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)
            val req = call.receive<PasswordResetRequest>()
            val updated = authService.resetUserPassword(id, req.newPassword, req.confirmPassword, call.userId())
            call.respond(UserActionResponse("Password reset successful", updated))
        }
        put("/{id}/account-state") {
            if (!call.requirePermission(Permission.USER_MANAGE)) return@put
            if (!call.requireReauthentication(reauthentication, ReauthScope.ADMIN_ACCESS)) return@put
            val id = call.parameters["id"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)
            val req = call.receive<AccountStateUpdateRequest>()
            call.respond(UserActionResponse("Account state updated", authService.updateAccountState(id, req.state, call.userId())))
        }
        put("/{id}/email-approval") {
            if (!call.requireRole(UserRole.ADMIN)) return@put
            if (!call.requireReauthentication(reauthentication, ReauthScope.ADMIN_ACCESS)) return@put
            val id = call.parameters["id"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)
            val req = call.receive<EmailApprovalRequest>()
            if (!req.approved) {
                return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Verified invitation accounts cannot be returned to pending status"))
            }
            val updated = authService.updateUserEmailApproval(id, true, call.userId())
            call.respond(UserActionResponse("User approved for login", updated))
        }
        put("/{id}/access-approval") {
            if (!call.requireRole(UserRole.ADMIN)) return@put
            if (!call.requireReauthentication(reauthentication, ReauthScope.ADMIN_ACCESS)) return@put
            val id = call.parameters["id"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)
            val req = call.receive<ExternalAccountApprovalRequest>()
            val updated = authService.approveExternalAccount(id, req.department, req.role, call.userId())
            call.respond(UserActionResponse("Account access granted", updated))
        }
        delete("/{id}") {
            if (!call.requireRole(UserRole.ADMIN)) return@delete
            if (!call.requireReauthentication(reauthentication, ReauthScope.ACCOUNT_DELETE)) return@delete
            val id = call.parameters["id"]?.toIntOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val deleted = authService.deleteUserAccount(id, call.userId())
            call.respond(UserActionResponse("User deleted", deleted))
        }
        route("/admin") {
            post("/eligibility") {
                if (!call.requireRole(UserRole.ADMIN)) return@post
                val req = call.receive<AdminEligibilityRequest>()
                call.respond(authService.adminGrantEligibility(req.targetEmail))
            }
            post("/verify") {
                if (!call.requireRole(UserRole.ADMIN)) return@post
                val actor = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val req = call.receive<AdminSensitiveVerifyRequest>()
                val (ok, token, meta) = authService.verifySensitiveAction(actor, req.password)
                if (!ok) return@post call.respond(
                    HttpStatusCode.Forbidden,
                    AdminSensitiveVerifyResponse(verified = false, message = meta)
                )
                val principal = call.principal()!!
                val reauth = reauthentication.issue(principal.sessionId, actor, ReauthScope.ADMIN_ACCESS)
                call.respond(
                    AdminSensitiveVerifyResponse(
                        verified = true,
                        verificationToken = token,
                        reauthToken = reauth.token,
                        expiresAt = meta,
                        message = "Verification successful"
                    )
                )
            }
            post("/grant") {
                if (!call.requireRole(UserRole.ADMIN)) return@post
                if (!call.requireReauthentication(reauthentication, ReauthScope.ADMIN_ACCESS)) return@post
                val actor = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val req = call.receive<AdminGrantRequest>()
                val result = authService.grantAdminByEmail(req.targetEmail, actor, req.verificationToken)
                if (!result.success) return@post call.respond(HttpStatusCode.BadRequest, result)
                call.respond(result)
            }
            post("/{id}/mfa-reset") {
                if (!call.requireRole(UserRole.SUPERADMIN)) return@post
                if (!call.requireReauthentication(reauthentication, ReauthScope.MFA_CHANGE)) return@post
                val actor = call.principal()!!
                val targetId = call.parameters["id"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
                val target = authService.resetAdministratorMfa(targetId, actor.user.id, actor.user.role)
                call.respond(UserActionResponse("Administrator MFA reset; all target sessions were revoked", target))
            }
        }
    }
}

private fun ApplicationCall.establishSession(sessions: SessionService, userId: Int, mfaComplete: Boolean = true) {
    val issued = sessions.issue(userId, request.local.remoteHost, request.headers[HttpHeaders.UserAgent], mfaComplete = mfaComplete)
    val secure = Env.get("APP_ENV").equals("production", true)
    response.cookies.append(SessionCookies.auth(issued.token, secure))
    response.cookies.append(SessionCookies.csrf(issued.csrfToken, secure))
}

private fun ApplicationCall.clearSessionCookies() {
    val secure = Env.get("APP_ENV").equals("production", true)
    response.cookies.append(SessionCookies.auth("", secure, 0))
    response.cookies.append(SessionCookies.csrf("", secure, 0))
}
