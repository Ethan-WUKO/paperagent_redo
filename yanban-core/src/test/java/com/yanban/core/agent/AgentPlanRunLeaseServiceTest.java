package com.yanban.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@ContextConfiguration(classes = AgentPlanRunLeaseServiceTest.TestConfig.class)
@Import(AgentPlanRunLeaseService.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgentPlanRunLeaseServiceTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = AgentPlan.class)
    @EnableJpaRepositories(basePackageClasses = AgentPlanRepository.class)
    static class TestConfig { }

    private final AgentPlanRepository plans;
    private final AgentPlanStepRepository steps;
    private final AgentPlanEventRepository events;
    private final AgentPlanRunLeaseService leases;

    @Autowired
    AgentPlanRunLeaseServiceTest(AgentPlanRepository plans, AgentPlanStepRepository steps,
                                 AgentPlanEventRepository events, AgentPlanRunLeaseService leases) {
        this.plans = plans;
        this.steps = steps;
        this.events = events;
        this.leases = leases;
    }

    @Test
    void claimRenewReleaseAndTerminalStateAreDatabaseGoverned() {
        AgentPlan plan = durablePlan(101L);

        AgentPlanExecutionLease first = leases.claim(plan.getId(), 101L, "owner-a", Duration.ofSeconds(5))
                .orElseThrow();
        assertThat(leases.claim(plan.getId(), 101L, "owner-b", Duration.ofSeconds(5))).isEmpty();
        assertThat(leases.renew(first, Duration.ofSeconds(5))).isTrue();

        leases.release(first, "TEST_RELEASED");
        AgentPlanExecutionLease second = leases.claim(plan.getId(), 101L, "owner-b", Duration.ofSeconds(5))
                .orElseThrow();
        assertThat(second.fence()).isGreaterThan(first.fence());

        AgentPlan terminal = plans.findById(plan.getId()).orElseThrow();
        terminal.markCompleted();
        leases.finish(second, terminal, "one canonical answer",
                "0".repeat(64), "COMPLETED");

        AgentPlan stored = plans.findById(plan.getId()).orElseThrow();
        assertThat(stored.getCanonicalAnswer()).isEqualTo("one canonical answer");
        assertThat(stored.getLeaseOwner()).isNull();
        assertThat(leases.claim(plan.getId(), 101L, "owner-c", Duration.ofSeconds(5))).isEmpty();
    }

    @Test
    void queuedRunIsDiscoverableAfterRestartBeforeItsFirstClaim() {
        AgentPlan plan = durablePlan(107L);

        assertThat(leases.queue(plan.getId(), 107L)).isTrue();

        AgentPlan queued = plans.findById(plan.getId()).orElseThrow();
        assertThat(queued.getStatus()).isEqualTo("RUNNING");
        assertThat(queued.getRecoveryStatus()).isEqualTo("QUEUED");
        assertThat(leases.expiredRuns()).contains(new AgentPlanRunLeaseService.RecoverableRun(plan.getId(), 107L));
        assertThat(leases.claim(plan.getId(), 107L, "restarted-owner", Duration.ofSeconds(5))).isPresent();
    }

    @Test
    void concurrentClaimHasExactlyOneWinner() throws Exception {
        AgentPlan plan = durablePlan(102L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> claimAfterBarrier(plan, 102L, "owner-a", ready, start));
            Future<Boolean> second = executor.submit(() -> claimAfterBarrier(plan, 102L, "owner-b", ready, start));
            ready.await();
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredLeaseIsReclaimedAndOldFenceCannotWrite() throws Exception {
        AgentPlan plan = durablePlan(103L);
        AgentPlanStep step = steps.saveAndFlush(new AgentPlanStep(
                plan.getId(), "step", 1, "step", "read", "ANALYSIS", "[]", "[]", "done"));
        AgentPlanExecutionLease first = leases.claim(
                plan.getId(), 103L, "owner-a", Duration.ofMillis(100)).orElseThrow();

        Thread.sleep(250L);
        assertThatThrownBy(() -> leases.rejectRecovery(first, "must not write after expiry"))
                .isInstanceOf(AgentPlanLeaseLostException.class);
        AgentPlanExecutionLease second = leases.claim(
                plan.getId(), 103L, "owner-b", Duration.ofSeconds(5)).orElseThrow();

        assertThat(second.fence()).isGreaterThan(first.fence());
        assertThat(leases.renew(first, Duration.ofSeconds(5))).isFalse();
        step.markRunning();
        assertThatThrownBy(() -> leases.saveOwnedStep(first, step))
                .isInstanceOf(AgentPlanLeaseLostException.class);
        leases.release(first, "STALE_RELEASE");
        assertThat(plans.findById(plan.getId()).orElseThrow().getLeaseOwner()).isEqualTo("owner-b");
    }

    @Test
    void cancellationInvalidatesOwnerAndCannotBeReclaimed() {
        AgentPlan plan = durablePlan(104L);
        AgentPlanExecutionLease lease = leases.claim(
                plan.getId(), 104L, "owner-a", Duration.ofSeconds(5)).orElseThrow();

        leases.cancel(plan.getId(), 104L, "cancelled in test");

        assertThatThrownBy(() -> leases.assertOwned(lease)).isInstanceOf(AgentPlanLeaseLostException.class);
        assertThat(leases.claim(plan.getId(), 104L, "owner-b", Duration.ofSeconds(5))).isEmpty();
        assertThat(plans.findById(plan.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void staleRunCannotBeClaimed() {
        AgentPlan plan = durablePlan(106L);
        AgentPlanExecutionLease lease = leases.claim(
                plan.getId(), 106L, "owner-a", Duration.ofSeconds(5)).orElseThrow();

        leases.release(lease, "STALE");

        assertThat(leases.claim(plan.getId(), 106L, "owner-b", Duration.ofSeconds(5))).isEmpty();
        assertThat(plans.findById(plan.getId()).orElseThrow().getRecoveryStatus()).isEqualTo("STALE");
    }

    @Test
    void idempotentEventsAndCanonicalAnswerCannotDiverge() {
        AgentPlan plan = durablePlan(105L);
        AgentPlanExecutionLease lease = leases.claim(
                plan.getId(), 105L, "owner-a", Duration.ofSeconds(5)).orElseThrow();
        AgentPlanEvent first = new AgentPlanEvent(plan.getId(), null, "plan_started", "{}", "evt:same");
        AgentPlanEvent replay = new AgentPlanEvent(plan.getId(), null, "plan_started", "{}", "evt:same");

        assertThat(leases.saveOwnedEvent(lease, first)).isTrue();
        assertThat(leases.saveOwnedEvent(lease, replay)).isFalse();
        assertThat(events.findByPlanIdOrderByCreatedAtAsc(plan.getId())).hasSize(1);

        AgentPlan source = plans.findById(plan.getId()).orElseThrow();
        source.markCompleted();
        leases.finish(lease, source, "canonical", "a".repeat(64), "COMPLETED");
        AgentPlan stored = plans.findById(plan.getId()).orElseThrow();
        assertThatThrownBy(() -> stored.publishCanonicalAnswer("different", "b".repeat(64)))
                .isInstanceOf(IllegalStateException.class);
    }

    private boolean claimAfterBarrier(AgentPlan plan, Long userId, String owner,
                                      CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return leases.claim(plan.getId(), userId, owner, Duration.ofSeconds(5)).isPresent();
    }

    private AgentPlan durablePlan(Long userId) {
        AgentPlan plan = new AgentPlan(900L + userId, userId, "goal", "summary", true, null, "{}");
        plan.enableDurableExecution();
        return plans.saveAndFlush(plan);
    }
}
