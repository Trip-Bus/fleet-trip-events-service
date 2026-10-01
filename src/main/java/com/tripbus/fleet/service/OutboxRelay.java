package com.tripbus.fleet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.fleet.common.events.EventPublisher;
import com.tripbus.fleet.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publica los mensajes pendientes del outbox en el bus de eventos. Usa
 * SELECT ... FOR UPDATE SKIP LOCKED, así varias réplicas pueden correrlo sin duplicar
 * trabajo; el eventId es el id del outbox (los consumidores deduplican por eventId).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final EventPublisher publisher;
    private final OutboxRepository outboxRepository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxRelay(JdbcTemplate jdbc, EventPublisher publisher, OutboxRepository outboxRepository,
                       TransactionTemplate tx, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.outboxRepository = outboxRepository;
        this.tx = tx;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tripbus.jobs.outbox-relay-ms:500}", initialDelay = 3000)
    public void relay() {
        try {
            Integer published;
            do {
                published = tx.execute(s -> publishBatch());
            } while (published != null && published == 100);
        } catch (RuntimeException e) {
            log.warn("No se pudo publicar el outbox (se reintentará): {}", e.getMessage());
        }
    }

    private int publishBatch() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, event_type, payload FROM outbox
                WHERE publicado_en IS NULL
                ORDER BY creado_en
                LIMIT 100
                FOR UPDATE SKIP LOCKED
                """);
        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            try {
                Object payload = objectMapper.readTree((String) row.get("payload"));
                publisher.publish(id.toString(), (String) row.get("event_type"), payload);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.error("Mensaje de outbox corrupto {}", id);
            }
            outboxRepository.markPublished(id, clock.instant());
        }
        return rows.size();
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "America/Bogota")
    public void purge() {
        tx.executeWithoutResult(s -> outboxRepository.deletePublishedBefore(clock.instant().minus(Duration.ofDays(2))));
    }
}
