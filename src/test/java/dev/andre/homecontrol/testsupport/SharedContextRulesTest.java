package dev.andre.homecontrol.testsupport;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.BeanOverride;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.conditions.ArchConditions.be;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Keeps every test that extends a shared-context base ({@link WebSliceTest}, {@link ModulesOffTest},
 * {@link FullAppTest}) on that base's one context. An allow-list: such a test class, and every class nested in one,
 * carries no Spring annotation at all (a property source, a profile, {@code @DirtiesContext}, an {@code @Import} or
 * {@code @AutoConfigure…}, a class-level bean override) and declares no bean override and no dynamic property; any of
 * those would give it a context of its own. A {@code @SpringBootTest} that needs one extends no base and is listed in
 * {@link #OWN_CONTEXT}, with the reason.
 */
class SharedContextRulesTest {

    private static final JavaClasses TESTS = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
            .importPackages("dev.andre.homecontrol");

    private static final DescribedPredicate<JavaClass> A_SHARED_CONTEXT_TEST = DescribedPredicate.describe(
            "a subclass of WebSliceTest, ModulesOffTest or FullAppTest", SharedContextRulesTest::isASharedContextTest);

    private static final DescribedPredicate<JavaClass> INSIDE_A_SHARED_CONTEXT_TEST = DescribedPredicate.describe(
            "nested in a subclass of WebSliceTest, ModulesOffTest or FullAppTest", SharedContextRulesTest::isInsideASharedContextTest);

    private static final DescribedPredicate<JavaClass> A_SHARED_CONTEXT_TEST_OR_INSIDE_ONE = DescribedPredicate.describe(
            "a subclass of WebSliceTest, ModulesOffTest or FullAppTest, or nested in one",
            type -> isASharedContextTest(type) || isInsideASharedContextTest(type));

    private static final DescribedPredicate<JavaAnnotation<?>> A_SPRING_ANNOTATION = DescribedPredicate.describe(
            "a Spring annotation", annotation -> annotation.getRawType().getPackageName().startsWith("org.springframework"));

    private static boolean isASharedContextTest(JavaClass type) {
        return (type.isAssignableTo(WebSliceTest.class) && !type.isEquivalentTo(WebSliceTest.class))
                || (type.isAssignableTo(ModulesOffTest.class) && !type.isEquivalentTo(ModulesOffTest.class))
                || (type.isAssignableTo(FullAppTest.class) && !type.isEquivalentTo(FullAppTest.class));
    }

    private static boolean isInsideASharedContextTest(JavaClass type) {
        for (Optional<JavaClass> outer = type.getEnclosingClass(); outer.isPresent(); outer = outer.get().getEnclosingClass()) {
            if (isASharedContextTest(outer.get())) {
                return true;
            }
        }
        return false;
    }

    private static final DescribedPredicate<JavaClass> A_WEB_MVC_TEST = DescribedPredicate.describe(
            "a @WebMvcTest, directly, through a composed annotation or by inheritance",
            type -> Stream.concat(Stream.of(type), type.getAllRawSuperclasses().stream())
                    .anyMatch(each -> each.isAnnotatedWith(WebMvcTest.class) || each.isMetaAnnotatedWith(WebMvcTest.class)));

    /** The only full-application tests with a context of their own, because their beans or properties differ. */
    private static final Set<String> OWN_CONTEXT = Set.of(
            "CastEndToEndTest",               // Cast heartbeat and stale timeout for its own receiver
            "WebOsEndToEndTest", "TizenEndToEndTest", "SpeakersEndToEndTest", // SSDP on, TV modules off
            "BluetoothSpeakerEndToEndTest", "BluetoothJellyfinEndToEndTest",  // a @Primary fake BlueZ and mpv
            "WorkflowEndToEndTest",           // a recording device adapter
            "TheSportsDbOff", "WorkflowOnlySetupTest");                       // mixed module combinations

    private static final DescribedPredicate<JavaClass> A_SPRING_BOOT_TEST = DescribedPredicate.describe(
            "a @SpringBootTest, directly, through a composed annotation or by inheritance",
            type -> Stream.concat(Stream.of(type), type.getAllRawSuperclasses().stream())
                    .anyMatch(each -> each.isAnnotatedWith(SpringBootTest.class)
                            || each.isMetaAnnotatedWith(SpringBootTest.class)));

    private static final DescribedPredicate<JavaClass> LISTED_WITH_A_CONTEXT_OF_ITS_OWN = DescribedPredicate.describe(
            "listed in OWN_CONTEXT", type -> OWN_CONTEXT.contains(type.getSimpleName()));

    @Test
    void everySpringBootTestSharesAContextOrIsListed() {
        classes().that(A_SPRING_BOOT_TEST)
                .should().beAssignableTo(FullAppTest.class)
                .orShould().beAssignableTo(ModulesOffTest.class)
                .orShould(be(LISTED_WITH_A_CONTEXT_OF_ITS_OWN))
                .check(TESTS);
    }

    @Test
    void onlyTheSharedSliceIsAWebMvcTest() {
        classes().that(A_WEB_MVC_TEST).should().beAssignableTo(WebSliceTest.class).check(TESTS);
    }

    @Test
    void sharedContextTestsCarryNoSpringAnnotation() {
        noClasses().that(A_SHARED_CONTEXT_TEST)
                .should().beAnnotatedWith(A_SPRING_ANNOTATION).orShould().beMetaAnnotatedWith(A_SPRING_ANNOTATION)
                .allowEmptyShould(true)
                .check(TESTS);
    }

    @Test
    void classesNestedInSharedContextTestsCarryNoSpringAnnotation() {
        noClasses().that(INSIDE_A_SHARED_CONTEXT_TEST)
                .should().beAnnotatedWith(A_SPRING_ANNOTATION).orShould().beMetaAnnotatedWith(A_SPRING_ANNOTATION)
                .allowEmptyShould(true)
                .check(TESTS);
    }

    @Test
    void sharedContextTestsDeclareNoBeanOverride() {
        noFields().that().areDeclaredInClassesThat(A_SHARED_CONTEXT_TEST_OR_INSIDE_ONE)
                .should().beMetaAnnotatedWith(BeanOverride.class)
                .allowEmptyShould(true)
                .check(TESTS);
    }

    @Test
    void sharedContextTestsRegisterNoProperties() {
        noMethods().that().areDeclaredInClassesThat(A_SHARED_CONTEXT_TEST_OR_INSIDE_ONE)
                .should().beAnnotatedWith(DynamicPropertySource.class)
                .allowEmptyShould(true)
                .check(TESTS);
    }
}
