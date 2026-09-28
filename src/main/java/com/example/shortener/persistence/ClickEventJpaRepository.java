package com.example.shortener.persistence;

import com.example.shortener.domain.ClickEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ClickEventJpaRepository extends JpaRepository<ClickEvent, Long> {

    long countByCodeAndOccurredAtGreaterThanEqual(String code, Instant since);

    @Query("select c.referrer, count(c) from ClickEvent c "
            + "where c.code = :code group by c.referrer order by count(c) desc")
    List<Object[]> topReferrers(@Param("code") String code, Pageable pageable);
}
