package ao.creativemode.kixi.academic.controller;

import ao.creativemode.kixi.academic.dto.coursesubject.CourseSubjectRequest;
import ao.creativemode.kixi.academic.dto.coursesubject.CourseSubjectResponse;
import ao.creativemode.kixi.academic.service.CurriculumService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * The curriculum of a course (issue #253): reading is for any signed-in account (a student choosing
 * where to study reads it), writing is for administrators only.
 */
@RestController
@RequestMapping("/api/v1/courses/{courseId}/subjects")
public class CourseCurriculumController {

    private final CurriculumService service;

    public CourseCurriculumController(CurriculumService service) {
        this.service = service;
    }

    @GetMapping
    public Mono<ResponseEntity<List<CourseSubjectResponse>>> list(@PathVariable Long courseId) {
        return service.listForCourse(courseId).collectList().map(ResponseEntity::ok);
    }

    @PostMapping
    public Mono<ResponseEntity<CourseSubjectResponse>> add(
            @PathVariable Long courseId, @Valid @RequestBody CourseSubjectRequest request) {
        return service.add(courseId, request)
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created));
    }

    @DeleteMapping("/{linkId}")
    public Mono<ResponseEntity<Void>> remove(@PathVariable Long courseId, @PathVariable Long linkId) {
        return service.remove(courseId, linkId).then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}
