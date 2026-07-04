package com.yanban.api.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "yanban.observability")
public class ObservabilityProperties {

    private int defaultWindowMinutes = 60;
    private double planFailureRateWarning = 0.10d;
    private double planFailureRateCritical = 0.25d;
    private long runningPlanAgeWarningSeconds = 180L;
    private long runningPlanAgeCriticalSeconds = 600L;
    private long planP95WarningMs = 120_000L;
    private long planP95CriticalMs = 300_000L;
    private int guardrailEventWarning = 5;
    private int guardrailEventCritical = 20;

    public int getDefaultWindowMinutes() {
        return defaultWindowMinutes;
    }

    public void setDefaultWindowMinutes(int defaultWindowMinutes) {
        this.defaultWindowMinutes = defaultWindowMinutes;
    }

    public double getPlanFailureRateWarning() {
        return planFailureRateWarning;
    }

    public void setPlanFailureRateWarning(double planFailureRateWarning) {
        this.planFailureRateWarning = planFailureRateWarning;
    }

    public double getPlanFailureRateCritical() {
        return planFailureRateCritical;
    }

    public void setPlanFailureRateCritical(double planFailureRateCritical) {
        this.planFailureRateCritical = planFailureRateCritical;
    }

    public long getRunningPlanAgeWarningSeconds() {
        return runningPlanAgeWarningSeconds;
    }

    public void setRunningPlanAgeWarningSeconds(long runningPlanAgeWarningSeconds) {
        this.runningPlanAgeWarningSeconds = runningPlanAgeWarningSeconds;
    }

    public long getRunningPlanAgeCriticalSeconds() {
        return runningPlanAgeCriticalSeconds;
    }

    public void setRunningPlanAgeCriticalSeconds(long runningPlanAgeCriticalSeconds) {
        this.runningPlanAgeCriticalSeconds = runningPlanAgeCriticalSeconds;
    }

    public long getPlanP95WarningMs() {
        return planP95WarningMs;
    }

    public void setPlanP95WarningMs(long planP95WarningMs) {
        this.planP95WarningMs = planP95WarningMs;
    }

    public long getPlanP95CriticalMs() {
        return planP95CriticalMs;
    }

    public void setPlanP95CriticalMs(long planP95CriticalMs) {
        this.planP95CriticalMs = planP95CriticalMs;
    }

    public int getGuardrailEventWarning() {
        return guardrailEventWarning;
    }

    public void setGuardrailEventWarning(int guardrailEventWarning) {
        this.guardrailEventWarning = guardrailEventWarning;
    }

    public int getGuardrailEventCritical() {
        return guardrailEventCritical;
    }

    public void setGuardrailEventCritical(int guardrailEventCritical) {
        this.guardrailEventCritical = guardrailEventCritical;
    }
}
