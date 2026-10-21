package com.stellartrack.order.control;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface GlobalControlRepository extends JpaRepository<GlobalControlEntity, Short> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from GlobalControlEntity g where g.id = 1")
    Optional<GlobalControlEntity> lockSingleton();

    @Query("select g from GlobalControlEntity g where g.id = 1")
    Optional<GlobalControlEntity> singleton();
}
