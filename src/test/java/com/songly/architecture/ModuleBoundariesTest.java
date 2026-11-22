package com.songly.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import jakarta.persistence.Entity;
import java.util.Set;
import org.springframework.data.repository.Repository;

@AnalyzeClasses(packages = ModuleBoundariesTest.BASE, importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundariesTest {

	static final String BASE = "com.songly";

	private static final Set<String> DOMAIN_MODULES = Set.of("analytics", "community", "content", "identity",
			"matching", "moderationlog", "notification", "nowplaying", "profile", "social");

	private static boolean isPublishedApi(JavaClass target) {
		if (!target.getModifiers().contains(JavaModifier.PUBLIC)) {
			return false;
		}
		return target.isInterface() || target.getSimpleName().endsWith("Event");
	}

	private static String moduleOf(JavaClass clazz) {
		String packageName = clazz.getPackageName();
		if (!packageName.startsWith(BASE + ".")) {
			return null;
		}
		String remainder = packageName.substring(BASE.length() + 1);
		int dot = remainder.indexOf('.');
		String head = dot < 0 ? remainder : remainder.substring(0, dot);
		return DOMAIN_MODULES.contains(head) ? head : null;
	}

	private static final DescribedPredicate<JavaClass> IN_DOMAIN_MODULE = new DescribedPredicate<>(
			"leżą w module domenowym") {
		@Override
		public boolean test(JavaClass clazz) {
			return moduleOf(clazz) != null;
		}
	};

	private static final ArchCondition<JavaClass> ONLY_TOUCH_PUBLISHED_API = new ArchCondition<>(
			"sięgać do innych modułów wyłącznie przez ich publiczne interfejsy i zdarzenia") {
		@Override
		public void check(JavaClass clazz, ConditionEvents events) {
			String ownModule = moduleOf(clazz);
			clazz.getDirectDependenciesFromSelf().forEach(dependency -> {
				JavaClass target = dependency.getTargetClass().getBaseComponentType();
				String targetModule = moduleOf(target);
				if (targetModule == null || targetModule.equals(ownModule) || isPublishedApi(target)) {
					return;
				}
				events.add(SimpleConditionEvent.violated(clazz,
						"%s sięga do wnętrza modułu '%s' — dozwolone są tylko publiczne interfejsy i zdarzenia (%s)"
								.formatted(clazz.getName(), targetModule, dependency.getDescription())));
			});
		}
	};

	@ArchTest
	static final ArchRule moduly_siegaja_do_siebie_tylko_przez_opublikowane_api = classes().that(IN_DOMAIN_MODULE)
			.should(ONLY_TOUCH_PUBLISHED_API);

	@ArchTest
	static final ArchRule moduly_nie_tworza_cykli = SlicesRuleDefinition.slices()
			.matching(
					BASE + ".(analytics|community|content|identity|matching|notification|nowplaying|profile|social)..")
			.should().beFreeOfCycles();

	@ArchTest
	static final ArchRule common_nie_zna_domeny = noClasses().that().resideInAPackage(BASE + ".common..").should()
			.dependOnClassesThat(IN_DOMAIN_MODULE);

	@ArchTest
	static final ArchRule encje_nie_sa_publiczne = noClasses().that().areAnnotatedWith(Entity.class).should()
			.bePublic();

	@ArchTest
	static final ArchRule repozytoria_nie_sa_publiczne = noClasses().that().areAssignableTo(Repository.class).and()
			.resideInAPackage(BASE + "..").should().bePublic();

	@ArchTest
	static final ArchRule kontrolery_nie_dotykaja_encji = noClasses().that().haveSimpleNameEndingWith("Controller")
			.should().dependOnClassesThat().areAnnotatedWith(Entity.class);
}
