package com.yanban.paper.literature;

import java.util.List;

public interface LiteratureSource {
    String name();

    List<LiteratureCandidate> search(String query, int limit);
}
