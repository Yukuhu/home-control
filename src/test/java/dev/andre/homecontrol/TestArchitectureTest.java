package dev.andre.homecontrol;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The package rules docs/dev/architecture.md sets for the tests themselves; {@link ArchitectureTest} checks the
 * application's classes.
 */
@AnalyzeClasses(packages = "dev.andre.homecontrol", importOptions = ImportOption.OnlyIncludeTests.class)
class TestArchitectureTest {

    @ArchTest
    static final ArchRule coreTestsDoNotUseSources = noClasses()
            .that().resideInAPackage("dev.andre.homecontrol.core..")
            .should().dependOnClassesThat().resideInAPackage("dev.andre.homecontrol.sources..")
            .because("core's tests exercise core alone; a source's own types are tested in its module, and tests "
                    + "that need every module sit in playback");
}
