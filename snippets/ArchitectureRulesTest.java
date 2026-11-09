package com.yourcompany.yourservice; // change to your root package

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architecture rules that agents (and humans) cannot break without a failing build.
 * Put this file in src/test/java/<your root package>/ and adjust package names.
 * Each rule mirrors a rule id of the java-coding-standards skill.
 */
@AnalyzeClasses(packages = "com.yourcompany.yourservice", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    // JV-02: controller -> service -> repository, never backwards
    @ArchTest
    static final ArchRule layers = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Controller").definedBy("..controller..", "..web..", "..api..")
            .layer("Service").definedBy("..service..")
            .layer("Repository").definedBy("..repository..", "..persistence..")
            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller", "Service")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service");

    // JV-03: constructor injection only
    @ArchTest
    static final ArchRule noFieldInjection = fields()
            .should().notBeAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
            .because("use constructor injection (JV-03)");

    // JV-10: logging through SLF4J only
    @ArchTest
    static final ArchRule noStandardStreams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    // JV-02: HTTP types stay in the web layer
    @ArchTest
    static final ArchRule noHttpBelowControllers = noClasses()
            .that().resideInAnyPackage("..service..", "..repository..", "..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.http..", "jakarta.servlet..")
            .because("HTTP belongs to controllers (JV-02)");

    // JV-23: java.time only
    @ArchTest
    static final ArchRule noLegacyDates = noClasses()
            .should().dependOnClassesThat().haveFullyQualifiedName("java.util.Date")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.Calendar")
            .because("use java.time (JV-23)");

    // JV-12: transactions on services, not controllers
    @ArchTest
    static final ArchRule transactionsInServices = classes()
            .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
            .should().notBeAnnotatedWith("org.springframework.transaction.annotation.Transactional")
            .because("transactions belong to services (JV-12)");
}
