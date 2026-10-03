package com.msj.securefile;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.constructors;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Boundary rules of the hexagonal layout (see CLAUDE.md). Each bounded context is split in
 * domain / application / infrastructure (plus api for its inbound web adapter).
 */
@AnalyzeClasses(packages = "com.msj.securefile", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String LOMBOK_GENERATED = "lombok.Generated";

    @ArchTest
    static final ArchRule domainIsPureJava = classes()
            .that().resideInAPackage("..domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..", "..domain..", "io.hypersistence.tsid..", "lombok..");

    // Spring is allowed here for composition and transactions only: no web, security or persistence types.
    @ArchTest
    static final ArchRule applicationDependsOnDomainOnly = classes()
            .that().resideInAPackage("..application..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..", "..domain..", "..application..", "lombok..", "org.slf4j..",
                    "org.springframework.stereotype..", "org.springframework.transaction.annotation..");

    @ArchTest
    static final ArchRule nothingInsideDependsOnTheOutside = noClasses()
            .that().resideInAnyPackage("..domain..", "..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..", "..api..", "..config..");

    @ArchTest
    static final ArchRule jooqStaysInInfrastructure = noClasses()
            .that().resideOutsideOfPackage("..infrastructure..")
            .should().dependOnClassesThat().resideInAPackage("org.jooq..");

    // Lombok annotations are source-retention: the generated members are found through @lombok.Generated.
    @ArchTest
    static final ArchRule noLombokBuilderNorSettersInDomainAndApplication = methods()
            .that().areDeclaredInClassesThat().resideInAnyPackage("..domain..", "..application..")
            .and().areAnnotatedWith(LOMBOK_GENERATED)
            .should().haveNameNotMatching("builder|toBuilder|set[A-Z].*");

    @ArchTest
    static final ArchRule domainHasNoGeneratedConstructors = constructors()
            .that().areDeclaredInClassesThat().resideInAPackage("..domain..")
            .should().notBeAnnotatedWith(LOMBOK_GENERATED);
}