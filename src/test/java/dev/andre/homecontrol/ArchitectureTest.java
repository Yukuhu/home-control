package dev.andre.homecontrol;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The package rules of docs/dev/architecture.md, checked on every build. Rules the code still breaks are frozen:
 * their known violations are recorded in src/test/archunit-store, and only new ones fail.
 */
@AnalyzeClasses(packages = "dev.andre.homecontrol", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule coreDependsOnlyOnTheJdk = classes()
            .that().resideInAPackage("dev.andre.homecontrol.core..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "dev.andre.homecontrol.core..")
            .because("core is the domain model every other package builds on");

    @ArchTest
    static final ArchRule sourcesAreIndependent = slices()
            .matching("dev.andre.homecontrol.sources.(*)..")
            .should().notDependOnEachOther()
            .ignoreDependency(alwaysTrue(), resideInAPackage("dev.andre.homecontrol.sources.http.."))
            .because("each content source is a module that can be switched off; sources.http is their shared support");

    @ArchTest
    static final ArchRule adaptersAreIndependent = slices()
            .matching("dev.andre.homecontrol.adapters.(*)..")
            .should().notDependOnEachOther()
            .ignoreDependency(alwaysTrue(), resideInAnyPackage("dev.andre.homecontrol.adapters.net..",
                    "dev.andre.homecontrol.adapters.links..", "dev.andre.homecontrol.adapters.support.."))
            .ignoreDependency(resideInAPackage("dev.andre.homecontrol.adapters.sonos.."),
                    resideInAPackage("dev.andre.homecontrol.adapters.upnp.protocol.."))
            .because("each device adapter is a module that can be switched off; net, links and support are shared, "
                    + "and Sonos speaks UPnP");

    @ArchTest
    static final ArchRule networkLibrariesStayInAdaptersSourcesAndDiscovery = noClasses()
            .that().resideOutsideOfPackages("dev.andre.homecontrol.adapters..", "dev.andre.homecontrol.sources..",
                    "dev.andre.homecontrol.discovery..")
            .should().dependOnClassesThat().resideInAnyPackage("java.net.http..", "org.apache.hc..", "javax.jmdns..",
                    "org.freedesktop.dbus..", "com.github.hypfvieh..")
            .because("only adapters speak device protocols and only sources speak content APIs");
}
