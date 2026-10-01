package com.tripbus.fleet.repository;

import com.tripbus.fleet.domain.OutboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    @Modifying
    @Query("update OutboxMessage m set m.publicadoEn = :now where m.id = :id")
    int markPublished(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("delete from OutboxMessage m where m.publicadoEn < :antes")
    int deletePublishedBefore(@Param("antes") Instant antes);
}
