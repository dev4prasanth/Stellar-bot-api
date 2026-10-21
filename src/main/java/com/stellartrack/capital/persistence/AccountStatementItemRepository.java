package com.stellartrack.capital.persistence;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountStatementItemRepository extends JpaRepository<AccountStatementItemEntity, String> {

    Page<AccountStatementItemEntity> findByCategory(AccountStatementItemEntity.Category category, Pageable pageable);

    @Query("select max(i.itemDate) from AccountStatementItemEntity i where i.category = :category")
    Optional<Instant> latestItemDate(@Param("category") AccountStatementItemEntity.Category category);
}
