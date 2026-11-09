package org.example.duobaan.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.example.duobaan.model.ApiProfile;
import org.example.duobaan.model.ApiProfileType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ApiProfileRepository extends JpaRepository<ApiProfile, Long> {

    List<ApiProfile> findByProfileTypeOrderByUpdatedAtDesc(ApiProfileType type);

    /**
     * 一次取出多类配置（设置页「AI 模型」统一列表用）：按类型分组、组内按更新时间倒序。
     *
     * @param types 需要查询的配置类型，调用方保证非空
     */
    List<ApiProfile> findByProfileTypeInOrderByProfileTypeAscUpdatedAtDesc(Collection<ApiProfileType> types);

    Optional<ApiProfile> findByProfileTypeAndIsActiveTrue(ApiProfileType type);

    long countByProfileType(ApiProfileType type);

    @Transactional
    @Modifying
    @Query("UPDATE ApiProfile SET isActive = false WHERE profileType = :type AND isActive = true")
    int clearActiveByType(@Param("type") ApiProfileType type);
}
