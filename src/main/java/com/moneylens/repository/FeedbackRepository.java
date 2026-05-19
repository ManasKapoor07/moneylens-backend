package com.moneylens.repository;

import com.moneylens.entity.Feedback;
import com.moneylens.entity.Feedback.Personalization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public interface FeedbackRepository extends JpaRepository<Feedback, UUID> {

    // ── personalization counts ──────────────────────────────────────────────
    @Query("""
        SELECT f.personalization AS label, COUNT(f) AS cnt
        FROM Feedback f
        GROUP BY f.personalization
        ORDER BY cnt DESC
    """)
    List<PersonalizationCount> countByPersonalization();

    interface PersonalizationCount {
        Personalization getLabel();
        Long getCnt();
    }

    // ── contact opt-ins ─────────────────────────────────────────────────────
    long countByContactOkTrue();

    // ── latest describe-to-friend (non-null) ────────────────────────────────
    @Query("""
        SELECT f.describeToFriend FROM Feedback f
        WHERE f.describeToFriend IS NOT NULL
        ORDER BY f.submittedAt DESC
        LIMIT 20
    """)
    List<String> findRecentDescriptions();

    // ── latest holy-shit answers (non-null) ─────────────────────────────────
    @Query("""
        SELECT f.holyShitInsight FROM Feedback f
        WHERE f.holyShitInsight IS NOT NULL
        ORDER BY f.submittedAt DESC
        LIMIT 20
    """)
    List<String> findRecentHolyShit();

    // ── unnest wanted_insights array and count ──────────────────────────────
    // Native query because JPQL can't unnest arrays
    @Query(value = """
        SELECT tag, COUNT(*) AS cnt
        FROM feedback, unnest(wanted_insights) AS tag
        GROUP BY tag
        ORDER BY cnt DESC
    """, nativeQuery = true)
    List<InsightCount> countWantedInsights();

    interface InsightCount {
        String getTag();
        Long getCnt();
    }
}