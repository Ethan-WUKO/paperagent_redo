package com.yanban.api.settings;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserModelRepository extends JpaRepository<UserModel, Long> {

    List<UserModel> findByUserIdOrderBySortOrderAscIdAsc(Long userId);
}
