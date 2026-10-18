package com.aitsm

import backend.config.DatabaseFactory
import backend.config.DatabaseMigrator
import backend.config.Env
import backend.config.ServiceContainer
import backend.models.ApiErrorResponse
import backend.models.AssetIpPrefixesResponse
import backend.routes.aiRoutes
import backend.routes.analyticsRoutes
import backend.routes.authRoutes
import backend.routes.deviceRoutes
import backend.routes.colleagueRoutes
import backend.routes.knowledgeRoutes
import backend.routes.monitoringRoutes
import backend.routes.profilePhotoRoutes
import backend.routes.notificationRoutes
import backend.routes.inventoryRoutes
import backend.routes.settingsRoutes
import backend.routes.slaRoutes
import backend.routes.ticketRoutes
import backend.routes.auditRoutes
import backend.security.PasswordHasher
import backend.security.Permission
import backend.security.configureSecurityBoundary
import backend.security.requirePermission
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

fun main() {
    embeddedServer(Netty, port = Env.get("PORT")?.toIntOrNull() ?: 8070, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    val appEnv = (Env.get("APP_ENV") ?: "development").lowercase()
    validateRuntimeConfiguration(appEnv, Env::get)
    val isProduction = appEnv == "production"
    val metrics = ConcurrentHashMap<String, AtomicLong>()

    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "no-referrer")
        header("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")
        header("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()")
        if (isProduction) header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
    }
    install(CallLogging)
    install(ContentNegotiation) { json() }
    intercept(ApplicationCallPipeline.Monitoring) {
        val started = System.nanoTime()
        proceed()
        val durationMs = (System.nanoTime() - started) / 1_000_000
        metrics.computeIfAbsent("requests.total", { AtomicLong(0) }).incrementAndGet()
        metrics.computeIfAbsent("requests.durationMs", { AtomicLong(0) }).addAndGet(durationMs)
    }

    install(CORS) {
        // Ktor only permits simple methods by default. Browser PATCH/PUT/DELETE
        // requests otherwise receive 403 before authentication, CSRF validation,
        // or the API route can run.
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader("Content-Type")
        allowHeader("X-CSRF-Token")
        allowHeader("X-Reauth-Token")
        allowHeader("X-AI-Session-Id")
        allowCredentials = true

        val allowedOrigins = (Env.get("CORS_ALLOWED_ORIGINS") ?: "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (allowedOrigins.isEmpty()) {
            if (!isProduction) anyHost()
        } else {
            allowedOrigins.forEach { origin ->
                val uri = URI(origin)
                require(uri.host != null && uri.authority != null && uri.scheme in setOf("http", "https")) {
                    "CORS_ALLOWED_ORIGINS contains an invalid origin"
                }
                allowHost(uri.authority, schemes = listOf(uri.scheme))
            }
        }
    }
    install(StatusPages) {
        status(HttpStatusCode.Unauthorized) { call, status ->
            if (call.request.path().startsWith("/api/")) call.respond(status, ApiErrorResponse(status.value, "Authentication required"))
        }
        status(HttpStatusCode.Forbidden) { call, status ->
            if (call.request.path().startsWith("/api/")) call.respond(status, ApiErrorResponse(status.value, "Access denied"))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            if (call.request.path().startsWith("/api/")) call.respond(status, ApiErrorResponse(status.value, "API endpoint not found"))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            if (call.request.path().startsWith("/api/")) call.respond(status, ApiErrorResponse(status.value, "Method not allowed"))
        }
        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorResponse(HttpStatusCode.BadRequest.value, cause.message ?: "Invalid request")
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            if (call.request.path() == "/settings/asset-ip-prefixes") {
                call.respond(
                    HttpStatusCode.OK,
                    AssetIpPrefixesResponse(
                        prefixes = emptyList(),
                        message = "Prefixes unchanged: ${cause.message ?: "invalid input"}"
                    )
                )
                return@exception
            }
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(HttpStatusCode.BadRequest.value, cause.message ?: "Invalid request"))
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(HttpStatusCode.BadRequest.value, cause.message ?: "Invalid state"))
        }
        exception<SecurityException> { call, cause ->
            call.respond(HttpStatusCode.Forbidden, ApiErrorResponse(HttpStatusCode.Forbidden.value, cause.message ?: "Access denied"))
        }
        exception<Throwable> { call, cause ->
            metrics.computeIfAbsent("requests.errors", { AtomicLong(0) }).incrementAndGet()
            this@module.environment.log.error("Unhandled request failure: ${call.request.httpMethod.value} ${call.request.path()}", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiErrorResponse(HttpStatusCode.InternalServerError.value, if (isProduction) "Internal server error" else "Unexpected error"))
        }
    }

    if (Env.get("DB_MIGRATE_ON_START").equals("true", ignoreCase = true)) DatabaseMigrator.migrate()
    DatabaseFactory.init()
    val container = ServiceContainer(this)
    configureSecurityBoundary(container.sessionService)
    container.knowledgeRepo.seedDefaults()
    container.aiChatService.startupLog()
    container.slaService.seedDefaults()
    container.slaAutomationService.start(this)
    container.notificationService.startRetentionCleanup(this)

    val superAdminEmail = Env.get("SUPERADMIN_EMAIL")?.takeIf { it.isNotBlank() }
    val superAdminPassword = Env.get("SUPERADMIN_PASSWORD")?.takeIf { it.isNotBlank() }
    val bootstrapSuperAdmin = Env.get("BOOTSTRAP_SUPERADMIN").equals("true", true)

    if (bootstrapSuperAdmin && (superAdminEmail == null || superAdminPassword == null)) {
        error("SUPERADMIN_EMAIL and SUPERADMIN_PASSWORD are required when BOOTSTRAP_SUPERADMIN=true")
    }
    if (bootstrapSuperAdmin && superAdminEmail != null && superAdminPassword != null) {
        container.userRepo.ensureSuperAdmin(
            email = superAdminEmail,
            passwordHash = PasswordHasher.hash(superAdminPassword),
            fullName = Env.get("SUPERADMIN_NAME") ?: "System Super Admin",
            company = Env.get("SUPERADMIN_COMPANY") ?: "AITSM",
            department = Env.get("SUPERADMIN_DEPARTMENT") ?: "Platform"
        )
    }

    routing {
        val frontendClassLoader = this@module.environment.classLoader

        suspend fun ApplicationCall.redirectKeepingQuery(target: String, permanent: Boolean = false) {
            val query = request.queryString().takeIf { it.isNotBlank() }
            respondRedirect(if (query == null) target else "$target?$query", permanent = permanent)
        }

        val pageResources = mapOf(
            "/login" to "login/html/login.html",
            "/signup" to "signup/html/signup.html",
            "/dashboard-admin" to "dashboard-admin/html/dashboard-admin.html",
            "/dashboard-user" to "dashboard-user/html/dashboard-user.html",
            "/ticket-management" to "ticket-management/html/ticket-management.html",
            "/tickets" to "tickets/html/tickets.html",
            "/create-ticket" to "create-ticket/html/create-ticket.html",
            "/assets" to "assets/html/assets.html",
            "/monitoring" to "monitoring/html/monitoring.html",
            "/inventory" to "inventory/html/inventory.html",
            "/knowledge" to "knowledge/html/knowledge.html",
            "/knowledge-library" to "knowledge-library/html/knowledge-library.html",
            "/settings" to "settings/html/settings.html",
            "/profile" to "profile/html/profile.html",
            "/security" to "security/html/security.html",
            "/notifications" to "notifications/html/notifications.html",
            "/user-management" to "user-management/html/user-management.html",
            "/ai-assistant" to "ai-assistant/html/ai-assistant.html",
            "/colleagues" to "colleagues/html/colleagues.html"
        )

        get("/") { call.respondRedirect("/login") }
        get("/index.html") { call.respondRedirect("/login", permanent = true) }
        get("/index/html/index.html") { call.respondRedirect("/login", permanent = true) }
        pageResources.forEach { (cleanPath, resourcePath) ->
            get(cleanPath) {
                val resource = frontendClassLoader.getResourceAsStream("static/frontend/$resourcePath")
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respondBytes(resource.use { it.readBytes() }, ContentType.Text.Html)
            }
            get("$cleanPath.html") { call.redirectKeepingQuery(cleanPath, permanent = true) }
            get("/${resourcePath}") { call.redirectKeepingQuery(cleanPath, permanent = true) }
        }
        get("/api/health") {
            call.respond(mapOf(
                "status" to "ok",
                "environment" to (Env.get("DEPLOYMENT_ENV")?.lowercase() ?: "production"),
                "application" to (Env.get("COMPANY_NAME") ?: "AITSM")
            ))
        }
        get("/metrics") {
            if (!call.requirePermission(Permission.SECURITY_MANAGE)) return@get
            call.respond(metrics.mapValues { it.value.get() })
        }
        get("/docs") {
            call.respond(mapOf("message" to "OpenAPI publishing is tracked for a follow-up release."))
        }
        staticResources("/", "static/frontend")
        authRoutes(container.authService, container.sessionService, container.invitationService, container.mfaService, container.reauthenticationService)
        profilePhotoRoutes(container.profilePhotoService)
        ticketRoutes(container.ticketService, container.slaAutomationService)
        monitoringRoutes(container.monitoringService, container.deviceRepo, container.assetDetectionService, container.inventoryService)
        analyticsRoutes(container.ticketService, container.monitoringService, container.aiService)
        deviceRoutes(container.deviceRepo, container.userRepo, container.monitoringService, container.assetDetectionService, container.inventoryService)
        notificationRoutes(container.notificationService)
        colleagueRoutes(container.colleagueService)
        knowledgeRoutes(container.knowledgeService)
        inventoryRoutes(container.inventoryService, container.reauthenticationService)
        slaRoutes(container.slaService, container.ticketService, container.reauthenticationService)
        settingsRoutes(container.assetDetectionService, container.reauthenticationService)
        aiRoutes(container.aiChatService, container.aiConfigService, container.ticketAiAdvisor, container.reauthenticationService, container.ollamaBridgeService)
        auditRoutes(container.auditRepo)
    }
}

internal fun validateRuntimeConfiguration(appEnv: String, value: (String) -> String?) {
    require(appEnv in setOf("development", "test", "production")) {
        "APP_ENV must be development, test, or production"
    }
    if (appEnv != "production") return

    val required = listOf(
        "DB_URL", "DB_USER", "DB_PASSWORD", "APP_BASE_URL", "COMPANY_NAME",
        "CORS_ALLOWED_ORIGINS", "MFA_ENCRYPTION_KEY", "SMTP_HOST", "SMTP_PORT",
        "SMTP_USERNAME", "SMTP_PASSWORD", "SMTP_FROM", "SMTP_STARTTLS"
    )
    val missing = required.filter { value(it).isNullOrBlank() }
    require(missing.isEmpty()) { "Missing mandatory production configuration: ${missing.joinToString(", ")}" }

    val baseUri = URI(value("APP_BASE_URL")!!)
    require(baseUri.scheme == "https" && baseUri.host != null && baseUri.userInfo == null &&
        baseUri.query == null && baseUri.fragment == null && (baseUri.path.isNullOrBlank() || baseUri.path == "/")) {
        "APP_BASE_URL must be an HTTPS origin without credentials, path, query, or fragment"
    }

    val corsOrigins = value("CORS_ALLOWED_ORIGINS")!!.split(',').map(String::trim)
    require(corsOrigins.all(String::isNotBlank)) { "CORS_ALLOWED_ORIGINS must not contain empty entries" }
    corsOrigins.forEach { origin ->
        val uri = URI(origin)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && (uri.path.isNullOrBlank() || uri.path == "/")) {
            "Every CORS_ALLOWED_ORIGINS entry must be an HTTPS origin"
        }
    }

    val databaseUri = runCatching { URI(value("DB_URL")!!.removePrefix("jdbc:")) }.getOrNull()
    val databaseParameters = databaseUri?.rawQuery.orEmpty().split('&').filter(String::isNotBlank)
        .mapNotNull { parameter -> parameter.split('=', limit = 2).takeIf { it.size == 2 } }
    val sslModes = databaseParameters.filter { it[0].equals("sslmode", true) }.map { it[1] }
    val socketFactories = databaseParameters.filter { it[0].equals("socketFactory", true) }.map { it[1] }
    val cloudSqlInstances = databaseParameters.filter { it[0].equals("cloudSqlInstance", true) }.map { it[1] }
    val cloudSqlConnector = socketFactories.singleOrNull() == "com.google.cloud.sql.postgres.SocketFactory" &&
        cloudSqlInstances.singleOrNull()?.split(':')?.let { it.size == 3 && it.all(String::isNotBlank) } == true &&
        (sslModes.isEmpty() || (sslModes.size == 1 && sslModes.single().equals("disable", true)))
    val verifiedTls = databaseUri?.host != null && sslModes.size == 1 &&
        sslModes.single().equals("verify-full", true) && socketFactories.isEmpty() && cloudSqlInstances.isEmpty()
    require(databaseUri?.scheme == "postgresql" && databaseUri.userInfo == null &&
        !databaseUri.path.isNullOrBlank() && databaseUri.path != "/" && (verifiedTls || cloudSqlConnector)) {
        "Production DB_URL must use sslmode=verify-full or the authenticated Cloud SQL Java connector"
    }
    val mfaKey = runCatching { Base64.getDecoder().decode(value("MFA_ENCRYPTION_KEY")) }.getOrNull()
    require(mfaKey?.size == 32) { "MFA_ENCRYPTION_KEY must be base64-encoded 32 bytes" }
    require(!value("AUTH_EXPOSE_DEV_VERIFICATION_CODE").equals("true", true)) {
        "AUTH_EXPOSE_DEV_VERIFICATION_CODE must be false in production"
    }
    require((value("SMTP_PORT")?.toIntOrNull() ?: 0) in 1..65535) { "SMTP_PORT must be a valid TCP port" }
    require(value("SMTP_STARTTLS").equals("true", true)) { "SMTP_STARTTLS must be true in production" }
    require((value("DEPLOYMENT_ENV") ?: "production").lowercase() in setOf("production", "beta")) {
        "DEPLOYMENT_ENV must be production or beta"
    }

    if (value("BOOTSTRAP_SUPERADMIN").equals("true", true)) {
        require(!value("SUPERADMIN_EMAIL").isNullOrBlank()) { "SUPERADMIN_EMAIL is required for bootstrap" }
        require((value("SUPERADMIN_PASSWORD")?.length ?: 0) >= 16) {
            "SUPERADMIN_PASSWORD must contain at least 16 characters for bootstrap"
        }
    }
}
