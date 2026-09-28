package com.example.shortener.persistence;

import com.example.shortener.domain.ShortUrl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ShortUrlJpaRepository extends JpaRepository<ShortUrl, Long> {

    Optional<ShortUrl> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * Single-statement atomic increment. Avoids the read-modify-write lost-update race that
     * {@code link.setClickCount(link.getClickCount() + 1); save(link)} would have under concurrent redirects.
     */
    @Modifying
    @Query("update ShortUrl s set s.clickCount = s.clickCount + 1 where s.code = :code")
    int incrementClickCount(@Param("code") String code);
}
