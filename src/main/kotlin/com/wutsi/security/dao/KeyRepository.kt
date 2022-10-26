package com.wutsi.security.dao

import com.wutsi.security.entity.KeyEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.util.Date

@Repository
interface KeyRepository : CrudRepository<KeyEntity, Long> {
    fun findByExpiresLessThan(expires: Date, pagination: Pageable): List<KeyEntity>
}
