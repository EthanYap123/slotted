package dev.ethanyap.slotted;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;

/**
 * Layer rules from docs/ARCHITECTURE.md. Each feature package ({@code booking}, {@code room}, ...)
 * has {@code web}, {@code application}, {@code domain} and {@code infrastructure} sub-packages.
 * Rules allow empty packages so they hold from the first feature onwards.
 */
@AnalyzeClasses(
    packages = "dev.ethanyap.slotted",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule DOMAIN_HAS_NO_SPRING_OR_WEB_DEPENDENCIES =
      noClasses()
          .that()
          .resideInAPackage("dev.ethanyap.slotted..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "dev.ethanyap.slotted..web..",
              "dev.ethanyap.slotted..infrastructure..")
          .allowEmptyShould(true)
          .because("domain rules are plain Java, unit-tested without Spring (ARCHITECTURE.md 3.1)");

  @ArchTest
  static final ArchRule ONLY_THE_WEB_LAYER_USES_WEB_CLASSES =
      noClasses()
          .that()
          .resideInAnyPackage(
              "dev.ethanyap.slotted..application..",
              "dev.ethanyap.slotted..domain..",
              "dev.ethanyap.slotted..infrastructure..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("dev.ethanyap.slotted..web..")
          .allowEmptyShould(true)
          .because("controllers and DTOs sit at the edge; inner layers must not know about them");

  private static final DescribedPredicate<JavaMethodCall> NO_ARGUMENT_NOW_IN_JAVA_TIME =
      DescribedPredicate.describe(
          "a no-argument now() in java.time",
          call ->
              call.getName().equals("now")
                  && call.getTargetOwner().getPackageName().equals("java.time")
                  && call.getTarget().getRawParameterTypes().isEmpty());

  @ArchTest
  static final ArchRule TIME_COMES_FROM_THE_INJECTED_CLOCK =
      noClasses()
          .should()
          .callMethodWhere(NO_ARGUMENT_NOW_IN_JAVA_TIME)
          .because("rules must use the injected Clock so tests can fix the time (ADR-0003)");

  @ArchTest
  static final ArchRule NO_FIELD_INJECTION =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION.allowEmptyShould(true);

  @ArchTest
  static final ArchRule NO_STANDARD_STREAMS =
      GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

  @ArchTest
  static final ArchRule FEATURES_HAVE_NO_CYCLES =
      slices()
          .matching("dev.ethanyap.slotted.(*)..")
          .should()
          .beFreeOfCycles()
          .allowEmptyShould(true);
}
