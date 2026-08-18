package com.jmail.backend

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * Structural rules that are easy to state and tedious to police by review.
 *
 * These are not style preferences. Each one encodes a decision that, if quietly broken,
 * causes a real defect: entities leaking into API responses, a controller bypassing the
 * ownership checks in its service, or credentials reaching a log file.
 */
class ArchitectureTest {

    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.jmail.backend")

    @Test
    fun `controllers do not touch repositories directly`() {
        noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .because(
                "ownership scoping and transaction boundaries live in the services; a controller " +
                    "reaching past them is how one user ends up reading another's mail",
            )
            .check(classes)
    }

    @Test
    fun `JPA entities never leave the service layer`() {
        noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().resideInAnyPackage("com.jmail.backend.mail")
            .andShould().dependOnClassesThat().haveSimpleName("Message")
            .because("controllers return DTOs so that a schema change cannot alter the public API")
            .allowEmptyShould(true)
            .check(classes)
    }

    @Test
    fun `services do not depend on Servlet types`() {
        noClasses()
            .that().haveSimpleNameEndingWith("Service")
            .should().dependOnClassesThat().resideInAPackage("jakarta.servlet..")
            .because("a service coupled to HTTP cannot be reused from the sync scheduler")
            .check(classes)
    }

    @Test
    fun `domain logic does not depend on the web layer`() {
        // Packages are organised by feature, so a controller sits beside the domain it serves.
        // The rule is about the *rest* of the feature: an engine or entity that reaches for
        // Spring MVC cannot be exercised from the sync scheduler or a background job.
        noClasses()
            .that().resideInAPackage("com.jmail.backend.category..")
            .and().haveSimpleNameNotEndingWith("Controller")
            .and().resideOutsideOfPackage("com.jmail.backend.category.dto..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.web..")
            .allowEmptyShould(true)
            .check(classes)
    }

    @Test
    fun `nothing prints to standard output`() {
        noClasses()
            .should().accessTargetWhere(
                com.tngtech.archunit.core.domain.JavaAccess.Predicates.targetOwner(
                    com.tngtech.archunit.base.DescribedPredicate.describe("java.lang.System") { owner ->
                        owner.name == "java.lang.System"
                    },
                ).and(
                    com.tngtech.archunit.base.DescribedPredicate.describe("out or err") { target ->
                        target.name == "out" || target.name == "err"
                    },
                ),
            )
            .because("logs must go through SLF4J so they are structured, levelled and filterable")
            .allowEmptyShould(true)
            .check(classes)
    }

    @Test
    fun `repositories are interfaces annotated as such`() {
        classes()
            .that().haveSimpleNameEndingWith("Repository")
            .and().areNotNestedClasses()
            .should().beInterfaces()
            .because("Spring Data generates the implementation; a hand-written one is a smell")
            .check(classes)
    }

    @Test
    fun `controllers live in a package with their feature, not in a controller package`() {
        classes()
            .that().haveSimpleNameEndingWith("Controller")
            .should().resideInAnyPackage(
                "com.jmail.backend.auth..",
                "com.jmail.backend.mail..",
                "com.jmail.backend.category..",
                "com.jmail.backend.user..",
            )
            .because("JMail is organised by feature; a layer-shaped package would scatter each one")
            .check(classes)
    }

    @Test
    fun `every controller endpoint class is annotated for the API documentation`() {
        classes()
            .that().haveSimpleNameEndingWith("Controller")
            .should().beAnnotatedWith(io.swagger.v3.oas.annotations.tags.Tag::class.java)
            .because("the OpenAPI document is the contract the multiplatform client is written against")
            .check(classes)
    }
}
