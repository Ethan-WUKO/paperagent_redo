package com.yanban.paper.literature;

import com.yanban.paper.domain.LiteratureSearchTask;

public interface LiteratureSearchTaskPublisher {
    void publishTaskCreated(LiteratureSearchTask task);
}
