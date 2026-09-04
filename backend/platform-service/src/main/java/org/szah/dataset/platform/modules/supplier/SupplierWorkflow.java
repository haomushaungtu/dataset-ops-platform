package org.szah.dataset.platform.modules.supplier;

import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Component;
import org.szah.dataset.platform.common.api.BusinessException;

import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;

@Component
final class SupplierWorkflow {
    private final RuntimeService runtimeService;
    private final TaskService taskService;

    SupplierWorkflow(RuntimeService runtimeService, TaskService taskService) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
    }

    String start(UUID applicationId, String applicantId) {
        return runtimeService.startProcessInstanceByKey(
                "supplierOnboarding", applicationId.toString(), Map.of("applicantId", applicantId)).getId();
    }

    void resubmit(String processInstanceId, String actorId) {
        Task task = requireTask(processInstanceId, "supplierRevision");
        if (!actorId.equals(task.getAssignee())) {
            throw new BusinessException("WORKFLOW_TASK_FORBIDDEN", "整改任务不属于当前申请人", CONFLICT);
        }
        taskService.complete(task.getId());
    }

    void startReview(String processInstanceId, String actorId) {
        Task task = requireTask(processInstanceId, "supplierReview");
        if (task.getAssignee() == null) {
            taskService.claim(task.getId(), actorId);
        } else if (!actorId.equals(task.getAssignee())) {
            throw new BusinessException("WORKFLOW_TASK_CLAIMED", "审核任务已被其他人员领取", CONFLICT);
        }
    }

    void decide(String processInstanceId, String actorId, SupplierApplicationStatus decision) {
        Task task = requireTask(processInstanceId, "supplierReview");
        if (!actorId.equals(task.getAssignee())) {
            throw new BusinessException("WORKFLOW_TASK_FORBIDDEN", "必须先领取审核任务", CONFLICT);
        }
        taskService.complete(task.getId(), Map.of("decision", decision.name()));
    }

    void cancel(String processInstanceId, String reason) {
        if (processInstanceId != null && runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult() != null) {
            runtimeService.deleteProcessInstance(processInstanceId, reason);
        }
    }

    private Task requireTask(String processInstanceId, String taskDefinitionKey) {
        Task task = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .singleResult();
        if (task == null) {
            throw new BusinessException("WORKFLOW_TASK_NOT_FOUND", "流程任务不存在或已经处理", CONFLICT);
        }
        return task;
    }
}
