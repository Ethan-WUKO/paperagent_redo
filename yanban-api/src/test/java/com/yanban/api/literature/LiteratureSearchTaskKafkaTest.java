package com.yanban.api.literature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.literature.LiteratureSearchTaskMessage;
import com.yanban.paper.literature.LiteratureSearchTaskWorker;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class LiteratureSearchTaskKafkaTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publisherSendsTaskCreatedMessage() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        LiteratureSearchTaskKafkaPublisher publisher = new LiteratureSearchTaskKafkaPublisher(kafkaTemplate, objectMapper, "literature-search-tasks");
        LiteratureSearchTask task = new LiteratureSearchTask(11L, null, "hybrid RAG", "hybrid rag", 8, null, true, "PENDING", "QUEUED", "req-1", "idem");
        ReflectionTestUtils.setField(task, "id", 101L);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);

        publisher.publishTaskCreated(task);

        verify(kafkaTemplate).send(eq("literature-search-tasks"), eq("101"), payload.capture());
        LiteratureSearchTaskMessage message = objectMapper.readValue(payload.getValue(), LiteratureSearchTaskMessage.class);
        assertThat(message.taskId()).isEqualTo(101L);
        assertThat(message.userId()).isEqualTo(11L);
    }

    @Test
    void consumerSubmitsTaskToWorker() throws Exception {
        LiteratureSearchTaskWorker worker = mock(LiteratureSearchTaskWorker.class);
        LiteratureSearchTaskConsumer consumer = new LiteratureSearchTaskConsumer(objectMapper, worker);
        String payload = objectMapper.writeValueAsString(new LiteratureSearchTaskMessage(101L, 11L));

        consumer.onMessage(payload);

        verify(worker).submit(101L);
    }
}
