package org.szah.dataset.platform.modules.supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.szah.dataset.platform.common.api.BusinessException;

import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.FORBIDDEN;

@Service
public class SupplierApplicationHistoryService {
    private final SupplierApplicationHistoryRepository repository;

    public SupplierApplicationHistoryService(SupplierApplicationHistoryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<SupplierApplicationHistoryView> list(
            UUID applicationId, String actorId, boolean operator) {
        String applicantId = repository.requireApplicantId(applicationId);
        if (!operator && !applicantId.equals(actorId)) {
            throw new BusinessException("OBJECT_ACCESS_DENIED", "无权访问该供应商申请历史", FORBIDDEN);
        }
        return repository.findByApplicationId(applicationId);
    }
}
