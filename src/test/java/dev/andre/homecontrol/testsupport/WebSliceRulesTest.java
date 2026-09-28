package dev.andre.homecontrol.testsupport;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.BeanOverride;

import java.util.Optional;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Keeps every web-layer test on the one shared context of {@link WebSliceTest}. An allow-list: a test class that
 * extends it, and every class nested in one, carries no Spring annotation at all (a property source, a profile,
 * {@code @DirtiesContext}, an {@code @Import} or {@code @AutoConfigure…}, a class-level bean override) and declares
 * no bean override and no dynamic property; any of those would give it a context of its own.
 */
class WebSliceRulesTest {

    private static final JavaClasses TESTS = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
            .importPackages("dev.andre.homecontrol");

    private static final DescribedPredicate<JavaClass> A_SLICE_TEST = DescribedPredicate.describe(
            "a subclass of WebSliceTest", WebSliceRulesTest::isASliceTest);

    private static final DescribedPredicate<JavaClass> INSIDE_A_SLICE_TEST = DescribedPredicate.describe(
            "nested in a subclass of WebSliceTest", WebSliceRulesTest::isInsideASliceTest);

    private static final DescribedPredicate<JavaClass> A_SLICE_TEST_OR_INSIDE_ONE = DescribedPredicate.describe(
            "a subclass of WebSliceTest or nested in one", type -> isASliceTest(type) || isInsideASliceTest(type));

    private static final DescribedPredicate<JavaAnnotation<?>> A_SPRING_ANNOTATION = DescribedPredicate.describe(
            "a Spring annotation", annotation -> annotation.getRawType().getPackageName().startsWith("org.springframework"));

    private static boolean isASliceTest(JavaClass type) {
        return type.isAssignableTo(WebSliceTest.class) && !type.isEquivalentTo(WebSliceTest.class);
    }

    private static boolean isInsideASliceTest(JavaClass type) {
        for (Optional<JavaClass> outer = type.getEnclosingClass(); outer.isPresent(); outer = outer.get().getEnclosingClass()) {
            if (isASliceTest(outer.get())) {
                return true;
            }
        }
        return false;
    }

    private static final DescribedPredicate<JavaClass> A_WEB_MVC_TEST = DescribedPredicate.describe(
            "a @WebMvcTest, directly, through a composed annotation or by inheritance",
            type -> Stream.concat(Stream.of(type), type.getAllRawSuperclasses().stream())
                    .anyMatch(each -> each.isAnnotatedWith(WebMvcTest.class) || each.isMetaAnnotatedWith(WebMvcTest.class)));

    @Test
    void onlyTheSharedSliceIsAWebMvcTest() {
        // BluetoothSetupOffTest needs the Bluetooth module off; Phase 1.3d-2 moves it into the modules-off context.
        classes().that(A_WEB_MVC_TEST)
                .should().beAssignableTo(WebSliceTest.class).orShould().haveSimpleName("BluetoothSetupOffTest")
                .check(TESTS);
    }

    @Test
    void sliceTestsCarryNoSpringAnnotation() {
        noClasses().that(A_SLICE_TEST)
                .should().beAnnotatedWith(A_SPRING_ANNOTATION).orShould().beMetaAnnotatedWith(A_SPRING_ANNOTATION)
                .allowEmptyShould(true)
                .check(TESTS);
    }

    @Test
    void classesNestedInSliceTestsCarryNoSpringAnnotation() {
        noClasses().that(INSIDE_A_SLICE_TEST)
                .should().beAnnotatedWith(A_SPRING_ANNOTATION).orShould().beMetaAnnotatedWith(A_SPRING_ANNOTATION)
                .allowEmptyShould(true)
                .check(TESTS);
    }

    @Test
    void sliceTestsDeclareNoBeanOverride() {
        noFields().that().areDeclaredInClassesThat(A_SLICE_TEST_OR_INSIDE_ONE)
                .should().beMetaAnnotatedWith(BeanOverride.class)
                .allowEmptyShould(true)
                .check(TESTS);
    }

    @Test
    void sliceTestsRegisterNoProperties() {
        noMethods().that().areDeclaredInClassesThat(A_SLICE_TEST_OR_INSIDE_ONE)
                .should().beAnnotatedWith(DynamicPropertySource.class)
                .allowEmptyShould(true)
                .check(TESTS);
    }
}
