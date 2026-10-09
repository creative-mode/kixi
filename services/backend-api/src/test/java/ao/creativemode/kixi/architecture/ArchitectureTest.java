package ao.creativemode.kixi.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Locks in the dependency direction between the backend's domain modules:
 *
 * <pre>
 * shared (kernel, no outgoing dependency on any domain module)
 *  |- identity   (accounts, roles, sessions, users, auth) -&gt; shared only
 *  |- academic   (school years, terms, subjects, courses, classes) -&gt; shared only
 *  |- institutions (schools, subject/teacher/student affiliations) -&gt; shared, identity, academic
 *  |- exams      (statements, questions, options, question images) -&gt; shared, institutions
 *  |- simulations (simulations, simulation answers) -&gt; shared, identity, academic, exams
 *  |- ocr        (OCR client + persistence orchestration) -&gt; shared, academic, exams
 *  '- chat       (AI tutor sessions, messages, Groq streaming) -&gt; shared, exams
 * </pre>
 *
 * If a change violates one of these, this test fails at build time instead of
 * the dependency direction quietly drifting back into a tangle.
 *
 * Only production code is imported: integration tests legitimately wire up
 * several modules together (e.g. AuthorizationIntegrationTest exercises
 * identity, exams, simulations and ocr controllers in one test class), so the
 * dependency-direction rules below apply to src/main, not src/test.
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ao.creativemode.kixi");
    }

    @Test
    void sharedDoesNotDependOnAnyDomainModule() {
        noClasses().that().resideInAPackage("..shared..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..identity..", "..academic..", "..institutions..", "..exams..", "..simulations..", "..ocr..")
                .check(classes);
    }

    @Test
    void identityOnlyDependsOnShared() {
        noClasses().that().resideInAPackage("..identity..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..academic..", "..institutions..", "..exams..", "..simulations..", "..ocr..")
                .check(classes);
    }

    @Test
    void academicOnlyDependsOnShared() {
        noClasses().that().resideInAPackage("..academic..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..identity..", "..institutions..", "..exams..", "..simulations..", "..ocr..")
                .check(classes);
    }

    @Test
    void institutionsOnlyDependsOnSharedIdentityAndAcademic() {
        noClasses().that().resideInAPackage("..institutions..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..exams..", "..simulations..", "..ocr..")
                .check(classes);
    }

    @Test
    void examsDoesNotDependOnIdentitySimulationsOrOcr() {
        noClasses().that().resideInAPackage("..exams..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..identity..", "..simulations..", "..ocr..")
                .check(classes);
    }

    @Test
    void simulationsDoesNotDependOnOcr() {
        noClasses().that().resideInAPackage("..simulations..")
                .should().dependOnClassesThat().resideInAPackage("..ocr..")
                .check(classes);
    }

    @Test
    void ocrDoesNotDependOnIdentityOrSimulations() {
        noClasses().that().resideInAPackage("..ocr..")
                .should().dependOnClassesThat().resideInAnyPackage("..identity..", "..simulations..")
                .check(classes);
    }

    /**
     * The tutor reads statements/questions (exams) and resolves the caller
     * through shared, and nothing else: no reaching into identity for roles
     * (the security chain already decided who is calling) or into academic
     * for names that are not part of the prompt.
     */
    @Test
    void chatOnlyDependsOnSharedAndExams() {
        noClasses().that().resideInAPackage("..chat..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..identity..", "..academic..", "..institutions..", "..simulations..", "..ocr..")
                .check(classes);
    }

    @Test
    void domainModulesAreFreeOfCycles() {
        SlicesRuleDefinition.slices()
                .matching("ao.creativemode.kixi.(*)..")
                .should().beFreeOfCycles()
                .check(classes);
    }
}
