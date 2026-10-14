package ao.creativemode.kixi.institutions.service;

import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.shared.service.InstitutionNameReader;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class InstitutionNames implements InstitutionNameReader {

    private final InstitutionRepository institutions;

    public InstitutionNames(InstitutionRepository institutions) {
        this.institutions = institutions;
    }

    @Override
    public Mono<String> nameOf(Long institutionId) {
        return institutions.findById(institutionId).map(Institution::getName);
    }
}
