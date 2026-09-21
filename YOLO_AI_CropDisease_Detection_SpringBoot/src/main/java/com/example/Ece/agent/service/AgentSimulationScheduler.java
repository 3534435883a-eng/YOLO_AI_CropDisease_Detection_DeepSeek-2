package com.example.Ece.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Advances only the active RUNNING simulation; failures are surfaced as data/alerts by the API. */
@Component
public class AgentSimulationScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentSimulationScheduler.class);
    private final AgentRunService agentRunService;

    public AgentSimulationScheduler(AgentRunService agentRunService) {
        this.agentRunService = agentRunService;
    }

    @Scheduled(fixedDelayString = "${agent.scheduler.delay-ms:5000}")
    public void tick() {
        try {
            agentRunService.tickActiveRun();
        } catch (RuntimeException error) {
            // A missing migration or a transient database outage must not stop Spring's scheduler.
            LOGGER.warn("agent simulation tick skipped: {}", error.getMessage());
        }
    }
}
