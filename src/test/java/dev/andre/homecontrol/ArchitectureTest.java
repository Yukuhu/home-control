package dev.andre.homecontrol;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import tools.jackson.databind.json.JsonMapper;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;

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
            .ignoreDependency(resideInAPackage("dev.andre.homecontrol.adapters.support.."),
                    resideInAPackage("dev.andre.homecontrol.adapters.upnp.protocol.."))
            .because("each device adapter is a module that can be switched off; net, links and support are shared, "
                    + "and Sonos and the renderer helpers in support speak UPnP");

    @ArchTest
    static final ArchRule networkLibrariesStayInAdaptersSourcesAndDiscovery = noClasses()
            .that().resideOutsideOfPackages("dev.andre.homecontrol.adapters..", "dev.andre.homecontrol.sources..",
                    "dev.andre.homecontrol.discovery..")
            .should().dependOnClassesThat().resideInAnyPackage("java.net.http..", "org.apache.hc..", "javax.jmdns..",
                    "org.freedesktop.dbus..", "com.github.hypfvieh..")
            .because("only adapters speak device protocols and only sources speak content APIs");

    @ArchTest
    static final ArchRule sourcesReachTheNetworkOnlyThroughTheGuardedClient = noClasses()
            .that().resideInAPackage("dev.andre.homecontrol.sources..")
            .and().resideOutsideOfPackage("dev.andre.homecontrol.sources.http..")
            .should().dependOnClassesThat().resideInAnyPackage("java.net.http..", "org.apache.hc..")
            .because("the guarded client pins addresses, bounds bodies and time, and keeps URLs out of errors; "
                    + "see ADR 0005");

    @ArchTest
    static final ArchRule sportsFeedsSitBelowTheSource = layeredArchitecture()
            .consideringOnlyDependenciesInAnyPackage("dev.andre.homecontrol.sources.sports..")
            .layer("Source").definedBy("dev.andre.homecontrol.sources.sports")
            .layer("Calendars").definedBy("dev.andre.homecontrol.sources.sports.calendar..")
            .layer("Competitions").definedBy("dev.andre.homecontrol.sources.sports.thesportsdb..")
            .layer("Shared").definedBy("dev.andre.homecontrol.sources.sports.feed..",
                    "dev.andre.homecontrol.sources.sports.settings..")
            .layer("Ics").definedBy("dev.andre.homecontrol.sources.sports.ics..")
            .whereLayer("Source").mayNotBeAccessedByAnyLayer()
            .whereLayer("Calendars").mayOnlyBeAccessedByLayers("Source")
            .whereLayer("Competitions").mayOnlyBeAccessedByLayers("Source")
            .whereLayer("Shared").mayOnlyBeAccessedByLayers("Source", "Calendars", "Competitions")
            .whereLayer("Ics").mayOnlyBeAccessedByLayers("Calendars")
            .because("the feeds build on the shared sports types and the source builds on the feeds; an edge back "
                    + "up would tie them into a cycle again");

    @ArchTest
    static final ArchRule icsIsALibrary = classes()
            .that().resideInAPackage("dev.andre.homecontrol.sources.sports.ics..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "dev.andre.homecontrol.sources.sports.ics..")
            .because("the calendar parser is a library: it takes text and returns values, and knows nothing of "
                    + "Spring or the app");

    @ArchTest
    static final ArchRule onlyTheConfigurationBuildsAJsonMapper = noClasses()
            .that().resideOutsideOfPackages("dev.andre.homecontrol.config..", "dev.andre.homecontrol.adapters..")
            .should().callMethodWhere(target(name("builder")).and(target(owner(assignableTo(JsonMapper.class)))))
            .because("one mapper, hardened against hostile JSON, reads every data file and every source's answer; "
                    + "device protocols keep their own");

    @ArchTest
    static final ArchRule onlyTheConfigurationReachesIntoDevice = noClasses()
            .that().resideOutsideOfPackage("dev.andre.homecontrol.device..")
            .and().doNotBelongToAnyOf(HomeControlConfiguration.class)
            .should().dependOnClassesThat().resideInAPackage("dev.andre.homecontrol.device..")
            .because("callers see devices through the four core interfaces; only the application's configuration "
                    + "wires the device package");

    @ArchTest
    static final ArchRule protocolPackagesStandAlone = freeze(noClasses()
            .that().resideInAPackage("..protocol..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..")
            .orShould().dependOnClassesThat(resideInAPackage("dev.andre.homecontrol..")
                    .and(not(resideInAnyPackage("..protocol..", "dev.andre.homecontrol.adapters.net.."))))
            .because("wire protocols are libraries: they take plain values and know nothing of Spring or the app"));

    /** Frozen per cycle, by the packages it runs through (see {@link CycleViolations}). */
    @ArchTest
    static final ArchRule topLevelPackagesAreFreeOfCycles = freeze(slices()
            .matching("dev.andre.homecontrol.(*)..")
            .should().beFreeOfCycles()
            .because("packages in a cycle cannot be understood, tested or split apart on their own"))
            .associateViolationLinesVia(new CycleViolations());

    @ArchTest
    static final ArchRule sourcesDoNotDependOnAdapters = freeze(noClasses()
            .that().resideInAPackage("dev.andre.homecontrol.sources..")
            .should().dependOnClassesThat().resideInAPackage("dev.andre.homecontrol.adapters..")
            .because("sources see devices only through the domain model in core"));

    @ArchTest
    static final ArchRule adaptersDoNotDependOnSourcesOrWeb = freeze(noClasses()
            .that().resideInAPackage("dev.andre.homecontrol.adapters..")
            .should().dependOnClassesThat().resideInAnyPackage("dev.andre.homecontrol.sources..",
                    "dev.andre.homecontrol.web..")
            .because("adapters speak device protocols and nothing else"));

    @ArchTest
    static final ArchRule webDoesNotDependOnAdapters = freeze(noClasses()
            .that().resideInAPackage("dev.andre.homecontrol.web..")
            .should().dependOnClassesThat().resideInAPackage("dev.andre.homecontrol.adapters..")
            .because("the web layer sees the domain model only"));

    @ArchTest
    static final ArchRule servletTypesStayAtTheWebEdge = noClasses()
            .that().resideOutsideOfPackages("dev.andre.homecontrol.web..", "dev.andre.homecontrol.security..")
            .and().areNotMetaAnnotatedWith(Controller.class)
            .and().areNotMetaAnnotatedWith(ControllerAdvice.class)
            .should().dependOnClassesThat().resideInAPackage("jakarta.servlet..")
            .because("services and stores take values, not requests");
}
