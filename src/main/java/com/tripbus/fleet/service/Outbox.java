package com.tripbus.fleet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.fleet.domain.OutboxMessage;
import com.tripbus.fleet.repository.OutboxRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** Escribe eventos en la tabla outbox dentro de la transacción del cambio de estado. */
@Component
public class Outbox {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public Outbox(OutboxRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void add(String eventType, Object payload) {
        try {
            repository.save(new OutboxMessage(eventType, objectMapper.writeValueAsString(payload), clock.instant()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
