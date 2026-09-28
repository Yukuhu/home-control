package dev.andre.homecontrol.testsupport;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/** Keeps every web-layer test on the one shared context of {@link WebSliceTest}. */
class WebSliceRulesTest {

    private static final JavaClasses TESTS = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
            .importPackages("dev.andre.homecontrol");

    private static final DescribedPredicate<JavaClass> A_SLICE_TEST = DescribedPredicate.describe(
            "a subclass of WebSliceTest",
            type -> type.isAssignableTo(WebSliceTest.class) && !type.isEquivalentTo(WebSliceTest.class));

    @Test
    void onlyTheSharedSliceIsAWebMvcTest() {
        // BluetoothSetupOffTest needs the Bluetooth module off; Phase 1.3d-2 moves it into the modules-off context.
        classes().that().areAnnotatedWith(WebMvcTest.class)
                .should().be(WebSliceTest.class).orShould().haveSimpleName("BluetoothSetupOffTest")
                .check(TESTS);
    }

    @Test
    void sliceTestsDeclareNoBeansOfTheirOwn() {
        noFields().that().areDeclaredInClassesThat(A_SLICE_TEST)
                .should().beAnnotatedWith(MockitoBean.class).orShould().beAnnotatedWith(MockitoSpyBean.class)
                .allowEmptyShould(true)
                .check(TESTS);
        noClasses().that(A_SLICE_TEST).should().beAnnotatedWith(Import.class)
                .allowEmptyShould(true)
                .check(TESTS);
        noClasses().that(DescribedPredicate.describe("nested in a subclass of WebSliceTest",
                        (JavaClass type) -> type.getEnclosingClass().map(A_SLICE_TEST::test).orElse(false)))
                .should().beAnnotatedWith(TestConfiguration.class)
                .allowEmptyShould(true)
                .check(TESTS);
    }
}
