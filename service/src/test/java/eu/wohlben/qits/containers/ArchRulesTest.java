package eu.wohlben.qits.containers;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import eu.wohlben.qits.archrules.CausationRowRules;
import eu.wohlben.qits.archrules.HttpClientRules;

/**
 * The platform's shared ArchUnit rules over this component's classes. Today that is the
 * causation-row completeness guard: every {@code @Entity} either implements {@code CausedRow} — and
 * then lists {@code CausationStamp} in its {@code @EntityListeners} — or declares {@code @Uncaused}
 * with its reason in the javadoc. A new entity that skips the decision fails this build naming the
 * class, instead of leaving a silent hole in the trace.
 *
 * <p><b>It lives in {@code service/} rather than in {@code core/}, and the reason is the module
 * split.</b> {@code service} is the only classpath that carries every class of this component — the
 * domain jar's entities and whatever this module adds itself — so a copy in {@code core} would
 * judge the domain twice and the deployable never. The package it analyzes is the component's whole
 * root, so an entity added anywhere under it is covered by the test that is already here.
 *
 * <p>The rules judge types by fully-qualified name and depend on neither qits-eventstream nor
 * jakarta.persistence, which is why this test needs nothing but the one test-scope dependency. They
 * also allow an empty match set: a component with no entity yet — this one, in the scaffold commit —
 * passes rather than failing on ArchUnit's fail-on-empty default.
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.containers",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchRulesTest {

  @ArchTest static final ArchTests CAUSATION = ArchTests.in(CausationRowRules.class);

  @ArchTest static final ArchTests HTTP_CLIENTS = ArchTests.in(HttpClientRules.class);
}
