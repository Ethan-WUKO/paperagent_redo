package com.yanban.api.invite;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {

    Optional<InviteCode> findByCode(String code);

    /**
     * Atomically increments the used count only if the code is valid and has remaining uses.
     * Returns 1 if incremented successfully, 0 otherwise (code not found, disabled, or exhausted).
     */
    @Modifying
    @Query("UPDATE InviteCode c SET c.usedCount = c.usedCount + 1 "
            + "WHERE c.code = :code AND c.enabled = true AND c.usedCount < c.maxUses")
    int incrementUsedCount(@Param("code") String code);
}
