package com.fixup.messaging.infrastructure;

import com.fixup.messaging.domain.ChatMessage;
import com.fixup.messaging.domain.ChatMessages;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaChatMessages implements ChatMessages {
    private final ChatMessageJpaRepository repository;

    JpaChatMessages(ChatMessageJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(ChatMessage message) {
        repository.saveAndFlush(ChatMessageEntity.from(message));
    }

    @Override
    public List<ChatMessage> findByRequest(UUID requestId) {
        return repository.findByRequestIdOrderBySentAtAsc(requestId).stream()
                .map(ChatMessageEntity::toDomain).toList();
    }
}
