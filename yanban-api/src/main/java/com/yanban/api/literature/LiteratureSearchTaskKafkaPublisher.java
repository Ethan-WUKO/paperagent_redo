package com.yanban.api.literature;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.literature.LiteratureSearchTaskMessage;
import com.yanban.paper.literature.LiteratureSearchTaskPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class LiteratureSearchTaskKafkaPublisher implements LiteratureSearchTaskPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public LiteratureSearchTaskKafkaPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                              ObjectMapper objectMapper,
                                              @Value("${yanban.literature.task.topic:literature-search-tasks}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public void publishTaskCreated(LiteratureSearchTask task) {
        kafkaTemplate.send(topic, String.valueOf(task.getId()), message(task));
    }

    private String message(LiteratureSearchTask task) {
        try {
            return objectMapper.writeValueAsString(new LiteratureSearchTaskMessage(task.getId(), task.getUserId()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("构造文献检索任务消息失败", ex);
        }
    }
}
