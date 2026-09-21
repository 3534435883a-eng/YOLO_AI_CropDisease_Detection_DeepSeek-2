package com.example.Ece.agent.model;

import java.time.LocalDateTime;

/** A self-contained simulation state. All values are simulated, never sensor readings. */
public class SimulationState {
    private final LocalDateTime simulatedAt;
    private final double temperatureC;
    private final double airHumidityPct;
    private final double soilMoisturePct;
    private final double co2Ppm;
    private final double lightPpfd;
    private final double soilPh;
    private final double vpdKpa;
    private final double environmentRisk;
    private final double diseasePressure;
    private final String riskLevel;

    public SimulationState(LocalDateTime simulatedAt, double temperatureC, double airHumidityPct,
                           double soilMoisturePct, double co2Ppm, double lightPpfd, double soilPh,
                           double vpdKpa, double environmentRisk, double diseasePressure, String riskLevel) {
        this.simulatedAt = simulatedAt;
        this.temperatureC = temperatureC;
        this.airHumidityPct = airHumidityPct;
        this.soilMoisturePct = soilMoisturePct;
        this.co2Ppm = co2Ppm;
        this.lightPpfd = lightPpfd;
        this.soilPh = soilPh;
        this.vpdKpa = vpdKpa;
        this.environmentRisk = environmentRisk;
        this.diseasePressure = diseasePressure;
        this.riskLevel = riskLevel;
    }

    public LocalDateTime getSimulatedAt() {
        return simulatedAt;
    }

    public double getTemperatureC() {
        return temperatureC;
    }

    public double getAirHumidityPct() {
        return airHumidityPct;
    }

    public double getSoilMoisturePct() {
        return soilMoisturePct;
    }

    public double getCo2Ppm() {
        return co2Ppm;
    }

    public double getLightPpfd() {
        return lightPpfd;
    }

    public double getSoilPh() {
        return soilPh;
    }

    public double getVpdKpa() {
        return vpdKpa;
    }

    public double getEnvironmentRisk() {
        return environmentRisk;
    }

    public double getDiseasePressure() {
        return diseasePressure;
    }

    public String getRiskLevel() {
        return riskLevel;
    }
}
