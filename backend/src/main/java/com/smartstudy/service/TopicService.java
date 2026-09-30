package com.smartstudy.service;

import com.smartstudy.dto.CreateTopicRequest;
import com.smartstudy.dto.TopicResponse;
import com.smartstudy.model.Topic;
import com.smartstudy.repository.TopicRepository;
import com.smartstudy.util.ValidationException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Topic listing and creation. */
public class TopicService {

    static final int NAME_MAX = 100;

    private final TopicRepository topics;

    public TopicService(TopicRepository topics) {
        this.topics = topics;
    }

    public List<TopicResponse> list() {
        return topics.findAll().stream().map(TopicService::toResponse).toList();
    }

    public TopicResponse create(CreateTopicRequest request) {
        String name = request.name() == null ? null : request.name().trim();

        Map<String, String> errors = new LinkedHashMap<>();
        if (name == null || name.isEmpty()) {
            errors.put("name", "Name is required");
        } else if (name.length() > NAME_MAX) {
            errors.put("name", "Name must be at most " + NAME_MAX + " characters");
        } else if (name.codePoints().anyMatch(Character::isISOControl)) {
            errors.put("name", "Name contains invalid characters");
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        return toResponse(topics.create(name));
    }

    private static TopicResponse toResponse(Topic t) {
        return new TopicResponse(t.id(), t.name());
    }
}
