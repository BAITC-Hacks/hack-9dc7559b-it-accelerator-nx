package com.hackalem.web.sources;
import com.hackalem.domain.knowledge.KnowledgeRepository;
import org.mapstruct.Mapper;
@Mapper(componentModel="spring")
public interface KnowledgeResponseMapper {
    KnowledgeResponses.Job job(KnowledgeRepository.Job job);
}
