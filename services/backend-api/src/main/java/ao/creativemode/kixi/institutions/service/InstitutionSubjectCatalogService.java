package ao.creativemode.kixi.institutions.service;

import ao.creativemode.kixi.institutions.model.InstitutionSubject;
import ao.creativemode.kixi.institutions.repository.InstitutionSubjectRepository;
import ao.creativemode.kixi.shared.service.InstitutionSubjectCatalog;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** The school catalog side of the curriculum: a course can only teach what its school teaches. */
@Service
public class InstitutionSubjectCatalogService implements InstitutionSubjectCatalog {

    private final InstitutionSubjectRepository links;

    public InstitutionSubjectCatalogService(InstitutionSubjectRepository links) {
        this.links = links;
    }

    @Override
    public Mono<Void> ensureInCatalog(Long institutionId, Long subjectId) {
        return links.findFirstByInstitutionIdAndSubjectId(institutionId, subjectId)
            .flatMap(link -> {
                if (link.isDeleted()) {
                    link.restore();
                    return links.save(link);
                }
                return Mono.just(link);
            })
            .switchIfEmpty(Mono.defer(() -> links.save(new InstitutionSubject(institutionId, subjectId))))
            .then();
    }
}
