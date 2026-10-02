package dev.andre.homecontrol;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The package rules docs/dev/architecture.md sets for the app's tests; {@link ArchitectureTest} checks the
 * application's classes.
 */
@AnalyzeClasses(packages = "dev.andre.homecontrol", importOptions = ImportOption.OnlyIncludeTests.class)
class TestArchitectureTest {

    @ArchTest
    static final ArchRule coreTestsSitInTheCoreModule = noClasses()
            .should().resideInAPackage("dev.andre.homecontrol.core..")
            .because("core's tests sit in the core module, which compiles them against core alone");

    @ArchTest
    static final ArchRule protocolTestsSitInTheProtocolsModule = noClasses()
            .should().resideInAnyPackage("..protocol..", "dev.andre.homecontrol.adapters.net..",
                    "dev.andre.homecontrol.sources.sports.ics..")
            .because("the tests of protocols' packages sit in the protocols module, which compiles them against "
                    + "protocols and core alone");
}
