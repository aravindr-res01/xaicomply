package com.xaicomply.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "normalizer_stats")
public class NormalizerStats {

    @Id
    private String featureName;

    @Column(nullable = false)
    private long count;

    @Column(nullable = false)
    private double mean;

    @Column(nullable = false)
    private double m2; // for Welford algorithm (sum of squared deviations)

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public NormalizerStats() {}

    public NormalizerStats(String featureName, long count, double mean, double m2) {
        this.featureName = featureName;
        this.count = count;
        this.mean = mean;
        this.m2 = m2;
    }

    public double getVariance() {
        if (count < 2) return 1.0;
        return m2 / (count - 1);
    }

    public double getStdDev() {
        double variance = getVariance();
        return variance <= 0 ? 1.0 : Math.sqrt(variance);
    }

    public String getFeatureName() { return featureName; }
    public void setFeatureName(String featureName) { this.featureName = featureName; }

    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }

    public double getMean() { return mean; }
    public void setMean(double mean) { this.mean = mean; }

    public double getM2() { return m2; }
    public void setM2(double m2) { this.m2 = m2; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
