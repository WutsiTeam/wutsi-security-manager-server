package com.wutsi.security.service

import com.auth0.jwt.JWT
import com.wutsi.platform.core.error.Error
import com.wutsi.platform.core.error.exception.ConflictException
import com.wutsi.platform.core.error.exception.ForbiddenException
import com.wutsi.platform.core.error.exception.NotFoundException
import com.wutsi.platform.core.logging.DefaultKVLogger
import com.wutsi.platform.core.messaging.MessagingType
import com.wutsi.platform.core.security.SubjectType
import com.wutsi.platform.core.security.TokenBlacklistService
import com.wutsi.platform.core.security.spring.jwt.JWTBuilder
import com.wutsi.security.dao.LoginRepository
import com.wutsi.security.dto.CreateOTPRequest
import com.wutsi.security.dto.LoginRequest
import com.wutsi.security.dto.VerifyOTPRequest
import com.wutsi.security.entity.LoginEntity
import com.wutsi.security.entity.OtpEntity
import com.wutsi.security.entity.PasswordEntity
import com.wutsi.security.error.ErrorURN
import org.apache.commons.codec.digest.DigestUtils
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.util.Date
import java.util.Optional

@Service
class LoginService(
    private val otpService: OtpService,
    private val passwordService: PasswordService,
    private val keyProvider: RSAKeyProviderImpl,
    private val blacklistService: TokenBlacklistService,
    private val dao: LoginRepository
) {
    companion object {
        const val USER_TOKEN_TTL_MILLIS = 84600000L // 1 day
    }

    fun login(request: LoginRequest): String {
        if (request.mfaToken.isEmpty()) {
            val otp = send(request)
            throw ForbiddenException(
                error = Error(
                    code = ErrorURN.AUTHENTICATION_MFA_REQUIRED.urn,
                    data = mapOf(
                        "mfaToken" to otp.token
                    )
                )
            )
        } else {
            return verify(request)
        }
    }

    fun logout(accessToken: String): LoginEntity? {
        val login = findByAccessToken(accessToken).orElse(null) ?: return null
        return logout(login)
    }

    fun findByAccessToken(accessToken: String): Optional<LoginEntity> =
        dao.findByHash(hash(accessToken))

    @Async
    fun logoutPreviousSession(login: LoginEntity, traceId: String) {
        val logger = DefaultKVLogger()
        var count = 0
        try {
            val logins = dao.findByAccountIdAndExpiredIsNull(login.accountId)
            logins.forEach {
                if (it.id != login.id) {
                    logout(it)
                    count++
                }
            }
        } finally {
            logger.add("trace_id", traceId)
            logger.add("account_id", login.accountId)
            logger.add("additional_logout_count", count)
            logger.log()
        }
    }

    private fun logout(login: LoginEntity): LoginEntity {
        // Expire
        login.expired = Date()
        dao.save(login)

        // Blacklist
        val jwt = JWT.decode(login.accessToken)
        val ttl = (jwt.expiresAt.time - System.currentTimeMillis()) / 1000
        if (ttl > 0) {
            blacklistService.add(login.accessToken, ttl)
        }
        return login
    }

    private fun send(request: LoginRequest): OtpEntity {
        val password = passwordService.findByUsername(request.phoneNumber)
            .orElseThrow {
                NotFoundException(
                    error = Error(
                        code = ErrorURN.PASSWORD_NOT_FOUND.urn
                    )
                )
            }
        val otpRequest = CreateOTPRequest(
            address = password.username,
            type = MessagingType.SMS.name
        )
        val otp = otpService.create(otpRequest)

        return otp
    }

    private fun verify(request: LoginRequest): String {
        val otp = otpService.verify(
            token = request.mfaToken,
            request = VerifyOTPRequest(
                code = request.verificationCode
            )
        )

        val password = passwordService.findByUsername(otp.address)
            .orElseThrow {
                ConflictException(
                    error = Error(
                        code = ErrorURN.PASSWORD_NOT_FOUND.urn
                    )
                )
            }

        val accessToken = JWTBuilder(
            ttl = USER_TOKEN_TTL_MILLIS,
            subjectType = SubjectType.USER,
            name = otp.address,
            subject = password.accountId.toString(),
            keyProvider = keyProvider
        ).build()

        createLogin(accessToken, password)

        return accessToken
    }

    private fun createLogin(accessToken: String, password: PasswordEntity) =
        dao.save(
            LoginEntity(
                accountId = password.accountId,
                hash = hash(accessToken),
                accessToken = accessToken,
                created = Date(),
                expires = Date(System.currentTimeMillis() + USER_TOKEN_TTL_MILLIS)
            )
        )

    fun hash(accessToken: String): String =
        DigestUtils.md5Hex(accessToken)
}
