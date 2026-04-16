package com.xaicomply.repository;

import com.xaicomply.domain.NormalizerStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NormalizerStatsRepository extends JpaRepository<NormalizerStats, String> {
}
