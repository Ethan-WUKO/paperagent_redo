package com.yanban.api.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.literature.LiteratureSearchTaskMessage;
import com.yanban.paper.literature.LiteratureSearchTaskWorker;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class LiteratureSearchTaskConsumer {

    private final ObjectMapper objectMapper;
    private final LiteratureSearchTaskWorker worker;

    public LiteratureSearchTaskConsumer(ObjectMapper objectMapper, LiteratureSearchTaskWorker worker) {
        this.objectMapper = objectMapper;
        this.worker = worker;
    }

    @KafkaListener(topics = "${yanban.literature.task.topic:literature-search-tasks}", groupId = "${yanban.literature.task.group-id:yanban-literature-search}")
    public void onMessage(String payload) {
        try {
            LiteratureSearchTaskMessage message = objectMapper.readValue(payload, LiteratureSearchTaskMessage.class);
            worker.submit(message.taskId());
        } catch (Exception ex) {
            throw new IllegalStateException("处理文献检索任务消息失败", ex);
        }
    }
}
